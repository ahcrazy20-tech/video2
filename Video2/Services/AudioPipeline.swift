import Foundation
import AVFoundation

// MARK: - أخطاء المحرك الصوتي

enum AudioPipelineError: LocalizedError {
    case noAudioTrack
    case exportFailed(String)
    case readerFailed(String)
    case writerFailed(String)

    var errorDescription: String? {
        switch self {
        case .noAudioTrack:
            return "الفيديو لا يحتوي مساراً صوتياً — لا يمكن تفريغ كلام بدون صوت."
        case .exportFailed(let m):
            return "فشل تحويل الفيديو: \(m)"
        case .readerFailed(let m):
            return "فشل قراءة الصوت من الفيديو: \(m)"
        case .writerFailed(let m):
            return "فشل كتابة مقاطع الصوت: \(m)"
        }
    }
}

// MARK: - وصف جزء صوتي

struct AudioChunk: Codable, Equatable {
    var index: Int
    var fileName: String
    var start: Double
    var duration: Double
}

// MARK: - خط أنابيب الصوت

enum AudioPipeline {

    static let sampleRate = 16000.0
    static let chunkSeconds = 900.0

    // MARK: ═══════════════════════════════════════════════════════════
    // MARK: CloudConvert API (مدموج)
    // ═════════════════════════════════════════════════════════════════
    
    /// يجلب مفتاح CloudConvert من Keychain (أولوية) أو Info.plist
    private static func cloudConvertKey() -> String? {
        print("[AudioPipeline] ═══ Checking CloudConvert key...")
        
        // أولاً من Keychain
        if let key = KeychainStore.get("cloudconvert"), !key.isEmpty {
            print("[AudioPipeline] ✅ Found CloudConvert key in Keychain")
            return key
        }
        print("[AudioPipeline] ❌ No CloudConvert key in Keychain")
        
        // ثانياً من Info.plist (fallback)
        if let key = Bundle.main.infoDictionary?["CLOUDCONVERT_API_KEY"] as? String {
            if !key.isEmpty && !key.contains("ضع") {
                print("[AudioPipeline] ✅ Found CloudConvert key in Info.plist")
                return key
            }
        }
        print("[AudioPipeline] ❌ No CloudConvert key in Info.plist")
        
        return nil
    }
    
    /// يتحقق من توفر مفتاح CloudConvert
    private static var hasCloudConvertKey: Bool {
        cloudConvertKey() != nil
    }

    /// يجلب مفتاح ffmpeg-api.com من Keychain (أولوية) أو Info.plist — مزود سحابي
    /// احتياطي إضافي لتحويل HLS إلى MP4 عندما تنفد حصة CloudConvert المجانية.
    private static func ffmpegApiKey() -> String? {
        print("[AudioPipeline] ═══ Checking ffmpeg-api key...")
        if let key = KeychainStore.get("ffmpegapi"), !key.isEmpty {
            print("[AudioPipeline] ✅ Found ffmpeg-api key in Keychain")
            return key
        }
        if let key = Bundle.main.infoDictionary?["FFMPEG_API_KEY"] as? String,
           !key.isEmpty, !key.contains("ضع") {
            print("[AudioPipeline] ✅ Found ffmpeg-api key in Info.plist")
            return key
        }
        print("[AudioPipeline] ❌ No ffmpeg-api key")
        return nil
    }
    
    /// دمج .ts segments في ملف واحد. لا نقرأ الفيديو كله في الذاكرة، ولا نطبع
    /// سطراً لكل segment لأن تسجيل آلاف السطور كان يبطئ التحويل الطويل نفسه.
    private static func mergeTS(segments: [String], baseFolder: URL) async throws -> URL {
        print("[AudioPipeline] Merging \(segments.count) TS segments…")
        let out = FileManager.default.temporaryDirectory
            .appendingPathComponent("merge-\(UUID().uuidString).ts")
        guard FileManager.default.createFile(atPath: out.path, contents: nil) else {
            throw AudioPipelineError.exportFailed("تعذر إنشاء ملف مؤقت لدمج HLS")
        }

        do {
            let handle = try FileHandle(forWritingTo: out)
            defer { try? handle.close() }
            var totalBytes = 0

            for (index, segmentPath) in segments.enumerated() {
                if Task.isCancelled {
                    throw CancellationError()
                }
                if index == 0 || index + 1 == segments.count || (index + 1).isMultiple(of: 100) {
                    print("[AudioPipeline] HLS merge \(index + 1)/\(segments.count)")
                }

                let data: Data
                if segmentPath.hasPrefix("http://") || segmentPath.hasPrefix("https://") {
                    guard let remote = URL(string: segmentPath) else {
                        throw AudioPipelineError.exportFailed("رابط HLS غير صالح: \(segmentPath)")
                    }
                    let (downloaded, response) = try await URLSession.shared.data(from: remote)
                    guard let http = response as? HTTPURLResponse,
                          (200...299).contains(http.statusCode) else {
                        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                        throw AudioPipelineError.exportFailed("تعذر تنزيل جزء HLS — HTTP \(status)")
                    }
                    data = downloaded
                } else {
                    let local = segmentPath.hasPrefix("/")
                        ? URL(fileURLWithPath: segmentPath)
                        : baseFolder.appendingPathComponent(segmentPath)
                    guard FileManager.default.fileExists(atPath: local.path) else {
                        throw AudioPipelineError.exportFailed("جزء HLS مفقود: \(local.lastPathComponent)")
                    }
                    data = try Data(contentsOf: local, options: [.mappedIfSafe])
                }
                guard !data.isEmpty else {
                    throw AudioPipelineError.exportFailed("جزء HLS فارغ: \(segmentPath)")
                }
                try handle.write(contentsOf: data)
                totalBytes += data.count
            }

            guard totalBytes > 0 else {
                throw AudioPipelineError.exportFailed("لم يتم دمج أي بيانات من HLS")
            }
            print("[AudioPipeline] HLS merge complete: \(totalBytes / 1024 / 1024) MB")
            return out
        } catch {
            try? FileManager.default.removeItem(at: out)
            throw error
        }
    }
    
    /// تحويل HLS عبر CloudConvert. في مسار الترجمة نطلب M4A فقط لأننا
    /// نحتاج الصوت لا الفيديو؛ ذلك يتجنب إعادة ترميز وتنزيل ملف فيديو كامل.
    private static func convertWithCloudConvert(m3u8URL: URL, outputFormat: String = "mp4") async throws -> URL {
        let audioOnly = outputFormat.lowercased() == "m4a"
        print("[CloudConvert] ═══════════════════════════════════════")
        print("[CloudConvert] Starting HLS → \(outputFormat.uppercased())")
        print("[CloudConvert] m3u8: \(m3u8URL.path)")
        
        guard let apiKey = cloudConvertKey() else {
            print("[CloudConvert] ❌ No API key found")
            throw AudioPipelineError.exportFailed("مفتاح CloudConvert غير موجود — أضفه من الإعدادات")
        }
        print("[CloudConvert] ✅ API key found (length: \(apiKey.count))")
        
        // اقرأ m3u8
        guard let content = try? String(contentsOf: m3u8URL, encoding: .utf8) else {
            print("[CloudConvert] ❌ Failed to read m3u8")
            throw AudioPipelineError.exportFailed("تعذر قراءة m3u8")
        }
        print("[CloudConvert] ✅ Read m3u8 content (\(content.count) chars)")
        
        // استخرج segments
        let lines = content.components(separatedBy: .newlines)
        print("[CloudConvert] Total lines in m3u8: \(lines.count)")
        
        let segs: [String] = lines.compactMap { l in
            let t = l.trimmingCharacters(in: .whitespaces)
            return t.isEmpty || t.hasPrefix("#") ? nil : t
        }
        
        print("[CloudConvert] Found \(segs.count) segments")
        if segs.isEmpty {
            print("[CloudConvert] ❌ No segments found")
            print("[CloudConvert] First 10 lines:")
            for (i, line) in lines.prefix(10).enumerated() {
                print("[CloudConvert]   Line \(i): \(line)")
            }
            throw AudioPipelineError.exportFailed("m3u8 فارغ أو لا يحتوي segments")
        }
        
        // اطبع أول 3 segments عشان نتأكد
        print("[CloudConvert] First 3 segments:")
        for (i, seg) in segs.prefix(3).enumerated() {
            print("[CloudConvert]   \(i + 1). \(seg)")
        }
        
        // دمج segments
        print("[CloudConvert] Merging segments...")
        let merged = try await mergeTS(segments: segs, baseFolder: m3u8URL.deletingLastPathComponent())
        defer { try? FileManager.default.removeItem(at: merged) }
        
        let size = (try? merged.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        print("[CloudConvert] ✅ Merged TS: \(size / 1024 / 1024) MB")
        
        guard size > 0 else {
            throw AudioPipelineError.exportFailed("ملف الدمج فارغ")
        }
        
        // إنشاء job
        print("[CloudConvert] Creating job...")
        let createURL = URL(string: "https://api.cloudconvert.com/v2/jobs")!
        var createReq = URLRequest(url: createURL)
        createReq.httpMethod = "POST"
        createReq.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        createReq.setValue("application/json", forHTTPHeaderField: "Content-Type")
        createReq.timeoutInterval = 60
        
        // لا نمرّر input_format صراحةً — CloudConvert يحدّد صيغة الإدخال من امتداد
        // الملف المرفوع (merge-*.ts). في الترجمة نحول إلى M4A فقط: لا فيديو
        // لإعادة ترميزه ولا ملف MP4 كبير لتنزيله بعد اكتمال التحويل.
        var convertTask: [String: Any] = [
            "operation": "convert",
            "input": ["import"],
            "output_format": outputFormat,
            "engine": "ffmpeg",
            "audio_codec": "aac"
        ]
        if !audioOnly {
            convertTask["video_codec"] = "h264"
        }
        let jobBody: [String: Any] = [
            "tasks": [
                "import": ["operation": "import/upload"],
                "convert": convertTask,
                "export": ["operation": "export/url", "input": ["convert"], "multiple": false]
            ],
            "tag": audioOnly ? "video2-hls-audio" : "video2-hls"
        ]
        createReq.httpBody = try JSONSerialization.data(withJSONObject: jobBody)
        
        let (createData, createResp) = try await URLSession.shared.data(for: createReq)
        
        guard let hr = createResp as? HTTPURLResponse else {
            print("[CloudConvert] ❌ Invalid response")
            throw AudioPipelineError.exportFailed("CloudConvert: استجابة غير صالحة")
        }
        
        print("[CloudConvert] HTTP status: \(hr.statusCode)")
        
        guard (200...299).contains(hr.statusCode) else {
            let errorBody = String(data: createData, encoding: .utf8) ?? "unknown"
            print("[CloudConvert] ❌ HTTP error: \(hr.statusCode)")
            print("[CloudConvert] Error body: \(errorBody)")
            throw AudioPipelineError.exportFailed("CloudConvert: فشل إنشاء job — HTTP \(hr.statusCode)")
        }
        
        guard let json = try? JSONSerialization.jsonObject(with: createData) as? [String: Any] else {
            print("[CloudConvert] ❌ Failed to parse JSON")
            throw AudioPipelineError.exportFailed("CloudConvert: استجابة غير صالحة")
        }
        
        guard let jobData = json["data"] as? [String: Any] else {
            print("[CloudConvert] ❌ No 'data' in response")
            print("[CloudConvert] Response: \(json)")
            throw AudioPipelineError.exportFailed("CloudConvert: استجابة غير صالحة")
        }
        
        guard let jobID = jobData["id"] as? String else {
            print("[CloudConvert] ❌ No 'id' in data")
            print("[CloudConvert] Data: \(jobData)")
            throw AudioPipelineError.exportFailed("CloudConvert: استجابة غير صالحة")
        }
        
        print("[CloudConvert] ✅ Job created: \(jobID)")
        
        // الحصول على upload URL — في CloudConvert v2 بيانات الرفع تظهر في result.form (وليس params.upload_url)
        print("[CloudConvert] Getting upload URL...")
        let jobURL = URL(string: "https://api.cloudconvert.com/v2/jobs/\(jobID)")!
        
        func fetchJob() async throws -> [String: Any] {
            var req = URLRequest(url: jobURL)
            req.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard let hr = resp as? HTTPURLResponse else {
                throw AudioPipelineError.exportFailed("CloudConvert: استجابة تفاصيل المهمة غير صالحة")
            }
            guard (200...299).contains(hr.statusCode) else {
                let body = String(data: data, encoding: .utf8) ?? ""
                throw AudioPipelineError.exportFailed("CloudConvert: فشل قراءة تفاصيل المهمة — HTTP \(hr.statusCode) \(String(body.prefix(180)))")
            }
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let job = json["data"] as? [String: Any] else {
                throw AudioPipelineError.exportFailed("CloudConvert: تفاصيل المهمة غير صالحة")
            }
            return job
        }
        
        // في CloudConvert v2 توجد tasks مباشرة داخل data.tasks، لا داخل
        // relationships. ننتظر لحظة فقط إن لم يُنشأ نموذج الرفع بعد.
        var formURL: URL?
        var formParams: [String: Any] = [:]
        let uploadWait = Date()
        while formURL == nil, Date().timeIntervalSince(uploadWait) < 30 {
            let job = try await fetchJob()
            guard let tasks = job["tasks"] as? [[String: Any]],
                  let uploadTask = tasks.first(where: { $0["operation"] as? String == "import/upload" }),
                  let form = (uploadTask["result"] as? [String: Any])?["form"] as? [String: Any],
                  let urlString = form["url"] as? String,
                  let url = URL(string: urlString) else {
                try await Task.sleep(nanoseconds: 1_000_000_000)
                continue
            }
            formURL = url
            formParams = (form["parameters"] as? [String: Any]) ?? [:]
            print("[CloudConvert] ✅ Upload URL ready")
        }
        
        guard let uploadURL = formURL else {
            print("[CloudConvert] ❌ Upload form not ready")
            throw AudioPipelineError.exportFailed("CloudConvert: لم يتم العثور على upload URL")
        }
        
        // رفع الملف — يجب إرسال خانات الـ form الموقّعة مع الملف في حقل file (وإلا 401 Invalid Signature)
        print("[CloudConvert] Uploading file (\(size / 1024 / 1024) MB)...")
        let boundary = "Boundary-\(UUID().uuidString)"
        var uploadReq = URLRequest(url: uploadURL)
        uploadReq.httpMethod = "POST"
        uploadReq.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        uploadReq.timeoutInterval = 600

        // نبني body الرفع على القرص ونرفعه بـ upload(for:fromFile:) — تحميل
        // الملف كاملاً في الذاكرة (Data(contentsOf:)) يُسقط التطبيق (OOM)
        // مع البثوث الطويلة التي تُنتج ملفات مدمجة بالجيجابايت.
        let bodyURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-body-\(UUID().uuidString).bin")
        do {
            FileManager.default.createFile(atPath: bodyURL.path, contents: nil)
            let bodyFH = try FileHandle(forWritingTo: bodyURL)
            func writeStr(_ s: String) throws {
                guard let d = s.data(using: .utf8) else {
                    throw AudioPipelineError.exportFailed("CloudConvert: ترميز غير صالح")
                }
                try bodyFH.write(contentsOf: d)
            }
            for (k, v) in formParams {
                try writeStr("--\(boundary)\r\n")
                try writeStr("Content-Disposition: form-data; name=\"\(k)\"\r\n\r\n")
                try writeStr("\(v)\r\n")
            }
            try writeStr("--\(boundary)\r\n")
            try writeStr("Content-Disposition: form-data; name=\"file\"; filename=\"\(merged.lastPathComponent)\"\r\n")
            try writeStr("Content-Type: application/octet-stream\r\n\r\n")
            let inFH = try FileHandle(forReadingFrom: merged)
            while true {
                let chunk = inFH.readData(ofLength: 2 * 1024 * 1024)
                if chunk.isEmpty { break }
                try bodyFH.write(contentsOf: chunk)
            }
            try inFH.close()
            try writeStr("\r\n--\(boundary)--\r\n")
            try bodyFH.close()
        }
        defer { try? FileManager.default.removeItem(at: bodyURL) }

        print("[CloudConvert] Sending upload request...")
        let (_, uploadResp) = try await URLSession.shared.upload(for: uploadReq, fromFile: bodyURL)
        
        guard let uhr = uploadResp as? HTTPURLResponse else {
            print("[CloudConvert] ❌ Invalid upload response")
            throw AudioPipelineError.exportFailed("CloudConvert: فشل رفع الملف")
        }
        
        print("[CloudConvert] Upload HTTP status: \(uhr.statusCode)")
        
        guard (200...299).contains(uhr.statusCode) else {
            print("[CloudConvert] ❌ Upload failed: HTTP \(uhr.statusCode)")
            throw AudioPipelineError.exportFailed("CloudConvert: فشل رفع الملف — HTTP \(uhr.statusCode)")
        }
        
        print("[CloudConvert] ✅ Uploaded successfully")
        
        // انتظار التحويل
        print("[CloudConvert] Waiting for conversion...")
        let start = Date()
        var lastStatus = ""
        
        while Date().timeIntervalSince(start) < 600 {
            var pollReq = URLRequest(url: jobURL)
            pollReq.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
            let (pollData, pollResp) = try await URLSession.shared.data(for: pollReq)
            guard let http = pollResp as? HTTPURLResponse else {
                throw AudioPipelineError.exportFailed("CloudConvert: استجابة متابعة التحويل غير صالحة")
            }
            guard (200...299).contains(http.statusCode) else {
                let body = String(data: pollData, encoding: .utf8) ?? ""
                throw AudioPipelineError.exportFailed("CloudConvert: فشل متابعة التحويل — HTTP \(http.statusCode) \(String(body.prefix(180)))")
            }
            guard let response = try? JSONSerialization.jsonObject(with: pollData) as? [String: Any],
                  let job = response["data"] as? [String: Any],
                  let status = job["status"] as? String else {
                throw AudioPipelineError.exportFailed("CloudConvert: حالة التحويل غير صالحة")
            }

            if status != lastStatus {
                print("[CloudConvert] Status: \(status)")
                lastStatus = status
            }

            if status == "finished" {
                print("[CloudConvert] ✅ Conversion finished")
                if let tasks = job["tasks"] as? [[String: Any]],
                   let exportTask = tasks.first(where: { $0["operation"] as? String == "export/url" }),
                   let result = exportTask["result"] as? [String: Any],
                   let files = result["files"] as? [[String: Any]],
                   let file = files.first,
                   let downloadURL = file["url"] as? String,
                   let url = URL(string: downloadURL) {
                    print("[CloudConvert] Downloading result...")
                    var request = URLRequest(url: url)
                    request.timeoutInterval = 600
                    // لا نجمع نتيجة التحويل (حتى M4A طويل) في Data بالذاكرة.
                    let (temporaryResult, downloadResponse) = try await URLSession.shared.download(for: request)
                    guard let downloadHTTP = downloadResponse as? HTTPURLResponse,
                          (200...299).contains(downloadHTTP.statusCode) else {
                        let statusCode = (downloadResponse as? HTTPURLResponse)?.statusCode ?? 0
                        throw AudioPipelineError.exportFailed("CloudConvert: فشل تنزيل النتيجة — HTTP \(statusCode)")
                    }
                    let out = FileManager.default.temporaryDirectory
                        .appendingPathComponent("cc-\(UUID().uuidString).\(outputFormat.lowercased())")
                    try? FileManager.default.removeItem(at: out)
                    try FileManager.default.moveItem(at: temporaryResult, to: out)
                    let outSize = (try? out.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                    guard outSize > 0 else {
                        throw AudioPipelineError.exportFailed("CloudConvert: ملف النتيجة فارغ")
                    }
                    print("[CloudConvert] ✅ Downloaded: \(outSize / 1024 / 1024) MB")
                    return out
                }
                throw AudioPipelineError.exportFailed("CloudConvert: لم يتم العثور على رابط تنزيل")
            }

            if status == "error" {
                var message = job["message"] as? String
                if message == nil, let tasks = job["tasks"] as? [[String: Any]] {
                    message = tasks.first(where: { $0["status"] as? String == "error" })?["message"] as? String
                }
                throw AudioPipelineError.exportFailed("CloudConvert: فشل التحويل — \(message ?? "خطأ غير معروف")")
            }

            try await Task.sleep(nanoseconds: 5_000_000_000)
        }
        
        print("[CloudConvert] ❌ Timeout after 10 minutes")
        throw AudioPipelineError.exportFailed("CloudConvert: انتهت مهلة الانتظار")
    }

    /// تحويل ملف وسائط محلي (TS أو MP4 مدمج) إلى MP4 عبر ffmpeg-api.com — مزود سحابي
    /// احتياطي يعمل كمستقل عن CloudConvert. يتطلّب مفتاحاً من https://ffmpeg-api.com
    private static func convertWithFFmpegAPI(fileURL: URL, apiKey: String) async throws -> URL {
        print("[ffmpeg-api] ═══════════════════════════════════════════")
        print("[ffmpeg-api] Starting file → MP4")
        print("[ffmpeg-api] File: \(fileURL.lastPathComponent) (\((try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) bytes)")

        let base = "https://api.ffmpeg-api.com"
        let auth = ["Authorization": "Basic \(apiKey)"]

        // 1) احصل على رابط رفع
        let fileBody = try JSONSerialization.data(withJSONObject: ["file_name": fileURL.lastPathComponent])
        let (fileData, _) = try await HTTP.request("POST", "\(base)/file", headers: auth, body: fileBody, timeout: 60)
        let fileJson = HTTP.json(from: fileData)
        guard let fileObj = fileJson["file"] as? [String: Any],
              let filePath = fileObj["file_path"] as? String,
              let uploadObj = fileJson["upload"] as? [String: Any],
              let uploadURL = uploadObj["url"] as? String else {
            print("[ffmpeg-api] ❌ Unexpected file response: \(fileJson)")
            throw AudioPipelineError.exportFailed("ffmpeg-api: استجابة غير متوقعة عند طلب رابط الرفع")
        }
        print("[ffmpeg-api] ✅ Got upload URL")

        // 2) ارفع الملف (PUT — نقرأه من القرص مباشرةً بلا تحميله كاملًا في الذاكرة)
        guard let uploadU = URL(string: uploadURL) else {
            throw AudioPipelineError.exportFailed("ffmpeg-api: رابط رفع غير صالح")
        }
        var uploadReq = URLRequest(url: uploadU)
        uploadReq.httpMethod = "PUT"
        uploadReq.timeoutInterval = 3600
        let (_, uploadResp) = try await URLSession.shared.upload(for: uploadReq, fromFile: fileURL)
        if let uhr = uploadResp as? HTTPURLResponse, !(200..<300).contains(uhr.statusCode) {
            throw AudioPipelineError.exportFailed("ffmpeg-api: فشل رفع الملف — HTTP \(uhr.statusCode)")
        }
        print("[ffmpeg-api] ✅ Uploaded")

        // 3) ابدأ المعالجة — نعيد التغليف (copy) حتى بدون إعادة ترميز مكلفة
        let taskBody = try JSONSerialization.data(withJSONObject: [
            "task": [
                "inputs": [["file_path": filePath]],
                "outputs": [["file": "output.mp4", "options": ["-c:v", "copy", "-c:a", "copy", "-movflags", "+faststart"]]]
            ]
        ])
        let (procData, _) = try await HTTP.request("POST", "\(base)/ffmpeg/process", headers: auth, body: taskBody, timeout: 300)
        let procJson = HTTP.json(from: procData)
        guard let results = procJson["result"] as? [[String: Any]],
              let first = results.first,
              let dlURL = first["download_url"] as? String else {
            let err = procJson["error"] as? String ?? "unknown"
            print("[ffmpeg-api] ❌ Process failed: \(err)")
            throw AudioPipelineError.exportFailed("ffmpeg-api: فشل التحويل — \(err)")
        }
        print("[ffmpeg-api] ✅ Processing done")

        // 4) نزّل النتيجة إلى القرص مباشرةً؛ هذا مسار احتياطي وقد تكون النتيجة MP4 كبيرة.
        guard let resultURL = URL(string: dlURL) else {
            throw AudioPipelineError.exportFailed("ffmpeg-api: رابط تنزيل غير صالح")
        }
        var downloadRequest = URLRequest(url: resultURL)
        downloadRequest.timeoutInterval = 600
        let (temporaryResult, downloadResponse) = try await URLSession.shared.download(for: downloadRequest)
        guard let downloadHTTP = downloadResponse as? HTTPURLResponse,
              (200...299).contains(downloadHTTP.statusCode) else {
            let status = (downloadResponse as? HTTPURLResponse)?.statusCode ?? 0
            throw AudioPipelineError.exportFailed("ffmpeg-api: فشل تنزيل النتيجة — HTTP \(status)")
        }
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("ffmpeg-api-\(UUID().uuidString).mp4")
        try? FileManager.default.removeItem(at: out)
        try FileManager.default.moveItem(at: temporaryResult, to: out)
        let sz = (try? out.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        print("[ffmpeg-api] ✅ Downloaded \(sz / 1024 / 1024) MB")
        return out
    }

    // MARK: ═══════════════════════════════════════════════════════════
    // MARK: تحويل HLS إلى وسائط — صوت محلي ثم بدائل MP4
    // ═════════════════════════════════════════════════════════════════

    /// يقتطع URI المُهيّئ (EXT-X-MAP) من قائمة m3u8 — ضروري لـ fMP4/CMAF.
    private static func hlsInitURI(from content: String) -> String? {
        for line in content.components(separatedBy: .newlines) {
            let t = line.trimmingCharacters(in: .whitespaces)
            guard t.hasPrefix("#EXT-X-MAP:") else { continue }
            if let start = t.range(of: "URI=\"") {
                let rest = t[start.upperBound...]
                if let end = rest.firstIndex(of: "\"") {
                    return String(rest[..<end])
                }
            }
        }
        return nil
    }

    /// محاولة سريعة قبل أي رفع سحابي: نخدم HLS المحلي عبر localhost ثم نستخدم
    /// AVFoundation لتصدير الصوت فقط. إذا لم يدعم الجهاز/الحاوية هذا المسار،
    /// يستدعي المستدعي CloudConvert وffmpeg كاحتياطيين كما كان سابقاً.
    private static func exportLocalHLSAudio(_ playlist: URL) async throws -> URL {
        let servedPlaylist = try LocalFileServer.shared.hlsURL(forPlaylist: playlist)
        let asset = AVURLAsset(url: servedPlaylist)
        let exportable = try await asset.load(.isExportable)
        guard exportable else {
            throw AudioPipelineError.exportFailed("HLS المحلي غير قابل للتصدير عبر AVFoundation")
        }
        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        guard !audioTracks.isEmpty else {
            throw AudioPipelineError.noAudioTrack
        }
        guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A),
              session.supportedFileTypes.contains(.m4a) else {
            throw AudioPipelineError.exportFailed("لا يدعم الجهاز تصدير صوت M4A لهذا البث")
        }

        let output = FileManager.default.temporaryDirectory
            .appendingPathComponent("hls-local-audio-\(UUID().uuidString).m4a")
        try? FileManager.default.removeItem(at: output)
        session.outputURL = output
        session.outputFileType = .m4a
        session.shouldOptimizeForNetworkUse = true
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            session.exportAsynchronously { continuation.resume() }
        }
        guard session.status == .completed,
              ((try? output.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) > 0 else {
            try? FileManager.default.removeItem(at: output)
            throw AudioPipelineError.exportFailed(session.error?.localizedDescription ?? "تعذر تصدير صوت HLS محلياً")
        }
        return output
    }

    private static func exportHLSToTempMedia(_ url: URL, outputFormat: String) async throws -> URL {
        print("[AudioPipeline] ═══════════════════════════════════════")
        print("[AudioPipeline] Starting HLS → \(outputFormat.uppercased()) conversion")
        print("[AudioPipeline] URL: \(url.path)")
        
        guard FileManager.default.fileExists(atPath: url.path) else {
            print("[AudioPipeline] ❌ File not found: \(url.path)")
            throw AudioPipelineError.exportFailed("ملف HLS غير موجود: \(url.lastPathComponent)")
        }
        
        print("[AudioPipeline] ✅ File exists")
        print("[AudioPipeline] m3u8: \(url.lastPathComponent)")

        guard let content = try? String(contentsOf: url, encoding: .utf8) else {
            throw AudioPipelineError.exportFailed("تعذر قراءة m3u8")
        }
        
        let lines = content.components(separatedBy: .newlines)
        let segmentPaths: [String] = lines.compactMap { l in
            let t = l.trimmingCharacters(in: .whitespaces)
            return t.isEmpty || t.hasPrefix("#") ? nil : t
        }
        guard !segmentPaths.isEmpty else {
            throw AudioPipelineError.exportFailed("ملف m3u8 فارغ")
        }

        let m3u8Folder = url.deletingLastPathComponent()
        let upper = content.uppercased()
        let isEncrypted = upper.contains("EXT-X-KEY:METHOD=AES-128") ||
            upper.contains("EXT-X-KEY:METHOD=SAMPLE-AES") ||
            upper.contains("EXT-X-KEY:METHOD=SAMPLE-AES-CTR")

        // يحدّد نوع الحاوية: fMP4/CMAF (init.mp4 + m4s) أم MPEG-TS (.ts).
        // AVFoundation لا يستطيع قراءة .ts محلياً، بينما يقرأ fMP4 بسهولة.
        let isFMP4 = hlsInitURI(from: content) != nil ||
            segmentPaths.contains { $0.lowercased().hasSuffix(".m4s") } ||
            segmentPaths.contains { $0.lowercased().hasSuffix(".mp4") }

        // HLS مشفّر AES-128: لا يمكن فكّه محلياً بدون المفتاح — نعتمد على CloudConvert
        // قدر الإمكان، ونحرّر err واضح بدل "ALL METHODS FAILED".
        if isEncrypted {
            print("[AudioPipeline] ⚠️ Detected AES-128-encrypted HLS")
        }

        if isFMP4 && !isEncrypted {
            print("[AudioPipeline] ═══ Detected fMP4/CMAF HLS → embedding (method 1)...")
            do {
                let result = try await tryFMP4Method(segments: segmentPaths, initURI: hlsInitURI(from: content), folder: m3u8Folder)
                print("[AudioPipeline] ✅ fMP4 embed succeeded!")
                return result
            } catch {
                print("[AudioPipeline] ⚠️ fMP4 embed failed: \(error.localizedDescription) — falling through")
            }
        }

        // MPEG-TS (أو HLS مشفّر): AVFoundation لا يقرأه محلياً — نعتمد على خدمة
        // ffmpeg سحابية. نجرّب CloudConvert أولاً ثم ffmpeg-api.com كمزوّد احتياطي.
        var cloudErrors: [String] = []

        if hasCloudConvertKey {
            print("[AudioPipeline] ═══ Trying CloudConvert API (MPEG-TS)...")
            do {
                let result = try await convertWithCloudConvert(m3u8URL: url, outputFormat: outputFormat)
                print("[AudioPipeline] ✅ CloudConvert succeeded!")
                return result
            } catch {
                cloudErrors.append("CloudConvert: \(error.localizedDescription)")
                print("[AudioPipeline] ⚠️ CloudConvert failed: \(error.localizedDescription)")
            }
        } else {
            cloudErrors.append("CloudConvert: لا يوجد مفتاح (المفتاح محفوظ في الإعدادات)")
            print("[AudioPipeline] ⚠️ CloudConvert not available (no API key) — TS يحتاج ffmpeg")
        }

        if let ffmpegKey = ffmpegApiKey() {
            print("[AudioPipeline] ═══ Trying ffmpeg-api.com (backup)...")
            do {
                let merged = try await mergeTS(segments: segmentPaths, baseFolder: m3u8Folder)
                defer { try? FileManager.default.removeItem(at: merged) }
                let result = try await convertWithFFmpegAPI(fileURL: merged, apiKey: ffmpegKey)
                print("[AudioPipeline] ✅ ffmpeg-api succeeded!")
                return result
            } catch {
                cloudErrors.append("ffmpeg-api: \(error.localizedDescription)")
                print("[AudioPipeline] ⚠️ ffmpeg-api failed: \(error.localizedDescription)")
            }
        } else {
            cloudErrors.append("ffmpeg-api: لا يوجد مفتاح")
            print("[AudioPipeline] ⚠️ ffmpeg-api not available (no API key)")
        }

        // المحاولة الأخيرة محلياً: ندمج TS ونحاول قراءته (غالباً يفشل لأن AVFoundation
        // لا يقرأ .ts، لكن نجرّب قبل الإقلاع).
        print("[AudioPipeline] ═══ Trying native merge (method 2)...")
        do {
            let merged = try await mergeTS(segments: segmentPaths, baseFolder: m3u8Folder)
            defer { try? FileManager.default.removeItem(at: merged) }
            let result = try await transcodeFileToMP4(merged)
            print("[AudioPipeline] ✅ Native merge succeeded!")
            return result
        } catch {
            print("[AudioPipeline] ⚠️ Native merge failed: \(error.localizedDescription)")
        }

        print("[AudioPipeline] ❌ ALL METHODS FAILED")
        if isEncrypted {
            throw AudioPipelineError.exportFailed("هذا البث HLS مشفّر بـ AES-128 ولا يمكن فكّه محلياً بدون مفتاح فكّ التشفير — جرّب رابطاً غير مشفّر.")
        }
        if !hasCloudConvertKey && ffmpegApiKey() == nil {
            throw AudioPipelineError.exportFailed("تحويل هذا البث (MPEG-TS) يحتاج ffmpeg عبر خدمة سحابية — احفظ مفتاح CloudConvert (أو ffmpeg-api) من الإعدادات ثم أعد المحاولة.")
        }
        if !cloudErrors.isEmpty {
            throw AudioPipelineError.exportFailed("تعذّر التحويل عبر خدمات السحابة:\n" + cloudErrors.joined(separator: "\n"))
        }
        throw AudioPipelineError.exportFailed("تعذّر تحويل HLS إلى \(outputFormat.uppercased()): لا توجد خدمة تحويل متاحة.")
    }

    /// تحويل كامل إلى MP4 — يستخدم عند التصدير أو التحويل اليدوي فقط.
    static func exportHLSToTempMP4(_ url: URL) async throws -> URL {
        try await exportHLSToTempMedia(url, outputFormat: "mp4")
    }

    /// مسار الترجمة: نطلب الصوت فقط من السحابة لتجنب تنزيل فيديو محوّل كامل.
    private static func exportHLSToTempAudio(_ url: URL) async throws -> URL {
        try await exportHLSToTempMedia(url, outputFormat: "m4a")
    }

    // MARK: ── الطريقة 1 (fMP4/CMAF): دمج init.mp4 + m4s ثم إعادة تصدير ──

    /// HLS من نوع fMP4/CMAF: نلحم `init.mp4` (moov) مع كل `segment*.m4s` (moof+mdat)
    /// في ملف MP4 واحد (معطَّل التجزئة) ثم نمرّره على AVAssetExportSession.
    /// هذا يتجاوز مشكلة أن AVFoundation يرفض قراءة قائمة `.m3u8` المحلية مباشرةً.
    private static func tryFMP4Method(segments: [String], initURI: String?, folder: URL) async throws -> URL {
        print("[AudioPipeline] ═══ fMP4/CMAF: concatenating init.mp4 + segments...")

        var initURL: URL?
        if let initURI = initURI {
            let candidate: URL = initURI.hasPrefix("/")
                ? URL(fileURLWithPath: initURI)
                : folder.appendingPathComponent(initURI)
            if FileManager.default.fileExists(atPath: candidate.path) {
                initURL = candidate
            }
        }
        if initURL == nil {
            let fallback = folder.appendingPathComponent("init.mp4")
            if FileManager.default.fileExists(atPath: fallback.path) {
                initURL = fallback
            }
        }

        let out = FileManager.default.temporaryDirectory.appendingPathComponent("v2-fmp4-\(UUID().uuidString).mp4")
        guard FileManager.default.createFile(atPath: out.path, contents: nil) else {
            throw AudioPipelineError.exportFailed("تعذر إنشاء ملف مؤقت")
        }
        let fh = try FileHandle(forWritingTo: out)
        defer { try? fh.close() }

        var totalBytes = 0
        if let initURL = initURL, let data = try? Data(contentsOf: initURL) {
            fh.write(data)
            totalBytes += data.count
            print("[AudioPipeline] ✅ Init: \(initURL.lastPathComponent) (\(data.count / 1024) KB)")
        } else {
            print("[AudioPipeline] ⚠️ No init.mp4 found — writing segments only")
        }

        var added = 0
        for seg in segments {
            let segURL: URL = seg.hasPrefix("/")
                ? URL(fileURLWithPath: seg)
                : folder.appendingPathComponent(seg)
            guard FileManager.default.fileExists(atPath: segURL.path) else {
                print("[AudioPipeline]   ❌ Not found: \(segURL.lastPathComponent)")
                continue
            }
            guard let data = try? Data(contentsOf: segURL) else { continue }
            fh.write(data)
            totalBytes += data.count
            added += 1
            print("[AudioPipeline]   ✅ \(segURL.lastPathComponent) (\(data.count / 1024) KB)")
        }

        try? fh.close()
        guard added > 0, totalBytes > 0 else {
            try? FileManager.default.removeItem(at: out)
            throw AudioPipelineError.exportFailed("لم يتم لحم أي segment")
        }
        print("[AudioPipeline] ✅ Concatenated \(added) segments (\(totalBytes / 1024 / 1024) MB)")

        // أي وظيفة تصدير/فكّ التجزئة، مع حذف الملف الملمّح بعد انتهاء التصدير.
        do {
            let result = try await transcodeFileToMP4(out)
            try? FileManager.default.removeItem(at: out)
            return result
        } catch {
            try? FileManager.default.removeItem(at: out)
            throw error
        }
    }

    /// يمرّر ملف وسائط (يفضَّل MP4 ملمّح أو مدمج) عبر AVAssetExportSession
    /// ليخرج MP4 نظيفاً — يجرّب Passthrough أولاً ثم إعادة ترميز عالية الجودة.
    private static func transcodeFileToMP4(_ input: URL) async throws -> URL {
        let asset = AVURLAsset(url: input)

        // بعض ملفات fMP4 الملمّحة تُبلّغ مدة 0 حتى تُحمَّل — لا نستمر على المدة،
        // بل نكتفي بوجود مسار صوت/فيديو واحد على الأقل.
        let hasVideo = (try? await asset.loadTracks(withMediaType: .video))?.isEmpty == false
        let hasAudio = (try? await asset.loadTracks(withMediaType: .audio))?.isEmpty == false
        guard hasVideo || hasAudio else {
            throw AudioPipelineError.exportFailed("الملف لا يحتوي مسارات وسائط قابلة للقراءة")
        }

        let out = FileManager.default.temporaryDirectory.appendingPathComponent("v2-tx-\(UUID().uuidString).mp4")
        try? FileManager.default.removeItem(at: out)

        let presets = [AVAssetExportPresetPassthrough, AVAssetExportPresetHighestQuality,
                       AVAssetExportPresetMediumQuality, AVAssetExportPresetLowQuality]

        for preset in presets {
            let compatible = AVAssetExportSession.exportPresets(compatibleWith: asset)
            guard compatible.contains(preset) else { continue }
            guard let session = AVAssetExportSession(asset: asset, presetName: preset) else { continue }
            guard session.supportedFileTypes.contains(.mp4) else { continue }

            try? FileManager.default.removeItem(at: out)
            session.outputURL = out
            session.outputFileType = .mp4
            session.shouldOptimizeForNetworkUse = true

            print("[AudioPipeline] Exporting with preset: \(preset)")
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                session.exportAsynchronously { c.resume() }
            }

            if session.status == .completed {
                let sz = (try? out.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                if sz > 0 {
                    print("[AudioPipeline] ✅ Export succeeded (\(sz / 1024 / 1024) MB) preset=\(preset)")
                    return out
                }
            }
            print("[AudioPipeline] ❌ Export failed: \(session.error?.localizedDescription ?? "unknown")")
        }

        throw AudioPipelineError.exportFailed("فشل التصدير إلى MP4")
    }

    // MARK: ── الطريقة 2: AVMutableComposition ──

    private static func tryCompositionMethod(segments: [String], folder: URL) async throws -> URL {
        print("[AudioPipeline] Creating AVMutableComposition...")
        let composition = AVMutableComposition()
        // نستخدم مساراً واحداً لكل نوع (فيديو / صوت) بدلاً من إنشاء مسار لكل segment
        // — إنشاء مسار جديد لكل segment يجعل التصدير يفشل.
        var videoTrack: AVMutableCompositionTrack?
        var audioTrack: AVMutableCompositionTrack?
        var currentTime = CMTime.zero
        var addedCount = 0

        for (index, segPath) in segments.enumerated() {
            print("[AudioPipeline] Processing segment \(index + 1)/\(segments.count)")
            
            if Task.isCancelled { throw CancellationError() }
            
            let segURL: URL
            if segPath.hasPrefix("/") { 
                segURL = URL(fileURLWithPath: segPath) 
            } else { 
                segURL = folder.appendingPathComponent(segPath) 
            }
            
            guard FileManager.default.fileExists(atPath: segURL.path) else {
                print("[AudioPipeline]   ❌ Not found: \(segURL.lastPathComponent)")
                continue
            }
            
            print("[AudioPipeline]   ✅ Found: \(segURL.lastPathComponent)")
            
            let segAsset = AVURLAsset(url: segURL)
            
            do {
                let duration = try await segAsset.load(.duration)
                let durationSec = CMTimeGetSeconds(duration)
                print("[AudioPipeline]   Duration: \(durationSec)s")
                
                guard durationSec > 0 else { 
                    print("[AudioPipeline]   ❌ Invalid duration")
                    continue 
                }

                let videoTracks = try await segAsset.loadTracks(withMediaType: .video)
                print("[AudioPipeline]   Video tracks: \(videoTracks.count)")
                
                for srcTrack in videoTracks {
                    if videoTrack == nil {
                        videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)
                        print("[AudioPipeline]   ✅ Created video track")
                    }
                    let tr = try await srcTrack.load(.timeRange)
                    try videoTrack?.insertTimeRange(tr, of: srcTrack, at: currentTime)
                }
                
                let audioTracks = try await segAsset.loadTracks(withMediaType: .audio)
                print("[AudioPipeline]   Audio tracks: \(audioTracks.count)")
                
                for srcTrack in audioTracks {
                    if audioTrack == nil {
                        audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
                        print("[AudioPipeline]   ✅ Created audio track")
                    }
                    let tr = try await srcTrack.load(.timeRange)
                    try audioTrack?.insertTimeRange(tr, of: srcTrack, at: currentTime)
                }
                
                currentTime = CMTimeAdd(currentTime, duration)
                addedCount += 1
                print("[AudioPipeline]   ✅ Segment added successfully")
                
            } catch {
                print("[AudioPipeline]   ❌ Error: \(error.localizedDescription)")
                continue
            }
        }
        
        guard addedCount > 0 else { 
            print("[AudioPipeline] ❌ No segments added")
            throw AudioPipelineError.exportFailed("لم يتم إضافة أي segment") 
        }
        
        print("[AudioPipeline] ✅ Composition created: \(addedCount) segments")

        let out = FileManager.default.temporaryDirectory.appendingPathComponent("v2-comp-\(UUID().uuidString).mp4")
        try? FileManager.default.removeItem(at: out)
        
        print("[AudioPipeline] Exporting composition...")

        for preset in [AVAssetExportPresetPassthrough, AVAssetExportPresetHighestQuality, AVAssetExportPresetMediumQuality] {
            print("[AudioPipeline] Trying preset: \(preset)")
            
            let compatible = AVAssetExportSession.exportPresets(compatibleWith: composition)
            
            guard compatible.contains(preset) else { 
                print("[AudioPipeline]   ❌ Not compatible")
                continue 
            }
            
            guard let session = AVAssetExportSession(asset: composition, presetName: preset) else { 
                print("[AudioPipeline]   ❌ Failed to create session")
                continue 
            }
            
            guard session.supportedFileTypes.contains(.mp4) else { 
                print("[AudioPipeline]   ❌ MP4 not supported")
                continue 
            }
            
            session.outputURL = out
            session.outputFileType = .mp4
            session.shouldOptimizeForNetworkUse = true
            
            print("[AudioPipeline]   Exporting...")
            
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                session.exportAsynchronously { c.resume() }
            }
            
            if session.status == .completed {
                let sz = (try? out.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                if sz > 0 { 
                    print("[AudioPipeline]   ✅ Export succeeded: \(sz / 1024 / 1024) MB")
                    return out 
                }
            }
            
            print("[AudioPipeline]   ❌ Export failed: \(session.error?.localizedDescription ?? "unknown")")
            try? FileManager.default.removeItem(at: out)
        }
        
        print("[AudioPipeline] ❌ All presets failed")
        throw AudioPipelineError.exportFailed("فشل تصدير composition")
    }

    // MARK: ── الطريقة 3: segment-by-segment ──

    private static func trySegmentBySegmentMethod(segments: [String], folder: URL) async throws -> URL {
        print("[AudioPipeline] Creating segment-by-segment output...")
        
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("v2-seg-\(UUID().uuidString).m4a")
        try? FileManager.default.removeItem(at: out)
        
        print("[AudioPipeline] Output: \(out.lastPathComponent)")
        
        let writer = try AVAssetWriter(url: out, fileType: .m4a)
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 32000
        ]
        let writerInput = AVAssetWriterInput(mediaType: .audio, outputSettings: settings)
        writerInput.expectsMediaDataInRealTime = false
        
        guard writer.canAdd(writerInput) else { 
            print("[AudioPipeline] ❌ Cannot add writer input")
            throw AudioPipelineError.writerFailed("لا يمكن إضافة writer input") 
        }
        
        writer.add(writerInput)
        writer.shouldOptimizeForNetworkUse = true
        
        guard writer.startWriting() else { 
            print("[AudioPipeline] ❌ Failed to start writing")
            throw AudioPipelineError.writerFailed(writer.error?.localizedDescription ?? "فشل") 
        }
        
        print("[AudioPipeline] ✅ Writer started")

        var currentTime = CMTime.zero
        var totalSamples = 0

        for (index, segPath) in segments.enumerated() {
            print("[AudioPipeline] Reading segment \(index + 1)/\(segments.count)")
            
            if Task.isCancelled { 
                try? FileManager.default.removeItem(at: out)
                throw CancellationError() 
            }
            
            let segURL: URL
            if segPath.hasPrefix("/") { 
                segURL = URL(fileURLWithPath: segPath) 
            } else { 
                segURL = folder.appendingPathComponent(segPath) 
            }
            
            guard FileManager.default.fileExists(atPath: segURL.path) else { 
                print("[AudioPipeline]   ❌ Not found")
                continue 
            }
            
            let segAsset = AVURLAsset(url: segURL)
            
            do {
                let duration = try await segAsset.load(.duration)
                let durationSec = CMTimeGetSeconds(duration)
                
                guard durationSec > 0 else { 
                    print("[AudioPipeline]   ❌ Invalid duration")
                    continue 
                }
                
                let audioTracks = try await segAsset.loadTracks(withMediaType: .audio)
                guard let audioTrack = audioTracks.first else { 
                    print("[AudioPipeline]   ❌ No audio track")
                    continue 
                }
                
                print("[AudioPipeline]   Creating reader...")
                let reader = try AVAssetReader(asset: segAsset)
                let pcmSettings: [String: Any] = [
                    AVFormatIDKey: kAudioFormatLinearPCM,
                    AVSampleRateKey: sampleRate,
                    AVNumberOfChannelsKey: 1,
                    AVLinearPCMBitDepthKey: 16,
                    AVLinearPCMIsFloatKey: false,
                    AVLinearPCMIsBigEndianKey: false,
                    AVLinearPCMIsNonInterleaved: false
                ]
                let readerOutput = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: pcmSettings)
                reader.add(readerOutput)
                
                guard reader.startReading() else { 
                    print("[AudioPipeline]   ❌ Failed to start reading")
                    continue 
                }
                
                writer.startSession(atSourceTime: currentTime)
                
                var segSamples = 0
                
                while let sb = readerOutput.copyNextSampleBuffer() {
                    while !writerInput.isReadyForMoreMediaData { 
                        try await Task.sleep(nanoseconds: 5_000_000) 
                    }
                    
                    if !writerInput.append(sb) { 
                        print("[AudioPipeline]   ❌ Failed to append sample")
                        break 
                    }
                    
                    segSamples += 1
                    totalSamples += 1
                }
                
                if reader.status == .failed { 
                    print("[AudioPipeline]   ❌ Reader failed")
                    continue 
                }
                
                let segDur = Double(segSamples) / sampleRate
                currentTime = CMTimeAdd(currentTime, CMTime(seconds: segDur, preferredTimescale: Int32(sampleRate)))
                print("[AudioPipeline]   ✅ \(segSamples) samples")
                
            } catch {
                print("[AudioPipeline]   ❌ Error: \(error.localizedDescription)")
                continue
            }
        }

        guard totalSamples > 0 else {
            print("[AudioPipeline] ❌ No samples read")
            try? FileManager.default.removeItem(at: out)
            throw AudioPipelineError.exportFailed("لم يتم قراءة أي عينات صوتية")
        }
        
        print("[AudioPipeline] Finishing writer...")
        writerInput.markAsFinished()
        await writer.finishWriting()
        
        guard writer.status == .completed else {
            print("[AudioPipeline] ❌ Writer failed")
            try? FileManager.default.removeItem(at: out)
            throw AudioPipelineError.writerFailed(writer.error?.localizedDescription ?? "فشل")
        }
        
        let sz = (try? out.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        print("[AudioPipeline] ✅ Output: \(sz / 1024) KB")
        return out
    }

    // MARK: ═══════════════════════════════════════════════════════════
    // MARK: الاستخراج والتقطيع الصوتي
    // ═════════════════════════════════════════════════════════════════

    static func extractChunks(from videoURL: URL,
                              into dir: URL,
                              singleFile: Bool,
                              progress: @escaping (Double) -> Void) async throws -> (chunks: [AudioChunk], duration: Double) {

        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let chunksDir = dir.appendingPathComponent("chunks", isDirectory: true)
        try FileManager.default.createDirectory(at: chunksDir, withIntermediateDirectories: true)
        let manifestURL = dir.appendingPathComponent("chunks.json")

        var sourceURL = videoURL
        // شريط التقدم داخل مرحلة الاستخراج: HLS MPEG-TS يحتاج معالجة إضافية.
        // نطلب M4A فقط من CloudConvert بدلاً من MP4 كامل، لذلك لا نعيد ترميز
        // الفيديو ولا ننزله ثانية من السحابة لمجرد تفريغ الصوت.
        var progressBase: Double = 0
        var progressSpan: Double = 1
        if videoURL.pathExtension.lowercased() == "m3u8" {
            let audioCache = dir.appendingPathComponent("hls-source.m4a")
            let videoCache = dir.appendingPathComponent("hls-source.mp4")
            let cached: URL
            if FileManager.default.fileExists(atPath: audioCache.path) {
                cached = audioCache
            } else if FileManager.default.fileExists(atPath: videoCache.path) {
                // توافق مع المهام التي بدأت قبل هذا التحديث.
                cached = videoCache
            } else {
                progress(0.02)
                let temporary: URL
                do {
                    print("[AudioPipeline] ═══ Trying local HLS → M4A (no cloud upload)...")
                    temporary = try await exportLocalHLSAudio(videoURL)
                    print("[AudioPipeline] ✅ Local HLS audio export succeeded")
                } catch {
                    // هذا المسار اختياري: بعض HLS/إصدارات AVFoundation لا تقرأ
                    // MPEG-TS المحلي. نبقي كل بدائل CloudConvert وffmpeg عاملة.
                    print("[AudioPipeline] ⚠️ Local HLS audio export failed: \(error.localizedDescription) — using cloud fallback")
                    temporary = try await exportHLSToTempAudio(videoURL)
                }
                let ext = temporary.pathExtension.lowercased() == "m4a" ? "m4a" : "mp4"
                let destination = dir.appendingPathComponent("hls-source.\(ext)")
                try? FileManager.default.removeItem(at: destination)
                do { try FileManager.default.moveItem(at: temporary, to: destination) }
                catch { try FileManager.default.copyItem(at: temporary, to: destination) }
                cached = destination
            }
            guard FileManager.default.fileExists(atPath: cached.path) else {
                throw AudioPipelineError.exportFailed("فقد ملف HLS الصوتي المحوّل")
            }
            sourceURL = cached
            progressBase = 0.4
            progressSpan = 0.6
            progress(progressBase)
        }

        if let data = try? Data(contentsOf: manifestURL),
           let cached = try? JSONDecoder().decode([AudioChunk].self, from: data), !cached.isEmpty {
            let allExist = cached.allSatisfy { FileManager.default.fileExists(atPath: chunksDir.appendingPathComponent($0.fileName).path) }
            let durData = try? Data(contentsOf: dir.appendingPathComponent("duration.txt"))
            let dur = durData.flatMap { Double(String(data: $0, encoding: .utf8) ?? "") } ?? 0
            if allExist { progress(1); return (cached, dur) }
        }

        let asset = AVURLAsset(url: sourceURL)
        let duration = try await loadDuration(asset)
        try? String(duration).write(to: dir.appendingPathComponent("duration.txt"), atomically: true, encoding: .utf8)

        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        guard let track = audioTracks.first else { throw AudioPipelineError.noAudioTrack }
        let reader = try AVAssetReader(asset: asset)
        let pcmSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: 1, AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false, AVLinearPCMIsNonInterleaved: false
        ]
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: pcmSettings)
        output.alwaysCopiesSampleData = false
        reader.add(output)
        guard reader.startReading() else { throw AudioPipelineError.readerFailed(reader.error?.localizedDescription ?? "غير معروف") }

        let effectiveChunkSeconds = singleFile ? 1_000_000_000.0 : chunkSeconds
        var chunks: [AudioChunk] = []
        var chunkIndex = 0
        var chunkStartPTS: Double = 0
        var writer: AVAssetWriter? = nil
        var writerInput: AVAssetWriterInput? = nil
        var lastProgressFrac: Double = 0

        func makeWriter(index: Int) throws -> (AVAssetWriter, AVAssetWriterInput) {
            let fn = singleFile ? "audio-full.m4a" : String(format: "chunk-%03d.m4a", index)
            let u = chunksDir.appendingPathComponent(fn)
            try? FileManager.default.removeItem(at: u)
            let w = try AVAssetWriter(url: u, fileType: .m4a)
            let s: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: sampleRate, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 32000]
            let i = AVAssetWriterInput(mediaType: .audio, outputSettings: s)
            i.expectsMediaDataInRealTime = false
            if w.canAdd(i) { w.add(i) }
            w.shouldOptimizeForNetworkUse = true
            return (w, i)
        }
        func finishWriter() async throws {
            writerInput?.markAsFinished()
            if let w = writer {
                await w.finishWriting()
                if w.status == .failed { throw AudioPipelineError.writerFailed(w.error?.localizedDescription ?? "") }
            }
            writer = nil; writerInput = nil
        }

        while true {
            if Task.isCancelled { reader.cancelReading(); try? await finishWriter(); throw CancellationError() }
            guard let sample = output.copyNextSampleBuffer() else { break }
            let pts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
            if !pts.isFinite { continue }
            if writer == nil {
                chunkStartPTS = pts
                let (w, i) = try makeWriter(index: chunkIndex)
                w.startWriting(); w.startSession(atSourceTime: CMTime(seconds: pts, preferredTimescale: 600))
                writer = w; writerInput = i
            }
            // تقدم مستمر (لكل الوضعين: ملف واحد أو مقاطع) — شريط التقدم
            // يتحرك مع الوقت الفعلي بدل الانتظار حتى نهاية الجزء.
            if duration > 0 {
                let frac = min(1.0, max(0.0, pts / duration))
                if frac - lastProgressFrac >= 0.01 {
                    lastProgressFrac = frac
                    progress(progressBase + progressSpan * frac)
                }
            }
            if !singleFile && pts - chunkStartPTS >= effectiveChunkSeconds {
                try await finishWriter()
                chunks.append(AudioChunk(index: chunkIndex, fileName: String(format: "chunk-%03d.m4a", chunkIndex), start: chunkStartPTS, duration: pts - chunkStartPTS))
                chunkIndex += 1; chunkStartPTS = pts
                let (w, i) = try makeWriter(index: chunkIndex)
                w.startWriting(); w.startSession(atSourceTime: CMTime(seconds: pts, preferredTimescale: 600))
                writer = w; writerInput = i
            }
            // حارس ضد التعليق الأبدي: لو فشل writer (امتلاء التخزين/خطأ
            // ترميز) تبقى isReadyForMoreMediaData=false إلى الأبد — نحوّلها
            // إلى خطأ قابل للالتقاط بدل تجميد المهمة في "استخراج الصوت".
            while writerInput?.isReadyForMoreMediaData == false {
                if let w = writer, w.status == .failed {
                    reader.cancelReading()
                    throw AudioPipelineError.writerFailed(w.error?.localizedDescription ?? "تعذر الكتابة إلى ملف الصوت — تحقق من مساحة التخزين")
                }
                try await Task.sleep(nanoseconds: 5_000_000)
                if Task.isCancelled { reader.cancelReading(); try? await finishWriter(); throw CancellationError() }
            }
            if writerInput?.append(sample) == false {
                reader.cancelReading()
                throw AudioPipelineError.writerFailed(writer?.error?.localizedDescription ?? "تعذر إلحاق عيّنة صوت")
            }
        }

        if reader.status == .failed { throw AudioPipelineError.readerFailed(reader.error?.localizedDescription ?? "") }
        if let w = writer {
            writerInput?.markAsFinished(); await w.finishWriting()
            if w.status == .failed { throw AudioPipelineError.writerFailed(w.error?.localizedDescription ?? "") }
            chunks.append(AudioChunk(index: chunkIndex, fileName: singleFile ? "audio-full.m4a" : String(format: "chunk-%03d.m4a", chunkIndex), start: chunkStartPTS, duration: max(0, duration - chunkStartPTS)))
        }
        guard !chunks.isEmpty else { throw AudioPipelineError.noAudioTrack }
        let data = try JSONEncoder().encode(chunks)
        try data.write(to: manifestURL, options: .atomic)
        progress(1)
        return (chunks, duration)
    }

    static func loadDuration(_ asset: AVURLAsset) async throws -> Double {
        let d = try await asset.load(.duration)
        let s = CMTimeGetSeconds(d)
        return s.isFinite ? s : 0
    }

    static func cleanupAudio(in dir: URL) {
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("chunks", isDirectory: true))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("hls-source.mp4"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("hls-source.m4a"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("merged.ts"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("chunks.json"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("duration.txt"))
    }
}

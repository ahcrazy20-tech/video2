import Foundation
import AVFoundation

/// خدمة CloudConvert لتحويل الملفات عبر الإنترنت
/// مجاني 25 conversion/day
/// سجل في: https://cloudconvert.com
/// اعمل API Key من: Dashboard → API Keys
/// أضف الـ Key في Info.plist:
///   <key>CLOUDCONVERT_API_KEY</key>
///   <string>your-key-here</string>
final class CloudConvertService {
    
    static let shared = CloudConvertService()
    
    private init() {}
    
    // MARK: - الأخطاء
    
    enum CloudConvertError: LocalizedError {
        case missingAPIKey
        case invalidResponse
        case uploadFailed(Int)
        case jobFailed(String)
        case timeout
        case noResultURL
        
        var errorDescription: String? {
            switch self {
            case .missingAPIKey:
                return "CloudConvert API key غير موجود في Info.plist"
            case .invalidResponse:
                return "استجابة غير صالحة من CloudConvert"
            case .uploadFailed(let code):
                return "فشل رفع الملف: HTTP \(code)"
            case .jobFailed(let msg):
                return "فشل التحويل: \(msg)"
            case .timeout:
                return "انتهت مهلة الانتظار للتحويل"
            case .noResultURL:
                return "لم يتم العثور على رابط تنزيل"
            }
        }
    }
    
    // MARK: - API Key
    
    /// يجلب API key من Keychain (الأولوية) ثم Info.plist
    static func apiKey() -> String? {
        if let key = KeychainStore.get("cloudconvert"), !key.isEmpty {
            return key
        }
        if let key = Bundle.main.infoDictionary?["CLOUDCONVERT_API_KEY"] as? String {
            return key.isEmpty || key.contains("ضع") ? nil : key
        }
        return nil
    }
    
    /// يتحقق من توفر API key
    static var isAvailable: Bool {
        guard let key = apiKey() else { return false }
        return !key.isEmpty && key != "ضع_الـAPI_Key_هنا"
    }
    
    // MARK: - التحويل الرئيسي
    
    /// يحول ملف فيديو إلى MP4. يبقى للتحويل/التصدير اليدوي للفيديو.
    func convertToMP4(
        inputFile: URL,
        apiKey: String? = nil,
        progress: @escaping (Double) -> Void = { _ in }
    ) async throws -> URL {
        try await convert(inputFile: inputFile,
                          outputFormat: "mp4",
                          apiKey: apiKey,
                          progress: progress)
    }

    /// يحول الإدخال إلى M4A صوتي فقط. هذا هو المسار المناسب للتفريغ والترجمة.
    func convertToM4A(
        inputFile: URL,
        apiKey: String? = nil,
        progress: @escaping (Double) -> Void = { _ in }
    ) async throws -> URL {
        try await convert(inputFile: inputFile,
                          outputFormat: "m4a",
                          apiKey: apiKey,
                          progress: progress)
    }

    private func convert(
        inputFile: URL,
        outputFormat: String,
        apiKey: String?,
        progress: @escaping (Double) -> Void
    ) async throws -> URL {
        let key = apiKey ?? Self.apiKey()
        guard let apiKey = key, !apiKey.isEmpty else {
            throw CloudConvertError.missingAPIKey
        }

        print("[CloudConvert] ═══════════════════════════════════════")
        print("[CloudConvert] Starting conversion to \(outputFormat.uppercased()): \(inputFile.lastPathComponent)")
        let fileSize = (try? inputFile.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        print("[CloudConvert] Input size: \(fileSize / 1024 / 1024) MB")

        progress(0.05)
        let jobID = try await createJob(apiKey: apiKey, outputFormat: outputFormat)
        print("[CloudConvert] ✅ Job created: \(jobID)")

        progress(0.10)
        let uploadInfo = try await getUploadURL(apiKey: apiKey, jobID: jobID)
        print("[CloudConvert] Upload URL ready")

        progress(0.15)
        try await uploadFile(fileURL: inputFile, uploadURL: uploadInfo.url, formParams: uploadInfo.params)
        print("[CloudConvert] ✅ File uploaded")

        progress(0.50)
        let downloadURL = try await waitForJob(apiKey: apiKey, jobID: jobID, maxWait: 600) { p in
            progress(0.50 + 0.40 * p)
        }
        print("[CloudConvert] ✅ Conversion complete")

        progress(0.92)
        let outputFile = try await downloadResult(url: downloadURL,
                                                  originalName: inputFile.deletingPathExtension().lastPathComponent,
                                                  outputExtension: outputFormat)
        print("[CloudConvert] ✅ Downloaded: \(outputFile.lastPathComponent)")
        progress(1.0)
        return outputFile
    }

    // MARK: - HLS → M4A
    
    /// يحول HLS (m3u8) إلى M4A صوتي فقط لتفريغ/ترجمة أسرع.
    func convertHLS(
        m3u8URL: URL,
        apiKey: String? = nil,
        progress: @escaping (Double) -> Void = { _ in }
    ) async throws -> URL {
        
        print("[CloudConvert] ═══════════════════════════════════════")
        print("[CloudConvert] HLS → M4A audio conversion")
        
        guard FileManager.default.fileExists(atPath: m3u8URL.path) else {
            throw AudioPipelineError.exportFailed("ملف HLS غير موجود")
        }
        
        // نقرأ playlist ونستخرج .ts paths
        guard let playlistContent = try? String(contentsOf: m3u8URL, encoding: .utf8) else {
            throw AudioPipelineError.exportFailed("تعذر قراءة m3u8")
        }
        
        let lines = playlistContent.components(separatedBy: .newlines)
        let segmentPaths: [String] = lines.compactMap { line in
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard !trimmed.isEmpty, !trimmed.hasPrefix("#") else { return nil }
            return trimmed
        }
        
        guard !segmentPaths.isEmpty else {
            throw AudioPipelineError.exportFailed("ملف m3u8 فارغ")
        }
        
        print("[CloudConvert] Found \(segmentPaths.count) segments")
        
        // نلحم كل .ts في ملف واحد
        progress(0.01)
        let mergedTS = try await mergeTSSegments(
            paths: segmentPaths,
            baseFolder: m3u8URL.deletingLastPathComponent()
        ) { p in
            progress(0.01 + 0.09 * p)
        }
        
        defer { try? FileManager.default.removeItem(at: mergedTS) }
        
        let mergedSize = (try? mergedTS.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        print("[CloudConvert] Merged TS: \(mergedSize / 1024 / 1024) MB")
        
        // نحتاج الصوت فقط للتفريغ، فلا نعيد ترميز أو ننزّل فيديو MP4 كاملاً.
        let result = try await convertToM4A(
            inputFile: mergedTS,
            apiKey: apiKey,
            progress: { p in
                progress(0.10 + 0.90 * p)
            }
        )
        
        return result
    }
    
    // MARK: - Helper: إنشاء Job
    
    private func createJob(apiKey: String, outputFormat: String) async throws -> String {
        let url = URL(string: "https://api.cloudconvert.com/v2/jobs")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 60
        
        var convertTask: [String: Any] = [
            "operation": "convert",
            "input": ["task-import"],
            "output_format": outputFormat,
            "engine": "ffmpeg",
            "engine_version": "latest"
        ]
        if outputFormat.lowercased() == "m4a" {
            convertTask["audio_codec"] = "aac"
        }
        let body: [String: Any] = [
            "tasks": [
                "task-import": ["operation": "import/upload"],
                "task-convert": convertTask,
                "task-export": [
                    "operation": "export/url",
                    "input": ["task-convert"],
                    "multiple": false
                ]
            ],
            "tag": outputFormat.lowercased() == "m4a" ? "video2-audio" : "video2-app"
        ]
        
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResp = response as? HTTPURLResponse else {
            throw CloudConvertError.invalidResponse
        }
        
        guard (200...299).contains(httpResp.statusCode) else {
            let msg = String(data: data, encoding: .utf8) ?? "unknown"
            print("[CloudConvert] ❌ Create job failed: \(msg)")
            throw CloudConvertError.uploadFailed(httpResp.statusCode)
        }
        
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let jobData = json["data"] as? [String: Any],
              let jobID = jobData["id"] as? String else {
            throw CloudConvertError.invalidResponse
        }
        
        return jobID
    }
    
    // MARK: - Helper: الحصول على Upload URL
    
    private struct UploadInfo {
        let url: URL
        let taskID: String
        let params: [String: Any]
    }
    
    private func getUploadURL(apiKey: String, jobID: String) async throws -> UploadInfo {
        let url = URL(string: "https://api.cloudconvert.com/v2/jobs/\(jobID)")!
        var request = URLRequest(url: url)
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResp = response as? HTTPURLResponse, (200...299).contains(httpResp.statusCode) else {
            throw CloudConvertError.invalidResponse
        }
        
        // CloudConvert v2 يعيد المهمة داخل data، والمهام داخل data.tasks.
        // لا توجد طبقة JSON:API باسم relationships في هذه الاستجابة.
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let job = json["data"] as? [String: Any],
              let tasks = job["tasks"] as? [[String: Any]] else {
            throw CloudConvertError.invalidResponse
        }
        
        // في CloudConvert v2 بيانات الرفع تظهر في result.form (وليس params.upload_url)
        guard let uploadTask = tasks.first(where: { $0["operation"] as? String == "import/upload" }),
              let taskID = uploadTask["id"] as? String,
              let form = (uploadTask["result"] as? [String: Any])?["form"] as? [String: Any],
              let urlStr = form["url"] as? String,
              let uploadURL = URL(string: urlStr) else {
            throw CloudConvertError.invalidResponse
        }
        
        let formParams = (form["parameters"] as? [String: Any]) ?? [:]
        return UploadInfo(url: uploadURL, taskID: taskID, params: formParams)
    }
    
    // MARK: - Helper: رفع الملف
    
    private func uploadFile(fileURL: URL, uploadURL: URL, formParams: [String: Any]) async throws {
        let boundary = "Boundary-\(UUID().uuidString)"
        var request = URLRequest(url: uploadURL)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 600

        // نجمع multipart على القرص ثم نرفعه من ملف؛ Data(contentsOf:) لملف HLS
        // طويل كانت قد تضاعف الذاكرة وتُسقط التطبيق.
        let bodyURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("cloudconvert-body-\(UUID().uuidString).bin")
        defer { try? FileManager.default.removeItem(at: bodyURL) }
        guard FileManager.default.createFile(atPath: bodyURL.path, contents: nil) else {
            throw CloudConvertError.invalidResponse
        }
        let bodyHandle = try FileHandle(forWritingTo: bodyURL)
        defer { try? bodyHandle.close() }
        func write(_ string: String) throws {
            try bodyHandle.write(contentsOf: Data(string.utf8))
        }
        for (key, value) in formParams {
            try write("--\(boundary)\r\n")
            try write("Content-Disposition: form-data; name=\"\(key)\"\r\n\r\n")
            try write("\(value)\r\n")
        }
        try write("--\(boundary)\r\n")
        try write("Content-Disposition: form-data; name=\"file\"; filename=\"\(fileURL.lastPathComponent)\"\r\n")
        try write("Content-Type: application/octet-stream\r\n\r\n")
        let input = try FileHandle(forReadingFrom: fileURL)
        defer { try? input.close() }
        while true {
            let chunk = input.readData(ofLength: 2 * 1024 * 1024)
            if chunk.isEmpty { break }
            try bodyHandle.write(contentsOf: chunk)
        }
        try write("\r\n--\(boundary)--\r\n")
        try bodyHandle.close()

        let (_, response) = try await URLSession.shared.upload(for: request, fromFile: bodyURL)
        guard let httpResp = response as? HTTPURLResponse else {
            throw CloudConvertError.invalidResponse
        }
        guard (200...299).contains(httpResp.statusCode) else {
            throw CloudConvertError.uploadFailed(httpResp.statusCode)
        }
    }
    
    // MARK: - Helper: انتظار انتهاء التحويل
    
    private func waitForJob(
        apiKey: String,
        jobID: String,
        maxWait: TimeInterval,
        progress: @escaping (Double) -> Void
    ) async throws -> String {
        
        let url = URL(string: "https://api.cloudconvert.com/v2/jobs/\(jobID)")!
        let startTime = Date()
        
        while Date().timeIntervalSince(startTime) < maxWait {
            var request = URLRequest(url: url)
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
            
            let (data, response) = try await URLSession.shared.data(for: request)
            
            guard let httpResp = response as? HTTPURLResponse, (200...299).contains(httpResp.statusCode) else {
                throw CloudConvertError.invalidResponse
            }
            
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let jobData = json["data"] as? [String: Any] else {
                throw CloudConvertError.invalidResponse
            }
            
            let status = jobData["status"] as? String ?? "unknown"
            
            if status == "finished" {
                // CloudConvert v2: data.tasks مباشرة، وليس relationships.tasks.data.
                if let tasks = jobData["tasks"] as? [[String: Any]] {
                    for task in tasks {
                        if task["operation"] as? String == "export/url",
                           let result = task["result"] as? [String: Any],
                           let files = result["files"] as? [[String: Any]],
                           let firstFile = files.first,
                           let downloadURL = firstFile["url"] as? String {
                            progress(1.0)
                            return downloadURL
                        }
                    }
                }
                throw CloudConvertError.noResultURL
            }
            
            if status == "error" {
                let msg = jobData["message"] as? String ?? "خطأ غير معروف"
                throw CloudConvertError.jobFailed(msg)
            }
            
            let elapsed = Date().timeIntervalSince(startTime)
            let estimated = min(0.99, elapsed / maxWait)
            progress(estimated)
            
            try await Task.sleep(nanoseconds: 5_000_000_000)
        }
        
        throw CloudConvertError.timeout
    }
    
    // MARK: - Helper: تنزيل النتيجة
    
    private func downloadResult(url downloadURL: String, originalName: String, outputExtension: String) async throws -> URL {
        guard let url = URL(string: downloadURL) else {
            throw CloudConvertError.noResultURL
        }
        
        var request = URLRequest(url: url)
        request.timeoutInterval = 600

        // تنزيل الملف إلى القرص مباشرة بدلاً من وضع نتيجة تحويل كبيرة في الذاكرة.
        let (temporaryURL, response) = try await URLSession.shared.download(for: request)
        guard let httpResp = response as? HTTPURLResponse, (200...299).contains(httpResp.statusCode) else {
            throw CloudConvertError.uploadFailed((response as? HTTPURLResponse)?.statusCode ?? 0)
        }

        let outputFile = FileManager.default.temporaryDirectory
            .appendingPathComponent("cloudconvert-\(UUID().uuidString)-\(originalName).\(outputExtension.lowercased())")
        try? FileManager.default.removeItem(at: outputFile)
        try FileManager.default.moveItem(at: temporaryURL, to: outputFile)
        return outputFile
    }
    
    // MARK: - Helper: دمج .ts segments
    
    private func mergeTSSegments(
        paths: [String],
        baseFolder: URL,
        progress: @escaping (Double) -> Void
    ) async throws -> URL {
        
        let mergedURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("cloudconvert-merge-\(UUID().uuidString).ts")
        
        guard FileManager.default.createFile(atPath: mergedURL.path, contents: nil, attributes: nil) else {
            throw AudioPipelineError.exportFailed("تعذر إنشاء ملف مؤقت")
        }
        
        let fileHandle = try FileHandle(forWritingTo: mergedURL)
        defer { try? fileHandle.close() }
        
        for (index, segPath) in paths.enumerated() {
            if Task.isCancelled {
                try? FileManager.default.removeItem(at: mergedURL)
                throw CancellationError()
            }
            
            if segPath.hasPrefix("/") {
                let segURL = URL(fileURLWithPath: segPath)
                let data = try Data(contentsOf: segURL)
                fileHandle.write(data)
            } else if segPath.hasPrefix("http://") || segPath.hasPrefix("https://") {
                guard let u = URL(string: segPath) else { continue }
                let (data, _) = try await URLSession.shared.data(from: u)
                fileHandle.write(data)
            } else {
                let segURL = baseFolder.appendingPathComponent(segPath)
                let data = try Data(contentsOf: segURL)
                fileHandle.write(data)
            }
            
            progress(Double(index + 1) / Double(paths.count))
        }
        
        return mergedURL
    }
}

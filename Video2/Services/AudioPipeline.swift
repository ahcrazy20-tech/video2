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
    
    /// دمج .ts segments في ملف واحد
    private static func mergeTS(segments: [String], baseFolder: URL) async throws -> URL {
        print("[AudioPipeline] ═══ Merging TS segments...")
        print("[AudioPipeline] Base folder: \(baseFolder.path)")
        print("[AudioPipeline] Segments count: \(segments.count)")
        
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("merge-\(UUID().uuidString).ts")
        
        guard FileManager.default.createFile(atPath: out.path, contents: nil) else {
            print("[AudioPipeline] ❌ Failed to create merge file at: \(out.path)")
            throw AudioPipelineError.exportFailed("تعذر إنشاء ملف مؤقت")
        }
        print("[AudioPipeline] ✅ Created merge file: \(out.path)")
        
        let fh = try FileHandle(forWritingTo: out)
        defer { try? fh.close() }
        
        var totalBytes = 0
        
        for (index, segPath) in segments.enumerated() {
            print("[AudioPipeline] Processing segment \(index + 1)/\(segments.count): \(segPath)")
            
            if Task.isCancelled { 
                print("[AudioPipeline] ❌ Task cancelled")
                try? FileManager.default.removeItem(at: out)
                throw CancellationError() 
            }
            
            do {
                var segURL: URL
                
                if segPath.hasPrefix("/") {
                    segURL = URL(fileURLWithPath: segPath)
                    print("[AudioPipeline]   Absolute path")
                } else if segPath.hasPrefix("http://") || segPath.hasPrefix("https://") {
                    guard let u = URL(string: segPath) else {
                        print("[AudioPipeline]   ❌ Invalid URL: \(segPath)")
                        continue
                    }
                    segURL = u
                    print("[AudioPipeline]   HTTP URL - downloading...")
                    let (data, response) = try await URLSession.shared.data(from: segURL)
                    if let httpResponse = response as? HTTPURLResponse {
                        print("[AudioPipeline]   HTTP status: \(httpResponse.statusCode)")
                        guard (200...299).contains(httpResponse.statusCode) else {
                            print("[AudioPipeline]   ❌ HTTP error: \(httpResponse.statusCode)")
                            continue
                        }
                    }
                    fh.write(data)
                    totalBytes += data.count
                    print("[AudioPipeline]   ✅ Downloaded \(data.count / 1024) KB")
                    continue
                } else {
                    segURL = baseFolder.appendingPathComponent(segPath)
                    print("[AudioPipeline]   Relative path → \(segURL.path)")
                }
                
                // تحقق إن الملف موجود
                guard FileManager.default.fileExists(atPath: segURL.path) else {
                    print("[AudioPipeline]   ❌ File not found: \(segURL.path)")
                    
                    // حاول نشوف الملفات الموجودة
                    let dirPath = segURL.deletingLastPathComponent()
                    if let contents = try? FileManager.default.contentsOfDirectory(atPath: dirPath.path) {
                        print("[AudioPipeline]   Available files in \(dirPath.lastPathComponent):")
                        for file in contents.prefix(5) {
                            print("[AudioPipeline]     - \(file)")
                        }
                        if contents.count > 5 {
                            print("[AudioPipeline]     ... and \(contents.count - 5) more")
                        }
                    }
                    continue
                }
                
                // اقرأ البيانات
                let data = try Data(contentsOf: segURL)
                print("[AudioPipeline]   Read \(data.count / 1024) KB")
                fh.write(data)
                totalBytes += data.count
                print("[AudioPipeline]   ✅ Written")
                
            } catch {
                print("[AudioPipeline]   ❌ Error: \(error.localizedDescription)")
                continue
            }
        }
        
        let finalSize = (try? out.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        print("[AudioPipeline] ✅ Merge complete: \(totalBytes / 1024 / 1024) MB (final size: \(finalSize / 1024 / 1024) MB)")
        
        guard totalBytes > 0 else {
            try? FileManager.default.removeItem(at: out)
            throw AudioPipelineError.exportFailed("لم يتم دمج أي بيانات من segments")
        }
        
        return out
    }
    
    /// تحويل HLS عبر CloudConvert
    private static func convertWithCloudConvert(m3u8URL: URL) async throws -> URL {
        print("[CloudConvert] ═══════════════════════════════════════")
        print("[CloudConvert] Starting HLS → MP4")
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
        
        let jobBody: [String: Any] = [
            "tasks": [
                "import": ["operation": "import/upload"],
                "convert": ["operation": "convert", "input": ["import"], "output_format": "mp4", "engine": "ffmpeg"],
                "export": ["operation": "export/url", "input": ["convert"], "multiple": false]
            ]
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
        
        // الحصول على upload URL
        print("[CloudConvert] Getting upload URL...")
        let jobURL = URL(string: "https://api.cloudconvert.com/v2/jobs/\(jobID)")!
        var jobReq = URLRequest(url: jobURL)
        jobReq.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        let (jobData2, jobResp2) = try await URLSession.shared.data(for: jobReq)
        
        guard let hr2 = jobResp2 as? HTTPURLResponse, (200...299).contains(hr2.statusCode) else {
            print("[CloudConvert] ❌ Failed to get job details")
            throw AudioPipelineError.exportFailed("CloudConvert: فشل الحصول على تفاصيل job")
        }
        
        guard let j = try? JSONSerialization.jsonObject(with: jobData2) as? [String: Any] else {
            print("[CloudConvert] ❌ Failed to parse job JSON")
            throw AudioPipelineError.exportFailed("CloudConvert: استجابة غير صالحة")
        }
        
        guard let rel = j["relationships"] as? [String: Any] else {
            print("[CloudConvert] ❌ No 'relationships' in response")
            throw AudioPipelineError.exportFailed("CloudConvert: لم يتم العثور على upload URL")
        }
        
        guard let tasks = rel["tasks"] as? [String: Any] else {
            print("[CloudConvert] ❌ No 'tasks' in relationships")
            throw AudioPipelineError.exportFailed("CloudConvert: لم يتم العثور على upload URL")
        }
        
        guard let tData = tasks["data"] as? [[String: Any]] else {
            print("[CloudConvert] ❌ No 'data' in tasks")
            throw AudioPipelineError.exportFailed("CloudConvert: لم يتم العثور على upload URL")
        }
        
        guard let up = tData.first(where: { $0["operation"] as? String == "import/upload" }) else {
            print("[CloudConvert] ❌ No import/upload task found")
            print("[CloudConvert] Tasks: \(tData.map { $0["operation"] as? String ?? "unknown" })")
            throw AudioPipelineError.exportFailed("CloudConvert: لم يتم العثور على upload URL")
        }
        
        guard let tid = up["id"] as? String else {
            print("[CloudConvert] ❌ No 'id' in upload task")
            throw AudioPipelineError.exportFailed("CloudConvert: لم يتم العثور على upload URL")
        }
        
        guard let p = up["params"] as? [String: Any] else {
            print("[CloudConvert] ❌ No 'params' in upload task")
            throw AudioPipelineError.exportFailed("CloudConvert: لم يتم العثور على upload URL")
        }
        
        guard let us = p["upload_url"] as? String else {
            print("[CloudConvert] ❌ No 'upload_url' in params")
            print("[CloudConvert] Params: \(p)")
            throw AudioPipelineError.exportFailed("CloudConvert: لم يتم العثور على upload URL")
        }
        
        guard let uploadURL = URL(string: us) else {
            print("[CloudConvert] ❌ Invalid upload URL: \(us)")
            throw AudioPipelineError.exportFailed("CloudConvert: لم يتم العثور على upload URL")
        }
        
        print("[CloudConvert] ✅ Upload URL ready")
        
        // رفع الملف
        print("[CloudConvert] Uploading file (\(size / 1024 / 1024) MB)...")
        let fileData = try Data(contentsOf: merged)
        let boundary = "Boundary-\(UUID().uuidString)"
        var uploadReq = URLRequest(url: uploadURL)
        uploadReq.httpMethod = "POST"
        uploadReq.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        uploadReq.timeoutInterval = 600
        
        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(merged.lastPathComponent)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: application/octet-stream\r\n\r\n".data(using: .utf8)!)
        body.append(fileData)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        uploadReq.httpBody = body
        
        print("[CloudConvert] Sending upload request...")
        let (_, uploadResp) = try await URLSession.shared.data(for: uploadReq)
        
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
            let (pollData, _) = try await URLSession.shared.data(for: pollReq)
            
            if let pj = try? JSONSerialization.jsonObject(with: pollData) as? [String: Any],
               let pjd = pj["data"] as? [String: Any],
               let status = pjd["status"] as? String {
                
                if status != lastStatus {
                    print("[CloudConvert] Status: \(status)")
                    lastStatus = status
                }
                
                if status == "finished" {
                    print("[CloudConvert] ✅ Conversion finished")
                    
                    if let rel = pj["relationships"] as? [String: Any],
                       let tasks = rel["tasks"] as? [String: Any],
                       let tData = tasks["data"] as? [[String: Any]] {
                        
                        for t in tData {
                            if t["operation"] as? String == "export/url",
                               let r = t["result"] as? [String: Any],
                               let fs = r["files"] as? [[String: Any]],
                               let f = fs.first, let dlURL = f["url"] as? String {
                                
                                print("[CloudConvert] Download URL: \(dlURL)")
                                
                                // تنزيل النتيجة
                                guard let url = URL(string: dlURL) else {
                                    print("[CloudConvert] ❌ Invalid download URL")
                                    throw AudioPipelineError.exportFailed("CloudConvert: رابط تنزيل غير صالح")
                                }
                                
                                print("[CloudConvert] Downloading result...")
                                var dlReq = URLRequest(url: url)
                                dlReq.timeoutInterval = 600
                                let (dlData, _) = try await URLSession.shared.data(for: dlReq)
                                
                                let out = FileManager.default.temporaryDirectory
                                    .appendingPathComponent("cc-\(UUID().uuidString).mp4")
                                try dlData.write(to: out)
                                
                                let outSize = (try? out.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                                print("[CloudConvert] ✅ Downloaded: \(outSize / 1024 / 1024) MB")
                                return out
                            }
                        }
                    }
                    
                    print("[CloudConvert] ❌ No export/url task with files found")
                    throw AudioPipelineError.exportFailed("CloudConvert: لم يتم العثور على رابط تنزيل")
                }
                
                if status == "error" {
                    print("[CloudConvert] ❌ Conversion failed with error")
                    if let message = pjd["message"] as? String {
                        print("[CloudConvert] Error message: \(message)")
                        throw AudioPipelineError.exportFailed("CloudConvert: فشل التحويل — \(message)")
                    }
                    throw AudioPipelineError.exportFailed("CloudConvert: فشل التحويل")
                }
            }
            
            try await Task.sleep(nanoseconds: 5_000_000_000)
        }
        
        print("[CloudConvert] ❌ Timeout after 10 minutes")
        throw AudioPipelineError.exportFailed("CloudConvert: انتهت مهلة الانتظار")
    }

    // MARK: ═══════════════════════════════════════════════════════════
    // MARK: تحويل HLS إلى MP4 — 3 طرق
    // ═════════════════════════════════════════════════════════════════

    static func exportHLSToTempMP4(_ url: URL) async throws -> URL {
        print("[AudioPipeline] ═══════════════════════════════════════")
        print("[AudioPipeline] Starting HLS → MP4 conversion")
        print("[AudioPipeline] URL: \(url.path)")
        
        guard FileManager.default.fileExists(atPath: url.path) else {
            print("[AudioPipeline] ❌ File not found: \(url.path)")
            throw AudioPipelineError.exportFailed("ملف HLS غير موجود: \(url.lastPathComponent)")
        }
        
        print("[AudioPipeline] ✅ File exists")
        print("[AudioPipeline] m3u8: \(url.lastPathComponent)")

        // الطريقة 1: CloudConvert API (لو متاح)
        if hasCloudConvertKey {
            print("[AudioPipeline] ═══ Trying CloudConvert API (method 1)...")
            do {
                let result = try await convertWithCloudConvert(m3u8URL: url)
                print("[AudioPipeline] ✅ CloudConvert succeeded!")
                return result
            } catch {
                print("[AudioPipeline] ⚠️ CloudConvert failed: \(error.localizedDescription)")
                print("[AudioPipeline] Falling back to native methods...")
            }
        } else {
            print("[AudioPipeline] ⚠️ CloudConvert not available (no API key)")
        }

        // نقرأ playlist
        print("[AudioPipeline] ═══ Reading m3u8 playlist...")
        guard let content = try? String(contentsOf: url, encoding: .utf8) else {
            print("[AudioPipeline] ❌ Failed to read m3u8")
            throw AudioPipelineError.exportFailed("تعذر قراءة m3u8")
        }
        
        print("[AudioPipeline] ✅ Read m3u8 (\(content.count) chars)")
        
        let lines = content.components(separatedBy: .newlines)
        print("[AudioPipeline] Total lines: \(lines.count)")
        
        let segmentPaths: [String] = lines.compactMap { l in
            let t = l.trimmingCharacters(in: .whitespaces)
            return t.isEmpty || t.hasPrefix("#") ? nil : t
        }
        
        print("[AudioPipeline] Found \(segmentPaths.count) segments")
        
        guard !segmentPaths.isEmpty else {
            print("[AudioPipeline] ❌ No segments found")
            throw AudioPipelineError.exportFailed("ملف m3u8 فارغ")
        }
        
        let m3u8Folder = url.deletingLastPathComponent()
        print("[AudioPipeline] Base folder: \(m3u8Folder.path)")

        // الطريقة 2: AVMutableComposition
        print("[AudioPipeline] ═══ Trying AVMutableComposition (method 2)...")
        do {
            let result = try await tryCompositionMethod(segments: segmentPaths, folder: m3u8Folder)
            print("[AudioPipeline] ✅ Composition succeeded!")
            return result
        } catch {
            print("[AudioPipeline] ⚠️ Composition failed: \(error.localizedDescription)")
        }

        // الطريقة 3: segment-by-segment
        print("[AudioPipeline] ═══ Trying segment-by-segment (method 3)...")
        do {
            let result = try await trySegmentBySegmentMethod(segments: segmentPaths, folder: m3u8Folder)
            print("[AudioPipeline] ✅ Segment-by-segment succeeded!")
            return result
        } catch {
            print("[AudioPipeline] ⚠️ Segment-by-segment failed: \(error.localizedDescription)")
        }

        print("[AudioPipeline] ❌ ALL METHODS FAILED")
        throw AudioPipelineError.exportFailed("كل طرق التحويل فشلت")
    }

    // MARK: ── الطريقة 2: AVMutableComposition ──

    private static func tryCompositionMethod(segments: [String], folder: URL) async throws -> URL {
        print("[AudioPipeline] Creating AVMutableComposition...")
        let composition = AVMutableComposition()
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
                    guard let ct = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else { 
                        print("[AudioPipeline]   ❌ Failed to add video track")
                        continue 
                    }
                    let tr = try await srcTrack.load(.timeRange)
                    try ct.insertTimeRange(tr, of: srcTrack, at: currentTime)
                    print("[AudioPipeline]   ✅ Added video track")
                }
                
                let audioTracks = try await segAsset.loadTracks(withMediaType: .audio)
                print("[AudioPipeline]   Audio tracks: \(audioTracks.count)")
                
                for srcTrack in audioTracks {
                    guard let ct = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else { 
                        print("[AudioPipeline]   ❌ Failed to add audio track")
                        continue 
                    }
                    let tr = try await srcTrack.load(.timeRange)
                    try ct.insertTimeRange(tr, of: srcTrack, at: currentTime)
                    print("[AudioPipeline]   ✅ Added audio track")
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
        if videoURL.pathExtension.lowercased() == "m3u8" {
            let cached = dir.appendingPathComponent("hls-source.mp4")
            if FileManager.default.fileExists(atPath: cached.path) {
                sourceURL = cached
            } else {
                progress(0.02)
                let tmp = try await exportHLSToTempMP4(videoURL)
                do { try FileManager.default.moveItem(at: tmp, to: cached) }
                catch { try? FileManager.default.copyItem(at: tmp, to: cached) }
                sourceURL = cached
            }
            guard FileManager.default.fileExists(atPath: sourceURL.path) else {
                throw AudioPipelineError.exportFailed("فقد الملف المحوّل")
            }
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
            if !singleFile && pts - chunkStartPTS >= effectiveChunkSeconds {
                try await finishWriter()
                chunks.append(AudioChunk(index: chunkIndex, fileName: String(format: "chunk-%03d.m4a", chunkIndex), start: chunkStartPTS, duration: pts - chunkStartPTS))
                chunkIndex += 1; chunkStartPTS = pts
                let (w, i) = try makeWriter(index: chunkIndex)
                w.startWriting(); w.startSession(atSourceTime: CMTime(seconds: pts, preferredTimescale: 600))
                writer = w; writerInput = i
                if duration > 0 { progress(min(0.98, pts / duration)) }
            }
            while writerInput?.isReadyForMoreMediaData == false {
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

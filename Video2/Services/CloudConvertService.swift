import Foundation

/// خدمة CloudConvert لتحويل الملفات عبر الإنترنت
/// - مجاني 25 conversion/day
/// - سجل في: https://cloudconvert.com
/// - اعمل API Key من: Dashboard → API Keys
/// - أضف الـ Key في Info.plist:
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
    
    /// يجلب API key من Info.plist
    static func apiKey() -> String? {
        return Bundle.main.infoDictionary?["CLOUDCONVERT_API_KEY"] as? String
    }
    
    /// يتحقق من توفر API key
    static var isAvailable: Bool {
        let key = apiKey()
        return key != nil && !key!.isEmpty
    }
    
    // MARK: - التحويل الرئيسي
    
    /// يحول ملف فيديو إلى MP4
    /// - Parameters:
    ///   - inputFile: مسار ملف المصدر
    ///   - apiKey: API key (اختياري — هيستخدم اللي في Info.plist)
    ///   - progress: closure للتقدم (0.0 - 1.0)
    /// - Returns: مسار ملف MP4 الناتج
    func convertToMP4(
        inputFile: URL,
        apiKey: String? = nil,
        progress: @escaping (Double) -> Void = { _ in }
    ) async throws -> URL {
        
        let key = apiKey ?? Self.apiKey()
        guard let apiKey = key, !apiKey.isEmpty else {
            throw CloudConvertError.missingAPIKey
        }
        
        print("[CloudConvert] ═══════════════════════════════════════")
        print("[CloudConvert] Starting conversion: \(inputFile.lastPathComponent)")
        
        let fileSize = (try? inputFile.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        print("[CloudConvert] Input size: \(fileSize / 1024 / 1024) MB")
        
        // 1. إنشاء job
        progress(0.05)
        let jobID = try await createJob(apiKey: apiKey)
        print("[CloudConvert] ✅ Job created: \(jobID)")
        
        // 2. الحصول على upload URL
        progress(0.10)
        let uploadInfo = try await getUploadURL(apiKey: apiKey, jobID: jobID)
        print("[CloudConvert] Upload URL ready")
        
        // 3. رفع الملف
        progress(0.15)
        try await uploadFile(
            fileURL: inputFile,
            uploadURL: uploadInfo.url,
            progress: { p in
                progress(0.15 + 0.30 * p) // من 0.15 إلى 0.45
            }
        )
        print("[CloudConvert] ✅ File uploaded")
        
        // 4. انتظار التحويل
        progress(0.50)
        let downloadURL = try await waitForJob(
            apiKey: apiKey,
            jobID: jobID,
            maxWait: 600, // 10 دقائق
            progress: { p in
                progress(0.50 + 0.40 * p) // من 0.50 إلى 0.90
            }
        )
        print("[CloudConvert] ✅ Conversion complete")
        
        // 5. تنزيل النتيجة
        progress(0.92)
        let outputFile = try await downloadResult(
            url: downloadURL,
            originalName: inputFile.deletingPathExtension().lastPathComponent
        )
        print("[CloudConvert] ✅ Downloaded: \(outputFile.lastPathComponent)")
        
        progress(1.0)
        return outputFile
    }
    
    // MARK: - Helper: إنشاء Job
    
    private func createJob(apiKey: String) async throws -> String {
        let url = URL(string: "https://api.cloudconvert.com/v2/jobs")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 60
        
        let body: [String: Any] = [
            "tasks": [
                "task-import": [
                    "operation": "import/upload"
                ],
                "task-convert": [
                    "operation": "convert",
                    "input": ["task-import"],
                    "output_format": "mp4",
                    "engine": "ffmpeg",
                    "engine_version": "latest"
                ],
                "task-export": [
                    "operation": "export/url",
                    "input": ["task-convert"],
                    "multiple": false
                ]
            ],
            "tag": "video2-app"
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
    }
    
    private func getUploadURL(apiKey: String, jobID: String) async throws -> UploadInfo {
        let url = URL(string: "https://api.cloudconvert.com/v2/jobs/\(jobID)")!
        var request = URLRequest(url: url)
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResp = response as? HTTPURLResponse, (200...299).contains(httpResp.statusCode) else {
            throw CloudConvertError.invalidResponse
        }
        
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let relationships = json["relationships"] as? [String: Any],
              let tasks = relationships["tasks"] as? [String: Any],
              let tasksData = tasks["data"] as? [[String: Any]] else {
            throw CloudConvertError.invalidResponse
        }
        
        guard let uploadTask = tasksData.first(where: { $0["operation"] as? String == "import/upload" }),
              let taskID = uploadTask["id"] as? String,
              let params = uploadTask["params"] as? [String: Any],
              let urlStr = params["upload_url"] as? String,
              let uploadURL = URL(string: urlStr) else {
            throw CloudConvertError.invalidResponse
        }
        
        return UploadInfo(url: uploadURL, taskID: taskID)
    }
    
    // MARK: - Helper: رفع الملف
    
    private func uploadFile(
        fileURL: URL,
        uploadURL: URL,
        progress: @escaping (Double) -> Void
    ) async throws {
        
        let fileData = try Data(contentsOf: fileURL)
        let boundary = "Boundary-\(UUID().uuidString)"
        
        var request = URLRequest(url: uploadURL)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 600
        
        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(fileURL.lastPathComponent)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: application/octet-stream\r\n\r\n".data(using: .utf8)!)
        body.append(fileData)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        
        request.httpBody = body
        
        // نستخدم URLSession مع delegate للتقدم (مبسّط)
        let (_, response) = try await URLSession.shared.data(for: request)
        
        progress(1.0)
        
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
        var lastProgressTime = Date()
        
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
                // نحصل على download URL
                if let relationships = json["relationships"] as? [String: Any],
                   let tasks = relationships["tasks"] as? [String: Any],
                   let tasksData = tasks["data"] as? [[String: Any]] {
                    
                    for task in tasksData {
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
            
            // نحسب تقدم تقديري
            let elapsed = Date().timeIntervalSince(startTime)
            let estimated = min(0.99, elapsed / maxWait)
            progress(estimated)
            
            // ننتظر 5 ثواني قبل الـ polling التالي
            try await Task.sleep(nanoseconds: 5_000_000_000)
        }
        
        throw CloudConvertError.timeout
    }
    
    // MARK: - Helper: تنزيل النتيجة
    
    private func downloadResult(url downloadURL: String, originalName: String) async throws -> URL {
        guard let url = URL(string: downloadURL) else {
            throw CloudConvertError.noResultURL
        }
        
        var request = URLRequest(url: url)
        request.timeoutInterval = 600
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResp = response as? HTTPURLResponse, (200...299).contains(httpResp.statusCode) else {
            throw CloudConvertError.uploadFailed(0)
        }
        
        let outputFile = FileManager.default.temporaryDirectory
            .appendingPathComponent("cloudconvert-\(UUID().uuidString)-\(originalName).mp4")
        try data.write(to: outputFile)
        
        return outputFile
    }
    
    // MARK: - دالة مساعدة: HLS → MP4
    
    /// يحول HLS (m3u8) إلى MP4 عبر CloudConvert
    /// بيلحم كل .ts segments في ملف واحد ثم يرفعه
    func convertHLS(
        m3u8URL: URL,
        apiKey: String? = nil,
        progress: @escaping (Double) -> Void = { _ in }
    ) async throws -> URL {
        
        print("[CloudConvert] ═══════════════════════════════════════")
        print("[CloudConvert] HLS → MP4 conversion")
        
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
            progress(0.01 + 0.09 * p) // من 0.01 إلى 0.10
        }
        
        defer { try? FileManager.default.removeItem(at: mergedTS) }
        
        let mergedSize = (try? mergedTS.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        print("[CloudConvert] Merged TS: \(mergedSize / 1024 / 1024) MB")
        
        // نحول الملف المدموج
        let result = try await convertToMP4(
            inputFile: mergedTS,
            apiKey: apiKey,
            progress: { p in
                progress(0.10 + 0.90 * p) // من 0.10 إلى 1.0
            }
        )
        
        return result
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
            
            let segURL: URL
            if segPath.hasPrefix("/") {
                segURL = URL(fileURLWithPath: segPath)
            } else if segPath.hasPrefix("http://") || segPath.hasPrefix("https://") {
                guard let u = URL(string: segPath) else { continue }
                let (data, _) = try await URLSession.shared.data(from: u)
                fileHandle.write(data)
                progress(Double(index + 1) / Double(paths.count))
                continue
            } else {
                segURL = baseFolder.appendingPathComponent(segPath)
            }
            
            guard FileManager.default.fileExists(atPath: segURL.path) else {
                print("[CloudConvert] ⚠️ Segment not found: \(segPath)")
                continue
            }
            
            let data = try Data(contentsOf: segURL)
            fileHandle.write(data)
            progress(Double(index + 1) / Double(paths.count))
        }
        
        return mergedURL
    }
}

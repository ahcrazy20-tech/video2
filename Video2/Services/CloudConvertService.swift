import Foundation
import AVFoundation

/// خدمة CloudConvert لتحويل الملفات عبر الإنترنت
/// مجاني 25 conversion/day
/// سجل في: https://cloudconvert.com
/// اعمل API Key من: Dashboard → API Keys مع تفعيل task.read و task.write
final class CloudConvertService {

    static let shared = CloudConvertService()

    private init() {}

    /// نجرّب أكثر من نطاق: الإنتاج الأوروبي/الأمريكي ثم الـ sandbox.
    /// بعض الشبكات (خصوصاً مع Cloudflare) ترفض نطاقاً وتعمل مع آخر.
    private static let apiBases = [
        "https://api.cloudconvert.com/v2",
        "https://eu-central.api.cloudconvert.com/v2",
        "https://us-east.api.cloudconvert.com/v2",
        "https://api.sandbox.cloudconvert.com/v2"
    ]

    // MARK: - الأخطاء

    enum CloudConvertError: LocalizedError {
        case missingAPIKey
        case invalidResponse
        case uploadFailed(Int)
        case createJobFailed(Int, String)
        case jobFailed(String)
        case timeout
        case noResultURL

        var errorDescription: String? {
            switch self {
            case .missingAPIKey:
                return "CloudConvert API key غير موجود — أضفه من الإعدادات"
            case .invalidResponse:
                return "استجابة غير صالحة من CloudConvert"
            case .uploadFailed(let code):
                return "فشل رفع الملف إلى CloudConvert: HTTP \(code)"
            case .createJobFailed(let code, let detail):
                return CloudConvertService.describeCreateFailure(status: code, body: detail)
            case .jobFailed(let msg):
                return "فشل التحويل: \(msg)"
            case .timeout:
                return "انتهت مهلة الانتظار للتحويل"
            case .noResultURL:
                return "لم يتم العثور على رابط تنزيل"
            }
        }
    }

    static func describeCreateFailure(status: Int, body: String) -> String {
        let lower = body.lowercased()
        if status == 401 {
            return "CloudConvert: المفتاح غير صحيح أو منتهي (401)"
        }
        if status == 402 || lower.contains("credit") || lower.contains("quota") || lower.contains("payment") {
            return "CloudConvert: نفدت الحصة المجانية (25 تحويل/يوم) — انتظر الغد أو استخدم ConvertAPI / ffmpeg-api"
        }
        if status == 403 {
            if lower.contains("cloudflare") || lower.contains("<html") || lower.contains("just a moment") || lower.contains("attention required") {
                return "CloudConvert: الشبكة محجوبة (Cloudflare 403) — بدّل Wi-Fi/البيانات أو جرّب VPN"
            }
            if lower.contains("sandbox") {
                return "CloudConvert: هذا مفتاح Sandbox على بيئة Live أو العكس — أنشئ مفتاح Live من اللوحة"
            }
            if lower.contains("unauthorized") || lower.contains("unauthenticated") || lower.contains("scope")
                || lower.contains("permission") || lower.contains("task.write") || lower.contains("forbidden") {
                return "CloudConvert: المفتاح بلا صلاحية task.write — أنشئ مفتاحاً جديداً وفعّل task.read و task.write"
            }
            return "CloudConvert: مرفوض 403 — فعّل task.write في المفتاح، أو بدّل الشبكة، أو استخدم التحويل المحلي/مزوداً بديلاً"
        }
        if status == 422 {
            return "CloudConvert: طلب التحويل مرفوض (422) — صيغة الملف غير مدعومة أو الخيارات غير صالحة"
        }
        if status == 429 {
            return "CloudConvert: حد الطلبات مؤقتاً (429) — أُعيدت المحاولة تلقائياً"
        }
        let snippet = body.replacingOccurrences(of: "\n", with: " ")
        let short = snippet.count > 160 ? String(snippet.prefix(160)) + "…" : snippet
        return "CloudConvert: فشل إنشاء المهمة — HTTP \(status) \(short)"
    }

    // MARK: - API Key

    static func apiKey() -> String? {
        if let key = KeychainStore.get("cloudconvert"), !key.isEmpty {
            return KeychainStore.normalized(key)
        }
        if let key = Bundle.main.infoDictionary?["CLOUDCONVERT_API_KEY"] as? String {
            let clean = KeychainStore.normalized(key)
            return clean.isEmpty || clean.contains("ضع") ? nil : clean
        }
        return nil
    }

    static var isAvailable: Bool {
        guard let key = apiKey() else { return false }
        return !key.isEmpty && key != "ضع_الـAPI_Key_هنا"
    }

    // MARK: - التحويل الرئيسي

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
        let key = apiKey.flatMap { KeychainStore.normalized($0) } ?? Self.apiKey()
        guard let apiKey = key, !apiKey.isEmpty else {
            throw CloudConvertError.missingAPIKey
        }

        print("[CloudConvert] ═══════════════════════════════════════")
        print("[CloudConvert] Starting conversion to \(outputFormat.uppercased()): \(inputFile.lastPathComponent)")
        let fileSize = (try? inputFile.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        print("[CloudConvert] Input size: \(fileSize / 1024 / 1024) MB")

        progress(0.05)
        let job = try await createJob(apiKey: apiKey, outputFormat: outputFormat)
        print("[CloudConvert] ✅ Job created: \(job.id) @ \(job.base)")

        progress(0.10)
        let uploadInfo = try await getUploadURL(apiKey: apiKey, job: job)
        print("[CloudConvert] Upload URL ready")

        progress(0.15)
        try await uploadFile(fileURL: inputFile, uploadURL: uploadInfo.url, formParams: uploadInfo.params)
        print("[CloudConvert] ✅ File uploaded")

        progress(0.50)
        let downloadURL = try await waitForJob(apiKey: apiKey, job: job, maxWait: 600) { p in
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

    // MARK: - HLS → صوت/فيديو

    /// يحول HLS (m3u8) عبر دمج الأجزاء ثم التحويل السحابي. للترجمة نطلب M4A.
    func convertHLS(
        m3u8URL: URL,
        outputFormat: String = "m4a",
        apiKey: String? = nil,
        progress: @escaping (Double) -> Void = { _ in }
    ) async throws -> URL {

        print("[CloudConvert] ═══════════════════════════════════════")
        print("[CloudConvert] HLS → \(outputFormat.uppercased())")

        guard FileManager.default.fileExists(atPath: m3u8URL.path) else {
            throw AudioPipelineError.exportFailed("ملف HLS غير موجود")
        }

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

        return try await convert(
            inputFile: mergedTS,
            outputFormat: outputFormat,
            apiKey: apiKey,
            progress: { p in
                progress(0.10 + 0.90 * p)
            }
        )
    }

    // MARK: - HTTP

    private struct JobRef {
        let id: String
        let base: String
    }

    private func authorizedRequest(url: URL, method: String, apiKey: String, body: Data? = nil, timeout: TimeInterval = 60) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = timeout
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 16_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.4 Mobile/15E148 Safari/604.1 Video2/1.0", forHTTPHeaderField: "User-Agent")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        return request
    }

    private func jobBody(outputFormat: String, includeEngine: Bool) -> Data? {
        var convertTask: [String: Any] = [
            "operation": "convert",
            "input": ["task-import"],
            "output_format": outputFormat
        ]
        if includeEngine {
            convertTask["engine"] = "ffmpeg"
        }
        if outputFormat.lowercased() == "m4a" || outputFormat.lowercased() == "mp3" {
            convertTask["audio_codec"] = "aac"
        }
        if outputFormat.lowercased() == "mp4" {
            convertTask["video_codec"] = "h264"
            convertTask["audio_codec"] = "aac"
        }
        let body: [String: Any] = [
            "tasks": [
                "task-import": ["operation": "import/upload"],
                "task-convert": convertTask,
                "task-export": [
                    "operation": "export/url",
                    "input": ["task-convert"],
                    "inline": false
                ]
            ],
            "tag": outputFormat.lowercased() == "m4a" ? "video2-audio" : "video2-app"
        ]
        return try? JSONSerialization.data(withJSONObject: body)
    }

    private func createJob(apiKey: String, outputFormat: String) async throws -> JobRef {
        let payloads = [jobBody(outputFormat: outputFormat, includeEngine: false),
                        jobBody(outputFormat: outputFormat, includeEngine: true)].compactMap { $0 }
        var lastStatus = 0
        var lastBody = ""

        for base in Self.apiBases {
            for payload in payloads {
                if Task.isCancelled { throw CancellationError() }
                guard let url = URL(string: "\(base)/jobs") else { continue }
                let request = authorizedRequest(url: url, method: "POST", apiKey: apiKey, body: payload, timeout: 60)
                do {
                    let (data, response) = try await URLSession.shared.data(for: request)
                    guard let http = response as? HTTPURLResponse else { continue }
                    lastStatus = http.statusCode
                    lastBody = String(data: data, encoding: .utf8) ?? ""
                    print("[CloudConvert] create job \(base) → HTTP \(http.statusCode)")
                    if (200...299).contains(http.statusCode),
                       let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                       let jobData = json["data"] as? [String: Any],
                       let jobID = jobData["id"] as? String {
                        return JobRef(id: jobID, base: base)
                    }
                    // 401 على نطاق معيّن (sandbox vs live) — نجرّب التالي
                    if http.statusCode == 401 || http.statusCode == 404 {
                        break
                    }
                    // 422 بسبب الخيارات — نجرّب الـ payload الآخر على نفس النطاق
                    if http.statusCode == 422 {
                        continue
                    }
                    // 403: إمّا حجب شبكة (نطاق آخر قد ينجح) أو صلاحيات (ستفشل كلها)
                    if http.statusCode == 403 {
                        break
                    }
                } catch {
                    print("[CloudConvert] create job network error on \(base): \(error.localizedDescription)")
                    lastBody = error.localizedDescription
                    lastStatus = 0
                }
            }
        }

        throw CloudConvertError.createJobFailed(lastStatus == 0 ? 403 : lastStatus, lastBody)
    }

    private struct UploadInfo {
        let url: URL
        let taskID: String
        let params: [String: Any]
    }

    private func getUploadURL(apiKey: String, job: JobRef) async throws -> UploadInfo {
        guard let url = URL(string: "\(job.base)/jobs/\(job.id)") else {
            throw CloudConvertError.invalidResponse
        }
        let waitStart = Date()
        while Date().timeIntervalSince(waitStart) < 30 {
            let request = authorizedRequest(url: url, method: "GET", apiKey: apiKey)
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                throw CloudConvertError.invalidResponse
            }
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let jobData = json["data"] as? [String: Any],
                  let tasks = jobData["tasks"] as? [[String: Any]] else {
                try await Task.sleep(nanoseconds: 1_000_000_000)
                continue
            }
            if let uploadTask = tasks.first(where: { $0["operation"] as? String == "import/upload" }),
               let taskID = uploadTask["id"] as? String,
               let form = (uploadTask["result"] as? [String: Any])?["form"] as? [String: Any],
               let urlStr = form["url"] as? String,
               let uploadURL = URL(string: urlStr) {
                let formParams = (form["parameters"] as? [String: Any]) ?? [:]
                return UploadInfo(url: uploadURL, taskID: taskID, params: formParams)
            }
            try await Task.sleep(nanoseconds: 1_000_000_000)
        }
        throw CloudConvertError.invalidResponse
    }

    private func uploadFile(fileURL: URL, uploadURL: URL, formParams: [String: Any]) async throws {
        let boundary = "Boundary-\(UUID().uuidString)"
        var request = URLRequest(url: uploadURL)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 16_4 like Mac OS X) AppleWebKit/605.1.15 Video2/1.0", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 600

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

    private func waitForJob(
        apiKey: String,
        job: JobRef,
        maxWait: TimeInterval,
        progress: @escaping (Double) -> Void
    ) async throws -> String {

        guard let url = URL(string: "\(job.base)/jobs/\(job.id)") else {
            throw CloudConvertError.invalidResponse
        }
        let startTime = Date()

        while Date().timeIntervalSince(startTime) < maxWait {
            if Task.isCancelled { throw CancellationError() }
            let request = authorizedRequest(url: url, method: "GET", apiKey: apiKey)
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
                var msg = jobData["message"] as? String
                if msg == nil, let tasks = jobData["tasks"] as? [[String: Any]] {
                    msg = tasks.first(where: { $0["status"] as? String == "error" })?["message"] as? String
                }
                throw CloudConvertError.jobFailed(msg ?? "خطأ غير معروف")
            }

            let elapsed = Date().timeIntervalSince(startTime)
            progress(min(0.99, elapsed / maxWait))
            try await Task.sleep(nanoseconds: 5_000_000_000)
        }

        throw CloudConvertError.timeout
    }

    private func downloadResult(url downloadURL: String, originalName: String, outputExtension: String) async throws -> URL {
        guard let url = URL(string: downloadURL) else {
            throw CloudConvertError.noResultURL
        }

        var request = URLRequest(url: url)
        request.timeoutInterval = 600
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 16_4 like Mac OS X) AppleWebKit/605.1.15 Video2/1.0", forHTTPHeaderField: "User-Agent")

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
                try fileHandle.write(contentsOf: Data(contentsOf: segURL, options: [.mappedIfSafe]))
            } else if segPath.hasPrefix("http://") || segPath.hasPrefix("https://") {
                guard let u = URL(string: segPath) else { continue }
                let (data, _) = try await URLSession.shared.data(from: u)
                try fileHandle.write(contentsOf: data)
            } else {
                let segURL = baseFolder.appendingPathComponent(segPath)
                try fileHandle.write(contentsOf: Data(contentsOf: segURL, options: [.mappedIfSafe]))
            }

            progress(Double(index + 1) / Double(paths.count))
        }

        return mergedURL
    }
}

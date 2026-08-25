import Foundation

/// ConvertAPI — مزود سحابي ثالث لتحويل TS/MPEG إلى M4A أو MP4.
/// شريحة تجريبية عند التسجيل: https://www.convertapi.com
final class ConvertAPIService {
    static let shared = ConvertAPIService()
    private init() {}

    enum ConvertAPIError: LocalizedError {
        case missingKey
        case invalidResponse
        case http(Int, String)
        case noResult

        var errorDescription: String? {
            switch self {
            case .missingKey:
                return "مفتاح ConvertAPI غير موجود — أضفه من الإعدادات"
            case .invalidResponse:
                return "ConvertAPI: استجابة غير صالحة"
            case .http(let code, let body):
                return ConvertAPIService.describe(status: code, body: body)
            case .noResult:
                return "ConvertAPI: لم يُرجع ملف نتيجة"
            }
        }
    }

    static func apiKey() -> String? {
        if let key = KeychainStore.get("convertapi"), !key.isEmpty { return key }
        if let key = Bundle.main.infoDictionary?["CONVERTAPI_SECRET"] as? String,
           !key.isEmpty, !key.contains("ضع") {
            return key
        }
        return nil
    }

    static var isAvailable: Bool { apiKey() != nil }

    static func describe(status: Int, body: String) -> String {
        let lower = body.lowercased()
        if status == 401 || status == 403 {
            if lower.contains("secret") || lower.contains("unauthorized") || lower.contains("invalid") {
                return "ConvertAPI: المفتاح غير صحيح أو منتهي (HTTP \(status))"
            }
            if lower.contains("credit") || lower.contains("balance") || lower.contains("quota") {
                return "ConvertAPI: نفدت الحصة التجريبية — راجع اللوحة أو انتظر التجديد"
            }
            return "ConvertAPI: مرفوض HTTP \(status) — تحقق من المفتاح أو بدّل الشبكة"
        }
        if status == 4010 || status == 5004 || lower.contains("no credits") {
            return "ConvertAPI: لا يوجد رصيد تحويل متبقٍ"
        }
        let snippet = body.count > 160 ? String(body.prefix(160)) + "…" : body
        return "ConvertAPI: فشل التحويل HTTP \(status) \(snippet)"
    }

    func convert(inputFile: URL, outputFormat: String) async throws -> URL {
        guard let secret = Self.apiKey() else { throw ConvertAPIError.missingKey }
        let ext = inputFile.pathExtension.lowercased()
        let inputFormat = (ext == "ts" || ext == "mts" || ext == "m2ts" || ext == "mpg" || ext == "mpeg") ? ext : "ts"
        let target = outputFormat.lowercased() == "m4a" ? "m4a" : (outputFormat.lowercased() == "mp3" ? "mp3" : "mp4")
        let candidates = [
            "https://v2.convertapi.com/convert/\(inputFormat)/to/\(target)",
            "https://v2.convertapi.com/convert/ts/to/\(target)",
            "https://v2.convertapi.com/convert/mpeg/to/\(target)"
        ]

        var lastError: Error = ConvertAPIError.invalidResponse
        for endpoint in candidates {
            do {
                return try await convertOnce(file: inputFile, url: endpoint, secret: secret, outputExtension: target)
            } catch {
                lastError = error
                print("[ConvertAPI] \(endpoint) failed: \(error.localizedDescription)")
            }
        }
        throw lastError
    }

    private func convertOnce(file: URL, url endpoint: String, secret: String, outputExtension: String) async throws -> URL {
        guard var components = URLComponents(string: endpoint) else { throw ConvertAPIError.invalidResponse }
        components.queryItems = [
            URLQueryItem(name: "Secret", value: secret),
            URLQueryItem(name: "StoreFile", value: "true")
        ]
        guard let url = components.url else { throw ConvertAPIError.invalidResponse }

        let boundary = "Boundary-\(UUID().uuidString)"
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 600
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 16_4 like Mac OS X) AppleWebKit/605.1.15 Video2/1.0", forHTTPHeaderField: "User-Agent")

        let bodyURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("convertapi-body-\(UUID().uuidString).bin")
        defer { try? FileManager.default.removeItem(at: bodyURL) }
        FileManager.default.createFile(atPath: bodyURL.path, contents: nil)
        let body = try FileHandle(forWritingTo: bodyURL)
        func write(_ string: String) throws { try body.write(contentsOf: Data(string.utf8)) }
        try write("--\(boundary)\r\n")
        try write("Content-Disposition: form-data; name=\"File\"; filename=\"\(file.lastPathComponent)\"\r\n")
        try write("Content-Type: application/octet-stream\r\n\r\n")
        let input = try FileHandle(forReadingFrom: file)
        while true {
            let chunk = input.readData(ofLength: 2 * 1024 * 1024)
            if chunk.isEmpty { break }
            try body.write(contentsOf: chunk)
        }
        try input.close()
        try write("\r\n--\(boundary)--\r\n")
        try body.close()

        print("[ConvertAPI] Uploading \(file.lastPathComponent) → \(outputExtension)")
        let (data, response) = try await URLSession.shared.upload(for: request, fromFile: bodyURL)
        guard let http = response as? HTTPURLResponse else { throw ConvertAPIError.invalidResponse }
        guard (200...299).contains(http.statusCode) else {
            throw ConvertAPIError.http(http.statusCode, String(data: data, encoding: .utf8) ?? "")
        }
        let json = HTTP.json(from: data)
        let files = (json["Files"] as? [[String: Any]]) ?? (json["files"] as? [[String: Any]]) ?? []
        guard let first = files.first else { throw ConvertAPIError.noResult }

        if let urlString = (first["Url"] as? String) ?? (first["url"] as? String),
           let download = URL(string: urlString) {
            return try await downloadResult(download, ext: outputExtension)
        }
        if let b64 = (first["FileData"] as? String) ?? (first["fileData"] as? String),
           let decoded = Data(base64Encoded: b64), !decoded.isEmpty {
            let out = FileManager.default.temporaryDirectory
                .appendingPathComponent("convertapi-\(UUID().uuidString).\(outputExtension)")
            try decoded.write(to: out, options: .atomic)
            return out
        }
        throw ConvertAPIError.noResult
    }

    private func downloadResult(_ url: URL, ext: String) async throws -> URL {
        var request = URLRequest(url: url)
        request.timeoutInterval = 600
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 16_4 like Mac OS X) AppleWebKit/605.1.15 Video2/1.0", forHTTPHeaderField: "User-Agent")
        let (tmp, response) = try await URLSession.shared.download(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw ConvertAPIError.http((response as? HTTPURLResponse)?.statusCode ?? 0, "")
        }
        let out = FileManager.default.temporaryDirectory
            .appendingPathComponent("convertapi-\(UUID().uuidString).\(ext)")
        try? FileManager.default.removeItem(at: out)
        try FileManager.default.moveItem(at: tmp, to: out)
        let size = (try? out.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        guard size > 0 else { throw ConvertAPIError.noResult }
        print("[ConvertAPI] ✅ downloaded \(size / 1024 / 1024) MB")
        return out
    }
}

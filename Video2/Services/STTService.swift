import Foundation

// MARK: - أدوات HTTP مشتركة

struct APIError: LocalizedError {
    let status: Int
    let body: String
    var retryAfter: Double?

    var errorDescription: String? {
        switch status {
        case 401:
            return "المفتاح غير صحيح أو منتهي الصلاحية (401)."
        case 403:
            return "مرفوض (403) — لو المفتاح سليم فغالباً حجب شبكة عند المزود: بدّل Wi-Fi/البيانات أو جرّب VPN."
        case 402:
            return "الرصيد غير كافٍ لدى مزود الخدمة — أضف رصيداً أو بدّل المزود."
        case 429:
            return "تجاوزت حد الطلبات المسموح مؤقتاً (429). سيُعاد المحاولة تلقائياً."
        case 408, 502, 503, 504:
            return "الخدمة مشغولة مؤقتاً (\(status)). سيُعاد المحاولة."
        default:
            let snippet = body.count > 200 ? String(body.prefix(200)) + "…" : body
            return "خطأ من الخدمة (\(status)): \(snippet)"
        }
    }
}

enum HTTP {
    static func request(_ method: String,
                        _ url: String,
                        headers: [String: String] = [:],
                        body: Data? = nil,
                        timeout: Double = 300) async throws -> (Data, HTTPURLResponse) {
        guard let u = URL(string: url) else { throw URLError(.badURL) }
        var req = URLRequest(url: u)
        req.httpMethod = method
        req.timeoutInterval = timeout
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        applyDefaultHeaders(&req)
        req.httpBody = body
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        guard (200..<300).contains(http.statusCode) else {
            let ra = http.value(forHTTPHeaderField: "Retry-After").flatMap { Double($0) }
            throw APIError(status: http.statusCode, body: String(data: data, encoding: .utf8) ?? "", retryAfter: ra)
        }
        return (data, http)
    }

    static func applyDefaultHeaders(_ req: inout URLRequest) {
        if req.value(forHTTPHeaderField: "User-Agent") == nil {
            req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 16_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.4 Mobile/15E148 Safari/604.1", forHTTPHeaderField: "User-Agent")
        }
        if req.value(forHTTPHeaderField: "Accept") == nil {
            req.setValue("application/json", forHTTPHeaderField: "Accept")
        }
    }

    static func uploadFile(_ url: String,
                           fileURL: URL,
                           headers: [String: String] = [:],
                           timeout: Double = 3600) async throws -> (Data, HTTPURLResponse) {
        guard let u = URL(string: url) else { throw URLError(.badURL) }
        var req = URLRequest(url: u)
        req.httpMethod = "POST"
        req.timeoutInterval = timeout
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        applyDefaultHeaders(&req)
        let (data, resp) = try await URLSession.shared.upload(for: req, fromFile: fileURL)
        guard let http = resp as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        guard (200..<300).contains(http.statusCode) else {
            throw APIError(status: http.statusCode, body: String(data: data, encoding: .utf8) ?? "", retryAfter: nil)
        }
        return (data, http)
    }

    static func withRetry<T: Sendable>(attempts: Int = 4,
                                       baseDelay: Double = 2.0,
                                       _ op: () async throws -> T) async throws -> T {
        var lastError: Error = URLError(.badServerResponse)
        for n in 0..<max(1, attempts) {
            if Task.isCancelled { throw CancellationError() }
            do {
                return try await op()
            } catch let e as APIError where e.status == 429 || e.status >= 500 {
                lastError = e
                let ra = e.retryAfter ?? (baseDelay * pow(2.0, Double(n)) + (e.status == 429 ? 4 : 0))
                try await Task.sleep(nanoseconds: UInt64(min(ra, 90) * 1_000_000_000))
            } catch let e as APIError {
                throw e
            } catch {
                lastError = error
                try await Task.sleep(nanoseconds: UInt64(baseDelay * pow(2.0, Double(n)) * 1_000_000_000))
            }
        }
        throw lastError
    }

    static func json(from data: Data) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    static func num(_ any: Any?) -> Double? {
        if let n = any as? NSNumber { return n.doubleValue }
        if let d = any as? Double { return d }
        if let i = any as? Int { return Double(i) }
        if let s = any as? String { return Double(s) }
        return nil
    }
}

// MARK: - SiliconFlow (النطاق العالمي والصيني)

/// مفاتيح SiliconFlow العالمية تعمل على `.com`، بينما بعض الحسابات القديمة
/// أُنشئت على `.cn`. نجرب النطاق المحفوظ أولاً ثم الآخر عند أخطاء المصادقة.
enum SiliconFlowAPI {
    private static let preferenceKey = "siliconflow.api.base"
    private static let globalBase = "https://api.siliconflow.com/v1"
    private static let chinaBase = "https://api.siliconflow.cn/v1"

    static func request(_ method: String,
                        path: String,
                        key: String,
                        headers: [String: String] = [:],
                        body: Data? = nil,
                        timeout: Double = 300) async throws -> (Data, HTTPURLResponse) {
        let saved = UserDefaults.standard.string(forKey: preferenceKey)
        var bases = [saved, globalBase, chinaBase].compactMap { $0 }
        bases = bases.reduce(into: []) { result, base in
            if !result.contains(base) { result.append(base) }
        }

        var lastError: Error = URLError(.userAuthenticationRequired)
        for (index, base) in bases.enumerated() {
            do {
                var allHeaders = headers
                allHeaders["Authorization"] = "Bearer \(KeychainStore.normalized(key))"
                let result = try await HTTP.request(method, base + path,
                                                    headers: allHeaders,
                                                    body: body,
                                                    timeout: timeout)
                UserDefaults.standard.set(base, forKey: preferenceKey)
                return result
            } catch let error as APIError where (error.status == 401 || error.status == 403) && index < bases.count - 1 {
                lastError = error
                continue
            } catch {
                throw error
            }
        }
        throw lastError
    }
}

// MARK: - نتيجة التفريغ

struct STTResult {
    var cues: [SubCue]
    var detectedLang: String?
}

// MARK: - محرك التفريغ الصوتي

enum STTService {

    // MARK: Groq Whisper

    static func groqTranscribe(chunks: [AudioChunk],
                               chunksDir: URL,
                               language: SubLang,
                               apiKey: String,
                               concurrency: Int,
                               chunkDone: @escaping (Int) -> Void,
                               chunkResult: @escaping (Int, [SubCue], String?) -> Void) async throws -> STTResult {
        let boundary = "v2\(UUID().uuidString.replacingOccurrences(of: "-", with: ""))"
        var detected: String? = nil
        var allCues: [SubCue] = []

        var i = 0
        while i < chunks.count {
            if Task.isCancelled { throw CancellationError() }
            let window = Array(chunks[i..<min(i + max(1, concurrency), chunks.count)])
            i += window.count

            try await withThrowingTaskGroup(of: (Int, [SubCue], String?).self) { group in
                for chunk in window {
                    group.addTask {
                        let r = try await transcribeOneChunkGroq(chunk: chunk,
                                                                  chunksDir: chunksDir,
                                                                  language: language,
                                                                  apiKey: apiKey,
                                                                  boundary: boundary)
                        return (chunk.index, r.cues, r.detectedLang)
                    }
                }
                for try await (idx, cues, lang) in group {
                    allCues.append(contentsOf: cues)
                    if detected == nil { detected = lang }
                    chunkDone(idx)
                    chunkResult(idx, cues, lang)
                }
            }
        }
        return STTResult(cues: allCues, detectedLang: detected)
    }

    private static func transcribeOneChunkGroq(chunk: AudioChunk,
                                               chunksDir: URL,
                                               language: SubLang,
                                               apiKey: String,
                                               boundary: String) async throws -> STTResult {
        let fileURL = chunksDir.appendingPathComponent(chunk.fileName)
        let fileData: Data
        do { fileData = try Data(contentsOf: fileURL) }
        catch { throw AudioPipelineError.readerFailed("تعذر قراءة ملف الجزء \(chunk.index)") }

        var fields: [String: String] = [
            "model": "whisper-large-v3-turbo",
            "response_format": "verbose_json"
        ]
        if let lang = language.bcp47 { fields["language"] = lang }

        let body = multipart(fields: fields,
                             fileField: "file",
                             fileName: chunk.fileName,
                             mime: "audio/mp4",
                             fileData: fileData,
                             boundary: boundary)

        let (data, _) = try await HTTP.withRetry {
            try await HTTP.request("POST",
                                   "https://api.groq.com/openai/v1/audio/transcriptions",
                                   headers: [
                                    "Authorization": "Bearer \(apiKey)",
                                    "Content-Type": "multipart/form-data; boundary=\(boundary)"
                                   ],
                                   body: body,
                                   timeout: 600)
        }

        let json = HTTP.json(from: data)
        var cues: [SubCue] = []
        if let segments = json["segments"] as? [[String: Any]] {
            for seg in segments {
                guard let s = HTTP.num(seg["start"]), let e = HTTP.num(seg["end"]),
                      let t = seg["text"] as? String else { continue }
                let text = t.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else { continue }
                if text.count < 3 && [".", "..", "...", "you", "thank you"].contains(text.lowercased()) { continue }
                let start = chunk.start + s
                let end = chunk.start + e
                guard end > start else { continue }
                cues.append(SubCue(id: cues.count, start: start, end: end, text: text, translated: nil))
            }
        }
        if cues.isEmpty, let full = json["text"] as? String, !full.isEmpty {
            cues.append(SubCue(id: 0, start: chunk.start, end: chunk.start + max(2, chunk.duration), text: full, translated: nil))
        }
        return STTResult(cues: cues, detectedLang: json["language"] as? String)
    }

    // MARK: AssemblyAI

    static func assemblyTranscribe(audioURL: URL,
                                   language: SubLang,
                                   apiKey: String,
                                   existingTranscriptID: String?,
                                   estimatedDuration: Double,
                                   pollTick: @escaping (String) -> Void) async throws -> (STTResult, transcriptID: String) {

        let auth = ["Authorization": apiKey]

        var transcriptID = existingTranscriptID
        if transcriptID == nil {
            pollTick("رفع الملف الصوتي…")
            let (upData, _)  = try await HTTP.withRetry(attempts: 3) {
                try await HTTP.uploadFile("https://api.assemblyai.com/v2/upload",
                                          fileURL: audioURL,
                                          headers: ["Authorization": apiKey,
                                                    "Content-Type": "application/octet-stream"])
            }
            guard let uploadURL = HTTP.json(from: upData)["upload_url"] as? String else {
                throw APIError(status: 0, body: "استجابة رفع غير متوقعة")
            }

            pollTick("بدء التفريغ…")
            var body: [String: Any] = [
                "audio_url": uploadURL,
                "speech_model": "universal",
                "punctuate": true,
                "format_text": true
            ]
            if let lang = language.bcp47 {
                body["language_code"] = lang
            } else {
                body["language_detection"] = true
            }
            let payload = try JSONSerialization.data(withJSONObject: body)
            let (tData, _) = try await HTTP.withRetry(attempts: 3) {
                try await HTTP.request("POST",
                                       "https://api.assemblyai.com/v2/transcript",
                                       headers: dictMerging(auth, ["Content-Type": "application/json"]),
                                       body: payload)
            }
            guard let id = HTTP.json(from: tData)["id"] as? String else {
                throw APIError(status: 0, body: "تعذر إنشاء مهمة التفريغ")
            }
            transcriptID = id
        }

        guard let id = transcriptID else {
            throw APIError(status: 0, body: "معرّف مهمة مفقود")
        }

        let timeout = max(900, estimatedDuration * 2.5)
        let started = Date()
        var lastStatus = ""
        while true {
            if Task.isCancelled { throw CancellationError() }
            let (data, _) = try await HTTP.withRetry(attempts: 5, baseDelay: 3) {
                try await HTTP.request("GET", "https://api.assemblyai.com/v2/transcript/\(id)",
                                       headers: auth, timeout: 60)
            }
            let json = HTTP.json(from: data)
            let status = json["status"] as? String ?? ""
            if status != lastStatus {
                lastStatus = status
                pollTick(statusAR(status))
            }
            if status == "error" {
                let msg = json["error"] as? String ?? "غير معروف"
                throw APIError(status: 0, body: "فشل التفريغ: \(msg)")
            }
            if status == "completed" {
                let cues = parseAssemblyResponse(json)
                let lang = json["language_code"] as? String
                return (STTResult(cues: cues, detectedLang: lang), id)
            }
            if Date().timeIntervalSince(started) > timeout {
                throw APIError(status: 0, body: "انتهت مدة الانتظار للتفريغ — جرّب الاستئناف")
            }
            try await Task.sleep(nanoseconds: 4_000_000_000)
        }
    }

    private static func statusAR(_ s: String) -> String {
        switch s {
        case "queued": return "في الطابور…"
        case "processing": return "جارٍ التفريغ…"
        default: return s
        }
    }

    private static func parseAssemblyResponse(_ json: [String: Any]) -> [SubCue] {
        var cues: [SubCue] = []
        if let utterances = json["utterances"] as? [[String: Any]], !utterances.isEmpty {
            for u in utterances {
                guard let s = HTTP.num(u["start"]), let e = HTTP.num(u["end"]),
                      let t = u["text"] as? String else { continue }
                let text = t.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else { continue }
                cues.append(SubCue(id: cues.count, start: s / 1000.0, end: e / 1000.0, text: text, translated: nil))
            }
            return cues
        }

        guard let words = json["words"] as? [[String: Any]], !words.isEmpty else {
            if let text = json["text"] as? String, !text.isEmpty {
                return [SubCue(id: 0, start: 0, end: 10, text: text, translated: nil)]
            }
            return []
        }

        struct W { let start: Double; let end: Double; let token: String }
        var parsed: [W] = []
        for w in words {
            guard let s = HTTP.num(w["start"]), let e = HTTP.num(w["end"]),
                  let t = w["text"] as? String else { continue }
            parsed.append(W(start: s / 1000.0, end: e / 1000.0, token: t))
        }

        var current: [W] = []
        var currentStart = 0.0
        func flush() {
            guard !current.isEmpty else { return }
            let text = current.map { $0.token }.joined(separator: " ")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty {
                cues.append(SubCue(id: cues.count,
                                   start: currentStart,
                                   end: current.last!.end,
                                   text: text,
                                   translated: nil))
            }
            current = []
        }

        for (i, w) in parsed.enumerated() {
            if current.isEmpty { currentStart = w.start }
            current.append(w)
            let dur = w.end - currentStart
            let endsSentence = w.token.hasSuffix(".") || w.token.hasSuffix("!") || w.token.hasSuffix("?") ||
                w.token.hasSuffix("।") || w.token.hasSuffix("؟")
            var bigGap = false
            if i + 1 < parsed.count {
                bigGap = parsed[i + 1].start - w.end > 1.2
            }
            if endsSentence || bigGap || dur > 7.0 {
                flush()
            }
        }
        flush()
        return cues
    }

    // MARK: SiliconFlow SenseVoice (ASR)

    static func siliconFlowTranscribe(chunks: [AudioChunk],
                                      chunksDir: URL,
                                      language: SubLang,
                                      apiKey: String,
                                      model: String,
                                      concurrency: Int,
                                      chunkDone: @escaping (Int) -> Void,
                                      chunkResult: @escaping (Int, [SubCue], String?) -> Void) async throws -> STTResult {
        var detected: String? = nil
        var allCues: [SubCue] = []

        var i = 0
        while i < chunks.count {
            if Task.isCancelled { throw CancellationError() }
            let window = Array(chunks[i..<min(i + max(1, concurrency), chunks.count)])
            i += window.count

            try await withThrowingTaskGroup(of: (Int, [SubCue], String?).self) { group in
                for chunk in window {
                    group.addTask {
                        let r = try await transcribeOneChunkSiliconFlow(chunk: chunk,
                                                                         chunksDir: chunksDir,
                                                                         language: language,
                                                                         apiKey: apiKey,
                                                                         model: model)
                        return (chunk.index, r.cues, r.detectedLang)
                    }
                }
                for try await (idx, cues, lang) in group {
                    allCues.append(contentsOf: cues)
                    if detected == nil { detected = lang }
                    chunkDone(idx)
                    chunkResult(idx, cues, lang)
                }
            }
        }
        return STTResult(cues: allCues, detectedLang: detected)
    }

    private static func transcribeOneChunkSiliconFlow(chunk: AudioChunk,
                                                      chunksDir: URL,
                                                      language: SubLang,
                                                      apiKey: String,
                                                      model: String) async throws -> STTResult {
        let fileURL = chunksDir.appendingPathComponent(chunk.fileName)
        let fileData: Data
        do { fileData = try Data(contentsOf: fileURL) }
        catch { throw AudioPipelineError.readerFailed("تعذر قراءة ملف الجزء \(chunk.index)") }

        let body: [String: Any] = [
            "model": model.isEmpty ? "FunAudioLLM/SenseVoiceSmall" : model
        ]
        let boundary = "sf\(UUID().uuidString.replacingOccurrences(of: "-", with: ""))"
        let payload = multipart(fields: body.mapValues { "\($0)" },
                                fileField: "file",
                                fileName: chunk.fileName,
                                mime: "audio/mp4",
                                fileData: fileData,
                                boundary: boundary)

        let (data, _) = try await HTTP.withRetry(attempts: 4, baseDelay: 4) {
            try await SiliconFlowAPI.request("POST",
                                                path: "/audio/transcriptions",
                                                key: apiKey,
                                                headers: ["Content-Type": "multipart/form-data; boundary=\(boundary)"],
                                                body: payload,
                                                timeout: 600)
        }
        let json = HTTP.json(from: data)
        var cues: [SubCue] = []
        // استجابة SiliconFlow: { "text": "...", "language": "ar|en|..." }
        if let text = json["text"] as? String {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                // SenseVoice لا يعطي segments افتراضياً، فنضع الجملة الكاملة
                cues.append(SubCue(id: 0,
                                   start: chunk.start,
                                   end: chunk.start + max(2, chunk.duration),
                                   text: trimmed,
                                   translated: nil))
            }
        }
        let lang = json["language"] as? String
        return STTResult(cues: cues, detectedLang: lang ?? language.rawValue)
    }

    // MARK: STT.ai

    static func sttaiTranscribe(audioURL: URL,
                                language: SubLang,
                                apiKey: String) async throws -> STTResult {
        // STT.ai uses one multipart request to /v1/transcribe. The previous
        // /v1/upload + audio_url flow is not part of their public API and can 404.
        let boundary = "sttai\(UUID().uuidString.replacingOccurrences(of: "-", with: ""))"
        let payload = multipart(fields: [
            "model": "large-v3-turbo",
            "language": language.bcp47 ?? "auto",
            "response_format": "json",
            "diarize": "false"
        ],
        fileField: "file",
        fileName: audioURL.lastPathComponent,
        mime: mimeType(forAudioURL: audioURL),
        fileData: try Data(contentsOf: audioURL),
        boundary: boundary)

        let (data, _) = try await HTTP.withRetry(attempts: 3, baseDelay: 3) {
            try await HTTP.request("POST", "https://api.stt.ai/v1/transcribe",
                                   headers: ["Authorization": "Bearer \(KeychainStore.normalized(apiKey))",
                                             "Content-Type": "multipart/form-data; boundary=\(boundary)"],
                                   body: payload,
                                   timeout: 1800)
        }
        let json = HTTP.json(from: data)
        var cues: [SubCue] = []
        if let segments = json["segments"] as? [[String: Any]] {
            for seg in segments {
                guard let text = seg["text"] as? String, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { continue }
                let s = HTTP.num(seg["start"]) ?? 0
                let e = HTTP.num(seg["end"]) ?? max(s + 2, cues.last?.end ?? 0)
                cues.append(SubCue(id: cues.count, start: s, end: e, text: text, translated: nil))
            }
        }
        if cues.isEmpty, let text = json["text"] as? String, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let duration = HTTP.num(json["duration"]) ?? 10
            cues.append(SubCue(id: 0, start: 0, end: max(2, duration), text: text, translated: nil))
        }
        return STTResult(cues: cues, detectedLang: (json["language"] as? String) ?? language.rawValue)
    }

    // MARK: Speechmatics

    static func speechmaticsTranscribe(audioURL: URL,
                                       language: SubLang,
                                       apiKey: String) async throws -> STTResult {
        // Speechmatics Batch API expects a single multipart POST to /v2/jobs/:
        //   - form field "config" = JSON string
        //   - form file  "data_file" = audio/video file
        // The public SaaS API also requires the regional host and Bearer auth.
        let base = "https://eu1.asr.api.speechmatics.com/v2"
        let boundary = "sm\(UUID().uuidString.replacingOccurrences(of: "-", with: ""))"
        let config: [String: Any] = [
            "type": "transcription",
            "transcription_config": [
                "language": language.bcp47 ?? language.rawValue,
                "model": "enhanced",
                "diarization": "none"
            ]
        ]
        let configData = try JSONSerialization.data(withJSONObject: config)
        let configString = String(data: configData, encoding: .utf8) ?? "{}"
        let audioData = try Data(contentsOf: audioURL)
        let payload = multipart(fields: ["config": configString],
                                fileField: "data_file",
                                fileName: audioURL.lastPathComponent,
                                mime: mimeType(forAudioURL: audioURL),
                                fileData: audioData,
                                boundary: boundary)
        let auth = ["Authorization": "Bearer \(KeychainStore.normalized(apiKey))"]

        let (jobData, _) = try await HTTP.withRetry(attempts: 3, baseDelay: 3) {
            try await HTTP.request("POST", "\(base)/jobs/",
                                   headers: dictMerging(auth, ["Content-Type": "multipart/form-data; boundary=\(boundary)"]),
                                   body: payload,
                                   timeout: 1800)
        }
        let jobJson = HTTP.json(from: jobData)
        let jobID = (jobJson["id"] as? String)
            ?? ((jobJson["job"] as? [String: Any])?["id"] as? String)
        guard let jobID, !jobID.isEmpty else {
            throw APIError(status: 0, body: "تعذر إنشاء مهمة Speechmatics")
        }

        let start = Date()
        while true {
            if Task.isCancelled { throw CancellationError() }
            let (statusData, _) = try await HTTP.withRetry(attempts: 4, baseDelay: 3) {
                try await HTTP.request("GET", "\(base)/jobs/\(jobID)",
                                       headers: auth, timeout: 60)
            }
            let statusJson = HTTP.json(from: statusData)
            let status = ((statusJson["job"] as? [String: Any])?["status"] as? String) ?? ""
            if status == "done" {
                let (data, _) = try await HTTP.withRetry(attempts: 4, baseDelay: 3) {
                    try await HTTP.request("GET", "\(base)/jobs/\(jobID)/transcript?format=json-v2",
                                           headers: auth, timeout: 120)
                }
                let json = HTTP.json(from: data)
                return STTResult(cues: parseSpeechmaticsResponse(json), detectedLang: language.rawValue)
            }
            if status == "rejected" || status == "failed" {
                throw APIError(status: 0, body: "فشل تفريغ Speechmatics: \(status)")
            }
            if Date().timeIntervalSince(start) > 1800 {
                throw APIError(status: 0, body: "انتهت مدة الانتظار لـ Speechmatics")
            }
            try await Task.sleep(nanoseconds: 5_000_000_000)
        }
    }

    private static func parseSpeechmaticsResponse(_ json: [String: Any]) -> [SubCue] {
        guard let results = json["results"] as? [[String: Any]] else { return [] }
        var cues: [SubCue] = []
        var words: [(text: String, start: Double, end: Double)] = []

        func flush() {
            guard !words.isEmpty else { return }
            let text = words.map(\.text)
                .joined(separator: " ")
                .replacingOccurrences(of: " ,", with: ",")
                .replacingOccurrences(of: " .", with: ".")
                .replacingOccurrences(of: " ?", with: "?")
                .replacingOccurrences(of: " !", with: "!")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty {
                cues.append(SubCue(id: cues.count,
                                   start: words.first?.start ?? 0,
                                   end: words.last?.end ?? ((words.first?.start ?? 0) + 2),
                                   text: text,
                                   translated: nil))
            }
            words.removeAll(keepingCapacity: true)
        }

        for item in results {
            guard let alternatives = item["alternatives"] as? [[String: Any]],
                  let best = alternatives.first,
                  let content = best["content"] as? String,
                  !content.isEmpty else { continue }
            let type = item["type"] as? String ?? "word"
            if type == "punctuation" {
                if var last = words.popLast() {
                    last.text += content
                    words.append(last)
                }
                if ".!?؟".contains(content) { flush() }
                continue
            }
            let s = HTTP.num(item["start_time"]) ?? (words.last?.end ?? 0)
            let e = HTTP.num(item["end_time"]) ?? max(s + 0.5, words.last?.end ?? 0)
            words.append((content, s, e))
            if words.count >= 18 || (e - (words.first?.start ?? s)) >= 7 { flush() }
        }
        flush()
        return cues
    }

    private static func mimeType(forAudioURL url: URL) -> String {
        switch url.pathExtension.lowercased() {
        case "m4a": return "audio/m4a"
        case "mp3", "mpeg": return "audio/mpeg"
        case "wav": return "audio/wav"
        case "aac": return "audio/aac"
        case "ogg": return "audio/ogg"
        case "flac": return "audio/flac"
        case "mp4": return "video/mp4"
        default: return "application/octet-stream"
        }
    }

    // MARK: بناء Multipart

    private static func multipart(fields: [String: String],
                                  fileField: String,
                                  fileName: String,
                                  mime: String,
                                  fileData: Data,
                                  boundary: String) -> Data {
        var body = Data()
        func append(_ s: String) { body.append(Data(s.utf8)) }
        for (k, v) in fields {
            append("--\(boundary)\r\n")
            append("Content-Disposition: form-data; name=\"\(k)\"\r\n\r\n")
            append("\(v)\r\n")
        }
        append("--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"\(fileField)\"; filename=\"\(fileName)\"\r\n")
        append("Content-Type: \(mime)\r\n\r\n")
        body.append(fileData)
        append("\r\n--\(boundary)--\r\n")
        return body
    }

    private static func dictMerging(_ a: [String: String], _ b: [String: String]) -> [String: String] {
        var m = a
        for (k, v) in b { m[k] = v }
        return m
    }
}

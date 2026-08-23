import Foundation

// MARK: - محرك الترجمة النصية

enum TranslateService {

    struct Batch {
        var startIndex: Int   // فهرس أول جملة في الدفعة داخل مصفوفة الجمل الكاملة
        var cueIDs: [Int]
        var texts: [String]
    }

    /// إعدادات جلسة الترجمة — تُمرر في كل استدعاء.
    struct Config {
        var provider: TranslatorKind
        var model: String
        var temperature: Double
        var maxOutputTokens: Int
    }

    /// يبني دفعات من الجُمل (حوالي 40 سطراً للدفعة — يوازن الجودة مع حدود المخرجات).
    static func makeBatches(cues: [SubCue], size: Int = 20) -> [Batch] {
        var batches: [Batch] = []
        var i = 0
        while i < cues.count {
            let slice = cues[i..<min(i + size, cues.count)]
            batches.append(Batch(startIndex: i,
                                 cueIDs: slice.map { $0.id },
                                 texts: slice.map { $0.text }))
            i += slice.count
        }
        return batches
    }

    // MARK: واجهة موحدة

    /// يترجم جُمل الدفعة ويعيد الترجمات بنفس الترتيب (قد يحتوي nil عند فشل سطر معين).
    static func translateBatch(provider: TranslatorKind,
                               batch: Batch,
                               contextTail: [(String, String)],
                               source: SubLang,
                               target: SubLang,
                               videoTitle: String,
                               geminiModel: String) async throws -> [String] {
        let cfg = Config(provider: provider,
                         model: geminiModel,
                         temperature: 0.15,
                         maxOutputTokens: 32768)
        return try await translateBatch(config: cfg,
                                         batch: batch,
                                         contextTail: contextTail,
                                         source: source,
                                         target: target,
                                         videoTitle: videoTitle)
    }

    /// النسخة الكاملة من translateBatch مع إعدادات مخصّصة.
    static func translateBatch(config: Config,
                               batch: Batch,
                               contextTail: [(String, String)],
                               source: SubLang,
                               target: SubLang,
                               videoTitle: String) async throws -> [String] {
        switch resolved(provider: config.provider) {
        case .gemini:
            return try await geminiBatch(batch, config: config, contextTail: contextTail,
                                         source: source, target: target, videoTitle: videoTitle)
        case .groqLLM:
            return try await groqBatch(batch, config: config, contextTail: contextTail,
                                       source: source, target: target, videoTitle: videoTitle)
        case .deepL:
            return try await deepLBatch(batch, source: source, target: target)
        case .siliconflow:
            return try await siliconFlowBatch(batch, config: config, contextTail: contextTail,
                                              source: source, target: target, videoTitle: videoTitle)
        case .auto:
            throw APIError(status: 0, body: "لا يوجد مزود ترجمة مفعّل")
        }
    }

    /// يحوّل "تلقائي" إلى مزود فعلي حسب المفاتيح المتاحة.
    static func resolved(provider: TranslatorKind) -> TranslatorKind {
        switch provider {
        case .auto:
            if KeychainStore.has("gemini") { return .gemini }
            if KeychainStore.has("groq") { return .groqLLM }
            if KeychainStore.has("siliconflow") { return .siliconflow }
            if KeychainStore.has("deepl") { return .deepL }
            return .auto
        default:
            return provider
        }
    }

    static func hasKey(for provider: TranslatorKind) -> Bool {
        guard let k = resolved(provider: provider).keyID else { return false }
        return KeychainStore.has(k)
    }

    static func providerName(_ provider: TranslatorKind) -> String {
        switch resolved(provider: provider) {
        case .gemini: return "Gemini"
        case .groqLLM: return "Groq LLM"
        case .deepL: return "DeepL"
        case .siliconflow: return "SiliconFlow"
        case .auto: return "—"
        }
    }

    // MARK: البرومبت المشترك

    private static func systemPrompt(source: SubLang, target: SubLang, videoTitle: String) -> String {
        """
        You are an expert subtitle translator. Translate subtitle lines into \(target.englishName).
        Video title: "\(videoTitle)".
        Source language: \(source.englishName).
        Rules:
        - Translate naturally as spoken \(target.englishName), preserving meaning, tone and formality.
        - Keep translations short and readable as on-screen subtitles (aim for similar or shorter length than the source).
        - Keep proper names, brands and technical terms in their commonly known form in \(target.englishName).
        - Numbers stay numbers. Do not add explanations, notes, or extra content.
        - If a line is already in \(target.englishName), lightly fix grammar only.
        - Never merge or drop lines.
        Return ONLY valid JSON of the shape {"lines":[{"i":<id>,"t":"<translation>"}]} with exactly the same ids as the input, same count, same order.
        """
    }

    private static func userPrompt(batch: Batch, contextTail: [(String, String)]) -> String {
        var lines: [String] = []
        for (id, text) in zip(batch.cueIDs, batch.texts) {
            if let jsonData = try? JSONSerialization.data(withJSONObject: ["i": id, "t": text],
                                                          options: [.sortedKeys]),
               let s = String(data: jsonData, encoding: .utf8) {
                lines.append(s)
            }
        }
        var ctx = ""
        if !contextTail.isEmpty {
            let pairs = contextTail.suffix(3).map { src, dst in
                "\(src) => \(dst)"
            }.joined(separator: "\n")
            ctx = "\nPrevious lines already translated (context only, do not translate again):\n\(pairs)\n"
        }
        return """
        \(ctx)Translate these subtitle lines. Input JSON array:
        [\(lines.joined(separator: ",\n"))]
        """
    }

    // MARK: Gemini

    private static func geminiBatch(_ batch: Batch,
                                    config: Config,
                                    contextTail: [(String, String)],
                                    source: SubLang,
                                    target: SubLang,
                                    videoTitle: String) async throws -> [String] {
        guard let key = KeychainStore.get("gemini") else {
            throw APIError(status: 401, body: "أدخل مفتاح Gemini من الإعدادات")
        }
        let model = config.model.isEmpty ? "gemini-2.5-flash" : config.model
        let url = "https://generativelanguage.googleapis.com/v1beta/models/\(model):generateContent?key=\(key)"
        let body: [String: Any] = [
            "system_instruction": ["parts": [["text": systemPrompt(source: source, target: target, videoTitle: videoTitle)]]],
            "contents": [["role": "user", "parts": [["text": userPrompt(batch: batch, contextTail: contextTail)]]]],
            "generationConfig": [
                "temperature": config.temperature,
                "responseMimeType": "application/json",
                "maxOutputTokens": config.maxOutputTokens
            ] as [String: Any]
        ]
        let payload = try JSONSerialization.data(withJSONObject: body)
        let (data, resp) = try await HTTP.withRetry(attempts: 5, baseDelay: 6) {
            try await HTTP.request("POST", url,
                                   headers: ["Content-Type": "application/json"],
                                   body: payload,
                                   timeout: 180)
        }
        // Gemini أحياناً يرجع 200 لكن بدون "candidates" بسبب فلترة الأمان — نرمي برسالة واضحة
        let json = HTTP.json(from: data)
        if let err = json["error"] as? [String: Any] {
            let msg = (err["message"] as? String) ?? "خطأ غير معروف"
            let code = (err["code"] as? Int) ?? resp.statusCode
            throw APIError(status: code, body: "Gemini: \(msg)")
        }
        var text = ""
        if let candidates = json["candidates"] as? [[String: Any]],
           let content = candidates.first?["content"] as? [String: Any],
           let parts = content["parts"] as? [[String: Any]] {
            text = parts.compactMap { $0["text"] as? String }.joined()
        }
        if text.isEmpty {
            if let promptFeedback = json["promptFeedback"] as? [String: Any] {
                let reason = promptFeedback["blockReason"] as? String ?? "empty"
                throw APIError(status: 0, body: "استجابة Gemini فارغة (\(reason)) — جرّب موديلاً آخر من شاشة اختيار الموديل")
            }
            // في بعض الأحيان Gemini يعيد candidates فارغة بسبب MAX_TOKENS
            if let finishReason = (json["candidates"] as? [[String: Any]])?.first?["finishReason"] as? String {
                throw APIError(status: 0, body: "Gemini توقف مبكراً (\(finishReason)) — قلل حجم الدفعة من الإعدادات")
            }
            throw APIError(status: 0, body: "استجابة Gemini فارغة — جرّب موديلاً آخر من شاشة اختيار الموديل")
        }
        return parseLines(rawJSON: text, batch: batch)
    }

    // MARK: Groq LLM

    private static func groqBatch(_ batch: Batch,
                                  config: Config,
                                  contextTail: [(String, String)],
                                  source: SubLang,
                                  target: SubLang,
                                  videoTitle: String) async throws -> [String] {
        guard let key = KeychainStore.get("groq") else {
            throw APIError(status: 401, body: "أدخل مفتاح Groq من الإعدادات")
        }
        let model = config.model.isEmpty ? "openai/gpt-oss-120b" : config.model
        let body: [String: Any] = [
            "model": model,
            "temperature": config.temperature,
            "response_format": ["type": "json_object"],
            "messages": [
                ["role": "system", "content": systemPrompt(source: source, target: target, videoTitle: videoTitle)],
                ["role": "user", "content": userPrompt(batch: batch, contextTail: contextTail)]
            ]
        ]
        let payload = try JSONSerialization.data(withJSONObject: body)
        let (data, _) = try await HTTP.withRetry(attempts: 5, baseDelay: 6) {
            try await HTTP.request("POST",
                                   "https://api.groq.com/openai/v1/chat/completions",
                                   headers: ["Authorization": "Bearer \(key)",
                                             "Content-Type": "application/json"],
                                   body: payload,
                                   timeout: 180)
        }
        let json = HTTP.json(from: data)
        if let err = json["error"] as? [String: Any] {
            let msg = (err["message"] as? String) ?? "خطأ غير معروف"
            throw APIError(status: 0, body: "Groq: \(msg)")
        }
        var text = ""
        if let choices = json["choices"] as? [[String: Any]],
           let message = choices.first?["message"] as? [String: Any],
           let content = message["content"] as? String {
            text = content
        }
        guard !text.isEmpty else {
            throw APIError(status: 0, body: "استجابة Groq فارغة")
        }
        return parseLines(rawJSON: text, batch: batch)
    }

    // MARK: SiliconFlow

    private static func siliconFlowBatch(_ batch: Batch,
                                         config: Config,
                                         contextTail: [(String, String)],
                                         source: SubLang,
                                         target: SubLang,
                                         videoTitle: String) async throws -> [String] {
        guard let key = KeychainStore.get("siliconflow") else {
            throw APIError(status: 401, body: "أدخل مفتاح SiliconFlow من الإعدادات")
        }
        let model = config.model.isEmpty ? "Qwen/Qwen2.5-72B-Instruct" : config.model
        let body: [String: Any] = [
            "model": model,
            "temperature": config.temperature,
            "response_format": ["type": "json_object"],
            "messages": [
                ["role": "system", "content": systemPrompt(source: source, target: target, videoTitle: videoTitle)],
                ["role": "user", "content": userPrompt(batch: batch, contextTail: contextTail)]
            ]
        ]
        let payload = try JSONSerialization.data(withJSONObject: body)
        let (data, _) = try await HTTP.withRetry(attempts: 5, baseDelay: 6) {
            try await SiliconFlowAPI.request("POST",
                                                path: "/chat/completions",
                                                key: key,
                                                headers: ["Content-Type": "application/json"],
                                                body: payload,
                                                timeout: 180)
        }
        let json = HTTP.json(from: data)
        if let err = json["error"] as? [String: Any] {
            let msg = (err["message"] as? String) ?? "خطأ غير معروف"
            throw APIError(status: 0, body: "SiliconFlow: \(msg)")
        }
        var text = ""
        if let choices = json["choices"] as? [[String: Any]],
           let message = choices.first?["message"] as? [String: Any],
           let content = message["content"] as? String {
            text = content
        }
        guard !text.isEmpty else {
            throw APIError(status: 0, body: "استجابة SiliconFlow فارغة")
        }
        return parseLines(rawJSON: text, batch: batch)
    }

    // MARK: DeepL

    private static func deepLBatch(_ batch: Batch,
                                   source: SubLang,
                                   target: SubLang) async throws -> [String] {
        guard let key = KeychainStore.get("deepl") else {
            throw APIError(status: 401, body: "أدخل مفتاح DeepL من الإعدادات")
        }
        let url = "https://api-free.deepl.com/v2/translate"
        let texts = batch.texts
        // DeepL API expects uppercase 2-letter target lang codes
        let targetCode = target.rawValue.uppercased()
        var body: [String: Any] = [
            "text": texts,
            "target_lang": targetCode
        ]
        if source != .auto {
            body["source_lang"] = source.rawValue.uppercased()
        }
        let payload = try JSONSerialization.data(withJSONObject: body)
        let (data, _) = try await HTTP.withRetry(attempts: 3, baseDelay: 2) {
            try await HTTP.request("POST", url,
                                   headers: [
                                    "Authorization": "DeepL-Auth-Key \(key)",
                                    "Content-Type": "application/json"
                                   ],
                                   body: payload,
                                   timeout: 180)
        }
        let json = HTTP.json(from: data)
        guard let translations = json["translations"] as? [[String: Any]] else {
            throw APIError(status: 0, body: "استجابة DeepL غير متوقعة")
        }
        var result: [String] = []
        for t in translations {
            let translatedText = (t["text"] as? String) ?? ""
            result.append(translatedText)
        }
        // إذا كان عدد النتائج أقل من المدخلات، نملأ بالباقي فارغاً
        while result.count < batch.texts.count {
            result.append("")
        }
        return Array(result.prefix(batch.texts.count))
    }

    // MARK: تحليل الاستجابة

    /// يقبل {"lines":[...]} أو [...] مع تنظيف أسوار Markdown إن وجدت.
    private static func parseLines(rawJSON: String, batch: Batch) -> [String] {
        var cleaned = rawJSON.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.hasPrefix("```") {
            cleaned = cleaned
                .replacingOccurrences(of: "```json", with: "")
                .replacingOccurrences(of: "```", with: "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        var map: [Int: String] = [:]
        if let data = cleaned.data(using: .utf8),
           let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let lines = obj["lines"] as? [[String: Any]] {
            for l in lines {
                if let n = HTTP.num(l["i"]), let i = Int(exactly: n), let t = l["t"] as? String {
                    map[i] = t.trimmingCharacters(in: .whitespacesAndNewlines)
                }
            }
        } else if let data = cleaned.data(using: .utf8),
                  let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] {
            for l in arr {
                if let n = HTTP.num(l["i"]), let i = Int(exactly: n), let t = l["t"] as? String {
                    map[i] = t.trimmingCharacters(in: .whitespacesAndNewlines)
                }
            }
        }
        // بنفس ترتيب الدفعة، وسقوط للنص الأصلي عند غياب الترجمة
        return batch.cueIDs.map { map[$0] ?? "" }
    }
}

import Foundation

// MARK: - محرك مراجعة وتدقيق نصوص التفريغ (Speech-to-Text Refinement & Proofreading)

enum SubtitleRefineService {

    private struct GeminiRefineError: LocalizedError {
        let detail: String
        var errorDescription: String? { "Gemini (مراجعة): \(detail)" }
    }

    struct Batch {
        var startIndex: Int   // فهرس أول جملة في الدفعة
        var cueIDs: [Int]
        var texts: [String]
    }

    /// إعدادات جلسة المراجعة والتدقيق
    struct Config {
        var provider: SubtitleRefinerKind
        var model: String
        var temperature: Double
        var maxOutputTokens: Int
    }

    /// يبني دفعات من الجُمل للمراجعة (18 سطراً للدفعة يضمن سياقاً مناسباً ودقة عالية).
    static func makeBatches(cues: [SubCue], size: Int = 18) -> [Batch] {
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

    // MARK: - واجهة موحدة

    /// يراجع جُمل الدفعة ويصحح الأخطاء الصوتية والتكرارات ويكمل الكلمات الناقصة في اللغة الأصلية.
    static func refineBatch(config: Config,
                            batch: Batch,
                            contextTail: [String],
                            source: SubLang,
                            videoTitle: String) async throws -> [String] {
        switch resolved(provider: config.provider) {
        case .gemini:
            return try await geminiBatch(batch, config: config, contextTail: contextTail,
                                         source: source, videoTitle: videoTitle)
        case .groqLLM:
            return try await groqBatch(batch, config: config, contextTail: contextTail,
                                       source: source, videoTitle: videoTitle)
        case .openRouter, .cerebras, .sambaNova:
            return try await openAIChatBatch(provider: resolved(provider: config.provider),
                                             batch: batch, config: config, contextTail: contextTail,
                                             source: source, videoTitle: videoTitle)
        case .auto:
            throw APIError(status: 0, body: "لا يوجد مزود مراجعة مفعّل")
        case .off:
            return batch.texts
        }
    }

    /// يحوّل "تلقائي" إلى مزود فعلي حسب المفاتيح المتاحة.
    static func resolved(provider: SubtitleRefinerKind) -> SubtitleRefinerKind {
        switch provider {
        case .auto:
            if KeychainStore.has("gemini") { return .gemini }
            if KeychainStore.has("groq") { return .groqLLM }
            if KeychainStore.has("cerebras") { return .cerebras }
            if KeychainStore.has("sambanova") { return .sambaNova }
            if KeychainStore.has("openrouter") { return .openRouter }
            return .off
        default:
            return provider
        }
    }

    static func hasKey(for provider: SubtitleRefinerKind) -> Bool {
        guard let k = resolved(provider: provider).keyID else {
            return provider == .off
        }
        return KeychainStore.has(k)
    }

    // MARK: - التبديل التلقائي عند نفاد الحصة

    static func isQuotaOrLimitError(_ error: Error) -> Bool {
        TranslateService.isQuotaOrLimitError(error)
    }

    private static let failoverOrder: [SubtitleRefinerKind] =
        [.gemini, .groqLLM, .cerebras, .sambaNova, .openRouter]

    static func failoverChain(from preferred: SubtitleRefinerKind) -> [SubtitleRefinerKind] {
        let start = resolved(provider: preferred)
        if start == .off { return [.off] }
        var chain: [SubtitleRefinerKind] = []
        if start != .auto, let key = start.keyID, KeychainStore.has(key) {
            chain.append(start)
        }
        for candidate in failoverOrder where !chain.contains(candidate) {
            if let key = candidate.keyID, KeychainStore.has(key) {
                chain.append(candidate)
            }
        }
        return chain
    }

    static func providerName(_ provider: SubtitleRefinerKind) -> String {
        switch resolved(provider: provider) {
        case .gemini: return "Gemini"
        case .groqLLM: return "Groq LLM"
        case .openRouter: return "OpenRouter"
        case .cerebras: return "Cerebras"
        case .sambaNova: return "SambaNova"
        case .auto: return "تلقائي"
        case .off: return "معطل"
        }
    }

    static func modelSelection(for provider: SubtitleRefinerKind,
                               geminiOverride: String? = nil) -> String {
        switch provider {
        case .gemini:
            if let geminiOverride, !geminiOverride.isEmpty { return geminiOverride }
            return ModelSelection.selected(purpose: "refiner", provider: .gemini,
                                           fallback: ModelSelection.selected(purpose: "translator", provider: .gemini, fallback: TranslateService.defaultGeminiModel))
        case .groqLLM:
            return ModelSelection.selected(purpose: "refiner", provider: .groq,
                                           fallback: ModelSelection.selected(purpose: "translator", provider: .groq, fallback: "openai/gpt-oss-120b"))
        case .openRouter:
            return ModelSelection.selected(purpose: "refiner", provider: .openRouter,
                                           fallback: ModelSelection.selected(purpose: "translator", provider: .openRouter, fallback: TranslateService.defaultOpenRouterModel))
        case .cerebras:
            return ModelSelection.selected(purpose: "refiner", provider: .cerebras,
                                           fallback: ModelSelection.selected(purpose: "translator", provider: .cerebras, fallback: TranslateService.defaultCerebrasModel))
        case .sambaNova:
            return ModelSelection.selected(purpose: "refiner", provider: .sambaNova,
                                           fallback: ModelSelection.selected(purpose: "translator", provider: .sambaNova, fallback: TranslateService.defaultSambaNovaModel))
        case .auto, .off:
            return ""
        }
    }

    // MARK: - البرومبت المخصص للتدقيق والمراجعة

    private static func systemPrompt(source: SubLang, videoTitle: String) -> String {
        let langName = source == .auto ? "the original spoken language" : source.englishName
        return """
        You are an expert subtitle editor and speech-to-text proofreader.
        Your task is to review, proofread, and correct raw speech-to-text subtitle transcriptions in \(langName).
        Video title: "\(videoTitle)".
        Source language: \(langName).

        Rules:
        1. Correct speech recognition (ASR) phonetic mistakes, misheard words, and wrong homophones based on sentence context.
        2. Restore missing small words, dropped endings, or missing punctuation to ensure the meaning and context are complete and coherent.
        3. Remove ASR hallucinations and repetitive artifacts (e.g. repeated filler phrases like "thank you for watching", "subtitles by...", looping words, audio glitch text).
        4. DO NOT translate. Keep the text strictly in \(langName).
        5. Keep natural spoken phrasing suitable for on-screen subtitles.
        6. CRITICAL: Preserve the EXACT same number of lines, same IDs, and same order. Do NOT merge, split, or delete line IDs.
        7. Return ONLY valid JSON in this shape: {"lines":[{"i":<id>,"t":"<reviewed_text>"}]} with exactly the same IDs as the input.
        """
    }

    private static func userPrompt(batch: Batch, contextTail: [String], source: SubLang) -> String {
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
            let previous = contextTail.suffix(3).joined(separator: "\n")
            ctx = "\nPrevious lines (context only, already reviewed):\n\(previous)\n"
        }
        let langName = source == .auto ? "the original language" : source.englishName
        return """
        \(ctx)Review and correct these raw subtitle lines in \(langName). Input JSON array:
        [\(lines.joined(separator: ",\n"))]
        """
    }

    // MARK: - مراجعة كاملة عند الطلب

    /// يراجع مصفوفة جمل كاملة ويحدّث نصوصها (مفيد للاستخدام في شاشة مراجعة الترجمة SubtitleReviewView).
    static func refineAll(cues: [SubCue],
                          source: SubLang,
                          provider: SubtitleRefinerKind,
                          videoTitle: String,
                          progress: @escaping (Double, String) -> Void) async throws -> [SubCue] {
        guard !cues.isEmpty else { return [] }
        let resolvedProv = resolved(provider: provider)
        guard resolvedProv != .off else { return cues }

        let chain = failoverChain(from: resolvedProv)
        guard !chain.isEmpty else {
            throw APIError(status: 401, body: "لا يوجد مفتاح متاح لمراجعة النصوص.")
        }

        var activeConfig = Config(
            provider: chain[0],
            model: modelSelection(for: chain[0]),
            temperature: 0.1,
            maxOutputTokens: 4096
        )

        let batches = makeBatches(cues: cues)
        var reviewedMap: [Int: String] = [:]
        var contextTail: [String] = []
        var done = 0

        for (idx, batch) in batches.enumerated() {
            if Task.isCancelled { throw CancellationError() }
            let p = Double(idx) / Double(batches.count)
            progress(p, "مراجعة الدفعة \(idx + 1) من \(batches.count)…")

            var batchDone = false
            var chainIdx = 0
            while !batchDone && chainIdx < chain.count {
                do {
                    let out = try await refineBatch(config: activeConfig,
                                                    batch: batch,
                                                    contextTail: contextTail,
                                                    source: source,
                                                    videoTitle: videoTitle)
                    for (cueID, text) in zip(batch.cueIDs, out) {
                        reviewedMap[cueID] = text.isEmpty ? cues.first(where: { $0.id == cueID })?.text ?? "" : text
                    }
                    if let last = out.last, !last.isEmpty {
                        contextTail.append(last)
                    }
                    batchDone = true
                    done += 1
                } catch {
                    if isQuotaOrLimitError(error), chainIdx + 1 < chain.count {
                        chainIdx += 1
                        let next = chain[chainIdx]
                        activeConfig = Config(provider: next,
                                              model: modelSelection(for: next),
                                              temperature: 0.1,
                                              maxOutputTokens: 4096)
                        try? await Task.sleep(nanoseconds: 2_000_000_000)
                    } else {
                        throw error
                    }
                }
            }
        }

        progress(1.0, "اكتملت مراجعة النصوص")

        var result = cues
        for i in result.indices {
            if let refined = reviewedMap[result[i].id], !refined.isEmpty {
                result[i].text = refined
            }
        }
        return result
    }

    // MARK: - مزودات الذكاء الاصطناعي للمراجعة

    // MARK: Gemini
    private static func geminiBatch(_ batch: Batch,
                                    config: Config,
                                    contextTail: [String],
                                    source: SubLang,
                                    videoTitle: String) async throws -> [String] {
        guard let key = KeychainStore.get("gemini") else {
            throw GeminiRefineError(detail: "أدخل مفتاح Gemini من الإعدادات")
        }
        let model = TranslateService.normalizedGeminiModel(config.model).isEmpty
            ? TranslateService.defaultGeminiModel
            : TranslateService.normalizedGeminiModel(config.model)

        let body: [String: Any] = [
            "system_instruction": ["parts": [["text": systemPrompt(source: source, videoTitle: videoTitle)]]],
            "contents": [["role": "user", "parts": [["text": userPrompt(batch: batch, contextTail: contextTail, source: source)]]]],
            "generationConfig": [
                "temperature": config.temperature,
                "responseMimeType": "application/json",
                "maxOutputTokens": config.maxOutputTokens
            ] as [String: Any]
        ]
        let payload = try JSONSerialization.data(withJSONObject: body)
        let endpoint = "https://generativelanguage.googleapis.com/v1beta/models/\(model):generateContent"
        let (data, _) = try await HTTP.withRetry(attempts: 3, baseDelay: 3) {
            try await HTTP.request("POST", endpoint,
                                   headers: ["Content-Type": "application/json", "x-goog-api-key": key],
                                   body: payload,
                                   timeout: 75)
        }

        let json = HTTP.json(from: data)
        if let err = json["error"] as? [String: Any] {
            let msg = (err["message"] as? String) ?? "خطأ غير معروف"
            let code = (err["code"] as? Int) ?? 0
            throw APIError(status: code, body: "Gemini: \(msg)")
        }
        var text = ""
        if let candidates = json["candidates"] as? [[String: Any]],
           let content = candidates.first?["content"] as? [String: Any],
           let parts = content["parts"] as? [[String: Any]] {
            text = parts.compactMap { $0["text"] as? String }.joined()
        }
        guard !text.isEmpty else {
            throw APIError(status: 0, body: "استجابة Gemini فارغة أثناء مراجعة النصوص")
        }
        return parseLines(rawJSON: text, batch: batch)
    }

    // MARK: Groq LLM
    private static func groqBatch(_ batch: Batch,
                                  config: Config,
                                  contextTail: [String],
                                  source: SubLang,
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
                ["role": "system", "content": systemPrompt(source: source, videoTitle: videoTitle)],
                ["role": "user", "content": userPrompt(batch: batch, contextTail: contextTail, source: source)]
            ]
        ]
        let payload = try JSONSerialization.data(withJSONObject: body)
        let (data, _) = try await HTTP.withRetry(attempts: 4, baseDelay: 4) {
            try await HTTP.request("POST",
                                   "https://api.groq.com/openai/v1/chat/completions",
                                   headers: ["Authorization": "Bearer \(key)",
                                             "Content-Type": "application/json"],
                                   body: payload,
                                   timeout: 120)
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
            throw APIError(status: 0, body: "استجابة Groq فارغة أثناء مراجعة النصوص")
        }
        return parseLines(rawJSON: text, batch: batch)
    }

    // MARK: OpenAI Compat (OpenRouter / Cerebras / SambaNova)
    private static func openAIChatBatch(provider: SubtitleRefinerKind,
                                        batch: Batch,
                                        config: Config,
                                        contextTail: [String],
                                        source: SubLang,
                                        videoTitle: String) async throws -> [String] {
        let (label, keyID, url, defaultModel) = openAICompatDetails(for: provider)
        guard let key = KeychainStore.get(keyID) else {
            throw APIError(status: 401, body: "أدخل مفتاح \(label) من الإعدادات")
        }
        let model = config.model.isEmpty ? defaultModel : config.model
        var headers = ["Authorization": "Bearer \(KeychainStore.normalized(key))",
                       "Content-Type": "application/json"]
        if provider == .openRouter {
            headers["HTTP-Referer"] = "https://github.com/ahcrazy20-tech/video2"
            headers["X-Title"] = "Video2"
        }

        let body: [String: Any] = [
            "model": model,
            "temperature": config.temperature,
            "messages": [
                ["role": "system", "content": systemPrompt(source: source, videoTitle: videoTitle)],
                ["role": "user", "content": userPrompt(batch: batch, contextTail: contextTail, source: source)]
            ]
        ]
        let payload = try JSONSerialization.data(withJSONObject: body)
        let (data, _) = try await HTTP.withRetry(attempts: 4, baseDelay: 4) {
            try await HTTP.request("POST", url, headers: headers, body: payload, timeout: 120)
        }

        let json = HTTP.json(from: data)
        if let err = json["error"] as? [String: Any] {
            let msg = (err["message"] as? String) ?? "خطأ غير معروف"
            throw APIError(status: 0, body: "\(label): \(msg)")
        }
        var text = ""
        if let choices = json["choices"] as? [[String: Any]],
           let message = choices.first?["message"] as? [String: Any],
           let content = message["content"] as? String {
            text = content
        }
        guard !text.isEmpty else {
            throw APIError(status: 0, body: "استجابة \(label) فارغة أثناء مراجعة النصوص")
        }
        return parseLines(rawJSON: text, batch: batch)
    }

    private static func openAICompatDetails(for provider: SubtitleRefinerKind) -> (label: String, keyID: String, url: String, defaultModel: String) {
        switch provider {
        case .openRouter:
            return ("OpenRouter", "openrouter", "https://openrouter.ai/api/v1/chat/completions", TranslateService.defaultOpenRouterModel)
        case .cerebras:
            return ("Cerebras", "cerebras", "https://api.cerebras.ai/v1/chat/completions", TranslateService.defaultCerebrasModel)
        case .sambaNova:
            return ("SambaNova", "sambanova", "https://api.sambanova.ai/v1/chat/completions", TranslateService.defaultSambaNovaModel)
        default:
            return ("API", "", "", "")
        }
    }

    // MARK: - تحليل الاستجابة

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
        // ترتيب النتيجة حسب الدفعة مع السقوط للنص الأصلي إذا لم يُرجع السطر
        return batch.cueIDs.map { cueID in
            if let text = map[cueID], !text.isEmpty {
                return text
            }
            if let idx = batch.cueIDs.firstIndex(of: cueID) {
                return batch.texts[idx]
            }
            return ""
        }
    }
}

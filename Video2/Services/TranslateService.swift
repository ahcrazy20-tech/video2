import Foundation

// MARK: - محرك الترجمة النصية

enum TranslateService {

    /// يحتفظ باسم المزود في رسالة المهمة؛ أخطاء استخراج HLS تُعرض من
    /// AudioPipeline بينما هذه الرسالة تخص Gemini فقط.
    private struct GeminiServiceError: LocalizedError {
        let detail: String
        var errorDescription: String? { "Gemini: \(detail)" }
    }

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

    /// يبني دفعات من الجُمل (20 سطراً للدفعة — يوازن الجودة والسياق مع سرعة الاستجابة).
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
                         maxOutputTokens: 4096)
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
        case .openRouter, .cerebras, .sambaNova, .nvidia, .zai:
            return try await openAIChatBatch(provider: resolved(provider: config.provider),
                                             batch: batch, config: config, contextTail: contextTail,
                                             source: source, target: target, videoTitle: videoTitle)
        case .cohere:
            return try await cohereBatch(batch, config: config, contextTail: contextTail,
                                         source: source, target: target, videoTitle: videoTitle)
        case .lara:
            return try await laraBatch(batch, source: source, target: target, videoTitle: videoTitle)
        case .myMemory:
            return try await myMemoryBatch(batch, source: source, target: target)
        case .auto:
            throw APIError(status: 0, body: "لا يوجد مزود ترجمة مفعّل")
        }
    }

    /// يحوّل "تلقائي" إلى مزود فعلي حسب المفاتيح المتاحة.
    static func resolved(provider: TranslatorKind) -> TranslatorKind {
        switch provider {
        case .auto:
            // ترتيب الأفضلية: Gemini (جودة سياقية) ثم Groq ثم NVIDIA ثم بقية
            // المزودات المجانية، وMyMemory كملاذ أخير لأنه يعمل بدون أي مفتاح.
            if KeychainStore.has("gemini") { return .gemini }
            if KeychainStore.has("groq") { return .groqLLM }
            if KeychainStore.has("nvidia") { return .nvidia }
            if KeychainStore.has("openrouter") { return .openRouter }
            if KeychainStore.has("cerebras") { return .cerebras }
            if KeychainStore.has("sambanova") { return .sambaNova }
            if KeychainStore.has("cohere") { return .cohere }
            if KeychainStore.has("zai") { return .zai }
            if KeychainStore.has("deepl") { return .deepL }
            if KeychainStore.has("lara") { return .lara }
            return .myMemory
        default:
            return provider
        }
    }

    static func hasKey(for provider: TranslatorKind) -> Bool {
        // MyMemory يعمل بدون أي مفتاح — جاهز دائماً كملاذ أخير (حتى مع
        // «تلقائي» عندما لا يوجد أي مفتاح محفوظ).
        let resolvedProvider = resolved(provider: provider)
        if resolvedProvider == .myMemory { return true }
        guard let k = resolvedProvider.keyID else { return false }
        return KeychainStore.has(k)
    }

    // MARK: التبديل التلقائي عند نفاد الحصة المجانية

    /// هل هذا الخطأ يعني أن المزود رفض الاستمرار لأن حصته المجانية/حدّه انتهى؟
    /// هذه هي الحالات التي يستفيد فيها المستخدم من الانتقال لمزود آخر بدل
    /// سقوط المهمة بالكامل: 429 حد طلبات، 403 حصة/منطقة، 402 رصيد، و404 موديل.
    static func isQuotaOrLimitError(_ error: Error) -> Bool {
        guard let api = error as? APIError else { return false }
        if [402, 403, 429].contains(api.status) { return true }
        if api.status == 404 { return true }
        // بعض المزودين يرجعون 400 مع نص صريح عن نفاد الحصة بدل 429.
        let body = api.body.lowercased()
        let markers = ["resource_exhausted", "resource exhausted", "quota exceeded",
                       "exceeded your current quota", "rate limit", "ratelimit",
                       "too many requests", "daily limit", "insufficient_quota",
                       "credits", "free tier"]
        return markers.contains { body.contains($0) }
    }

    /// سلسلة المزودين بالترتيب: المطلوب أولاً ثم بقية من يملك مفتاحاً.
    /// بهذا لا تسقط مهمة فيديو 5 ساعات لأن Gemini وصل حدّه المجاني اليومي —
    /// تنتقل تلقائياً لأقرب مزود متاح وتكمل من نفس النقطة المحفوظة.
    /// الترتيب بعد المزود المطلوب مبني على ملاءمة الشريحة المجانية للفيديوهات
    /// الطويلة: NVIDIA/Cerebras/SambaNova/Cohere/Z.ai (حصص يومية كبيرة) ثم
    /// DeepL (حصة أحادية) ثم OpenRouter (50 طلب/يوم) ثم Lara، وMyMemory
    /// دائماً في النهاية لأنه يعمل بدون مفتاح (5K كلمة/يوم).
    private static let failoverOrder: [TranslatorKind] =
        [.gemini, .groqLLM, .nvidia, .cerebras, .sambaNova, .cohere, .zai, .deepL, .openRouter, .lara]

    static func failoverChain(from preferred: TranslatorKind) -> [TranslatorKind] {
        let start = resolved(provider: preferred)
        var chain: [TranslatorKind] = []
        if start != .auto, let key = start.keyID, KeychainStore.has(key) {
            chain.append(start)
        }
        for candidate in failoverOrder where !chain.contains(candidate) {
            if let key = candidate.keyID, KeychainStore.has(key) {
                chain.append(candidate)
            }
        }
        // MyMemory لا يحتاج مفتاحاً — شبكة الأمان الأخيرة في كل الحالات.
        if !chain.contains(.myMemory) {
            chain.append(.myMemory)
        }
        return chain
    }

    /// الموديل المناسب لمزوّد معيّن (لكل مزود موديله المستقل — يمنع إرسال اسم
    /// موديل Gemini إلى Cerebras مثلاً). يستخدم اختيار المستخدم إن وُجد.
    static func modelSelection(for provider: TranslatorKind,
                               geminiOverride: String? = nil) -> String {
        switch provider {
        case .gemini:
            if let geminiOverride, !geminiOverride.isEmpty { return geminiOverride }
            return ModelSelection.selected(purpose: "translator", provider: .gemini,
                                           fallback: defaultGeminiModel)
        case .groqLLM:
            return ModelSelection.selected(purpose: "translator", provider: .groq,
                                           fallback: "openai/gpt-oss-120b")
        case .openRouter:
            return ModelSelection.selected(purpose: "translator", provider: .openRouter,
                                           fallback: defaultOpenRouterModel)
        case .cerebras:
            return ModelSelection.selected(purpose: "translator", provider: .cerebras,
                                           fallback: defaultCerebrasModel)
        case .sambaNova:
            return ModelSelection.selected(purpose: "translator", provider: .sambaNova,
                                           fallback: defaultSambaNovaModel)
        case .nvidia:
            return ModelSelection.selected(purpose: "translator", provider: .nvidia,
                                           fallback: defaultNVIDIAModel)
        case .cohere:
            return ModelSelection.selected(purpose: "translator", provider: .cohere,
                                           fallback: defaultCohereModel)
        case .zai:
            return ModelSelection.selected(purpose: "translator", provider: .zai,
                                           fallback: defaultZaiModel)
        case .deepL:
            return "DeepL API"
        case .lara:
            return "Lara Translate API"
        case .myMemory:
            return "MyMemory API"
        case .auto:
            return ""
        }
    }

    static func providerName(_ provider: TranslatorKind) -> String {
        switch resolved(provider: provider) {
        case .gemini: return "Gemini"
        case .groqLLM: return "Groq LLM"
        case .deepL: return "DeepL"
        case .openRouter: return "OpenRouter"
        case .cerebras: return "Cerebras"
        case .sambaNova: return "SambaNova"
        case .nvidia: return "NVIDIA NIM"
        case .cohere: return "Cohere"
        case .zai: return "Z.ai"
        case .lara: return "Lara"
        case .myMemory: return "MyMemory"
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

    /// الموديل الافتراضي الحالي (Gemini 3.8 Flash — أحدث إصدار ثابت من Google).
    /// نتحقق من الموديلات المتاحة فعلياً لكل مفتاح عند الحاجة، مع استرداد تلقائي.
    static let defaultGeminiModel = "gemini-3.8-flash"

    // المزودات الجديدة المتوافقة مع OpenAI — كلها بدون فيزا ولها شريحة مجانية.
    /// موديلات OpenRouter التي تنتهي بـ :free مجانية بالكامل (50 طلب/يوم بلا شحن).
    /// الافتراضي الحالي مُتحقق منه مجاني ونشط على OpenRouter (2026-08-24).
    /// تشكيل الموديلات المجانية على OpenRouter يتغيّر باستمرار؛ لو توقف هذا
    /// الموديل يُعالجه الاسترداد التلقائي في openAIChatBatch (يبديل بنسخة حية).
    static let defaultOpenRouterModel = "google/gemma-4-31b-it:free"
    /// Cerebras: حوالي 200 ألف token/يوم مجاناً لكل موديل؛ Llama 3.3 70B هو
    /// الافتراضي بعد إيقاف llama-4-scout وllama3.1-8b من القائمة.
    static let defaultCerebrasModel = "llama-3.3-70b"
    /// SambaNova: DeepSeek V3.2 ممتاز للسياق العربي ضمن حصة ~200K token/يوم.
    static let defaultSambaNovaModel = "DeepSeek-V3.2"
    /// NVIDIA NIM: Nemotron 3 Super 120B (MoE) بسياق مليون token — 10K طلب/يوم.
    static let defaultNVIDIAModel = "nvidia/nemotron-3-super-120b-a12b"
    /// Cohere: Command A هو الأفضل للترجمة متعددة اللغات (Aya للعربية).
    static let defaultCohereModel = "command-a-03-2025"
    /// Z.ai: GLM-4.7-Flash مجاني بالكامل بسياق 200K.
    static let defaultZaiModel = "glm-4.7-flash"

    private static let preferredGeminiModels = [
        "gemini-3.8-flash",
        "gemini-3.7-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite"
    ]

    /// يقبل القيم التي كانت تُخزن في الإصدارات السابقة مثل `models/...`
    /// أو رابط generateContent ويعيد اسم الموديل فقط.
    static func normalizedGeminiModel(_ value: String) -> String {
        var model = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if let decoded = model.removingPercentEncoding { model = decoded }
        if let range = model.range(of: "/models/") {
            model = String(model[range.upperBound...])
        }
        if model.hasPrefix("models/") {
            model = String(model.dropFirst("models/".count))
        }
        if let query = model.firstIndex(of: "?") {
            model = String(model[..<query])
        }
        if let action = model.range(of: ":generateContent", options: [.caseInsensitive]) {
            model = String(model[..<action.lowerBound])
        }
        return model.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// موديلات Gemini 2.0 أُوقفت نهائياً، لذلك لا نرسلها إلى API حتى لو
    /// بقيت محفوظة على جهاز المستخدم من إصدار قديم.
    static func isRetiredGeminiModel(_ value: String) -> Bool {
        let model = normalizedGeminiModel(value).lowercased()
        return model.hasPrefix("gemini-2.0-") || model.hasPrefix("gemini-1.")
    }

    /// موديلات Cerebras المسحوبة من القائمة (أُوقف llama-4-scout وllama3.1-8b،
    /// وzai-glm-4.7 انتهى تجريبه). نرحّل أي اختيار محفوظ قديم إلى الافتراضي
    /// الحالي Llama 3.3 70B حتى لا تفشل المهام بـ 404 على موديل غير موجود.
    static func isRetiredCerebrasModel(_ value: String) -> Bool {
        let model = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let retired = ["llama3.1-8b",
                       "llama-4-scout-17b-16e-instruct",
                       "llama-4-scout",
                       "zai-glm-4.7"]
        return retired.contains(model)
    }

    private static func selectedGeminiModel() -> String {
        let selected = ModelSelection.selected(purpose: "translator",
                                               provider: .gemini,
                                               fallback: defaultGeminiModel)
        let normalized = normalizedGeminiModel(selected)
        return normalized.isEmpty || isRetiredGeminiModel(normalized) ? defaultGeminiModel : normalized
    }

    private static func geminiEndpoint(model: String) -> String {
        var components = URLComponents()
        components.scheme = "https"
        components.host = "generativelanguage.googleapis.com"
        components.path = "/v1beta/models/\(normalizedGeminiModel(model)):generateContent"
        return components.url?.absoluteString
            ?? "https://generativelanguage.googleapis.com/v1beta/models/\(defaultGeminiModel):generateContent"
    }

    private static func geminiModelsEndpoint(pageToken: String? = nil) -> String {
        var components = URLComponents()
        components.scheme = "https"
        components.host = "generativelanguage.googleapis.com"
        components.path = "/v1beta/models"
        var items = [URLQueryItem(name: "pageSize", value: "100")]
        if let pageToken, !pageToken.isEmpty {
            items.append(URLQueryItem(name: "pageToken", value: pageToken))
        }
        components.queryItems = items
        return components.url?.absoluteString ?? "https://generativelanguage.googleapis.com/v1beta/models"
    }

    private static func geminiHeaders(key: String) -> [String: String] {
        ["Content-Type": "application/json", "x-goog-api-key": key]
    }

    /// يكيّف جسم الطلب بحسب عائلة Gemini قبل الإرسال الفعلي. هذا مهم عند
    /// الاسترداد التلقائي: قد يبدأ الطلب بـ Gemini 3 ثم يتحول إلى 2.5.
    /// Gemini 3.x يرفض معاملات أخذ العينات القديمة (temperature/topP/topK)،
    /// بينما تبقى temperature مدعومة في Gemini 2.5. إبقاء هذا في دالة واحدة
    /// يمنع اختلاف طلب الترجمة عن طلب مراجعة الترجمة.
    static func optimizedGeminiPayload(_ payload: Data, model: String) -> Data {
        guard var body = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any],
              var generationConfig = body["generationConfig"] as? [String: Any] else {
            return payload
        }

        let id = normalizedGeminiModel(model).lowercased()
        if id.hasPrefix("gemini-3.") {
            // Gemini 3.7 Flash يدعم low/medium/high فقط؛ لا نرسل minimal.
            generationConfig.removeValue(forKey: "temperature")
            generationConfig.removeValue(forKey: "topP")
            generationConfig.removeValue(forKey: "topK")
            // Be defensive if a future caller supplies OpenAI-style snake_case.
            generationConfig.removeValue(forKey: "top_p")
            generationConfig.removeValue(forKey: "top_k")
            generationConfig["thinkingConfig"] = ["thinkingLevel": "low"]
        } else if id.hasPrefix("gemini-2.5-") {
            // صيغة 2.5 مختلفة، لذا لا نرسل thinkingLevel الخاص بسلسلة Gemini 3.
            generationConfig.removeValue(forKey: "thinkingConfig")
            generationConfig["thinkingConfig"] = ["thinkingBudget": 0]
        }

        body["generationConfig"] = generationConfig
        return (try? JSONSerialization.data(withJSONObject: body)) ?? payload
    }

    private static func requestGemini(model: String,
                                      key: String,
                                      payload: Data,
                                      attempts: Int,
                                      timeout: Double) async throws -> (Data, HTTPURLResponse) {
        // يُبنى الجسم بحسب candidate الحالي حتى لا يرفض Gemini 2.5 إعداد Gemini 3.
        let optimizedPayload = optimizedGeminiPayload(payload, model: model)
        return try await HTTP.withRetry(attempts: attempts, baseDelay: 4) {
            try await HTTP.request("POST", geminiEndpoint(model: model),
                                   headers: geminiHeaders(key: key),
                                   body: optimizedPayload,
                                   timeout: timeout)
        }
    }

    /// يقرأ قائمة الموديلات المتاحة لنفس المفتاح. وجود المفتاح وحده لا يكفي:
    /// قد يكون موديل قديم موجوداً في الإعدادات لكنه غير مسموح لهذا المشروع.
    private static func availableGeminiModels(key: String) async throws -> [String] {
        var pageToken: String?
        var found: [String] = []
        var seen = Set<String>()

        for _ in 0..<5 {
            // نلتقط نسخة ثابتة لأن withRetry يستدعي closure بشكل غير متزامن.
            let tokenForRequest = pageToken
            let endpoint = geminiModelsEndpoint(pageToken: tokenForRequest)
            let (data, _) = try await HTTP.withRetry(attempts: 2, baseDelay: 1) {
                try await HTTP.request("GET", endpoint,
                                       headers: ["x-goog-api-key": key],
                                       timeout: 30)
            }
            let json = HTTP.json(from: data)
            let models = json["models"] as? [[String: Any]] ?? []
            for item in models {
                guard let rawName = item["name"] as? String else { continue }
                let methods = (item["supportedGenerationMethods"] as? [String] ?? [])
                    + (item["supportedActions"] as? [String] ?? [])
                let supportsGeneration = methods.contains {
                    $0.replacingOccurrences(of: "_", with: "").lowercased() == "generatecontent"
                }
                guard supportsGeneration else { continue }
                let model = normalizedGeminiModel(rawName)
                guard !model.isEmpty, seen.insert(model.lowercased()).inserted else { continue }
                found.append(model)
            }
            pageToken = json["nextPageToken"] as? String
            if pageToken == nil || pageToken?.isEmpty == true { break }
        }
        return found
    }

    private static func isTextTranslationModel(_ model: String) -> Bool {
        let lower = model.lowercased()
        let unsupportedMarkers = ["image", "imagen", "veo", "embedding", "tts", "live", "audio", "omni", "robotics"]
        return !unsupportedMarkers.contains { lower.contains($0) }
    }

    private static func replacementGeminiModels(available: [String], excluding: String) -> [String] {
        let excluded = normalizedGeminiModel(excluding).lowercased()
        let usable = available.filter {
            let normalized = normalizedGeminiModel($0)
            return normalized.lowercased() != excluded
                && !isRetiredGeminiModel(normalized)
                && isTextTranslationModel(normalized)
        }
        var result: [String] = []
        for preferred in preferredGeminiModels {
            if let model = usable.first(where: { $0.caseInsensitiveCompare(preferred) == .orderedSame }) {
                result.append(model)
            }
        }
        let remaining = usable.filter { candidate in
            !result.contains { $0.caseInsensitiveCompare(candidate) == .orderedSame }
        }.sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
        result.append(contentsOf: remaining)
        return result
    }

    private static func saveRecoveredGeminiModel(_ model: String) {
        let clean = normalizedGeminiModel(model)
        guard !clean.isEmpty else { return }
        ModelSelection.save(clean, purpose: "translator", provider: .gemini)
        // يبقى هذا المفتاح للتوافق مع النسخ السابقة من التطبيق.
        UserDefaults.standard.set(clean, forKey: "gemini.model")
    }

    private static func modelUnavailableError(model: String, original: APIError) -> APIError {
        let detail = original.body
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let suffix = detail.isEmpty ? "" : " تفاصيل Gemini: \(String(detail.prefix(220)))"
        return APIError(status: 404,
                        body: "Gemini: الموديل \(model) غير متاح لهذا المفتاح أو أُوقف. حاول التطبيق تلقائياً اختيار موديل حديث من قائمة حسابك ولم يجد بديلاً صالحاً. افتح اختيار موديل Gemini وحدّث القائمة.\(suffix)")
    }

    /// ينفذ الطلب بالموديل المختار، وعند 404 خاص بالموديل يقرأ قائمة الحساب
    /// ويجرب موديلات النص الحديثة واحداً واحداً. بهذه الطريقة لا تسقط مهمة
    /// كبيرة لمجرد أن Google أوقفت اسماً قديماً للموديل.
    private static func requestGeminiWithRecovery(requestedModel: String,
                                                   key: String,
                                                   payload: Data,
                                                   attempts: Int,
                                                   timeout: Double) async throws -> (data: Data, response: HTTPURLResponse, model: String) {
        let initial = normalizedGeminiModel(requestedModel).isEmpty ? defaultGeminiModel : normalizedGeminiModel(requestedModel)
        do {
            let (data, response) = try await requestGemini(model: initial, key: key,
                                                            payload: payload, attempts: attempts, timeout: timeout)
            return (data, response, initial)
        } catch let firstError as APIError where firstError.status == 404 {
            let replacements: [String]
            do {
                replacements = replacementGeminiModels(available: try await availableGeminiModels(key: key),
                                                       excluding: initial)
            } catch {
                throw modelUnavailableError(model: initial, original: firstError)
            }
            var lastNotFound = firstError
            for candidate in replacements {
                do {
                    let (data, response) = try await requestGemini(model: candidate, key: key,
                                                                    payload: payload, attempts: attempts, timeout: timeout)
                    saveRecoveredGeminiModel(candidate)
                    return (data, response, candidate)
                } catch let error as APIError where error.status == 404 {
                    lastNotFound = error
                    continue
                }
            }
            throw modelUnavailableError(model: initial, original: lastNotFound)
        }
    }

    /// اختبار حقيقي لـ generateContent، وليس مجرد ListModels. هذا يمنع أن يظهر
    /// "المفتاح يعمل" بينما الموديل المحفوظ نفسه يعيد 404 عند الترجمة.
    static func verifyGeminiKey(_ rawKey: String) async -> String {
        let key = KeychainStore.normalized(rawKey)
        guard !key.isEmpty else { return "⚠️ اكتب مفتاح Gemini أولاً" }
        let original = selectedGeminiModel()
        let probe: [String: Any] = [
            "contents": [["role": "user", "parts": [["text": "Reply only with OK."]]]],
            "generationConfig": ["maxOutputTokens": 8]
        ]
        do {
            let payload = try JSONSerialization.data(withJSONObject: probe)
            let (_, _, usedModel) = try await requestGeminiWithRecovery(requestedModel: original,
                                                                          key: key,
                                                                          payload: payload,
                                                                          attempts: 2,
                                                                          timeout: 45)
            if usedModel.caseInsensitiveCompare(original) == .orderedSame {
                return "✅ مفتاح Gemini والموديل \(usedModel) يعملان بنجاح"
            }
            return "✅ المفتاح يعمل — تم استبدال الموديل غير المتاح \(original) تلقائياً بـ \(usedModel)"
        } catch let error as APIError {
            let body = error.body.lowercased()
            if error.status == 401 || (error.status == 400 && (body.contains("api key") || body.contains("api_key"))) {
                return "❌ مفتاح Gemini غير صحيح أو غير مفعّل للمشروع"
            }
            if error.status == 403 {
                return "❌ مفتاح Gemini لا يملك صلاحية استخدام الموديل أو أن الخطة/المنطقة مقيّدة (403)"
            }
            if error.status == 429 {
                return "⚠️ المفتاح يعمل لكن وصل إلى حد Gemini مؤقتاً (429)"
            }
            let detail = error.body.replacingOccurrences(of: "\n", with: " ")
            return "❌ تعذر تشغيل Gemini: \(String(detail.prefix(260)))"
        } catch {
            return "⚠️ تعذر الاتصال بـ Gemini — تحقق من الإنترنت ثم أعد الاختبار"
        }
    }

    /// فحص خفيف قبل استخراج HLS الطويل؛ إن كان الموديل القديم غير متاح نبدله
    /// الآن بدلاً من اكتشاف 404 بعد رفع/تحويل الفيديو بالكامل.
    static func preflightGeminiModel() async throws -> String {
        guard let key = KeychainStore.get("gemini") else {
            throw GeminiServiceError(detail: "أدخل مفتاح Gemini من الإعدادات")
        }
        let probe: [String: Any] = [
            "contents": [["role": "user", "parts": [["text": "Reply only with OK."]]]],
            "generationConfig": ["maxOutputTokens": 8]
        ]
        let payload = try JSONSerialization.data(withJSONObject: probe)
        do {
            let result = try await requestGeminiWithRecovery(requestedModel: selectedGeminiModel(),
                                                              key: key,
                                                              payload: payload,
                                                              attempts: 2,
                                                              timeout: 45)
            return result.model
        } catch let error as APIError {
            // احتفظ برمز HTTP والجسم حتى يلتقطه مسار fallback تلقائياً.
            throw error
        } catch {
            throw GeminiServiceError(detail: error.localizedDescription)
        }
    }

    private static func geminiBatch(_ batch: Batch,
                                    config: Config,
                                    contextTail: [(String, String)],
                                    source: SubLang,
                                    target: SubLang,
                                    videoTitle: String) async throws -> [String] {
        guard let key = KeychainStore.get("gemini") else {
            throw GeminiServiceError(detail: "أدخل مفتاح Gemini من الإعدادات")
        }
        let model = normalizedGeminiModel(config.model).isEmpty ? defaultGeminiModel : normalizedGeminiModel(config.model)
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
        let data: Data
        let response: HTTPURLResponse
        do {
            let result = try await requestGeminiWithRecovery(requestedModel: model,
                                                              key: key,
                                                              payload: payload,
                                                              attempts: 3,
                                                              timeout: 75)
            data = result.data
            response = result.response
        } catch let error as APIError {
            // لا نغلف أخطاء HTTP: TranslationManager يحتاج status/body ليتحول
            // تلقائياً إلى مزود بديل عند 402/403/404/429 أو نفاد الحصة.
            throw error
        } catch {
            throw GeminiServiceError(detail: error.localizedDescription)
        }
        // Gemini أحياناً يرجع 200 لكن بدون "candidates" بسبب فلترة الأمان — نرمي برسالة واضحة
        let json = HTTP.json(from: data)
        if let err = json["error"] as? [String: Any] {
            let msg = (err["message"] as? String) ?? "خطأ غير معروف"
            let code = (err["code"] as? Int) ?? response.statusCode
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

    // MARK: مزوّدات متوافقة مع OpenAI (OpenRouter / Cerebras / SambaNova)

    /// كلها بدون فيزا وترجع JSON بصيغة choices[0].message.content تماماً مثل Groq.
    /// لا نرسل response_format لأن بعض الموديلات المجانية لا تدعمها فترفض الطلب؛
    /// البرومبت صريح بطلب JSON وparseLines متسامح مع أسوار ``` والنص الخام.
    private struct OpenAICompatSpec {
        let label: String
        let keyID: String
        let url: String
        let defaultModel: String
        let extraHeaders: [String: String]
        /// Z.ai له نطاقان (api.z.ai العالمي وopen.bigmodel.cn الصيني) بنفس
        /// المفتاح — نمرر الطلب عبر ZaiAPI ليجرب الاثنين تلقائياً.
        let usesZaiFallback: Bool

        init(label: String,
             keyID: String,
             url: String,
             defaultModel: String,
             extraHeaders: [String: String],
             usesZaiFallback: Bool = false) {
            self.label = label
            self.keyID = keyID
            self.url = url
            self.defaultModel = defaultModel
            self.extraHeaders = extraHeaders
            self.usesZaiFallback = usesZaiFallback
        }
    }

    private static func spec(for provider: TranslatorKind) -> OpenAICompatSpec? {
        switch provider {
        case .openRouter:
            // HTTP-Referer وX-Title اختيارية لكنها ممارسة جيدة وتظهر التطبيق في
            // لوحة OpenRouter. مفتاح يبدأ بـ sk-or- ويسجَّل بالبريد/GitHub بدون فيزا.
            return OpenAICompatSpec(
                label: "OpenRouter",
                keyID: "openrouter",
                url: "https://openrouter.ai/api/v1/chat/completions",
                defaultModel: defaultOpenRouterModel,
                extraHeaders: ["HTTP-Referer": "https://github.com/ahcrazy20-tech/video2",
                               "X-Title": "Video2"])
        case .cerebras:
            return OpenAICompatSpec(
                label: "Cerebras",
                keyID: "cerebras",
                url: "https://api.cerebras.ai/v1/chat/completions",
                defaultModel: defaultCerebrasModel,
                extraHeaders: [:])
        case .sambaNova:
            return OpenAICompatSpec(
                label: "SambaNova",
                keyID: "sambanova",
                url: "https://api.sambanova.ai/v1/chat/completions",
                defaultModel: defaultSambaNovaModel,
                extraHeaders: [:])
        case .nvidia:
            // NVIDIA NIM (build.nvidia.com): متوافق مع OpenAI، 40 طلب/دقيقة و
            // 10,000 طلب/يوم مجاناً بدون فيزا. للاستخدام التجريبي فقط.
            return OpenAICompatSpec(
                label: "NVIDIA NIM",
                keyID: "nvidia",
                url: "https://integrate.api.nvidia.com/v1/chat/completions",
                defaultModel: defaultNVIDIAModel,
                extraHeaders: ["Accept": "application/json"])
        case .zai:
            // Z.ai GLM: OpenAI-compatible على /chat/completions، مع تجربة
            // النطاقين العالمي والصيني تلقائياً (نفس المفتاح يعمل عليهما).
            return OpenAICompatSpec(
                label: "Z.ai",
                keyID: "zai",
                url: "https://api.z.ai/api/paas/v4/chat/completions",
                defaultModel: defaultZaiModel,
                extraHeaders: [:],
                usesZaiFallback: true)
        default:
            return nil
        }
    }

    private static func openAIChatBatch(provider: TranslatorKind,
                                        batch: Batch,
                                        config: Config,
                                        contextTail: [(String, String)],
                                        source: SubLang,
                                        target: SubLang,
                                        videoTitle: String) async throws -> [String] {
        guard let spec = spec(for: provider) else {
            throw APIError(status: 0, body: "مزوّد ترجمة غير مدعوم")
        }
        guard let key = KeychainStore.get(spec.keyID) else {
            throw APIError(status: 401, body: "أدخل مفتاح \(spec.label) من الإعدادات")
        }
        let model: String
        if provider == .openRouter {
            // خط أمان مزدوج: لا نرسل الدفعة لموديل OpenRouter مدفوع (مثل Qwen3 أو
            // أي إصدار أحدث بدون :free) بقصد أو بخلل — ولو بقيت قيمة قديمة
            // محفوظة على الجهاز — طالما يوجد موديل مجاني نستخدمه.
            model = config.model.hasSuffix(":free") ? config.model : spec.defaultModel
        } else {
            model = config.model.isEmpty ? spec.defaultModel : config.model
        }
        var headers = ["Authorization": "Bearer \(KeychainStore.normalized(key))",
                       "Content-Type": "application/json"]
        for (k, v) in spec.extraHeaders { headers[k] = v }
        let data: Data
        do {
            data = try await openAICompatRequest(spec: spec, headers: headers, model: model,
                                                 config: config, batch: batch, contextTail: contextTail,
                                                 source: source, target: target, videoTitle: videoTitle)
        } catch let error as APIError where provider == .openRouter && error.status == 404 {
            // الموديل المختار لم يعد مُقدَّماً على OpenRouter (قائمتهم المجانية
            // تدور: نسخ :free قديمة تُسحب). نقرأ القائمة الحية الآن، نبدل بنسخة
            // مجانية نشطة، ونعيد نفس الدفعة — فلا تموت مهمة طويلة في منتصف الفيديو.
            guard let recovered = await openRouterLiveFreeModel(excluding: model) else {
                throw APIError(status: 404,
                               body: "OpenRouter: الموديل \(model) لم يعد متاحاً ولم نجد بديلاً مجانياً حياً الآن. افتح اختيار موديل OpenRouter من الإعدادات واضغط تحديث.")
            }
            ModelSelection.save(recovered, purpose: "translator", provider: .openRouter)
            data = try await openAICompatRequest(spec: spec, headers: headers, model: recovered,
                                                 config: config, batch: batch, contextTail: contextTail,
                                                 source: source, target: target, videoTitle: videoTitle)
        }
        let json = HTTP.json(from: data)
        if let err = json["error"] as? [String: Any] {
            let msg = (err["message"] as? String) ?? "خطأ غير معروف"
            throw APIError(status: 0, body: "\(spec.label): \(msg)")
        }
        var text = ""
        if let choices = json["choices"] as? [[String: Any]],
           let message = choices.first?["message"] as? [String: Any],
           let content = message["content"] as? String {
            text = content
        }
        guard !text.isEmpty else {
            throw APIError(status: 0, body: "استجابة \(spec.label) فارغة")
        }
        return parseLines(rawJSON: text, batch: batch)
    }

    /// يبني جسم الطلب وينفذه مع إعادة المحاولة — مفصول عن openAIChatBatch
    /// حتى يُعاد بناءه بنسخة موديل مختلفة عند الاسترداد التلقائي.
    private static func openAICompatRequest(spec: OpenAICompatSpec,
                                            headers: [String: String],
                                            model: String,
                                            config: Config,
                                            batch: Batch,
                                            contextTail: [(String, String)],
                                            source: SubLang,
                                            target: SubLang,
                                            videoTitle: String) async throws -> Data {
        let body: [String: Any] = [
            "model": model,
            "temperature": config.temperature,
            "messages": [
                ["role": "system", "content": systemPrompt(source: source, target: target, videoTitle: videoTitle)],
                ["role": "user", "content": userPrompt(batch: batch, contextTail: contextTail)]
            ]
        ]
        let payload = try JSONSerialization.data(withJSONObject: body)
        if spec.usesZaiFallback {
            // Z.ai: نفس جسم OpenAI لكن عبر ZaiAPI التي تجرب النطاقين.
            let key = KeychainStore.get(spec.keyID) ?? ""
            let (data, _) = try await HTTP.withRetry(attempts: 5, baseDelay: 6) {
                try await ZaiAPI.request("POST",
                                         path: "/chat/completions",
                                         key: key,
                                         headers: headers,
                                         body: payload,
                                         timeout: 180)
            }
            return data
        }
        let (data, _) = try await HTTP.withRetry(attempts: 5, baseDelay: 6) {
            try await HTTP.request("POST", spec.url,
                                   headers: headers,
                                   body: payload,
                                   timeout: 180)
        }
        return data
    }

    /// يختار من الموديلات المجانية "الحية" الآن على OpenRouter (API عام بلا
    /// مفتاح) بديلاً لموديل لم يعد متاحاً. الموصى بها أولاً (ترتيب القائمة
    /// جاهز: موصى بها ثم الأكبر سياقاً).
    private static func openRouterLiveFreeModel(excluding: String) async -> String? {
        guard let entries = try? await ModelCatalogParser.openRouterFreeLive() else { return nil }
        let available = entries.filter { $0.rawID != excluding }
        return available.first(where: { $0.recommended })?.rawID ?? available.first?.rawID
    }

    // MARK: DeepL

    /// يحدد نطاق DeepL الصحيح للمفتاح: المفاتيح المنتهية بـ :fx هي مفاتيح
    /// الخطة المجانية القديمة وتعمل على api-free فقط، وغيرها (خطة Developer
    /// الجديدة أو Pro) تعمل على api.deepl.com — استخدام النطاق الخاطئ يرجع 403.
    static func deepLHost(key: String) -> String {
        let clean = key.trimmingCharacters(in: .whitespacesAndNewlines)
        return clean.hasSuffix(":fx") ? "https://api-free.deepl.com" : "https://api.deepl.com"
    }

    private static func deepLBatch(_ batch: Batch,
                                   source: SubLang,
                                   target: SubLang) async throws -> [String] {
        guard let key = KeychainStore.get("deepl") else {
            throw APIError(status: 401, body: "أدخل مفتاح DeepL من الإعدادات")
        }
        let url = deepLHost(key: key) + "/v2/translate"
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

    // MARK: Cohere (Command A / Aya — v2 Chat، ليست صيغة OpenAI)

    private static func cohereBatch(_ batch: Batch,
                                    config: Config,
                                    contextTail: [(String, String)],
                                    source: SubLang,
                                    target: SubLang,
                                    videoTitle: String) async throws -> [String] {
        guard let key = KeychainStore.get("cohere") else {
            throw APIError(status: 401, body: "أدخل مفتاح Cohere من الإعدادات")
        }
        let model = config.model.isEmpty ? defaultCohereModel : config.model
        let text = try await CohereChat.complete(
            key: key,
            model: model,
            system: systemPrompt(source: source, target: target, videoTitle: videoTitle),
            user: userPrompt(batch: batch, contextTail: contextTail),
            temperature: config.temperature)
        return parseLines(rawJSON: text, batch: batch)
    }

    // MARK: Lara Translate (محرك ترجمة متخصص — 10K حرف/شهر مجاناً)

    private static func laraBatch(_ batch: Batch,
                                  source: SubLang,
                                  target: SubLang,
                                  videoTitle: String) async throws -> [String] {
        let translations = try await LaraTranslate.translate(texts: batch.texts,
                                                              source: source,
                                                              target: target,
                                                              videoTitle: videoTitle)
        // Lara تعيد نفس الترتيب؛ أي سطر فاشل يعود فارغاً ونسقط للنص الأصلي.
        return batch.texts.indices.map { idx in
            let translated = idx < translations.count ? translations[idx] : ""
            return translated.isEmpty ? batch.texts[idx] : translated
        }
    }

    // MARK: MyMemory (بدون مفتاح — ملاذ أخير)

    private static func myMemoryBatch(_ batch: Batch,
                                      source: SubLang,
                                      target: SubLang) async throws -> [String] {
        let translations = try await MyMemoryTranslate.translate(texts: batch.texts,
                                                                  source: source,
                                                                  target: target)
        return batch.texts.enumerated().map { (idx, original) in
            let translated = idx < translations.count ? translations[idx] : ""
            return translated.isEmpty ? original : translated
        }
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

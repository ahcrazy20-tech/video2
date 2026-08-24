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

    /// الموديل الافتراضي الحالي. نعتمد اسماً ثابتاً بدلاً من موديلات 2.0
    /// المتوقفة، لكننا نتحقق من الموديلات المتاحة فعلياً لكل مفتاح عند الحاجة.
    static let defaultGeminiModel = "gemini-3.7-flash"

    private static let preferredGeminiModels = [
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

    /// يضيف إعداد تفكير مناسباً لطلب ترجمة الدفعة الذي يُرسل فعلياً. هذا مهم
    /// عند الاسترداد التلقائي: قد يبدأ الطلب بـ Gemini 3 ثم يتحول إلى 2.5.
    /// لا نعدّل طلب فحص المفتاح القصير؛ ترجمة الترجمة المصاحبة مهمة مباشرة ولا
    /// تحتاج تفكيراً متوسطاً/عميقاً.
    private static func optimizedGeminiPayload(_ payload: Data, model: String) -> Data {
        guard var body = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any],
              var generationConfig = body["generationConfig"] as? [String: Any],
              generationConfig["responseMimeType"] != nil else {
            return payload
        }

        let id = normalizedGeminiModel(model).lowercased()
        if id.hasPrefix("gemini-3.") {
            // Gemini 3.7 Flash يدعم low كأقل مستوى؛ موديلات Lite تستفيد من minimal.
            let level = id.contains("lite") ? "minimal" : "low"
            generationConfig["thinkingConfig"] = ["thinkingLevel": level]
        } else if id.hasPrefix("gemini-2.5-") {
            // صيغة 2.5 مختلفة، لذا لا نرسل thinkingLevel الخاص بسلسلة Gemini 3.
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
        let model = config.model.isEmpty ? "deepseek-ai/DeepSeek-V3.2" : config.model
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

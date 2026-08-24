import Foundation
import Combine

// MARK: - فئات الموديلات

/// ما يمكن استخدام الموديل من أجله داخل هذا التطبيق.
enum ModelCapability: String, Codable, CaseIterable, Identifiable {
    case translation   // ترجمة نصية سياقية
    case transcription // تفريغ صوتي
    case chat          // محادثة عامة
    case embedding     // تحويل إلى فيكتور
    case image         // توليد صور
    case tts           // تحويل نص إلى كلام
    case asr           // تعرف على الكلام
    case realtime      // موديل متعدد الوسائط (صوت/فيديو)

    var id: String { rawValue }

    var titleAR: String {
        switch self {
        case .translation: return "ترجمة نصية"
        case .transcription: return "تفريغ صوتي"
        case .chat: return "محادثة"
        case .embedding: return "تضمين (Embedding)"
        case .image: return "توليد صور"
        case .tts: return "تحويل نص إلى كلام"
        case .asr: return "تفريغ صوتي متقدم"
        case .realtime: return "متعدد الوسائط"
        }
    }

    var systemImage: String {
        switch self {
        case .translation: return "character.bubble.fill"
        case .transcription: return "waveform.badge.mic"
        case .chat: return "bubble.left.and.bubble.right.fill"
        case .embedding: return "square.grid.3x3.fill"
        case .image: return "photo.fill"
        case .tts: return "speaker.wave.3.fill"
        case .asr: return "mic.and.signal.meter"
        case .realtime: return "rectangle.on.rectangle.angled.fill"
        }
    }
}

// MARK: - وصف موديل واحد

struct ModelEntry: Identifiable, Codable, Hashable {
    var id: String { rawID }
    let rawID: String                // المعرّف الفعلي المُرسل للـ API (مثل "gemini-3.7-flash" أو "openai/gpt-oss-120b")
    let displayName: String          // اسم وصفي معروض للمستخدم
    let provider: ModelProvider      // المزود
    let capabilities: [ModelCapability]
    let contextWindow: Int?          // بطول السياق (tokens) — اختياري
    let isMultimodal: Bool
    let supportsArabic: Bool
    let descriptionAR: String?
    /// هل نرشّحه لهذا التطبيق؟ (يأتي من مولّد داخلي بناءً على القدرات)
    let recommended: Bool
    let recommendedReasonAR: String?
}

// MARK: - المزودون المدعومون في الكتالوج

enum ModelProvider: String, Codable, CaseIterable, Identifiable {
    case gemini       // Google Gemini
    case groq         // Groq (OpenAI-compatible)
    case siliconflow  // SiliconFlow (Qwen / DeepSeek / GLM / SenseVoice / CosyVoice)
    case openaiCompat // OpenAI-compatible (نحتفظ به للتوسعة)
    case elevenlabs   // ElevenLabs TTS (للدبلجة)

    var id: String { rawValue }

    var titleAR: String {
        switch self {
        case .gemini: return "Google Gemini"
        case .groq: return "Groq"
        case .siliconflow: return "SiliconFlow"
        case .openaiCompat: return "OpenAI Compatible"
        case .elevenlabs: return "ElevenLabs"
        }
    }

    var systemImage: String {
        switch self {
        case .gemini: return "sparkles"
        case .groq: return "bolt.fill"
        case .siliconflow: return "cpu.fill"
        case .openaiCompat: return "link"
        case .elevenlabs: return "waveform.path.ecg"
        }
    }

    /// معرّف المفتاح في Keychain
    var keyID: String? {
        switch self {
        case .gemini: return "gemini"
        case .groq: return "groq"
        case .siliconflow: return "siliconflow"
        case .openaiCompat: return nil
        case .elevenlabs: return "elevenlabs"
        }
    }

    /// هل هذا المزود متاح فعلاً (لديه مفتاح محفوظ)؟
    var isAvailable: Bool {
        guard let k = keyID else { return false }
        return KeychainStore.has(k)
    }

    /// رابط جلب الموديلات
    var modelsURL: String? {
        switch self {
        case .gemini:
            return "https://generativelanguage.googleapis.com/v1beta/models"
        case .groq:
            return "https://api.groq.com/openai/v1/models"
        case .siliconflow:
            return "https://api.siliconflow.cn/v1/models"
        case .openaiCompat, .elevenlabs:
            return nil
        }
    }
}

// MARK: - اختيار الموديل لكل مزود

enum ModelSelection {
    static func key(purpose: String, provider: ModelProvider) -> String {
        "\(purpose).model.\(provider.rawValue)"
    }

    static func selected(purpose: String, provider: ModelProvider, fallback: String) -> String {
        func normalized(_ value: String) -> String {
            provider == .gemini ? TranslateService.normalizedGeminiModel(value) : value
        }
        func usable(_ value: String) -> String? {
            let clean = normalized(value)
            guard !clean.isEmpty else { return nil }
            if provider == .gemini, TranslateService.isRetiredGeminiModel(clean) {
                // Gemini 2.0 أُوقف؛ نرحّل الاختيار المخزن حتى لا يكرر التطبيق 404.
                let replacement = TranslateService.defaultGeminiModel
                save(replacement, purpose: purpose, provider: provider)
                if purpose == "translator" {
                    UserDefaults.standard.set(replacement, forKey: "gemini.model")
                }
                return replacement
            }
            return clean
        }

        if let value = UserDefaults.standard.string(forKey: key(purpose: purpose, provider: provider)),
           let selected = usable(value) {
            return selected
        }
        // توافق مع الإصدارات السابقة، لكن فقط للمزوّد المطابق حتى لا يُرسل
        // موديل Gemini إلى SiliconFlow أو العكس.
        if provider == .gemini, purpose == "translator",
           let legacy = UserDefaults.standard.string(forKey: "gemini.model"),
           let selected = usable(legacy) {
            return selected
        }
        return normalized(fallback)
    }

    static func save(_ model: String, purpose: String, provider: ModelProvider) {
        UserDefaults.standard.set(model, forKey: key(purpose: purpose, provider: provider))
    }
}

// MARK: - كتالوج الموديلات

/// يخزّن ويرجع الموديلات من المزودين المختلفين مع تخزين مؤقت محلي.
@MainActor
final class ModelCatalog: ObservableObject {
    static let shared = ModelCatalog()

    @Published private(set) var models: [ModelProvider: [ModelEntry]] = [:]
    @Published private(set) var loading: Set<ModelProvider> = []
    @Published private(set) var lastError: [ModelProvider: String] = [:]
    @Published private(set) var lastFetched: [ModelProvider: Date] = [:]

    private let cacheKey = "modelcatalog.cache.v1"
    private let cacheTTL: TimeInterval = 60 * 60 * 6 // 6 ساعات

    private init() {
        loadCache()
    }

    // MARK: التحميل

    /// يجلب الموديلات من مزوّد معيّن. يُستدعى عند الضغط على زر "تحديث".
    func refresh(_ provider: ModelProvider) async {
        guard let url = provider.modelsURL, provider.isAvailable else {
            lastError[provider] = provider.isAvailable ? "هذا المزود لا يدعم جلب الموديلات تلقائياً." : "أدخل مفتاح المزود من الإعدادات أولاً."
            return
        }
        loading.insert(provider)
        defer { loading.remove(provider) }
        do {
            let headers: [String: String]
            switch provider {
            case .gemini:
                let key = KeychainStore.get("gemini") ?? ""
                headers = ["x-goog-api-key": key]
                let (data, _) = try await HTTP.withRetry(attempts: 2) {
                    try await HTTP.request("GET", url, headers: headers, timeout: 30)
                }
                let entries = ModelCatalogParser.gemini(data: data)
                models[provider] = entries
                lastError[provider] = nil
                lastFetched[provider] = Date()
                saveCache()
            case .groq:
                let key = KeychainStore.get("groq") ?? ""
                headers = ["Authorization": "Bearer \(key)"]
                let (data, _) = try await HTTP.withRetry(attempts: 2) {
                    try await HTTP.request("GET", url, headers: headers, timeout: 30)
                }
                let entries = ModelCatalogParser.groq(data: data)
                models[provider] = entries
                lastError[provider] = nil
                lastFetched[provider] = Date()
                saveCache()
            case .siliconflow:
                let key = KeychainStore.get("siliconflow") ?? ""
                headers = [:]
                let (data, _) = try await HTTP.withRetry(attempts: 2) {
                    try await SiliconFlowAPI.request("GET", path: "/models", key: key, timeout: 30)
                }
                let entries = ModelCatalogParser.siliconflow(data: data)
                models[provider] = entries
                lastError[provider] = nil
                lastFetched[provider] = Date()
                saveCache()
            case .openaiCompat, .elevenlabs:
                lastError[provider] = "هذا المزود لا يدعم جلب الموديلات تلقائياً."
            }
        } catch let e as APIError {
            lastError[provider] = e.errorDescription ?? "فشل جلب الموديلات (HTTP \(e.status))"
        } catch {
            lastError[provider] = error.localizedDescription
        }
    }

    // MARK: الفلترة والبحث

    func models(for provider: ModelProvider) -> [ModelEntry] {
        models[provider] ?? []
    }

    func models(provider: ModelProvider, capability: ModelCapability) -> [ModelEntry] {
        models(for: provider).filter { $0.capabilities.contains(capability) }
    }

    /// البحث النصي
    func search(_ query: String, in provider: ModelProvider? = nil) -> [ModelEntry] {
        let q = query.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        let pool: [ModelEntry]
        if let provider { pool = models(for: provider) }
        else { pool = models.values.flatMap { $0 } }
        guard !q.isEmpty else { return pool }
        return pool.filter {
            $0.rawID.lowercased().contains(q) ||
            $0.displayName.lowercased().contains(q) ||
            ($0.descriptionAR?.lowercased().contains(q) ?? false)
        }
    }

    /// أفضل موديل ترجمة متاح من مزوّد
    func bestTranslator(provider: ModelProvider) -> ModelEntry? {
        let pool = models(provider: provider, capability: .translation)
        if let exact = pool.first(where: { $0.recommended }) { return exact }
        return pool.first
    }

    /// أفضل موديل تفريغ صوتي
    func bestTranscriber(provider: ModelProvider) -> ModelEntry? {
        let pool = models(provider: provider, capability: .transcription)
        if let exact = pool.first(where: { $0.recommended }) { return exact }
        return pool.first
    }

    // MARK: التخزين المؤقت

    private func loadCache() {
        guard let data = try? Data(contentsOf: cacheURL()) else { return }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        guard let cached = try? decoder.decode(CacheShape.self, from: data) else { return }
        for (key, value) in cached.models {
            let provider = ModelProvider(rawValue: key) ?? .gemini
            // لا نعيد إظهار موديلات Gemini المتوقفة من cache قديم في قسم
            // "موصى به"؛ اختيار محفوظ قديم يُرحّل في ModelSelection أيضاً.
            let entries = provider == .gemini
                ? value.filter { !TranslateService.isRetiredGeminiModel($0.rawID) }
                : value
            models[provider] = entries
        }
        for (key, val) in cached.lastFetched { lastFetched[ModelProvider(rawValue: key) ?? .gemini] = val }
    }

    private func saveCache() {
        var shape = CacheShape(models: [:], lastFetched: [:])
        for (k, v) in models { shape.models[k.rawValue] = v }
        for (k, v) in lastFetched { shape.lastFetched[k.rawValue] = v }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        if let data = try? encoder.encode(shape) {
            try? data.write(to: cacheURL(), options: .atomic)
        }
    }

    private func cacheURL() -> URL {
        LibraryStore.documents.appendingPathComponent("modelcatalog.json")
    }

    private struct CacheShape: Codable {
        var models: [String: [ModelEntry]]
        var lastFetched: [String: Date]
    }
}

// MARK: - محللات الاستجابات

enum ModelCatalogParser {

    // MARK: Gemini
    // https://ai.google.dev/api/models#method:-models.list
    static func gemini(data: Data) -> [ModelEntry] {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = json["models"] as? [[String: Any]] else { return [] }
        var out: [ModelEntry] = []
        for m in raw {
            guard let name = m["name"] as? String else { continue }
            // name comes like "models/gemini-3.7-flash"
            let rawID = TranslateService.normalizedGeminiModel(name)
            // هذه الأسماء لا تصلح لـ generateContent بعد إيقاف Gemini 2.0؛
            // لا نعرضها حتى من ردود قائمة موديلات متأخرة.
            guard !rawID.isEmpty, !TranslateService.isRetiredGeminiModel(rawID) else { continue }
            let displayName = (m["displayName"] as? String) ?? rawID
            let description = m["description"] as? String
            let methods = m["supportedGenerationMethods"] as? [String] ?? []
            let capabilities = capabilitiesGemini(methods: methods, id: rawID)
            let context = (m["inputTokenLimit"] as? Int) ?? 0
            let isMM = capabilities.contains(.realtime) || methods.contains(where: { $0.contains("image") || $0.contains("video") })
            let supportsAr = geminiSupportsArabic(id: rawID)
            let (rec, reason) = recommendGemini(id: rawID, capabilities: capabilities)
            out.append(ModelEntry(rawID: rawID,
                                  displayName: displayName,
                                  provider: .gemini,
                                  capabilities: capabilities,
                                  contextWindow: context > 0 ? context : nil,
                                  isMultimodal: isMM,
                                  supportsArabic: supportsAr,
                                  descriptionAR: description,
                                  recommended: rec,
                                  recommendedReasonAR: reason))
        }
        return out.sorted { ($0.recommended ? 0 : 1, $0.rawID) < ($1.recommended ? 0 : 1, $1.rawID) }
    }

    private static func capabilitiesGemini(methods: [String], id: String) -> [ModelCapability] {
        var caps: [ModelCapability] = []
        let set = Set(methods.map { $0.lowercased() })
        if set.contains("generatecontent") { caps.append(.translation); caps.append(.chat) }
        if set.contains("audiomodality") || id.lowercased().contains("audio") { caps.append(.tts) }
        // Gemini يفهم العربية بشكل ممتاز في كل موديلاتها النصية
        return caps
    }

    private static func geminiSupportsArabic(id: String) -> Bool {
        let lc = id.lowercased()
        return !(lc.contains("imagen") || lc.contains("veo") || lc.contains("image"))
    }

    private static func recommendGemini(id: String, capabilities: [ModelCapability]) -> (Bool, String?) {
        let lc = id.lowercased()
        guard capabilities.contains(.translation) else { return (false, nil) }
        if lc.contains("3.7-flash") { return (true, "Gemini 3.7 Flash — أحدث خيار ثابت للترجمة السياقية") }
        if lc.contains("3.6-flash") { return (true, "Gemini 3.6 Flash — سريع وقوي للدفعات الطويلة") }
        if lc.contains("3.5-flash-lite") { return (true, "Gemini 3.5 Flash-Lite — أسرع وأوفر للترجمة بالدفعات") }
        if lc.contains("3.5-flash") { return (true, "Gemini 3.5 Flash — خيار ثابت ومتوازن للترجمة") }
        if lc.contains("3.1-flash-lite") { return (true, "Gemini 3.1 Flash-Lite — سريع واقتصادي للترجمة") }
        if lc.contains("2.5-flash") { return (true, "Gemini 2.5 Flash — متاح لبعض المشاريع القديمة فقط") }
        if lc.contains("2.5-pro") { return (true, "Gemini 2.5 Pro — جودة أعلى وأبطأ للترجمات الطويلة") }
        if lc.contains("exp") || lc.contains("preview") { return (false, "تجريبي — قد يكون غير مستقر") }
        return (false, nil)
    }

    // MARK: Groq
    // https://console.groq.com/docs/api-reference#models
    static func groq(data: Data) -> [ModelEntry] {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = json["data"] as? [[String: Any]] else { return [] }
        var out: [ModelEntry] = []
        for m in raw {
            guard let id = m["id"] as? String else { continue }
            let context = (m["context_window"] as? Int) ?? 0
            let caps = capabilitiesGroq(id: id, context: context)
            let (rec, reason) = recommendGroq(id: id, capabilities: caps)
            out.append(ModelEntry(rawID: id,
                                  displayName: id,
                                  provider: .groq,
                                  capabilities: caps,
                                  contextWindow: context > 0 ? context : nil,
                                  isMultimodal: caps.contains(.realtime),
                                  supportsArabic: true,
                                  descriptionAR: nil,
                                  recommended: rec,
                                  recommendedReasonAR: reason))
        }
        return out.sorted { ($0.recommended ? 0 : 1, $0.rawID) < ($1.recommended ? 0 : 1, $1.rawID) }
    }

    private static func capabilitiesGroq(id: String, context: Int) -> [ModelCapability] {
        let lc = id.lowercased()
        var caps: [ModelCapability] = [.chat]
        if lc.contains("whisper") { caps.append(.transcription) }
        if lc.contains("vision") || lc.contains("image") { caps.append(.realtime) }
        if lc.contains("tts") || lc.contains("playai") { caps.append(.tts) }
        if lc.contains("guard") || lc.contains("compound") { caps.append(.realtime) }
        if !lc.contains("whisper") && !lc.contains("tts") {
            caps.append(.translation) // أي LLM يصلح للترجمة
        }
        return caps
    }

    private static func recommendGroq(id: String, capabilities: [ModelCapability]) -> (Bool, String?) {
        let lc = id.lowercased()
        if lc.contains("whisper-large-v3-turbo") { return (true, "الأفضل للتفريغ الصوتي على Groq — سرعة فائقة بنفس مفتاحك") }
        if lc.contains("whisper-large-v3") { return (true, "Whisper الكامل — أعلى دقة وأبطأ") }
        if lc.contains("distil-whisper") { return (true, "Whisper مضغوط — أسرع مع دقة جيدة") }
        if lc.contains("qwen3.6-27b") { return (true, "Qwen 3.6 27B — خيار ترجمة سريع جداً (500 token/ثانية حسب Groq). تحقق من توفره وحدود حسابك لأنه Preview.") }
        if lc.contains("gpt-oss-120b") { return (true, "GPT-OSS 120B — جودة قوية وسرعة عالية؛ السعر/الشريحة المجانية بحسب حساب Groq.") }
        if lc.contains("gpt-oss-20b") { return (true, "GPT-OSS 20B — أصغر وأسرع من 120B، ممتاز للترجمة السريعة.") }
        if lc.contains("llama-3.3") { return (true, "Llama 3.3 70B — ترجمة قوية") }
        if lc.contains("llama-3.1") { return (false, "لا يزال يعمل — Llama 3.3 أحدث وأفضل") }
        if lc.contains("compound") { return (false, "موديل مركّب — مخصص لاستدعاء الأدوات") }
        if lc.contains("guard") { return (false, "موديل أمان — لا يصلح للترجمة") }
        if lc.contains("playai-tts") { return (true, "تحويل نص إلى كلام عربي على Groq — مفيد للدبلجة") }
        return (false, nil)
    }

    // MARK: SiliconFlow
    // https://docs.siliconflow.cn/en/api-reference/models/get-model-list
    static func siliconflow(data: Data) -> [ModelEntry] {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = json["data"] as? [[String: Any]] else { return [] }
        var out: [ModelEntry] = []
        for m in raw {
            guard let id = m["id"] as? String else { continue }
            let caps = capabilitiesSiliconFlow(id: id)
            let (rec, reason) = recommendSiliconFlow(id: id, capabilities: caps)
            out.append(ModelEntry(rawID: id,
                                  displayName: id,
                                  provider: .siliconflow,
                                  capabilities: caps,
                                  contextWindow: nil,
                                  isMultimodal: caps.contains(.realtime),
                                  supportsArabic: true,
                                  descriptionAR: m["summary"] as? String,
                                  recommended: rec,
                                  recommendedReasonAR: reason))
        }
        return out.sorted { ($0.recommended ? 0 : 1, $0.rawID) < ($1.recommended ? 0 : 1, $1.rawID) }
    }

    private static func capabilitiesSiliconFlow(id: String) -> [ModelCapability] {
        let lc = id.lowercased()
        var caps: [ModelCapability] = []
        // ASR / STT
        if lc.contains("sensevoice") || lc.contains("paraformer") || lc.contains("asr") || lc.contains("whisper") {
            caps.append(.transcription)
        }
        // TTS
        if lc.contains("cosyvoice") || lc.contains("tts") || lc.contains("speech") {
            caps.append(.tts)
        }
        // Embeddings
        if lc.contains("bge") || lc.contains("embedding") || lc.contains("m3e") {
            caps.append(.embedding)
        }
        // LLM (ترجمة ومحادثة). نضم موديلات الدردشة الجديدة أيضاً حتى تظهر في فلتر الترجمة.
        if lc.contains("qwen") || lc.contains("deepseek") || lc.contains("glm") || lc.contains("llama") || lc.contains("mistral") || lc.contains("yi")
            || lc.contains("kimi") || lc.contains("minimax") || lc.contains("longcat") || lc.contains("gemma") || lc.contains("hy3") {
            caps.append(.translation)
            caps.append(.chat)
        }
        if lc.contains("flux") || lc.contains("sdxl") || lc.contains("kolors") || lc.contains("image") {
            caps.append(.image)
        }
        if caps.isEmpty { caps.append(.chat) }
        return caps
    }

    private static func recommendSiliconFlow(id: String, capabilities: [ModelCapability]) -> (Bool, String?) {
        let lc = id.lowercased()

        // الترشيحات الحالية موجهة للترجمة المصاحبة: جودة سياقية أولاً ثم كلفة/سرعة.
        if lc.contains("deepseek-v3.2") { return (true, "DeepSeek V3.2 — الخيار المتوازن الموصى به للترجمة السياقية العربية: جودة عالية وكلفة منخفضة.") }
        if lc.contains("qwen3.5-397b") { return (true, "Qwen 3.5 397B — أعلى خيار Qwen للجودة السياقية، لكن أبطأ وأغلى للدفعات الطويلة.") }
        if lc.contains("qwen3.5-122b") { return (true, "Qwen 3.5 122B — جودة سياقية مرتفعة للترجمة عندما تفضّل الجودة على السرعة.") }
        if lc.contains("qwen3.6-35b") { return (true, "Qwen 3.6 35B A3B — موديل أحدث سريع/اقتصادي؛ جرّبه كبديل Qwen حديث للدفعات.") }
        if lc.contains("qwen3.5-35b") { return (true, "Qwen 3.5 35B A3B — أفضل توازن Qwen بين السرعة والكلفة وجودة الترجمة.") }
        if lc.contains("qwen3.6-27b") { return (true, "Qwen 3.6 27B — سريع وقوي للترجمة اليومية مع سياق طويل.") }
        if lc.contains("qwen3.5-27b") { return (true, "Qwen 3.5 27B — خيار اقتصادي سريع بجودة جيدة.") }
        if lc.contains("qwen3.5-9b") { return (true, "Qwen 3.5 9B — الأسرع والأوفر؛ مناسب للسرعة وليس الخيار المثالي لأصعب السياقات.") }

        if lc.contains("qwen2.5") { return (false, "Qwen 2.5 قديم/في طريقه للإيقاف على SiliconFlow؛ اختر DeepSeek V3.2 أو Qwen 3.5/3.6.") }
        if lc.contains("qwen2") { return (false, "Qwen 2.0 — قديم؛ اختر Qwen 3.5 أو DeepSeek V3.2.") }

        if lc.contains("deepseek-v3") { return (true, "DeepSeek V3 — جودة عالية جداً للترجمة السياقية") }
        if lc.contains("deepseek-v2.5") { return (false, "DeepSeek V2.5 — جيل أقدم؛ اختر DeepSeek V3.2 عند توفره.") }
        if lc.contains("deepseek-r1") { return (false, "DeepSeek R1 — موديل تفكير، بطيء وغير عملي للترجمة بالدفعات") }
        if lc.contains("glm-4") { return (false, "GLM-4 جيل قديم؛ لا نوصي به للترجمة الجديدة قبل التحقق من حالته في حسابك.") }
        if lc.contains("kimi") { return (false, "سياق طويل، لكنه ليس من الترشيحات المختبرة للترجمة المصاحبة؛ DeepSeek V3.2 أولاً.") }
        if lc.contains("minimax") || lc.contains("longcat") { return (false, "موديل عام حديث بسياق طويل؛ لا نوصي به افتراضياً للترجمة قبل اختبار الجودة والكلفة.") }
        if lc.contains("sensevoicesmall") { return (true, "SenseVoice Small — تفريغ صوتي ممتاز للهندية والصينية ومتعدد اللغات") }
        if lc.contains("cosyvoice2-0.5b") { return (true, "CosyVoice 2 — TTS صيني مع دعم لهجات، يولد كلاماً طبيعياً") }
        if lc.contains("funasr") { return (true, "FunASR — بديل SenseVoice للتفريغ") }
        if lc.contains("bge-m3") { return (true, "BGE-M3 — أفضل embedding متعدد اللغات") }
        if lc.contains("internlm") { return (false, "InternLM — جيد لكن Qwen وDeepSeek أقوى للترجمة") }
        return (false, nil)
    }
}

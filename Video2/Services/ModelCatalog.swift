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

    /// بيانات السعر/الحصة مستقلة عن رد `/models`، لأن أغلب المزودين لا يعيدونها
    /// من API. تُعرض في واجهة الاختيار حتى لا يوحي اسم "موصى به" بأنه مجاني.
    var billing: ModelBillingInfo {
        ModelBillingCatalog.info(provider: provider, model: rawID)
    }
}

// MARK: - السعر والحصة الظاهرين للمستخدم

enum ModelBillingKind: String, Hashable {
    case free
    case trialQuota
    case paid
    case accountDependent
    case deprecated
    case unknown

    var titleAR: String {
        switch self {
        case .free: return "مجاني"
        case .trialQuota: return "حصة مجانية مؤقتة"
        case .paid: return "مدفوع"
        case .accountDependent: return "حسب الحساب"
        case .deprecated: return "متوقف/قديم"
        case .unknown: return "السعر غير معروف"
        }
    }
}

struct ModelBillingInfo: Hashable {
    let kind: ModelBillingKind
    let detailAR: String
}

enum ModelBillingCatalog {
    static func info(provider: ModelProvider, model: String) -> ModelBillingInfo {
        let id = model.lowercased()
        switch provider {
        case .dashscope:
            if id == "qwen-mt-flash" {
                return ModelBillingInfo(kind: .trialQuota,
                                        detailAR: "Flash: توازن السرعة والجودة. حصة تجربة حتى 1M token/90 يوماً عند الأهلية؛ بعدها حسب المنطقة والحساب.")
            }
            if id == "qwen-mt-plus" {
                return ModelBillingInfo(kind: .trialQuota,
                                        detailAR: "Plus: أعلى جودة للمجالات المهنية؛ حصة تجربة عند الأهلية ثم مدفوع. لا يدعم البث التدريجي.")
            }
            if id == "qwen-mt-lite" {
                return ModelBillingInfo(kind: .accountDependent,
                                        detailAR: "Lite: الأسرع للحالات البسيطة (31 لغة). راجع حصة/سعر حساب Model Studio.")
            }
            return ModelBillingInfo(kind: .accountDependent,
                                    detailAR: "الحصة والسعر يعتمدان على المنطقة وحساب Model Studio.")

        case .siliconflow:
            if id.contains("hunyuan-mt-7b") {
                return ModelBillingInfo(kind: .deprecated,
                                        detailAR: "كان موديل ترجمة مجانياً، لكن SiliconFlow أعلن إيقافه؛ لا تعتمد عليه.")
            }
            if id.contains("deepseek-v3.2") {
                return ModelBillingInfo(kind: .paid,
                                        detailAR: "موديل مدفوع من رصيد SiliconFlow؛ راجع صفحة السعر الحالية قبل الاستخدام.")
            }
            if id.contains("qwen3.5-9b") {
                return ModelBillingInfo(kind: .paid,
                                        detailAR: "موديل مدفوع من رصيد SiliconFlow؛ قد يكون خياراً اقتصادياً، لكن السعر يتغير.")
            }
            if id.contains("qwen3.5-35b") || id.contains("qwen3.6-35b") || id.contains("qwen3.5-122b") || id.contains("qwen3.5-397b") {
                return ModelBillingInfo(kind: .paid,
                                        detailAR: "موديل مدفوع من رصيد SiliconFlow؛ تحقق من سعر الإدخال والإخراج الحاليين في لوحة المزود.")
            }
            if id.contains("qwen2.5") || id.contains("glm-4") {
                return ModelBillingInfo(kind: .accountDependent,
                                        detailAR: "إصدار أقدم؛ تحقق من توفره وسعره الحاليين قبل استخدامه في مهمة جديدة.")
            }
            return ModelBillingInfo(kind: .accountDependent,
                                    detailAR: "ليس مجانياً بالضرورة؛ تحقّق من الرصيد والسعر في SiliconFlow.")

        case .groq:
            if id.contains("gpt-oss-120b") || id.contains("gpt-oss-20b") {
                return ModelBillingInfo(kind: .accountDependent,
                                        detailAR: "قد يكون ضمن شريحة محدودة أو مدفوعاً حسب مشروع Groq؛ راجع Limits والتسعير الحاليين.")
            }
            if id.contains("qwen3.6-27b") {
                return ModelBillingInfo(kind: .accountDependent,
                                        detailAR: "Preview محدود أو مدفوع حسب الحساب، وقد لا يظهر في كل المشاريع.")
            }
            return ModelBillingInfo(kind: .accountDependent,
                                    detailAR: "الحدود والسعر يعتمدان على مشروع وخطة Groq.")

        case .gemini:
            return ModelBillingInfo(kind: .accountDependent,
                                    detailAR: "لا توجد قيمة متبقية عبر API key؛ راجع AI Studio لمعرفة الحصة/الفوترة الفعلية.")
        case .openaiCompat, .elevenlabs:
            return ModelBillingInfo(kind: .unknown,
                                    detailAR: "تحقّق من تسعير المزود وحسابه قبل الاستخدام.")
        }
    }
}

// MARK: - المزودون المدعومون في الكتالوج

enum ModelProvider: String, Codable, CaseIterable, Identifiable {
    case gemini       // Google Gemini
    case groq         // Groq (OpenAI-compatible)
    case siliconflow  // SiliconFlow (Qwen / DeepSeek / GLM / SenseVoice / CosyVoice)
    case dashscope    // Alibaba Cloud Model Studio / Qwen-MT
    case openaiCompat // OpenAI-compatible (نحتفظ به للتوسعة)
    case elevenlabs   // ElevenLabs TTS (للدبلجة)

    var id: String { rawValue }

    var titleAR: String {
        switch self {
        case .gemini: return "Google Gemini"
        case .groq: return "Groq"
        case .siliconflow: return "SiliconFlow"
        case .dashscope: return "DashScope / Qwen-MT"
        case .openaiCompat: return "OpenAI Compatible"
        case .elevenlabs: return "ElevenLabs"
        }
    }

    var systemImage: String {
        switch self {
        case .gemini: return "sparkles"
        case .groq: return "bolt.fill"
        case .siliconflow: return "cpu.fill"
        case .dashscope: return "character.bubble.fill"
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
        case .dashscope: return "dashscope"
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
        case .dashscope, .openaiCompat, .elevenlabs:
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
        // القائمة ثابتة وموثقة، لذلك نعرض Qwen-MT مباشرة حتى بلا مفتاح أو شبكة.
        if models[.dashscope] == nil {
            models[.dashscope] = ModelCatalogParser.dashscope()
        }
    }

    // MARK: التحميل

    /// يجلب الموديلات من مزوّد معيّن. يُستدعى عند الضغط على زر "تحديث".
    func refresh(_ provider: ModelProvider) async {
        // Qwen-MT له قائمة صغيرة ثابتة موثقة؛ نعرضها حتى قبل إدخال المفتاح كي
        // يستطيع المستخدم مقارنة الحصة/السعر، ولا ننفذ GET /models غير موثق.
        if provider == .dashscope {
            models[provider] = ModelCatalogParser.dashscope()
            lastError[provider] = nil
            lastFetched[provider] = Date()
            saveCache()
            return
        }

        guard provider.isAvailable else {
            lastError[provider] = "أدخل مفتاح المزود من الإعدادات أولاً."
            return
        }
        loading.insert(provider)
        defer { loading.remove(provider) }

        guard let url = provider.modelsURL else {
            lastError[provider] = "هذا المزود لا يدعم جلب الموديلات تلقائياً."
            return
        }
        do {
            let entries: [ModelEntry]
            switch provider {
            case .gemini:
                let key = KeychainStore.get("gemini") ?? ""
                let (data, _) = try await HTTP.withRetry(attempts: 2) {
                    try await HTTP.request("GET", url,
                                           headers: ["x-goog-api-key": key], timeout: 30)
                }
                entries = ModelCatalogParser.gemini(data: data)
            case .groq:
                let key = KeychainStore.get("groq") ?? ""
                let (data, _) = try await HTTP.withRetry(attempts: 2) {
                    try await HTTP.request("GET", url,
                                           headers: ["Authorization": "Bearer \(key)"], timeout: 30)
                }
                entries = ModelCatalogParser.groq(data: data)
            case .siliconflow:
                let key = KeychainStore.get("siliconflow") ?? ""
                let (data, _) = try await HTTP.withRetry(attempts: 2) {
                    try await SiliconFlowAPI.request("GET", path: "/models", key: key, timeout: 30)
                }
                entries = ModelCatalogParser.siliconflow(data: data)
            case .dashscope, .openaiCompat, .elevenlabs:
                lastError[provider] = "هذا المزود لا يدعم جلب الموديلات تلقائياً."
                return
            }
            models[provider] = entries
            lastError[provider] = nil
            lastFetched[provider] = Date()
            saveCache()
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

// MARK: - الرصيد والحدود لكل مزود

/// لا نخمّن المتبقي: نعرضه فقط عندما يعيده المزود عبر API. بعض المزودين (Gemini
/// وDashScope) لا يوفّرون هذا الرقم بمفتاح API عادي، لذلك نعرض مسار اللوحة بوضوح.
enum UsageProvider: String, CaseIterable, Identifiable, Hashable {
    case gemini, groq, siliconflow, deepL, dashscope

    var id: String { rawValue }

    var titleAR: String {
        switch self {
        case .gemini: return "Gemini"
        case .groq: return "Groq"
        case .siliconflow: return "SiliconFlow"
        case .deepL: return "DeepL"
        case .dashscope: return "DashScope / Qwen-MT"
        }
    }

    var systemImage: String {
        switch self {
        case .gemini: return "sparkles"
        case .groq: return "bolt.fill"
        case .siliconflow: return "cpu.fill"
        case .deepL: return "character.book.closed.fill"
        case .dashscope: return "character.bubble.fill"
        }
    }

    var keyID: String {
        switch self {
        case .gemini: return "gemini"
        case .groq: return "groq"
        case .siliconflow: return "siliconflow"
        case .deepL: return "deepl"
        case .dashscope: return "dashscope"
        }
    }

    var consoleURL: URL? {
        switch self {
        case .gemini: return URL(string: "https://aistudio.google.com/usage")
        case .groq: return URL(string: "https://console.groq.com/settings/limits")
        case .siliconflow: return URL(string: "https://cloud.siliconflow.com/")
        case .deepL: return URL(string: "https://www.deepl.com/your-account/usage")
        // رابط المنطقة (Beijing/Singapore) يختلف من حساب لآخر، فلا نفتح رابطاً خاطئاً.
        case .dashscope: return nil
        }
    }
}

enum ProviderUsageStatus: Hashable {
    case ready
    case manual
    case notConfigured
    case failed

    var titleAR: String {
        switch self {
        case .ready: return "محدّث"
        case .manual: return "من لوحة المزود"
        case .notConfigured: return "لا يوجد مفتاح"
        case .failed: return "تعذر التحديث"
        }
    }
}

struct ProviderUsageSnapshot: Identifiable, Hashable {
    var id: String { provider.rawValue }
    let provider: UsageProvider
    let status: ProviderUsageStatus
    let headlineAR: String
    let detailAR: String
    let updatedAt: Date?
}

@MainActor
final class ProviderUsageStore: ObservableObject {
    static let shared = ProviderUsageStore()

    @Published private(set) var snapshots: [UsageProvider: ProviderUsageSnapshot] = [:]
    @Published private(set) var loading: Set<UsageProvider> = []

    private init() {}

    func snapshot(for provider: UsageProvider) -> ProviderUsageSnapshot {
        // لا نعرض رصيد مفتاح حُذف أو استُبدل للتو.
        guard KeychainStore.has(provider.keyID) else { return defaultSnapshot(for: provider) }
        return snapshots[provider] ?? defaultSnapshot(for: provider)
    }

    func invalidate(keyID: String) {
        guard let provider = UsageProvider.allCases.first(where: { $0.keyID == keyID }) else { return }
        snapshots.removeValue(forKey: provider)
    }

    func refreshAll() async {
        for provider in UsageProvider.allCases {
            await refresh(provider)
        }
    }

    func refresh(_ provider: UsageProvider) async {
        guard let key = KeychainStore.get(provider.keyID) else {
            snapshots[provider] = ProviderUsageSnapshot(provider: provider,
                                                         status: .notConfigured,
                                                         headlineAR: "أدخل مفتاح \(provider.titleAR) أولاً",
                                                         detailAR: "لن يُرسل التطبيق أي طلب حتى تحفظ المفتاح في Keychain.",
                                                         updatedAt: nil)
            return
        }
        loading.insert(provider)
        defer { loading.remove(provider) }

        do {
            let snapshot: ProviderUsageSnapshot
            switch provider {
            case .deepL:
                snapshot = try await fetchDeepLUsage(key: key)
            case .siliconflow:
                snapshot = try await fetchSiliconFlowBalance(key: key)
            case .groq:
                snapshot = try await fetchGroqLimits(key: key)
            case .gemini:
                snapshot = ProviderUsageSnapshot(
                    provider: .gemini,
                    status: .manual,
                    headlineAR: "المتبقي لا يرسله Gemini عبر API key",
                    detailAR: "افتح Google AI Studio > Usage لمعرفة الحصة اليومية/الفوترة الفعلية. لا نعرض رقماً تخمينياً.",
                    updatedAt: Date())
            case .dashscope:
                snapshot = ProviderUsageSnapshot(
                    provider: .dashscope,
                    status: .manual,
                    headlineAR: "Qwen-MT: راجع Free Quota في Model Studio",
                    detailAR: "الحصة التجريبية — إن كانت مؤهلة — تصل حتى 1M token/90 يوماً لكل موديل (إدخال وإخراج معاً). المتبقي الدقيق لا توفره واجهة API بالمفتاح؛ فعّل Free Quota Only لمنع الدفع بعد نفادها.",
                    updatedAt: Date())
            }
            snapshots[provider] = snapshot
        } catch let error as APIError where provider == .deepL && error.status == 456 {
            snapshots[provider] = ProviderUsageSnapshot(
                provider: provider,
                status: .ready,
                headlineAR: "DeepL: انتهت حصة 500,000 حرف للشهر الحالي",
                detailAR: "يعيد DeepL الرمز 456 عند نفاد الحصة المجانية الشهرية؛ تتجدد حسب دورة الحساب.",
                updatedAt: Date())
        } catch let error as APIError {
            snapshots[provider] = ProviderUsageSnapshot(
                provider: provider,
                status: .failed,
                headlineAR: "تعذر تحديث \(provider.titleAR) (HTTP \(error.status))",
                detailAR: error.errorDescription ?? "تحقق من المفتاح والاتصال ثم أعد التحديث.",
                updatedAt: Date())
        } catch {
            snapshots[provider] = ProviderUsageSnapshot(
                provider: provider,
                status: .failed,
                headlineAR: "تعذر تحديث \(provider.titleAR)",
                detailAR: "\(error.localizedDescription)",
                updatedAt: Date())
        }
    }

    private func defaultSnapshot(for provider: UsageProvider) -> ProviderUsageSnapshot {
        if KeychainStore.has(provider.keyID) {
            return ProviderUsageSnapshot(provider: provider,
                                         status: .manual,
                                         headlineAR: "اضغط تحديث لقراءة الرصيد/الحدود",
                                         detailAR: "لا نحتفظ برصيد قديم حتى لا نعرض قيمة غير دقيقة.",
                                         updatedAt: nil)
        }
        return ProviderUsageSnapshot(provider: provider,
                                     status: .notConfigured,
                                     headlineAR: "لا يوجد مفتاح محفوظ",
                                     detailAR: "أدخل مفتاح \(provider.titleAR) ثم حدّث الحالة.",
                                     updatedAt: nil)
    }

    private func fetchDeepLUsage(key: String) async throws -> ProviderUsageSnapshot {
        let (data, _) = try await HTTP.request(
            "GET", "https://api-free.deepl.com/v2/usage",
            headers: ["Authorization": "DeepL-Auth-Key \(key)"], timeout: 30)
        let json = HTTP.json(from: data)
        guard let used = HTTP.num(json["character_count"]),
              let limit = HTTP.num(json["character_limit"]) else {
            throw APIError(status: 0, body: "استجابة DeepL لا تحتوي على character_count/character_limit")
        }
        let remaining = max(0, limit - used)
        return ProviderUsageSnapshot(
            provider: .deepL,
            status: .ready,
            headlineAR: "متبقٍ \(integer(remaining)) من \(integer(limit)) حرف",
            detailAR: "المستهلك هذا الشهر: \(integer(used)) حرف. هذه قيمة الحساب الفعلية من DeepL.",
            updatedAt: Date())
    }

    private func fetchSiliconFlowBalance(key: String) async throws -> ProviderUsageSnapshot {
        let (data, _) = try await SiliconFlowAPI.request("GET", path: "/user/info", key: key, timeout: 30)
        let json = HTTP.json(from: data)
        guard let account = json["data"] as? [String: Any] else {
            throw APIError(status: 0, body: "استجابة SiliconFlow لا تحتوي على بيانات الحساب")
        }
        let total = HTTP.num(account["totalBalance"])
            ?? HTTP.num(account["balance"])
            ?? HTTP.num(account["chargeBalance"])
        guard let total else {
            throw APIError(status: 0, body: "SiliconFlow لم يعد رصيد الحساب")
        }
        let charge = HTTP.num(account["chargeBalance"])
        let bonus = HTTP.num(account["balance"])
        var detail = "هذه قيمة الرصيد الفعلية كما أعادها SiliconFlow؛ راجع العملة/وحدة الرصيد في لوحة الحساب."
        if let charge, let bonus {
            detail = "مدفوع: \(balance(charge)) · رصيد/مكافآت: \(balance(bonus)). راجع وحدة العملة في لوحة SiliconFlow."
        }
        return ProviderUsageSnapshot(
            provider: .siliconflow,
            status: .ready,
            headlineAR: "الرصيد الكلي: \(balance(total))",
            detailAR: detail,
            updatedAt: Date())
    }

    private func fetchGroqLimits(key: String) async throws -> ProviderUsageSnapshot {
        let (_, response) = try await HTTP.request(
            "GET", "https://api.groq.com/openai/v1/models",
            headers: ["Authorization": "Bearer \(key)"], timeout: 30)
        let remainingRequests = response.value(forHTTPHeaderField: "x-ratelimit-remaining-requests")
        let requestLimit = response.value(forHTTPHeaderField: "x-ratelimit-limit-requests")
        let remainingTokens = response.value(forHTTPHeaderField: "x-ratelimit-remaining-tokens")
        let tokenLimit = response.value(forHTTPHeaderField: "x-ratelimit-limit-tokens")
        let resetRequests = response.value(forHTTPHeaderField: "x-ratelimit-reset-requests")

        guard let remainingRequests, let requestLimit else {
            return ProviderUsageSnapshot(
                provider: .groq,
                status: .manual,
                headlineAR: "Groq لم يُعد رؤوس الحد لهذه العملية",
                detailAR: "المفتاح اتصل بنجاح؛ راجع Limits في لوحة Groq لمعرفة المتبقي الفعلي.",
                updatedAt: Date())
        }
        let tokenLine: String
        if let remainingTokens, let tokenLimit {
            tokenLine = "متبقٍ الآن \(remainingTokens)/\(tokenLimit) token في الدقيقة."
        } else {
            tokenLine = "راجع لوحة Groq لحد token/دقيقة."
        }
        let resetLine = resetRequests.map { "يتجدد حد الطلبات خلال \($0)." } ?? "نافذة التجدد يحددها Groq."
        return ProviderUsageSnapshot(
            provider: .groq,
            status: .ready,
            headlineAR: "متبقٍ حالياً \(remainingRequests)/\(requestLimit) طلب",
            detailAR: "\(tokenLine) \(resetLine) هذه حدود rate وليست رصيداً شهرياً/مالياً.",
            updatedAt: Date())
    }

    private func integer(_ value: Double) -> String {
        Int(value.rounded()).formatted()
    }

    private func balance(_ value: Double) -> String {
        String(format: "%.2f", value)
    }
}

// MARK: - محللات الاستجابات

enum ModelCatalogParser {

    // MARK: DashScope / Qwen-MT

    /// لا تعتمد هذه القائمة على GET /models لأن واجهة Qwen-MT الموثقة تضمن هذه
    /// الموديلات الثلاثة فقط، بينما وصول كل حساب/منطقة يُتحقق منه بزر اختبار المفتاح.
    static func dashscope() -> [ModelEntry] {
        [
            ModelEntry(rawID: "qwen-mt-flash",
                       displayName: "Qwen-MT Flash",
                       provider: .dashscope,
                       capabilities: [.translation],
                       contextWindow: nil,
                       isMultimodal: false,
                       supportsArabic: true,
                       descriptionAR: "ترجمة متخصصة لـ 92 لغة؛ الخيار الافتراضي للترجمة المصاحبة.",
                       recommended: true,
                       recommendedReasonAR: "أفضل توازن بين الجودة والسرعة والكلفة للترجمة المصاحبة."),
            ModelEntry(rawID: "qwen-mt-plus",
                       displayName: "Qwen-MT Plus",
                       provider: .dashscope,
                       capabilities: [.translation],
                       contextWindow: nil,
                       isMultimodal: false,
                       supportsArabic: true,
                       descriptionAR: "نسخة الجودة الأعلى للوثائق والمجالات المهنية؛ أبطأ ولا تدعم البث التدريجي.",
                       recommended: true,
                       recommendedReasonAR: "اختره عندما تكون الجودة المتخصصة أهم من السرعة."),
            ModelEntry(rawID: "qwen-mt-lite",
                       displayName: "Qwen-MT Lite",
                       provider: .dashscope,
                       capabilities: [.translation],
                       contextWindow: nil,
                       isMultimodal: false,
                       supportsArabic: true,
                       descriptionAR: "أسرع نسخة للحالات البسيطة والحساسة للزمن؛ تغطي 31 لغة.",
                       recommended: true,
                       recommendedReasonAR: "خيار السرعة القصوى، وليس الخيار الأول للسياق المعقد.")
        ]
    }

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

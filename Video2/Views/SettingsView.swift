import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var lang: LanguageStore
    @EnvironmentObject var appLock: AppLock
    @State private var newPassword = ""
    @State private var passwordMessage: String?
    @State private var adblock = AdBlock.isEnabled
    @State private var mode = AdBlock.mode
    @State private var filterVideoAds = AdBlock.filterVideoAds

    @AppStorage("stt.provider") private var sttProviderRaw: String = STTProviderKind.auto.rawValue
    @AppStorage("refiner.provider") private var refinerProviderRaw: String = SubtitleRefinerKind.auto.rawValue
    @AppStorage("tr.provider") private var translatorRaw: String = TranslatorKind.auto.rawValue
    @AppStorage("stt.concurrency") private var sttConcurrency: Int = 3
    @AppStorage("dl.maxHeight") private var downloadMaxHeight: Int = 0
    @AppStorage("tts.edge") private var preferEdgeTTS: Bool = true
    @AppStorage(AzureSpeech.regionKey) private var azureSpeechRegion: String = AzureSpeech.defaultRegion
    @State private var storage = StorageManager.report()
    @State private var storageMessage: String?
    @State private var modelPickerConfig: ModelPickerConfig? = nil

    struct ModelPickerConfig: Identifiable {
        let id = UUID()
        let provider: ModelProvider
        let purpose: ModelPickerView.ModelPurpose
    }

    var body: some View {
        NavigationStack {
            List {
                Section("حماية التطبيق") {
                    SecureField("كلمة سر جديدة (4 أحرف على الأقل)", text: $newPassword)
                        .environment(\.layoutDirection, .leftToRight)
                    Button(appLock.hasPassword ? "تغيير كلمة السر" : "تفعيل كلمة السر") {
                        passwordMessage = appLock.setPassword(newPassword) ? "تم حفظ كلمة السر" : "كلمة السر قصيرة جداً"
                        if passwordMessage == "تم حفظ كلمة السر" { newPassword = "" }
                    }.disabled(newPassword.count < 4)
                    if appLock.hasPassword {
                        Button("قفل التطبيق الآن") { appLock.lock() }
                        Button("إلغاء كلمة السر", role: .destructive) { appLock.removePassword() }
                    }
                    if let passwordMessage { Text(passwordMessage).font(.caption).foregroundStyle(.secondary) }
                }
                Section(lang.t("set.lang")) {
                    Picker(lang.t("set.lang"), selection: $lang.code) {
                        Text(lang.t("set.lang.ar")).tag("ar")
                        Text(lang.t("set.lang.en")).tag("en")
                    }
                    .pickerStyle(.segmented)
                }
                Section(lang.t("set.device")) {
                    LabeledContent(lang.t("set.app"), value: "فيديو ٢")
                    LabeledContent(lang.t("set.target"), value: "iPhone 11 · iOS 16.4 · TrollStore")
                    LabeledContent(lang.t("set.bundle"), value: "com.ahcrazy.video2")
                }
                Section(lang.t("set.dl.title")) {
                    Picker(lang.t("set.dl.quality"), selection: $downloadMaxHeight) {
                        Text(lang.t("det.quality.auto")).tag(0)
                        Text("1080p").tag(1080)
                        Text("720p").tag(720)
                        Text("480p").tag(480)
                        Text("360p").tag(360)
                    }
                    Text(lang.t("set.dl.quality.hint"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Toggle(lang.t("set.tts.edge"), isOn: $preferEdgeTTS)
                    Text(lang.t("set.tts.edge.hint"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Section(lang.t("set.ad")) {
                    Toggle(lang.t("set.ad.on"), isOn: $adblock)
                        .onChange(of: adblock) { v in
                            AdBlock.isEnabled = v
                            AdBlock.compileIfNeeded()
                        }
                    Picker(lang.t("set.ad.mode"), selection: $mode) {
                        Text(lang.t("set.ad.balanced")).tag("balanced")
                        Text(lang.t("set.ad.strict")).tag("strict")
                    }
                    .disabled(!adblock)
                    .onChange(of: mode) { v in
                        AdBlock.mode = v
                    }
                    Toggle(lang.t("set.ad.filterVideoAds"), isOn: $filterVideoAds)
                        .onChange(of: filterVideoAds) { v in
                            AdBlock.filterVideoAds = v
                        }
                    Text(lang.t("set.ad.hint"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Section("إعداد مزودي الكلام الجدد") {
                    TextField("منطقة Speech (مثال: eastus)", text: $azureSpeechRegion)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .environment(\.layoutDirection, .leftToRight)
                    Text("في Azure Portal أنشئ Speech resource، ثم افتح Keys and Endpoint وانسخ Key 1 أو Key 2. اكتب اسم Region الظاهر في المورد، مثل eastus أو westeurope. المفتاح الواحد يعمل للتفريغ والدبلجة، وتُرسل الطلبات مباشرة من جهازك إلى Azure.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Link(destination: URL(string: "https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices")!) {
                        Label("إنشاء مورد Speech ومفتاح Azure", systemImage: "arrow.up.right.square")
                    }
                    Link(destination: URL(string: "https://portal.azure.com/#view/Microsoft_Azure_ProjectOxford/CognitiveServicesHub/~/SpeechServices")!) {
                        Label("فتح موارد Speech والاستخدام والفوترة", systemImage: "chart.bar.xaxis")
                    }
                    Link(destination: URL(string: "https://azure.microsoft.com/en-us/pricing/details/speech/")!) {
                        Label("أسعار وحدود Azure Speech الرسمية", systemImage: "creditcard")
                    }
                    Link(destination: URL(string: "https://console.deepgram.com/signup")!) {
                        Label("إنشاء حساب ومفتاح Deepgram", systemImage: "arrow.up.right.square")
                    }
                    Link(destination: URL(string: "https://deepgram.com/pricing")!) {
                        Label("حدود وأسعار Deepgram الرسمية", systemImage: "creditcard")
                    }
                    Text("الطبقة المجانية F0 الموثقة: 5 ساعات Speech-to-Text و500,000 حرف Neural Text-to-Speech شهرياً. Deepgram يعلن رصيد تسجيل يصل إلى 200$ للحسابات المؤهلة. قد تتطلب بعض الحسابات اشتراكاً/بطاقة؛ تحقق من اللوحة قبل الاستخدام الطويل.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Section {
                    APIKeyRow(title: "مفتاح Groq",
                              placeholder: "gsk_...",
                              keyID: "groq",
                              hint: "للتفريغ الصوتي (Whisper Turbo)، تدقيق النصوص، وترجمة GPT-OSS 120B — فيه شريحة مجانية سريعة جداً.")
                    APIKeyRow(title: "مفتاح Gemini",
                              placeholder: "AIza...",
                              keyID: "gemini",
                              hint: "لمراجعة وتدقيق نصوص التفريغ والترجمة النصية السياقية. مجاني بدون فيزا من Google AI Studio.")
                    APIKeyRow(title: "مفتاح OpenRouter",
                              placeholder: "sk-or-v1-...",
                              keyID: "openrouter",
                              hint: "للمراجعة والترجمة: الموديلات المجانية (‎:free) — التطبيق يجلب قائمتهم الحيّة تلقائياً. التسجيل بالبريد/GitHub بدون فيزا.")
                    APIKeyRow(title: "مفتاح Cerebras",
                              placeholder: "csk-...",
                              keyID: "cerebras",
                              hint: "لتدقيق وترجمة النصوص بسرعة فائقة — مليون token/يوم مجاناً بدون فيزا (Llama 3.1 70B / Qwen3). سجّل من cloud.cerebras.ai.")
                    APIKeyRow(title: "مفتاح SambaNova",
                              placeholder: "مفتاح API من اللوحة",
                              keyID: "sambanova",
                              hint: "للمراجعة والترجمة عبر DeepSeek V3.2 / Llama 3.3 — رصيد 5$ مجاني بدون فيزا. سريع جداً من cloud.sambanova.ai.")
                    APIKeyRow(title: "مفتاح SiliconFlow",
                              placeholder: "sk-...",
                              keyID: "siliconflow",
                              hint: "للتفريغ (SenseVoice) والدبلجة (CosyVoice) فقط. الرصيد يظهر في قسم الرصيد والحدود.")
                    APIKeyRow(title: "مفتاح ElevenLabs",
                              placeholder: "xi-api-key",
                              keyID: "elevenlabs",
                              hint: "أفضل جودة بشرية للدبلجة — 10K حرف/شهر مجاناً. متعدد اللغات بـ Multilingual v2.")
                    APIKeyRow(title: "مفتاح AssemblyAI",
                              placeholder: "من لوحة التحكم",
                              keyID: "assemblyai",
                              hint: "الخيار الأقوى للفيديوهات الطويلة (حتى 10 ساعات بملف واحد) — رصيد تجريبي عند التسجيل.")
                    APIKeyRow(title: "مفتاح Deepgram",
                              placeholder: "من Deepgram Console",
                              keyID: "deepgram",
                              hint: "تفريغ Nova-3 دقيق مع توقيتات المقاطع، ويدعم العربية. من Deepgram Console أنشئ Project ثم API Key وانسخه هنا؛ يظهر الرصيد والاستهلاك في اللوحة.")
                    APIKeyRow(title: "مفتاح Azure Speech",
                              placeholder: "مفتاح Speech من Azure",
                              keyID: "azure",
                              hint: "لـ Neural TTS والدبلجة وSTT. أنشئ Speech resource من Azure Portal، ثم أدخل Region أعلاه والمفتاح هنا. حد F0 الموثق: 5 ساعات STT + 500 ألف حرف TTS شهرياً.")
                    APIKeyRow(title: "مفتاح DeepL",
                              placeholder: "DeepL-...",
                              keyID: "deepl",
                              hint: "لترجمة نصية عالية الجودة — 500 ألف حرف/شهر مجاناً.")
                    APIKeyRow(title: "مفتاح STT.ai",
                              placeholder: "sttai_...",
                              keyID: "sttai",
                              hint: "تفريغ صوتي — 600 دقيقة شهرية مجانية + 100 دقيقة API.")
                    APIKeyRow(title: "مفتاح Speechmatics",
                              placeholder: "مفتاح Speechmatics API",
                              keyID: "speechmatics",
                              hint: "تفريغ صوتي — 480 دقيقة مجانية شهرياً لدقة عالية بأكثر من 55 لغة.")
                    APIKeyRow(title: "مفتاح CloudConvert",
                              placeholder: "مفتاح API من اللوحة",
                              keyID: "cloudconvert",
                              hint: "احتياطي فقط. أنشئ المفتاح من Dashboard → API Keys وفعّل task.read و task.write. التطبيق يحوّل معظم HLS محلياً بلا سحابة.")
                    APIKeyRow(title: "مفتاح ffmpeg-api.com",
                              placeholder: "من لوحة التحكم",
                              keyID: "ffmpegapi",
                              hint: "مزوّد سحابي احتياطي ثانٍ لتحويل HLS. من https://ffmpeg-api.com")
                    APIKeyRow(title: "مفتاح ConvertAPI",
                              placeholder: "Secret من convertapi.com",
                              keyID: "convertapi",
                              hint: "مزوّد سحابي ثالث (شريحة تجريبية بدون فيزا غالباً). بديل قوي إذا رُفض CloudConvert بـ 403.")
                } header: {
                    Text("مفاتيح مزودي الصوت والترجمة والفيديو")
                } footer: {
                    Text("تُخزَّن المفاتيح في Keychain على جهازك فقط. مزودات المراجعة والترجمة الحالية لها شرائح مجانية مختلفة؛ Azure وDeepgram قد يطلبان إعداداً/فوترة حسب الحساب، فراجع قسم الرصيد والحدود واللوحات الرسمية قبل المهام الطويلة.")
                        .font(.caption2)
                }

                ProviderUsageSection()

                Section("تفضيلات التفريغ والترجمة") {
                    // === 1. مزود التفريغ ===
                    Picker("مزود التفريغ الصوتي", selection: $sttProviderRaw) {
                        ForEach(STTProviderKind.allCases) { p in
                            Text(p.titleAR).tag(p.rawValue)
                        }
                    }
                    Text(STTProviderKind(rawValue: sttProviderRaw)?.detailAR
                         ?? STTProviderKind.auto.detailAR)
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    // === 2. مزود مراجعة وتدقيق النصوص ===
                    Picker("مراجعة وتدقيق نصوص التفريغ", selection: $refinerProviderRaw) {
                        ForEach(SubtitleRefinerKind.allCases) { p in
                            Text(p.titleAR).tag(p.rawValue)
                        }
                    }
                    Text(SubtitleRefineService.resolved(provider: SubtitleRefinerKind(rawValue: refinerProviderRaw) ?? .auto).detailAR)
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    // === 3. مزود الترجمة ===
                    Picker("مزود الترجمة النصية", selection: $translatorRaw) {
                        ForEach(TranslatorKind.allCases) { p in
                            Text(p.titleAR).tag(p.rawValue)
                        }
                    }
                    Text(TranslateService.resolved(provider: TranslatorKind(rawValue: translatorRaw) ?? .auto).detailAR)
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    // === موديل الترجمة ===
                    VStack(alignment: .leading, spacing: 9) {
                        Text("الموديل المستخدم فعلياً للترجمة")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                        modelNameLine(provider: resolvedTranslatorProviderName,
                                      model: displayedTranslatorModel)
                        if let billing = displayedTranslatorBilling {
                            modelBillingLine(billing)
                        }
                        if let provider = translatorCatalogProvider {
                            modelPickerButton(title: "اختيار موديل ترجمة \(provider.titleAR)",
                                              provider: provider,
                                              purpose: .translation)
                        } else {
                            Text("هذا المزود لا يحتاج اختيار موديل.")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 4)

                    // === موديل مراجعة وتدقيق النصوص ===
                    if resolvedRefiner != .off {
                        VStack(alignment: .leading, spacing: 9) {
                            Text("الموديل المستخدم فعلياً لمراجعة النصوص")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                            modelNameLine(provider: resolvedRefinerProviderName,
                                          model: displayedRefinerModel)
                            if let billing = displayedRefinerBilling {
                                modelBillingLine(billing)
                            }
                            if let provider = refinerCatalogProvider {
                                modelPickerButton(title: "اختيار موديل مراجعة \(provider.titleAR)",
                                                  provider: provider,
                                                  purpose: .refinement)
                            }
                        }
                        .padding(.vertical, 4)
                    }

                    // === موديل التفريغ ===
                    VStack(alignment: .leading, spacing: 9) {
                        Text("الموديل المستخدم فعلياً للتفريغ")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                        modelNameLine(provider: resolvedSTT.titleAR,
                                      model: displayedSTTModel)
                        if let provider = sttCatalogProvider {
                            modelPickerButton(title: "اختيار موديل تفريغ \(provider.titleAR)",
                                              provider: provider,
                                              purpose: .transcription)
                        }
                    }
                    .padding(.vertical, 4)

                    Stepper("توازي التفريغ: \(sttConcurrency)", value: $sttConcurrency, in: 1...4)
                    Text("عدد أجزاء الصوت التي تُفرَّغ معاً. قلّله عند ظهور أخطاء 429.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Section("كيف تعمل منظومة التفريغ والترجمة؟") {
                    howBullet("1", "استخراج الصوت من الفيديو. HLS يُستخرج محلياً أولاً (MPEG-TS → AAC)، وإن فشل تُجرَّب AVFoundation ثم CloudConvert / ffmpeg-api / ConvertAPI.")
                    howBullet("2", "تفريغ الكلام بتوقيتات دقيقة عبر Groq بالتوازي، Deepgram Nova-3 مع utterances، أو Azure Speech على مقاطع WAV قصيرة؛ وAssemblyAI بملف واحد حتى 10 ساعات.")
                    howBullet("3", "مراجعة وتدقيق النصوص بالذكاء الاصطناعي (Free API) لتصحيح الأخطاء الصوتية وحذف الهلاوس والتكرار وإكمال الكلمات لضمان اكتمال المعنى والسياق.")
                    howBullet("4", "ترجمة سياقية بالدفعات عبر Gemini أو Llama — تفهم سياق الجمل السابقة والمصطلحات.")
                    howBullet("5", "ثلاثة ملفات SRT: أصلي مراجع، مترجم، وثنائي اللغة — تظهر فوق المشغّل مع إمكانية التعديل والمراجعة اليدوية.")
                    Text("المهمام قابلة للاستئناف لحظياً: كل جزء مفرَّغ وكل دفعة مراجعة وترجمة تُحفَظ لحظياً، فلو انقطع الاتصال تُكمل من نفس النقطة بدون تكرار.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section(lang.t("set.extract")) {
                    Text("HTML5 video/source")
                        .environment(\.layoutDirection, .leftToRight)
                    Text("fetch / XMLHttpRequest")
                        .environment(\.layoutDirection, .leftToRight)
                    Text("Performance log")
                        .environment(\.layoutDirection, .leftToRight)
                    Text("HLS m3u8")
                        .environment(\.layoutDirection, .leftToRight)
                    Text("MP4 / WebM")
                        .environment(\.layoutDirection, .leftToRight)
                }
                Section(lang.t("set.drm")) {
                    Text(lang.t("set.drm.body"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Section(lang.t("set.build")) {
                    Text(lang.t("set.build.body"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Section {
                    howBullet("OpenL Translate Speech", lang.t("set.upcoming.openl"))
                } header: {
                    Text(lang.t("set.upcoming.title"))
                } footer: {
                    Text(lang.t("set.upcoming.footer"))
                        .font(.caption2)
                }

                Section {
                    LabeledContent(lang.t("set.storage.videos"), value: storage.line(storage.videos))
                    LabeledContent(lang.t("set.storage.thumbs"), value: storage.line(storage.thumbs))
                    LabeledContent(lang.t("set.storage.subs"), value: storage.line(storage.translations + storage.other))
                    LabeledContent(lang.t("set.storage.convert"), value: storage.line(storage.conversions))
                    LabeledContent(lang.t("set.storage.total"), value: storage.line(storage.total))
                    Button(lang.t("set.storage.purge")) {
                        let n = StorageManager.purgeTemporary()
                        storage = StorageManager.report()
                        storageMessage = String(format: lang.t("set.storage.purged"), ByteCountFormatter.string(fromByteCount: n, countStyle: .file))
                    }
                    Button(lang.t("set.storage.orphans"), role: .destructive) {
                        let n = StorageManager.purgeOrphans(videos: libraryVideos())
                        storage = StorageManager.report()
                        storageMessage = String(format: lang.t("set.storage.purged"), ByteCountFormatter.string(fromByteCount: n, countStyle: .file))
                    }
                    if let storageMessage {
                        Text(storageMessage)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text(lang.t("set.storage.body"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } header: {
                    Text(lang.t("set.storage.title"))
                }
            }
            .navigationTitle(lang.t("tab.settings"))
            .onAppear { storage = StorageManager.report() }
            .sheet(item: $modelPickerConfig) { config in
                ModelPickerView(provider: config.provider, purpose: config.purpose)
            }
        }
    }

    private func libraryVideos() -> [SavedVideo] {
        let url = LibraryStore.documents.appendingPathComponent("library.json")
        guard let data = try? Data(contentsOf: url) else { return [] }
        if let idx = try? JSONDecoder().decode(LibraryIndex.self, from: data) { return idx.videos }
        return (try? JSONDecoder().decode([SavedVideo].self, from: data)) ?? []
    }

    // MARK: - مساعدات الموديلات

    private var resolvedTranslator: TranslatorKind {
        TranslateService.resolved(provider: TranslatorKind(rawValue: translatorRaw) ?? .auto)
    }

    private var resolvedRefiner: SubtitleRefinerKind {
        SubtitleRefineService.resolved(provider: SubtitleRefinerKind(rawValue: refinerProviderRaw) ?? .auto)
    }

    private var resolvedSTT: STTProviderKind {
        TranslationManager.resolvedSTT(STTProviderKind(rawValue: sttProviderRaw) ?? .auto)
    }

    private var translatorCatalogProvider: ModelProvider? {
        switch resolvedTranslator {
        case .gemini: return .gemini
        case .groqLLM: return .groq
        case .openRouter: return .openRouter
        case .cerebras: return .cerebras
        case .sambaNova: return .sambaNova
        case .deepL, .auto: return nil
        }
    }

    private var refinerCatalogProvider: ModelProvider? {
        switch resolvedRefiner {
        case .gemini: return .gemini
        case .groqLLM: return .groq
        case .openRouter: return .openRouter
        case .cerebras: return .cerebras
        case .sambaNova: return .sambaNova
        case .auto, .off: return nil
        }
    }

    private var sttCatalogProvider: ModelProvider? {
        switch resolvedSTT {
        case .groq: return .groq
        case .siliconflow: return .siliconflow
        default: return nil
        }
    }

    private var resolvedTranslatorProviderName: String {
        resolvedTranslator == .auto ? "غير محدد" : resolvedTranslator.titleAR
    }

    private var resolvedRefinerProviderName: String {
        resolvedRefiner == .auto ? "تلقائي" : (resolvedRefiner == .off ? "معطل" : resolvedRefiner.titleAR)
    }

    private var displayedTranslatorModel: String {
        switch resolvedTranslator {
        case .gemini:
            return ModelSelection.selected(purpose: "translator", provider: .gemini, fallback: TranslateService.defaultGeminiModel)
        case .groqLLM:
            return ModelSelection.selected(purpose: "translator", provider: .groq, fallback: "openai/gpt-oss-120b")
        case .openRouter:
            return ModelSelection.selected(purpose: "translator", provider: .openRouter, fallback: TranslateService.defaultOpenRouterModel)
        case .cerebras:
            return ModelSelection.selected(purpose: "translator", provider: .cerebras, fallback: TranslateService.defaultCerebrasModel)
        case .sambaNova:
            return ModelSelection.selected(purpose: "translator", provider: .sambaNova, fallback: TranslateService.defaultSambaNovaModel)
        case .deepL: return "DeepL API"
        case .auto: return "—"
        }
    }

    private var displayedRefinerModel: String {
        SubtitleRefineService.modelSelection(for: resolvedRefiner)
    }

    private var displayedTranslatorBilling: ModelBillingInfo? {
        switch resolvedTranslator {
        case .gemini:
            return ModelBillingCatalog.info(provider: .gemini, model: displayedTranslatorModel)
        case .groqLLM:
            return ModelBillingCatalog.info(provider: .groq, model: displayedTranslatorModel)
        case .openRouter:
            return ModelBillingCatalog.info(provider: .openRouter, model: displayedTranslatorModel)
        case .cerebras:
            return ModelBillingCatalog.info(provider: .cerebras, model: displayedTranslatorModel)
        case .sambaNova:
            return ModelBillingCatalog.info(provider: .sambaNova, model: displayedTranslatorModel)
        case .deepL:
            return ModelBillingInfo(kind: .trialQuota,
                                    detailAR: "DeepL API Free: حتى 500,000 حرف/شهر؛ المتبقي الحقيقي يظهر في قسم الرصيد والحدود.")
        case .auto:
            return nil
        }
    }

    private var displayedRefinerBilling: ModelBillingInfo? {
        guard let p = refinerCatalogProvider else { return nil }
        return ModelBillingCatalog.info(provider: p, model: displayedRefinerModel)
    }

    private var displayedSTTModel: String {
        switch resolvedSTT {
        case .groq:
            return ModelSelection.selected(purpose: "stt", provider: .groq, fallback: "whisper-large-v3-turbo")
        case .siliconflow:
            return ModelSelection.selected(purpose: "stt", provider: .siliconflow, fallback: "FunAudioLLM/SenseVoiceSmall")
        case .assemblyai: return "universal"
        case .speechmatics: return "default"
        case .sttai: return "large-v3-turbo"
        case .deepgram: return "nova-3-general"
        case .azure: return "Speech short-audio REST"
        case .auto: return "—"
        }
    }

    private func modelNameLine(provider: String, model: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(provider).font(.caption)
            Text(model)
                .font(.footnote.monospaced())
                .environment(\.layoutDirection, .leftToRight)
                .frame(maxWidth: .infinity, alignment: .leading)
                .lineLimit(2)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(V2Theme.card, in: RoundedRectangle(cornerRadius: 10))
    }

    private func modelBillingLine(_ info: ModelBillingInfo) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(info.kind.titleAR)
                .font(.caption.weight(.semibold))
                .foregroundStyle(billingColor(info.kind))
            Text(info.detailAR)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func billingColor(_ kind: ModelBillingKind) -> Color {
        switch kind {
        case .free: return .green
        case .trialQuota: return V2Theme.gold
        case .paid: return .orange
        case .deprecated: return .red
        case .accountDependent, .unknown: return .secondary
        }
    }

    private func modelPickerButton(title: String,
                                   provider: ModelProvider,
                                   purpose: ModelPickerView.ModelPurpose) -> some View {
        Button { openModelPicker(provider: provider, purpose: purpose) } label: {
            Label(title, systemImage: "list.bullet.rectangle")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
    }

    private func openModelPicker(provider: ModelProvider, purpose: ModelPickerView.ModelPurpose) {
        modelPickerConfig = ModelPickerConfig(provider: provider, purpose: purpose)
    }

    private func howBullet(_ n: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text(n)
                .font(.caption2.bold())
                .frame(width: 20, height: 20)
                .background(Circle().fill(V2Theme.accent.opacity(0.2)))
                .foregroundStyle(V2Theme.accent)
            Text(text)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - الرصيد والحدود

struct ProviderUsageSection: View {
    @ObservedObject private var usage = ProviderUsageStore.shared

    var body: some View {
        Section("الرصيد والحدود") {
            Text("نعرض المتبقي فقط عندما يعيده المزود عبر API. لا يعني وجود شارة «مجاني» أن الاستخدام بلا حد أو بلا تكلفة بعد الحصة.")
                .font(.caption)
                .foregroundStyle(.secondary)

            Button {
                Task { await usage.refreshAll() }
            } label: {
                Label("تحديث الرصيد والحدود", systemImage: "arrow.clockwise")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            ForEach(UsageProvider.allCases) { provider in
                let snapshot = usage.snapshot(for: provider)
                VStack(alignment: .leading, spacing: 5) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Label(provider.titleAR, systemImage: provider.systemImage)
                            .font(.subheadline.weight(.semibold))
                        Spacer()
                        if usage.loading.contains(provider) {
                            ProgressView()
                                .controlSize(.small)
                        } else {
                            Button {
                                Task { await usage.refresh(provider) }
                            } label: {
                                Image(systemName: "arrow.clockwise")
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("تحديث \(provider.titleAR)")
                        }
                    }
                    Text(snapshot.status.titleAR)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(statusColor(snapshot.status))
                    Text(snapshot.headlineAR)
                        .font(.caption)
                    Text(snapshot.detailAR)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: 10) {
                        if let updatedAt = snapshot.updatedAt {
                            Text("آخر تحديث ") + Text(updatedAt, style: .relative)
                        }
                        if let url = provider.consoleURL {
                            Link(destination: url) {
                                Label("فتح اللوحة", systemImage: "arrow.up.right.square")
                            }
                        }
                    }
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                }
                .padding(.vertical, 5)
            }
        }
        .task {
            await usage.refreshAll()
        }
    }

    private func statusColor(_ status: ProviderUsageStatus) -> Color {
        switch status {
        case .ready: return .green
        case .manual: return V2Theme.gold
        case .notConfigured: return .secondary
        case .failed: return .red
        }
    }
}

// MARK: - صف إدخال مفتاح API (يُخزَّن في Keychain) مع زر اختبار

struct APIKeyRow: View {
    let title: String
    let placeholder: String
    let keyID: String
    let hint: String

    @State private var value: String = ""
    @State private var savedFlash = false
    @State private var testing = false
    @State private var testResult: String?

    private var stored: Bool { KeychainStore.has(keyID) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Label(title, systemImage: stored ? "key.fill" : "key")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                if stored {
                    Label("محفوظ", systemImage: "checkmark.circle.fill")
                        .font(.caption2)
                        .foregroundStyle(.green)
                }
            }
            HStack {
                SecureField(placeholder, text: $value)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.footnote)
                    .environment(\.layoutDirection, .leftToRight)
                if testing {
                    ProgressView()
                        .controlSize(.small)
                        .padding(.horizontal, 4)
                } else {
                    Button("اختبار") {
                        runTest()
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .disabled(stored == false && value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                Button(stored ? "تحديث" : "حفظ") {
                    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !trimmed.isEmpty else { return }
                    KeychainStore.set(trimmed, for: keyID)
                    ProviderUsageStore.shared.invalidate(keyID: keyID)
                    value = ""
                    savedFlash = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { savedFlash = false }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .disabled(value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                if stored {
                    Button(role: .destructive) {
                        KeychainStore.delete(keyID)
                        ProviderUsageStore.shared.invalidate(keyID: keyID)
                        testResult = nil
                    } label: {
                        Image(systemName: "trash")
                    }
                    .controlSize(.small)
                }
            }
            Text(hint)
                .font(.caption2)
                .foregroundStyle(.secondary)
            if savedFlash {
                Text("تم الحفظ في Keychain ✓")
                    .font(.caption2)
                    .foregroundStyle(.green)
            }
            if let r = testResult {
                Text(r)
                    .font(.caption.bold())
                    .foregroundStyle(r.hasPrefix("✅") ? Color.green : (r.hasPrefix("❌") ? Color.red : Color.orange))
            }
        }
        .padding(.vertical, 2)
    }

    private func runTest() {
        let typed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = typed.isEmpty ? (KeychainStore.get(keyID) ?? "") : typed
        guard !key.isEmpty else {
            testResult = "⚠️ اكتب المفتاح أولاً أو احفظه ثم اختبر"
            return
        }
        testing = true
        testResult = nil
        let provider = keyID
        Task {
            let result = await KeyTester.verify(provider: provider, key: key)
            testing = false
            testResult = result
            if result.hasPrefix("✅") {
                KeychainStore.set(key, for: keyID)
                ProviderUsageStore.shared.invalidate(keyID: keyID)
            }
        }
    }
}

// MARK: - اختبار صلاحية المفاتيح

enum KeyTester {
    static func verify(provider: String, key: String) async -> String {
        let key = KeychainStore.normalized(key)
        if provider == "gemini" {
            return await TranslateService.verifyGeminiKey(key)
        }

        if provider == "deepgram" {
            do {
                _ = try await HTTP.request("GET", "https://api.deepgram.com/v1/projects",
                                           headers: ["Authorization": "Token \(key)"], timeout: 30)
                return "✅ مفتاح Deepgram يعمل — افتح Console لمعرفة رصيد التسجيل والاستهلاك"
            } catch let e as APIError {
                if e.status == 401 || e.status == 403 { return "❌ مفتاح Deepgram غير صحيح أو غير مفعّل (HTTP \(e.status))" }
                if e.status == 429 { return "⚠️ المفتاح يعمل لكن وصلت لحد Deepgram مؤقتاً (429)" }
                return "⚠️ استجابة Deepgram غير متوقعة (رمز \(e.status))"
            } catch {
                return "⚠️ تعذر الاتصال بـ Deepgram — تحقق من الإنترنت"
            }
        }

        if provider == "azure" {
            return await AzureSpeech.verifyKey(key)
        }

        if provider == "openrouter" {
            do {
                let (_, resp) = try await HTTP.request("GET", "https://openrouter.ai/api/v1/key",
                                                        headers: ["Authorization": "Bearer \(key)"], timeout: 30)
                _ = resp
                return "✅ مفتاح OpenRouter يعمل — استخدم موديلات تنتهي بـ :free لترجمة مجانية بدون فيزا"
            } catch let e as APIError {
                if e.status == 401 { return "❌ مفتاح OpenRouter غير صحيح (401)" }
                if e.status == 403 { return "❌ تم رفض المفتاح (403) — تأكد أنه يبدأ بـ sk-or-" }
                if e.status == 429 { return "⚠️ المفتاح يعمل لكن وصلت لحد الطلبات مؤقتاً (429)" }
                return "⚠️ استجابة OpenRouter غير متوقعة (رمز \(e.status))"
            } catch {
                return "⚠️ تعذر الاتصال بـ OpenRouter — تحقق من الإنترنت"
            }
        }

        if provider == "cerebras" {
            do {
                _ = try await HTTP.request("GET", "https://api.cerebras.ai/v1/models",
                                           headers: ["Authorization": "Bearer \(key)"], timeout: 30)
                return "✅ مفتاح Cerebras يعمل — مليون token/يوم مجاناً بدون فيزا"
            } catch let e as APIError {
                if e.status == 401 { return "❌ مفتاح Cerebras غير صحيح (401)" }
                if e.status == 403 { return "❌ تم رفض المفتاح (403)" }
                if e.status == 429 { return "⚠️ المفتاح يعمل لكن وصلت للحد مؤقتاً (429)" }
                return "⚠️ استجابة Cerebras غير متوقعة (رمز \(e.status))"
            } catch {
                return "⚠️ تعذر الاتصال بـ Cerebras — تحقق من الإنترنت"
            }
        }

        if provider == "sambanova" {
            do {
                _ = try await HTTP.request("GET", "https://api.sambanova.ai/v1/models",
                                           headers: ["Authorization": "Bearer \(key)"], timeout: 30)
                return "✅ مفتاح SambaNova يعمل — رصيد 5$ مجاني/30 يوماً بدون فيزا"
            } catch let e as APIError {
                if e.status == 401 { return "❌ مفتاح SambaNova غير صحيح (401)" }
                if e.status == 403 { return "❌ تم رفض المفتاح (403)" }
                if e.status == 429 { return "⚠️ المفتاح يعمل لكن وصلت للحد مؤقتاً (429)" }
                return "⚠️ استجابة SambaNova غير متوقعة (رمز \(e.status))"
            } catch {
                return "⚠️ تعذر الاتصال بـ SambaNova — تحقق من الإنترنت"
            }
        }

        if provider == "siliconflow" {
            do {
                _ = try await SiliconFlowAPI.request("GET", path: "/models", key: key, timeout: 30)
                return "✅ المفتاح يعمل بنجاح"
            } catch let e as APIError {
                if e.status == 401 { return "❌ المفتاح مرفوض على النطاقين العالمي والصيني (401) — تأكد أنه API Key وليس كلمة مرور الحساب" }
                if e.status == 403 { return "❌ الحساب مقيّد أو الشبكة محجوبة (403) — جرّب بيانات الهاتف أو VPN" }
                if e.status == 429 { return "⚠️ المفتاح يعمل لكن وصلت لحد الطلبات مؤقتاً"
                }
                return "⚠️ استجابة SiliconFlow غير متوقعة (رمز \(e.status))"
            } catch {
                return "⚠️ تعذر الاتصال بـ SiliconFlow — تحقق من الإنترنت"
            }
        }

        let url: String
        var headers: [String: String] = [:]
        switch provider {
        case "groq":
            url = "https://api.groq.com/openai/v1/models"
            headers = ["Authorization": "Bearer \(key)"]
        case "siliconflow":
            url = "https://api.siliconflow.cn/v1/models"
            headers = ["Authorization": "Bearer \(key)"]
        case "elevenlabs":
            url = "https://api.elevenlabs.io/v1/user"
            headers = ["xi-api-key": key]
        case "assemblyai":
            url = "https://api.assemblyai.com/v2/transcript?limit=1"
            headers = ["Authorization": key]
        case "deepl":
            url = "https://api-free.deepl.com/v2/usage"
            headers = ["Authorization": "DeepL-Auth-Key \(key)"]
        case "sttai":
            url = "https://api.stt.ai/v1/usage"
            headers = ["Authorization": "Bearer \(key)"]
        case "speechmatics":
            url = "https://eu1.asr.api.speechmatics.com/v2/jobs/"
            headers = ["Authorization": "Bearer \(key)"]
        case "cloudconvert":
            return await verifyCloudConvert(key)
        case "convertapi":
            return await verifyConvertAPI(key)
        case "ffmpegapi":
            url = "https://api.ffmpeg-api.com/"
            headers = ["Authorization": "Basic \(key)"]
        default:
            return "⚠️ مزود غير معروف"
        }
        do {
            let (_, resp) = try await HTTP.request("GET", url, headers: headers, timeout: 30)
            _ = resp
            if provider == "cloudconvert" {
                return "✅ مفتاح CloudConvert يعمل (تم التحقق من task.read). فعّل task.write أيضاً للتحويل."
            }
            return "✅ المفتاح يعمل بنجاح"
        } catch let e as APIError {
            if e.status == 403 {
                let b = e.body.lowercased()
                if b.contains("cloudflare") || b.contains("<html") || b.contains("just a moment") {
                    return "🚫 المفتاح سليم غالباً — الشبكة محجوبة عند المزود. بدّل Wi-Fi/البيانات أو جرّب VPN ثم أعد الاختبار"
                }
                if b.contains("organization") || b.contains("disabled") || b.contains("suspended") || b.contains("blocked") {
                    return "❌ الحساب موقوف أو مقيّد عند المزود (403)"
                }
                return "❌ مرفوض (403) — تأكد من المفتاح، ولو سليم بدّل الشبكة (Wi-Fi/بيانات/VPN) وأعد الاختبار"
            }
            if e.status == 401 {
                return "❌ المفتاح غير صحيح أو منتهي (401)"
            }
            if e.status == 404 {
                return "⚠️ المسار أو المورد غير موجود (404) — هذا لا يؤكد أن المفتاح يعمل. أعد الاختبار بعد تحديث التطبيق."
            }
            if e.status == 429 {
                return "⚠️ المفتاح يعمل لكن وصلت لحد الطلبات مؤقتاً — جرب بعد دقيقة"
            }
            return "⚠️ استجابة غير متوقعة (رمز \(e.status))"
        } catch {
            return "⚠️ تعذر الاتصال — تحقق من الإنترنت"
        }
    }

    /// اختبار CloudConvert على أكثر من نطاق، مع قراءة الرصيد إن أمكن.
    private static func verifyCloudConvert(_ key: String) async -> String {
        let bases = [
            "https://api.cloudconvert.com/v2",
            "https://eu-central.api.cloudconvert.com/v2",
            "https://us-east.api.cloudconvert.com/v2",
            "https://api.sandbox.cloudconvert.com/v2"
        ]
        var lastStatus = 0
        var lastBody = ""
        for base in bases {
            do {
                let (data, _) = try await HTTP.request(
                    "GET", "\(base)/users/me",
                    headers: ["Authorization": "Bearer \(key)"],
                    timeout: 20)
                let json = HTTP.json(from: data)
                let credits = ((json["data"] as? [String: Any])?["credits"] as? Int)
                    ?? (json["credits"] as? Int)
                let creditLine = credits.map { "الرصيد المتبقي: \($0) تحويل" } ?? "تم التحقق من الحساب"
                let sandbox = base.contains("sandbox") ? " (Sandbox)" : ""
                return "✅ مفتاح CloudConvert يعمل\(sandbox) — \(creditLine). تأكد أن task.write مفعّل للتحويل."
            } catch let e as APIError {
                lastStatus = e.status
                lastBody = e.body
                if e.status == 401 { continue }
                if e.status == 403 {
                    let lower = e.body.lowercased()
                    if lower.contains("cloudflare") || lower.contains("<html") || lower.contains("just a moment") {
                        continue
                    }
                    if lower.contains("scope") || lower.contains("unauthorized") || lower.contains("user.read") {
                        // المفتاح قد يعمل للمهام دون user.read — نجرّب /jobs
                    } else {
                        continue
                    }
                }
            } catch {
                lastBody = error.localizedDescription
            }

            do {
                _ = try await HTTP.request(
                    "GET", "\(base)/jobs?per_page=1",
                    headers: ["Authorization": "Bearer \(key)"],
                    timeout: 20)
                return "✅ مفتاح CloudConvert يعمل على \(base) (task.read). فعّل task.write أيضاً لإنشاء مهام التحويل."
            } catch let e as APIError {
                lastStatus = e.status
                lastBody = e.body
            } catch {
                lastBody = error.localizedDescription
            }
        }
        return "❌ " + CloudConvertService.describeCreateFailure(status: lastStatus == 0 ? 403 : lastStatus, body: lastBody)
    }

    private static func verifyConvertAPI(_ key: String) async -> String {
        var components = URLComponents(string: "https://v2.convertapi.com/user")
        components?.queryItems = [URLQueryItem(name: "Secret", value: key)]
        guard let url = components?.string else {
            return "❌ رابط ConvertAPI غير صالح"
        }
        do {
            let (data, _) = try await HTTP.request("GET", url, timeout: 20)
            let json = HTTP.json(from: data)
            if let seconds = HTTP.num(json["SecondsLeft"]) ?? HTTP.num(json["secondsLeft"]) {
                return "✅ مفتاح ConvertAPI يعمل — متبقٍ \(Int(seconds)) ثانية تحويل"
            }
            return "✅ مفتاح ConvertAPI يعمل"
        } catch let e as APIError {
            if e.status == 401 || e.status == 403 {
                return ConvertAPIService.describe(status: e.status, body: e.body)
            }
            if e.status == 404 {
                return "⚠️ مسار ConvertAPI تغيّر (404) — احفظ المفتاح وجرّب التحويل مباشرة"
            }
            return "⚠️ استجابة ConvertAPI غير متوقعة (رمز \(e.status))"
        } catch {
            return "⚠️ تعذر الاتصال بـ ConvertAPI — تحقق من الإنترنت"
        }
    }
}

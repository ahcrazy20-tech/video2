import Foundation
import AVFoundation

// MARK: - إعدادات الدبلجة

/// كل ما يتعلق ببناء مسار صوتي واحد من جُمل الترجمة.
struct DubbingRequest: Sendable {
    var cues: [SubCue]              // جُمل الترجمة (يجب أن يحتوي كل منها على translated != nil)
    var targetLang: SubLang
    var provider: DubbingProvider
    var voice: DubbingVoice?        // اختياري، يتم اختيار أفضل صوت للغة إن لم يُمرَّر
    var sampleRate: Int = 24000
    var format: AudioFormat = .mp3
    var stretchToFit: Bool = true   // تسريع/إبطاء الصوت ليتناسب مع توقيت الجملة
    var maxConcurrent: Int = 3

    enum AudioFormat: String, Codable {
        case mp3, wav, aac
    }
}

enum DubbingProvider: String, Codable, CaseIterable, Identifiable, Sendable {
    case edge        // Microsoft Edge (مجاني بدون مفتاح)
    case groqPlayAI  // Groq PlayAI TTS (نفس مفتاح Groq)
    case siliconflow // SiliconFlow CosyVoice (مفتوح، أفضل جودة)
    case elevenlabs  // ElevenLabs (احترافي، مدفوع)
    case auto        // يختار تلقائياً

    var id: String { rawValue }

    var titleAR: String {
        switch self {
        case .edge: return "Microsoft Edge (مجاني)"
        case .groqPlayAI: return "Groq PlayAI TTS"
        case .siliconflow: return "SiliconFlow CosyVoice"
        case .elevenlabs: return "ElevenLabs (احترافي)"
        case .auto: return "تلقائي (الأفضل)"
        }
    }

    var detailAR: String {
        switch self {
        case .edge:
            return "بدون مفتاح — صوت Zariyah/Ryan. جودة جيدة جداً للعربية الفصحى."
        case .groqPlayAI:
            return "نفس مفتاح Groq — أصوات متعددة، سرعة فائقة، لكن دعم العربية محدود."
        case .siliconflow:
            return "CosyVoice 2 من FunAudioLLM — جودة ممتازة للصينية، دعم العربية محدود لكن طبيعي."
        case .elevenlabs:
            return "أفضل جودة بشرية على الإنترنت — مدفوع. المفتاح: elevenlabs."
        case .auto:
            return "يختار Edge للعربية الفصحى، SiliconFlow للهجات، ElevenLabs إن وُجد مفتاح."
        }
    }

    /// هل المزود متاح الآن (مفتاح موجود)؟
    var isAvailable: Bool {
        switch self {
        case .edge: return true
        case .groqPlayAI: return KeychainStore.has("groq")
        case .siliconflow: return KeychainStore.has("siliconflow")
        case .elevenlabs: return KeychainStore.has("elevenlabs")
        case .auto: return true
        }
    }

    /// مفتاح Keychain المطلوب
    var keyID: String? {
        switch self {
        case .groqPlayAI: return "groq"
        case .siliconflow: return "siliconflow"
        case .elevenlabs: return "elevenlabs"
        case .edge, .auto: return nil
        }
    }
}

/// وصف صوت واحد في مزوّد ما.
struct DubbingVoice: Codable, Hashable, Identifiable, Sendable {
    var id: String                  // المعرّف الفريد (مثل "ar-SA-ZariyahNeural")
    var name: String                // اسم وصفي
    var language: String           // BCP-47
    var gender: Gender
    var naturalness: Int            // 1-5 — كم يبدو طبيعياً
    var provider: DubbingProvider

    enum Gender: String, Codable { case male, female, neutral }

    var displayAR: String {
        let g: String
        switch gender {
        case .male: g = "ذكر"
        case .female: g = "أنثى"
        case .neutral: g = "محايد"
        }
        return "\(name) — \(language) (\(g))"
    }
}

// MARK: - نتائج الدبلجة

struct DubbingResult: Sendable {
    let audioFileURL: URL          // ملف صوتي نهائي (m4a/aac)
    let totalDuration: Double
    let cuesCount: Int
    let provider: DubbingProvider
    let voice: DubbingVoice
}

// MARK: - خدمة الدبلجة الرئيسية

@MainActor
final class DubbingService: ObservableObject {
    static let shared = DubbingService()

    @Published private(set) var inProgress = false
    @Published private(set) var progress: Double = 0
    @Published private(set) var statusText: String = ""
    @Published private(set) var lastError: String?

    // حقول مساعدة لعرض ما تم اختياره فعلياً
    private(set) var lastResolvedProviderRaw: String = ""
    private(set) var lastResolvedVoiceID: String = ""
    private(set) var lastResolvedLangRaw: String = ""

    private var currentTask: Task<DubbingResult, Error>?
    private var currentTaskID: UUID?

    private init() {}

    // MARK: - واجهة البدء

    /// يولّد ملف صوتي نهائي (.m4a) من جُمل الترجمة.
    /// - Parameters:
    ///   - request: إعدادات الدبلجة
    ///   - outputURL: مسار ملف الإخراج (m4a)
    ///   - onProgress: استدعاء لكل جملة (0..1)
    /// - Returns: نتيجة الدبلجة
    func dub(request: DubbingRequest,
             outputURL: URL,
             onProgress: ((Double, String) -> Void)? = nil) async throws -> DubbingResult {
        guard !request.cues.isEmpty else { throw DubbingError.emptyCues }
        let translatable = request.cues.filter { cue in
            if let t = cue.translated, !t.isEmpty { return true }
            return false
        }
        guard !translatable.isEmpty else { throw DubbingError.noTranslations }

        // نشغّل داخل Task قابل للإلغاء، ثم نُرجع النتيجة
        currentTask?.cancel()
        let taskID = UUID()
        let task = Task { [weak self] in
            guard let self else { throw DubbingError.emptyCues }
            return try await self.runDub(request: request,
                                         outputURL: outputURL,
                                         translatable: translatable,
                                         onProgress: onProgress)
        }
        currentTask = task
        currentTaskID = taskID
        defer {
            if currentTaskID == taskID {
                currentTask = nil
                currentTaskID = nil
            }
        }
        return try await task.value
    }

    func cancel() {
        currentTask?.cancel()
        currentTask = nil
        currentTaskID = nil
        inProgress = false
        statusText = ""
    }

    // MARK: - التنفيذ الفعلي

    private func runDub(request: DubbingRequest,
                        outputURL: URL,
                        translatable: [SubCue],
                        onProgress: ((Double, String) -> Void)?) async throws -> DubbingResult {
        let sampleRate = request.sampleRate
        inProgress = true
        progress = 0
        statusText = "تجهيز الدبلجة…"
        lastError = nil
        defer {
            inProgress = false
            statusText = ""
        }

        // اختيار المزود والصوت
        let provider = resolveProvider(request.provider, lang: request.targetLang)
        let voice = request.voice ?? DubbingVoice.best(for: request.targetLang, provider: provider)
        lastResolvedProviderRaw = provider.rawValue
        lastResolvedVoiceID = voice.id
        lastResolvedLangRaw = request.targetLang.bcp47 ?? request.targetLang.rawValue

        // مخرج مؤقت لكل جملة
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("dubbing-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        // AVFoundation يتعامل بشكل أفضل مع m4a/aac، نستخدم m4a دائماً
        let perCueExt = "m4a"
        statusText = "توليد الصوت لكل جملة…"
        var generated: [(cue: SubCue, audioURL: URL, duration: Double)] = []
        let total = Double(translatable.count)

        // تسلسلي لتجنّب 429، أو متوازي حسب الإعداد
        if request.maxConcurrent <= 1 {
            for cue in translatable {
                if Task.isCancelled { throw CancellationError() }
                let text = cue.translated ?? cue.text
                let url = tempDir.appendingPathComponent("cue-\(cue.id).\(perCueExt)")
                let dur = try await DubbingService.synthesizeOneStatic(text: text,
                                                                       voice: voice,
                                                                       provider: provider,
                                                                       outputURL: url,
                                                                       sampleRate: sampleRate)
                generated.append((cue, url, dur))
                let p = Double(generated.count) / total
                progress = 0.85 * p
                onProgress?(progress, "توليد الصوت \(generated.count)/\(translatable.count)")
            }
        } else {
            // متوازي مع سقف
            let maxConcurrent = max(1, request.maxConcurrent)
            try await withThrowingTaskGroup(of: (SubCue, URL, Double).self) { group in
                var pending = Array(translatable)
                var done = 0
                var inFlight = 0

                while !pending.isEmpty || inFlight > 0 {
                    if Task.isCancelled { throw CancellationError() }

                    while !pending.isEmpty && inFlight < maxConcurrent {
                        let cue = pending.removeFirst()
                        let text = cue.translated ?? cue.text
                        let url = tempDir.appendingPathComponent("cue-\(cue.id).\(perCueExt)")
                        let v = voice
                        let p = provider
                        group.addTask {
                            let dur = try await DubbingService.synthesizeOneStatic(text: text,
                                                                                   voice: v,
                                                                                   provider: p,
                                                                                   outputURL: url,
                                                                                   sampleRate: sampleRate)
                            return (cue, url, dur)
                        }
                        inFlight += 1
                    }

                    if let result = try await group.next() {
                        inFlight -= 1
                        done += 1
                        generated.append(result)
                        progress = 0.85 * Double(done) / total
                        onProgress?(progress, "توليد الصوت \(done)/\(translatable.count)")
                    }
                }
            }
        }

        // رتّب حسب التوقيت
        generated.sort { $0.cue.start < $1.cue.start }

        // دمج في ملف واحد متزامن
        statusText = "دمج المقاطع وتزامن الترجمة…"
        let outURL = try await stitch(generated: generated,
                                      outputURL: outputURL,
                                      stretchToFit: request.stretchToFit,
                                      sampleRate: sampleRate)
        progress = 1
        onProgress?(1, "اكتملت الدبلجة")

        // حساب المدة النهائية
        let asset = AVURLAsset(url: outURL)
        let dur = CMTimeGetSeconds(asset.duration)

        return DubbingResult(audioFileURL: outURL,
                             totalDuration: dur.isFinite ? dur : 0,
                             cuesCount: translatable.count,
                             provider: provider,
                             voice: voice)
    }

    // MARK: - توليد صوت جملة واحدة (مع fallback تلقائي)

    /// توليد صوت جملة — لو المزود الأساسي فشل (مثل Edge TTS محجوب)، يجرب تلقائياً صوت الجهاز.
    /// nonisolated لأن `withThrowingTaskGroup` block لا يرث الـ MainActor
    nonisolated private static func synthesizeOneStatic(text: String,
                                                        voice: DubbingVoice,
                                                        provider: DubbingProvider,
                                                        outputURL: URL,
                                                        sampleRate: Int) async throws -> Double {
        do {
            return try await synthesizeOnePrimaryStatic(text: text, voice: voice, provider: provider, outputURL: outputURL, sampleRate: sampleRate)
        } catch {
            // الاحتياطي: صوت الجهاز — يعمل أوفلاين 100%
            if Task.isCancelled { throw CancellationError() }
            return try await LocalTTS.synthesizeToFile(text: text, voice: voice, outputURL: outputURL)
        }
    }

    /// المحاولة الأولى (المزود السحابي المختار)
    nonisolated private static func synthesizeOnePrimaryStatic(text: String,
                                                              voice: DubbingVoice,
                                                              provider: DubbingProvider,
                                                              outputURL: URL,
                                                              sampleRate: Int) async throws -> Double {
        switch provider {
        case .edge:
            return try await EdgeTTSClient.synthesizeAndSave(text: text, voice: voice.id, outputURL: outputURL)
        case .groqPlayAI:
            return try await GroqTTS.synthesize(text: text, voice: voice.id, outputURL: outputURL)
        case .siliconflow:
            return try await SiliconFlowTTS.synthesize(text: text, voice: voice.id, outputURL: outputURL)
        case .elevenlabs:
            return try await ElevenLabsTTS.synthesize(text: text, voice: voice.id, outputURL: outputURL)
        case .auto:
            throw DubbingError.invalidProvider
        }
    }

    // MARK: - دمج المقاطع في ملف نهائي

    private func stitch(generated: [(cue: SubCue, audioURL: URL, duration: Double)],
                        outputURL: URL,
                        stretchToFit: Bool,
                        sampleRate: Int) async throws -> URL {
        if generated.isEmpty { throw DubbingError.emptyCues }
        // إنشاء AVMutableComposition لدمج الصوتيات مع توقيت دقيق
        let composition = AVMutableComposition()
        guard let audioTrack = composition.addMutableTrack(withMediaType: .audio,
                                                           preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw DubbingError.compositionFailed
        }

        var insertedAny = false
        // حافة أمان: مدة المسار الصوتي الفعلي قد تكون أقصر بجزء من الثانية من
        // مدة الحاوية (padding في MP3/M4A). الإدراج بمدة أطول من مسار المصدر
        // يرفع NSInvalidArgumentException — استثناء Obj-C لا يلتقطه do/catch
        // — ويسبب كراش التطبيق أثناء "دمج المقاطع".
        let epsilon: CFTimeInterval = 0.02
        for item in generated {
            let asset = AVURLAsset(url: item.audioURL)
            guard let audioAssetTrack = asset.tracks(withMediaType: .audio).first else { continue }
            // نقيّد المدة بمدة المسار الفعلي (وليس مدة الحاوية) دائماً
            let sourceDuration = audioAssetTrack.timeRange.duration
            // تجاهل الملفات الفارغة/التالفة لتفادي بناء مسار زمني غير صالح
            guard sourceDuration.seconds.isFinite, sourceDuration.seconds > epsilon else { continue }

            let targetStart = max(0, item.cue.start)
            let targetEnd = max(targetStart + 0.2, item.cue.end)
            let targetDuration = targetEnd - targetStart

            let timeScale = CMTimeScale(600)
            let startTime = CMTime(seconds: targetStart, preferredTimescale: timeScale)
            var insertedDuration = sourceDuration
            if stretchToFit, sourceDuration.seconds > targetDuration * 1.05 {
                // تسريع الصوت ليتناسب مع التوقيت (محدود بمدة الجملة الأصلية)
                let clamped = min(sourceDuration.seconds, max(0.1, targetDuration))
                insertedDuration = CMTime(seconds: clamped, preferredTimescale: timeScale)
            }
            // لا نسمح أبداً بتجاوز مدة المصدر (هذا كان سبب الكراش)
            if insertedDuration.seconds > sourceDuration.seconds - epsilon {
                insertedDuration = CMTime(seconds: max(0.1, sourceDuration.seconds - epsilon), preferredTimescale: timeScale)
            }
            guard insertedDuration.seconds.isFinite, insertedDuration.seconds > 0 else { continue }

            let timeRange = CMTimeRange(start: .zero, duration: insertedDuration)
            do {
                try audioTrack.insertTimeRange(timeRange, of: audioAssetTrack, at: startTime)
                insertedAny = true
            } catch {
                // لو فشل الإدراج عند موضع الجملة (تداخل مثلاً)، ضعه في نهاية المسار
                let fallbackAt = audioTrack.timeRange.duration
                do {
                    try audioTrack.insertTimeRange(timeRange, of: audioAssetTrack, at: fallbackAt)
                    insertedAny = true
                } catch {
                    // تجاهل هذه الجملة إن تعذّر إدراجها تماماً
                }
            }
        }

        guard insertedAny else { throw DubbingError.compositionFailed }

        // اكتب كـ m4a
        if FileManager.default.fileExists(atPath: outputURL.path) {
            try? FileManager.default.removeItem(at: outputURL)
        }
        let compatible = AVAssetExportSession.exportPresets(compatibleWith: composition)
        var exporter = compatible.contains(AVAssetExportPresetAppleM4A)
            ? AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetAppleM4A)
            : AVAssetExportSession(asset: composition)
        guard let exp = exporter, exp.supportedFileTypes.contains(.m4a) else {
            throw DubbingError.exportFailed
        }
        exp.outputURL = outputURL
        exp.outputFileType = .m4a
        exp.shouldOptimizeForNetworkUse = false

        // iOS 16 — استخدم exportAsynchronously مع continuation
        let result: AVAssetExportSession.Status = try await withCheckedThrowingContinuation { cont in
            exp.exportAsynchronously {
                cont.resume(returning: exp.status)
            }
        }
        if result != .completed {
            throw DubbingError.exportFailed
        }
        // تحقق نهائي من الملف المصدَّر قبل اعتبار الدبلجة ناجحة
        let outSize = (try? outputURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        guard FileManager.default.fileExists(atPath: outputURL.path), outSize > 0 else {
            throw DubbingError.exportFailed
        }
        return outputURL
    }

    // MARK: - اختيار المزود

    private func resolveProvider(_ requested: DubbingProvider, lang: SubLang) -> DubbingProvider {
        switch requested {
        case .auto:
            // أفضل قيمة: ElevenLabs إن وُجد، وإلا SiliconFlow، وإلا Groq، وإلا Edge
            if DubbingProvider.elevenlabs.isAvailable { return .elevenlabs }
            if DubbingProvider.siliconflow.isAvailable { return .siliconflow }
            if DubbingProvider.groqPlayAI.isAvailable { return .groqPlayAI }
            return .edge
        default:
            return requested
        }
    }
}

// MARK: - الأخطاء

enum DubbingError: LocalizedError {
    case emptyCues
    case noTranslations
    case invalidProvider
    case compositionFailed
    case exportFailed
    case localSynthesisFailed

    var errorDescription: String? {
        switch self {
        case .emptyCues: return "لا توجد جمل للدبلجة."
        case .noTranslations: return "الجمل لا تحتوي على ترجمة. أكمل الترجمة أولاً."
        case .invalidProvider: return "مزود الدبلجة غير محدد."
        case .compositionFailed: return "فشل تجميع المسار الصوتي."
        case .exportFailed: return "فشل تصدير ملف الدبلجة."
        case .localSynthesisFailed: return "تعذر توليد صوت الجهاز الاحتياطي."
        }
    }
}

// MARK: - مكتبة الأصوات

extension DubbingVoice {

    /// أفضل صوت للغة معيّن في مزوّد معيّن.
    static func best(for lang: SubLang, provider: DubbingProvider) -> DubbingVoice {
        let candidates = voices(for: lang, provider: provider)
        return candidates.max(by: { $0.naturalness < $1.naturalness }) ?? defaultVoice(provider: provider)
    }

    /// قائمة الأصوات المتاحة لهذه اللغة.
    static func voices(for lang: SubLang, provider: DubbingProvider) -> [DubbingVoice] {
        switch provider {
        case .edge:
            return edgeVoices(for: lang)
        case .groqPlayAI:
            return groqVoices(for: lang)
        case .siliconflow:
            return siliconFlowVoices(for: lang)
        case .elevenlabs:
            return ElevenLabsTTS.defaultVoices
        case .auto:
            return edgeVoices(for: lang)
        }
    }

    private static func edgeVoices(for lang: SubLang) -> [DubbingVoice] {
        // قائمة محدّثة من Microsoft Edge TTS voices
        switch lang {
        case .ar:
            return [
                DubbingVoice(id: "ar-SA-ZariyahNeural", name: "Zariyah", language: "ar-SA", gender: .female, naturalness: 4, provider: .edge),
                DubbingVoice(id: "ar-SA-HamedNeural", name: "Hamed", language: "ar-SA", gender: .male, naturalness: 4, provider: .edge),
                DubbingVoice(id: "ar-EG-SalmaNeural", name: "Salma (مصرية)", language: "ar-EG", gender: .female, naturalness: 4, provider: .edge),
                DubbingVoice(id: "ar-EG-ShakirNeural", name: "Shakir (مصري)", language: "ar-EG", gender: .male, naturalness: 4, provider: .edge),
                DubbingVoice(id: "ar-AE-FatimaNeural", name: "Fatima (إماراتية)", language: "ar-AE", gender: .female, naturalness: 4, provider: .edge),
                DubbingVoice(id: "ar-AE-HamdanNeural", name: "Hamdan (إماراتي)", language: "ar-AE", gender: .male, naturalness: 4, provider: .edge)
            ]
        case .en:
            return [
                DubbingVoice(id: "en-US-JennyNeural", name: "Jenny", language: "en-US", gender: .female, naturalness: 5, provider: .edge),
                DubbingVoice(id: "en-US-GuyNeural", name: "Guy", language: "en-US", gender: .male, naturalness: 5, provider: .edge),
                DubbingVoice(id: "en-US-AriaNeural", name: "Aria", language: "en-US", gender: .female, naturalness: 5, provider: .edge),
                DubbingVoice(id: "en-GB-SoniaNeural", name: "Sonia (بريطانية)", language: "en-GB", gender: .female, naturalness: 5, provider: .edge)
            ]
        case .fr: return [DubbingVoice(id: "fr-FR-DeniseNeural", name: "Denise", language: "fr-FR", gender: .female, naturalness: 5, provider: .edge)]
        case .tr: return [DubbingVoice(id: "tr-TR-EmelNeural", name: "Emel", language: "tr-TR", gender: .female, naturalness: 4, provider: .edge)]
        case .de: return [DubbingVoice(id: "de-DE-KatjaNeural", name: "Katja", language: "de-DE", gender: .female, naturalness: 5, provider: .edge)]
        case .es: return [DubbingVoice(id: "es-ES-ElviraNeural", name: "Elvira", language: "es-ES", gender: .female, naturalness: 5, provider: .edge)]
        case .ru: return [DubbingVoice(id: "ru-RU-SvetlanaNeural", name: "Svetlana", language: "ru-RU", gender: .female, naturalness: 4, provider: .edge)]
        case .fa: return [DubbingVoice(id: "fa-IR-DilaraNeural", name: "Dilara", language: "fa-IR", gender: .female, naturalness: 4, provider: .edge)]
        case .id: return [DubbingVoice(id: "id-ID-GadisNeural", name: "Gadis", language: "id-ID", gender: .female, naturalness: 4, provider: .edge)]
        case .hi: return [DubbingVoice(id: "hi-IN-SwaraNeural", name: "Swara", language: "hi-IN", gender: .female, naturalness: 4, provider: .edge)]
        case .ur: return [DubbingVoice(id: "ur-PK-UzmaNeural", name: "Uzma", language: "ur-PK", gender: .female, naturalness: 4, provider: .edge)]
        case .auto: return edgeVoices(for: .ar)
        }
    }

    private static func groqVoices(for lang: SubLang) -> [DubbingVoice] {
        // Groq PlayAI — أوائل 2026: دعم إنجليزي قوي، دعم عربي محدود
        // نُحافظ على هذه القائمة مع تنبيه المستخدم
        switch lang {
        case .ar:
            return [
                DubbingVoice(id: "ar-SA-HamedNeural", name: "Hamed (عبر Edge المُحسَّن)", language: "ar-SA", gender: .male, naturalness: 3, provider: .groqPlayAI)
            ]
        case .en:
            return [
                DubbingVoice(id: "playai-tts-arabic", name: "PlayAI Arabic", language: "ar", gender: .female, naturalness: 3, provider: .groqPlayAI),
                DubbingVoice(id: "playai-tts-english", name: "PlayAI English", language: "en", gender: .female, naturalness: 4, provider: .groqPlayAI)
            ]
        default:
            return [DubbingVoice(id: "playai-tts-english", name: "PlayAI English", language: "en", gender: .female, naturalness: 4, provider: .groqPlayAI)]
        }
    }

    private static func siliconFlowVoices(for lang: SubLang) -> [DubbingVoice] {
        // CosyVoice 2 — أفضل جودة للعربية بين البدائل المفتوحة
        switch lang {
        case .ar:
            return [
                DubbingVoice(id: "FunAudioLLM/CosyVoice2-0.5B:alex", name: "Alex — CosyVoice 2", language: "multi", gender: .male, naturalness: 3, provider: .siliconflow)
            ]
        case .en:
            return [
                DubbingVoice(id: "FunAudioLLM/CosyVoice2-0.5B:alex", name: "Alex — CosyVoice 2", language: "multi", gender: .male, naturalness: 3, provider: .siliconflow)
            ]
        default:
            return [DubbingVoice(id: "FunAudioLLM/CosyVoice2-0.5B:alex", name: "Alex — CosyVoice 2", language: "multi", gender: .male, naturalness: 3, provider: .siliconflow)]
        }
    }

    private static func defaultVoice(provider: DubbingProvider) -> DubbingVoice {
        DubbingVoice(id: "ar-SA-ZariyahNeural", name: "Zariyah", language: "ar-SA",
                     gender: .female, naturalness: 4, provider: provider)
    }
}

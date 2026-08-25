import Foundation
import AVFoundation

/// بوابة تسلسلية غير متزامنة: تشغّل مهمة واحدة فقط في كل مرة
/// بدون حجب أي thread (الـ wait الحاسم يسبب deadlocks مع async).
final class AsyncSerialGate: @unchecked Sendable {
    private let lock = NSLock()
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var running = false

    func run<T: Sendable>(_ body: @Sendable () async throws -> T) async rethrows -> T {
        let gotToken = takeToken()
        if !gotToken {
            // دوري بعد غيري — انتظر حتى يُمرَّر الطوق
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                lock.lock()
                waiters.append(c)
                lock.unlock()
            }
        }
        defer { releaseToken() }
        return try await body()
    }

    private func takeToken() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if running { return false }
        running = true
        return true
    }

    private func releaseToken() {
        lock.lock()
        var next: CheckedContinuation<Void, Never>? = nil
        if let w = waiters.first {
            waiters.removeFirst()
            next = w
        } else {
            running = false
        }
        lock.unlock()
        // الطوق مُمرَّر: المنتظر التالي ينفّذ body (running يظل true بدله)
        next?.resume()
    }
}

/// تحويل النص إلى كلام باستخدام صوت الجهاز (AVSpeechSynthesizer).
/// يُستخدم كاحتياطي تلقائي عند فشل Edge TTS أو أي مزود سحابي.
enum LocalTTS {

    /// بوابة تسلسلية — AVSpeechSynthesizer وAVAudioEngine غير آمنين للتشغيل
    /// المتوازي (تعارض جلسة الصوت يسبب انهياراً غير قابل للالتقاط على iOS).
    private static let gate = AsyncSerialGate()

    /// يولّد ملف صوتي (.m4a) باستخدام صوت الجهاز ويعيد مدته بالثواني.
    static func synthesizeToFile(text: String,
                                 voice: DubbingVoice,
                                 outputURL: URL) async throws -> Double {
        try await gate.run {
            try await Self.realSynthesize(text: text, voice: voice, outputURL: outputURL)
        }
    }

    /// التوليد الفعلي — يُنفَّذ حصراً على MainActor: AVSpeechSynthesizer
    /// غير آمن من خيط ثانوي (كان سبب الكراش المتقطع عند الدبلجة)، والبوابة
    /// فوقه تضمن عدم تراكب تركيبين.
    @MainActor
    private static func realSynthesize(text: String,
                                       voice: DubbingVoice,
                                       outputURL: URL) async throws -> Double {
        // تهيئة جلسة الصوت على MainActor (مطلوب قبل تشغيل AVAudioEngine)
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .spokenAudio, options: [.mixWithOthers])
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            // نتجاهل أخطاء الجلسة ونكمل — المحرك قد يعمل رغم ذلك
        }
        #endif

        let wavURL = outputURL.deletingPathExtension().appendingPathExtension("wav")
        if FileManager.default.fileExists(atPath: wavURL.path) {
            try? FileManager.default.removeItem(at: wavURL)
        }
        if FileManager.default.fileExists(atPath: outputURL.path) {
            try? FileManager.default.removeItem(at: outputURL)
        }

        let recorder = SpeechRecorder()
        do {
            try recorder.start(writingTo: wavURL)
        } catch {
            // فشل بدء التسجيل: نرمي خطأ قابلاً للالتقاط بدل انهيار غير قابل للالتقاط
            throw DubbingError.localSynthesisFailed
        }

        let synth = AVSpeechSynthesizer()
        let voiceObj = bestVoice(for: voice)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = voiceObj
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * 0.96
        utterance.pitchMultiplier = voice.gender == .female ? 1.05 : 0.95
        utterance.volume = 1.0

        let state = SpeechState()
        state.recorder = recorder
        state.wavURL = wavURL
        state.fallback = estimateDuration(for: text)

        let duration: Double = await withCheckedContinuation { (cont: CheckedContinuation<Double, Never>) in
            state.continuation = cont
            synth.delegate = state
            // حارس زمني: لو لم ينتهِ النطق خلال 90 ثانية نكمل رغم ذلك
            // لتفادي تعليق الدبلجة بالكامل (النطق الفعلي أقل بكثير عادةً).
            DispatchQueue.main.asyncAfter(deadline: .now() + 90) { [weak state] in
                state?.finish()
            }
            synth.speak(utterance)
        }

        // تحويل WAV → M4A (AAC) لتوافق AVFoundation واقتصاد الحجم
        do {
            try await convertWAVToM4A(wavURL: wavURL, outputURL: outputURL)
        } catch {
            // احتياطي: انسخ WAV إن فشل التحويل
            try? FileManager.default.copyItem(at: wavURL, to: outputURL)
        }
        try? FileManager.default.removeItem(at: wavURL)

        return duration
    }

    /// حالة النطق + مندوب AVSpeechSynthesizer.
    /// المندوب والحارس الزمني يُستدعيان معاً من خيط Main (المرسل من
    /// realSynthesize على MainActor)، فلا حاجة لقفل.
    private final class SpeechState: NSObject, AVSpeechSynthesizerDelegate {
        var continuation: CheckedContinuation<Double, Never>?
        weak var recorder: SpeechRecorder?
        var wavURL = URL(fileURLWithPath: "/")
        var fallback: Double = 1
        private var resumed = false

        /// يُستدعى من المندوب أو الحارس الزمني — مرة واحدة فقط.
        func finish() {
            guard !resumed, let cont = continuation else { return }
            resumed = true
            continuation = nil
            recorder?.stop()
            // تأخير قصير لضمان كتابة آخر buffer قبل قراءة مدة الملف
            let url = wavURL
            let fb = fallback
            Task {
                try? await Task.sleep(nanoseconds: 150_000_000)
                let asset = AVURLAsset(url: url)
                let d = CMTimeGetSeconds(asset.duration)
                let final = d.isFinite && d > 0 ? d : fb
                cont.resume(returning: final)
            }
        }

        func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
            finish()
        }

        func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
            finish()
        }
    }

    /// أفضل صوت للجهاز يطابق اللغة/الجنس المطلوب
    private static func bestVoice(for voice: DubbingVoice) -> AVSpeechSynthesisVoice? {
        // جرّب اللغة الكاملة أولاً (مثل ar-SA)
        if let v = AVSpeechSynthesisVoice(language: voice.language) { return v }
        // ثم اللغة المختصرة (مثل ar)
        let shortLang = String(voice.language.prefix(2))
        if let v = AVSpeechSynthesisVoice(language: shortLang) { return v }
        // ثم اللغة حسب الكود
        let lang = langCodeToBCP47(voice.language)
        if let v = AVSpeechSynthesisVoice(language: lang) { return v }
        // أخيراً العربية كافتراضي
        return AVSpeechSynthesisVoice(language: "ar-SA")
    }

    private static func langCodeToBCP47(_ code: String) -> String {
        switch code.lowercased() {
        case "ar": return "ar-SA"
        case "en": return "en-US"
        case "hi": return "hi-IN"
        case "ur": return "ur-PK"
        case "fr": return "fr-FR"
        case "tr": return "tr-TR"
        case "de": return "de-DE"
        case "es": return "es-ES"
        case "ru": return "ru-RU"
        case "fa": return "fa-IR"
        case "id": return "id-ID"
        default: return "ar-SA"
        }
    }

    /// تقدير تقريبي لمدة النص بناءً على طول النص (كلمة/ثانية)
    private static func estimateDuration(for text: String) -> Double {
        let words = text.split(separator: " ").count
        // العربي: ~2.5 كلمة/ثانية، اللاتيني: ~2.0 كلمة/ثانية
        let isArabic = text.unicodeScalars.contains { 0x0600...0x06FF ~= $0.value }
        let wps = isArabic ? 2.5 : 2.0
        return max(0.5, Double(words) / wps)
    }

    /// تحويل WAV → M4A عبر AVAssetExportSession
    private static func convertWAVToM4A(wavURL: URL, outputURL: URL) async throws {
        if FileManager.default.fileExists(atPath: outputURL.path) {
            try? FileManager.default.removeItem(at: outputURL)
        }
        let asset = AVURLAsset(url: wavURL)
        guard let exporter = AVAssetExportSession(asset: asset,
                                                  presetName: AVAssetExportPresetAppleM4A) else {
            // fallback: انسخ WAV كما هو إلى outputURL
            try FileManager.default.copyItem(at: wavURL, to: outputURL)
            return
        }
        exporter.outputURL = outputURL
        exporter.outputFileType = .m4a
        exporter.shouldOptimizeForNetworkUse = false

        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            exporter.exportAsynchronously {
                cont.resume()
            }
        }
        if exporter.status != .completed {
            // fallback: انسخ WAV كـ m4a (مزيف بس مفيش crash)
            try FileManager.default.copyItem(at: wavURL, to: outputURL)
        }
    }
}

// MARK: - مسجل الصوت

/// يستخدم AVAudioEngine + AVAudioFile لتسجيل مخرجات AVSpeechSynthesizer.
/// يُنشأ ويُوقف على MainActor فقط (نفس خيط تركيب الـ tap).
private final class SpeechRecorder {
    private let engine = AVAudioEngine()
    private var audioFile: AVAudioFile?
    private var tapped = false
    private let bufferSize: AVAudioFrameCount = 4096

    func start(writingTo url: URL) throws {
        let format = engine.outputNode.inputFormat(forBus: 0)
        // صيغة صفرية (جهاز صوت غير متاح/جلسة معطلة) تسبب NSException داخل
        // AVAudioFile/installTap — نلقي خطأ قابلاً للالتقاط بدل الكراش.
        guard format.sampleRate > 0, format.channelCount > 0 else {
            throw DubbingError.localSynthesisFailed
        }
        audioFile = try AVAudioFile(forWriting: url,
                                    settings: format.settings,
                                    commonFormat: format.commonFormat,
                                    interleaved: format.isInterleaved)
        engine.outputNode.installTap(onBus: 0, bufferSize: bufferSize, format: format) { [weak self] buffer, _ in
            try? self?.audioFile?.write(from: buffer)
        }
        tapped = true
        do {
            try engine.start()
        } catch {
            if tapped {
                engine.outputNode.removeTap(onBus: 0)
                tapped = false
            }
            audioFile = nil
            throw DubbingError.localSynthesisFailed
        }
    }

    func stop() {
        if tapped {
            engine.outputNode.removeTap(onBus: 0)
            tapped = false
        }
        if engine.isRunning {
            engine.stop()
        }
        audioFile = nil
    }
}

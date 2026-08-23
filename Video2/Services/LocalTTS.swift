import Foundation
import AVFoundation

/// تحويل النص إلى كلام باستخدام صوت الجهاز (AVSpeechSynthesizer).
/// يُستخدم كاحتياطي تلقائي عند فشل Edge TTS أو أي مزود سحابي.
enum LocalTTS {

    /// بوابة تسلسلية — تضمن تشغيل صوت جهاز واحد فقط في كل مرة.
    /// تشغيل عدة AVAudioEngine/AVSpeechSynthesizer بالتوازي (التوازي الافتراضي
    /// للدبلجة = 3) يسبب تعارض جلسة الصوت وانهيار غير قابل للالتقاط على iOS،
    /// وهذا كان سبب الكراش عند بدء الدبلجة.
    private static let gate = DispatchQueue(label: "com.video2.localtts", qos: .userInitiated)

    /// يولّد ملف صوتي (.m4a) باستخدام صوت الجهاز ويعيد مدته بالثواني.
    static func synthesizeToFile(text: String,
                                 voice: DubbingVoice,
                                 outputURL: URL) async throws -> Double {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Double, Error>) in
            gate.async {
                let group = DispatchGroup()
                group.enter()
                var result: Result<Double, Error>?
                Task {
                    do {
                        let d = try await Self.realSynthesize(text: text, voice: voice, outputURL: outputURL)
                        result = .success(d)
                    } catch {
                        result = .failure(error)
                    }
                    group.leave()
                }
                group.wait() // يمنع تشغيل تركيبتين في نفس الوقت
                switch result! {
                case .success(let d): cont.resume(returning: d)
                case .failure(let e): cont.resume(throwing: e)
                }
            }
        }
    }

    /// التوليد الفعلي — يُستدعى داخل البوابة التسلسلية فقط.
    private static func realSynthesize(text: String,
                                       voice: DubbingVoice,
                                       outputURL: URL) async throws -> Double {
        // تهيئة جلسة الصوت على MainActor (مطلوب قبل تشغيل AVAudioEngine)
        await MainActor.run {
            #if os(iOS)
            let session = AVAudioSession.sharedInstance()
            do {
                try session.setCategory(.playback, mode: .spokenAudio, options: [.mixWithOthers])
                try session.setActive(true, options: .notifyOthersOnDeactivation)
            } catch {
                // نتجاهل أخطاء الجلسة ونكمل — المحرك قد يعمل رغم ذلك
            }
            #endif
        }

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

        let (duration, _) = await withCheckedContinuation { (cont: CheckedContinuation<(Double, Bool), Never>) in
            var resumed = false
            let finish: () -> Void = {
                guard !resumed else { return }
                resumed = true
                recorder.stop()
                let asset = AVURLAsset(url: wavURL)
                let d = CMTimeGetSeconds(asset.duration)
                let final = d.isFinite && d > 0 ? d : Self.estimateDuration(for: text)
                cont.resume(returning: (final, true))
            }
            let delegate = SpeechDelegate()
            delegate.onFinish = finish
            synth.delegate = delegate
            // حارس زمني: لو لم ينتهِ النطق خلال 60 ثانية نكمل رغم ذلك
            // لتفادي تعليق الدبلجة بالكامل.
            DispatchQueue.main.asyncAfter(deadline: .now() + 60) {
                guard !resumed else { return }
                resumed = true
                recorder.stop()
                let asset = AVURLAsset(url: wavURL)
                let d = CMTimeGetSeconds(asset.duration)
                let final = d.isFinite && d > 0 ? d : Self.estimateDuration(for: text)
                cont.resume(returning: (final, false))
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

    /// تقدير تقريبي للمدة بناءً على طول النص (كلمة/ثانية)
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
private final class SpeechRecorder {
    private let engine = AVAudioEngine()
    private var audioFile: AVAudioFile?
    private let bufferSize: AVAudioFrameCount = 4096

    func start(writingTo url: URL) throws {
        let format = engine.outputNode.inputFormat(forBus: 0)
        audioFile = try AVAudioFile(forWriting: url,
                                   settings: format.settings,
                                   commonFormat: format.commonFormat,
                                   interleaved: format.isInterleaved)
        engine.outputNode.installTap(onBus: 0, bufferSize: bufferSize, format: format) { [weak self] buffer, _ in
            try? self?.audioFile?.write(from: buffer)
        }
        try engine.start()
    }

    func stop() {
        engine.outputNode.removeTap(onBus: 0)
        engine.stop()
        audioFile = nil
    }
}

// MARK: - مندوب AVSpeechSynthesizer

private final class SpeechDelegate: NSObject, AVSpeechSynthesizerDelegate {
    var onFinish: (() -> Void)?

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        // تأخير بسيط لضمان كتابة آخر buffer قبل الإيقاف
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
            self?.onFinish?()
        }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
            self?.onFinish?()
        }
    }
}

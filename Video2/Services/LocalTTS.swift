import Foundation
import AVFoundation

/// تحويل النص إلى كلام باستخدام صوت الجهاز (AVSpeechSynthesizer).
/// يُستخدم كاحتياطي تلقائي عند فشل Edge TTS أو أي مزود سحابي.
enum LocalTTS {

    /// يولّد ملف صوتي (.m4a) باستخدام صوت الجهاز ويعيد مدته بالثواني.
    /// - Parameters:
    ///   - text: النص المراد نطقه
    ///   - voice: صوت DubbingVoice (يُستخدم لاستخراج اللغة والجنس)
    ///   - outputURL: مسار ملف الإخراج (m4a)
    /// - Returns: مدة الملف الصوتي بالثواني
    static func synthesizeToFile(text: String,
                                 voice: DubbingVoice,
                                 outputURL: URL) async throws -> Double {
        // AVSpeechSynthesizer لا يكتب إلى ملف مباشرة — نستخدم
        // AVAudioFile + AVAudioEngine لتسجيل المخرج يدوياً
        let synth = AVSpeechSynthesizer()
        let voiceObj = bestVoice(for: voice)

        // تأكد من تفعيل session للتسجيل
        // AVAudioSession يجب أن يضبط على MainActor لتجنب التحذيرات
        await MainActor.run {
            #if os(iOS)
            let session = AVAudioSession.sharedInstance()
            try? session.setCategory(.playback, mode: .spokenAudio, options: [.mixWithOthers])
            try? session.setActive(true)
            #endif
        }

        // ملف WAV مؤقت (لتسجيل AVSpeech بدقة)
        let wavURL = outputURL.deletingPathExtension().appendingPathExtension("wav")
        if FileManager.default.fileExists(atPath: wavURL.path) {
            try? FileManager.default.removeItem(at: wavURL)
        }

        let recorder = SpeechRecorder()
        try recorder.start(writingTo: wavURL)

        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = voiceObj
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * 0.96
        utterance.pitchMultiplier = voice.gender == .female ? 1.05 : 0.95
        utterance.volume = 1.0

        // انتظر حتى ينتهي النطق — نستخدم continuation مع delegate
        let delegate = SpeechDelegate()
        synth.delegate = delegate
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            delegate.onFinish = { cont.resume() }
            synth.speak(utterance)
        }

        recorder.stop()
        // احصل على المدة الحقيقية للملف
        let asset = AVURLAsset(url: wavURL)
        let duration = CMTimeGetSeconds(asset.duration)
        let finalDuration = duration.isFinite ? duration : estimateDuration(for: text)

        // تحويل من WAV إلى M4A (AAC) لتوافق AVFoundation واقتصاد الحجم
        try await convertWAVToM4A(wavURL: wavURL, outputURL: outputURL)
        try? FileManager.default.removeItem(at: wavURL)

        return finalDuration
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
            // fallback: انسخ WAV كما هو إلى outputURL (مهم عشان مفيش crash)
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
        // تأخير بسيط لضمان كتابة آخر buffer
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
            self?.onFinish?()
        }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        onFinish?()
    }
}

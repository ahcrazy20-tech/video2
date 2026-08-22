import Foundation
import AVFoundation
import CryptoKit
import Combine

/// قراءة الترجمة: Edge TTS (جودة أعلى، يحتاج إنترنت) مع احتياطي AVSpeech على الجهاز.
@MainActor
final class SpeechNarrator: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    static let shared = SpeechNarrator()

    @Published var isSpeaking = false
    @Published var preferEdge = true
    @Published var lastError: String?

    private let synth = AVSpeechSynthesizer()
    private var audioPlayer: AVAudioPlayer?
    private var speakGeneration = 0
    private var lastText: String = ""

    override init() {
        super.init()
        synth.delegate = self
        preferEdge = UserDefaults.standard.object(forKey: "tts.edge") as? Bool ?? true
    }

    func setPreferEdge(_ on: Bool) {
        preferEdge = on
        UserDefaults.standard.set(on, forKey: "tts.edge")
    }

    func stop() {
        speakGeneration += 1
        synth.stopSpeaking(at: .immediate)
        audioPlayer?.stop()
        audioPlayer = nil
        isSpeaking = false
    }

    func speak(_ text: String, language: String) {
        let clean = text
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        if clean == lastText, isSpeaking { return }
        lastText = clean
        speakGeneration += 1
        let gen = speakGeneration
        synth.stopSpeaking(at: .immediate)
        audioPlayer?.stop()
        isSpeaking = true
        lastError = nil

        let useEdge = UserDefaults.standard.object(forKey: "tts.edge") as? Bool ?? preferEdge
        if useEdge {
            Task { [weak self] in
                guard let self else { return }
                do {
                    let data = try await EdgeTTSClient.synthesize(text: clean, language: language)
                    guard gen == self.speakGeneration else { return }
                    try self.playMP3(data)
                } catch {
                    guard gen == self.speakGeneration else { return }
                    self.lastError = error.localizedDescription
                    self.speakLocal(clean, language: language)
                }
            }
        } else {
            speakLocal(clean, language: language)
        }
    }

    private func speakLocal(_ text: String, language: String) {
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: Self.bcp47(language))
            ?? AVSpeechSynthesisVoice(language: language)
            ?? AVSpeechSynthesisVoice(language: "ar-SA")
        u.rate = AVSpeechUtteranceDefaultSpeechRate * 0.96
        u.pitchMultiplier = 1.0
        isSpeaking = true
        synth.speak(u)
    }

    private func playMP3(_ data: Data) throws {
        audioPlayer = try AVAudioPlayer(data: data)
        audioPlayer?.delegate = self
        audioPlayer?.prepareToPlay()
        audioPlayer?.play()
        isSpeaking = true
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in self.isSpeaking = false }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in self.isSpeaking = false }
    }

    nonisolated static func bcp47(_ code: String) -> String {
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
        default: return code
        }
    }

    nonisolated static func edgeVoice(for language: String) -> String {
        switch language.lowercased() {
        case "ar": return "ar-SA-ZariyahNeural"
        case "en": return "en-US-JennyNeural"
        case "hi": return "hi-IN-SwaraNeural"
        case "ur": return "ur-PK-UzmaNeural"
        case "fr": return "fr-FR-DeniseNeural"
        case "tr": return "tr-TR-EmelNeural"
        case "de": return "de-DE-KatjaNeural"
        case "es": return "es-ES-ElviraNeural"
        case "ru": return "ru-RU-SvetlanaNeural"
        case "fa": return "fa-IR-DilaraNeural"
        case "id": return "id-ID-GadisNeural"
        default: return "ar-SA-ZariyahNeural"
        }
    }
}

extension SpeechNarrator: AVAudioPlayerDelegate {
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.isSpeaking = false }
    }
}

enum EdgeTTSError: LocalizedError {
    case empty
    case network
    case protocolFailed

    var errorDescription: String? {
        switch self {
        case .empty: return "لم يُرجع Edge TTS أي صوت."
        case .network: return "تعذر الاتصال بـ Edge TTS."
        case .protocolFailed: return "فشل بروتوكول Edge TTS."
        }
    }
}

enum EdgeTTSClient {
    private static let trustedToken = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private static let chromium = "130.0.2849.68"

    static func synthesize(text: String, language: String) async throws -> Data {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw EdgeTTSError.empty }

        let token = secMSGEC()
        let conn = UUID().uuidString.replacingOccurrences(of: "-", with: "").uppercased()
        var comps = URLComponents(string: "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1")!
        comps.queryItems = [
            URLQueryItem(name: "TrustedClientToken", value: trustedToken),
            URLQueryItem(name: "Sec-MS-GEC", value: token),
            URLQueryItem(name: "Sec-MS-GEC-Version", value: "1-\(chromium)"),
            URLQueryItem(name: "ConnectionId", value: conn)
        ]
        guard let url = comps.url else { throw EdgeTTSError.network }

        var req = URLRequest(url: url)
        req.timeoutInterval = 20
        req.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/\(chromium) Edg/\(chromium)", forHTTPHeaderField: "User-Agent")
        req.setValue("chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold", forHTTPHeaderField: "Origin")

        let session = URLSession(configuration: .default)
        let ws = session.webSocketTask(with: req)
        ws.resume()

        let stamp = isoStamp()
        let config = """
        X-Timestamp:\(stamp)\r
        Content-Type:application/json; charset=utf-8\r
        Path:speech.config\r
        \r
        {"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}
        """
        try await ws.send(.string(config))

        let voice = SpeechNarrator.edgeVoice(for: language)
        let lang = SpeechNarrator.bcp47(language)
        let ssml = """
        <speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='\(lang)'><voice name='\(voice)'>\(escapeXML(trimmed))</voice></speak>
        """
        let requestId = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let ssmlMsg = """
        X-RequestId:\(requestId)\r
        Content-Type:application/ssml+xml\r
        X-Timestamp:\(stamp)Z\r
        Path:ssml\r
        \r
        \(ssml)
        """
        try await ws.send(.string(ssmlMsg))

        var audio = Data()
        let deadline = Date().addingTimeInterval(18)
        while Date() < deadline {
            if Task.isCancelled {
                ws.cancel(with: .goingAway, reason: nil)
                session.invalidateAndCancel()
                throw CancellationError()
            }
            let msg: URLSessionWebSocketTask.Message
            do {
                msg = try await withTimeout(seconds: 8) { try await ws.receive() }
            } catch {
                break
            }
            switch msg {
            case .string(let s):
                if s.contains("Path:turn.end") {
                    ws.cancel(with: .normalClosure, reason: nil)
                    session.invalidateAndCancel()
                    if audio.isEmpty { throw EdgeTTSError.empty }
                    return audio
                }
            case .data(let d):
                audio.append(stripWSBinaryHeader(d))
            @unknown default:
                break
            }
        }
        ws.cancel(with: .goingAway, reason: nil)
        session.invalidateAndCancel()
        if audio.isEmpty { throw EdgeTTSError.network }
        return audio
    }

    private static func stripWSBinaryHeader(_ data: Data) -> Data {
        // أول بايتين = طول الترويسة بنظام big-endian
        guard data.count >= 2 else { return data }
        let headerLen = Int(data[0]) << 8 | Int(data[1])
        let start = 2 + headerLen
        guard start < data.count else { return Data() }
        return data.subdata(in: start..<data.count)
    }

    private static func secMSGEC() -> String {
        let ticks = Int((Date().timeIntervalSince1970 + 11_644_473_600) * 10_000_000)
        let rounded = ticks - (ticks % 3_000_000_000)
        let raw = "\(rounded)\(trustedToken)"
        let digest = SHA256.hash(data: Data(raw.utf8))
        return digest.map { String(format: "%02X", $0) }.joined()
    }

    private static func isoStamp() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(secondsFromGMT: 0)
        f.dateFormat = "EEE MMM dd yyyy HH:mm:ss 'GMT'XXX"
        return f.string(from: Date())
    }

    private static func escapeXML(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&apos;")
    }

    private static func withTimeout<T>(seconds: Double, _ work: @escaping () async throws -> T) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await work() }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                throw URLError(.timedOut)
            }
            let first = try await group.next()!
            group.cancelAll()
            return first
        }
    }
}

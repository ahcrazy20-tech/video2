import Foundation
import AVFoundation

// MARK: - Azure Speech Neural TTS/STT configuration

/// Azure Speech uses one Speech resource key for both Neural TTS and the
/// short-audio STT endpoint. The resource region is not part of the key, so it
/// is stored separately in UserDefaults (never in the Keychain value).
enum AzureSpeech {
    static let defaultRegion = "eastus"
    static let regionKey = "azure.speech.region"

    static var normalizedRegion: String {
        let raw = UserDefaults.standard.string(forKey: regionKey) ?? defaultRegion
        let clean = raw.lowercased().filter { $0.isLetter || $0.isNumber || $0 == "-" }
        return clean.isEmpty ? defaultRegion : clean
    }

    static func locale(for language: SubLang) -> String {
        switch language {
        case .ar, .auto: return "ar-SA"
        case .en: return "en-US"
        case .hi: return "hi-IN"
        case .ur: return "ur-PK"
        case .fr: return "fr-FR"
        case .tr: return "tr-TR"
        case .de: return "de-DE"
        case .es: return "es-ES"
        case .ru: return "ru-RU"
        case .fa: return "fa-IR"
        case .id: return "id-ID"
        }
    }

    /// REST القصير لا يكشف اللغة عند تمرير مصدر تلقائي؛ لا نعرض ar-SA
    /// على أنه نتيجة كشف في تلك الحالة.
    static func detectedLocale(for language: SubLang) -> String? {
        language == .auto ? nil : locale(for: language)
    }

    /// يولّد MP3 من SSML باستخدام صوت Neural المحدد.
    static func synthesize(text: String, voice: DubbingVoice, outputURL: URL) async throws -> Double {
        guard let key = KeychainStore.get("azure") else {
            throw APIError(status: 401, body: "أدخل مفتاح Azure Speech من الإعدادات")
        }
        let endpoint = "https://\(normalizedRegion).tts.speech.microsoft.com/cognitiveservices/v1"
        let language = voice.language.isEmpty ? "ar-SA" : voice.language
        let ssml = "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"\(xmlEscape(language))\"><voice name=\"\(xmlEscape(voice.id))\">\(xmlEscape(text))</voice></speak>"
        let payload = Data(ssml.utf8)
        let (data, _) = try await HTTP.withRetry(attempts: 3, baseDelay: 2) {
            try await HTTP.request("POST", endpoint,
                                   headers: [
                                    "Ocp-Apim-Subscription-Key": KeychainStore.normalized(key),
                                    "Content-Type": "application/ssml+xml; charset=utf-8",
                                    "X-Microsoft-OutputFormat": "audio-24khz-48kbitrate-mono-mp3",
                                    "Accept": "audio/mpeg"
                                   ],
                                   body: payload,
                                   timeout: 90)
        }
        try data.write(to: outputURL, options: .atomic)
        let duration = CMTimeGetSeconds(AVURLAsset(url: outputURL).duration)
        return duration.isFinite && duration > 0
            ? duration
            : EdgeTTSClient.approximateMP3Duration(bytes: data.count)
    }

    /// فحص خفيف للمفتاح والمنطقة عبر قائمة الأصوات الرسمية.
    static func verifyKey(_ key: String) async -> String {
        let endpoint = "https://\(normalizedRegion).tts.speech.microsoft.com/cognitiveservices/voices/list"
        do {
            _ = try await HTTP.request("GET", endpoint,
                                       headers: ["Ocp-Apim-Subscription-Key": KeychainStore.normalized(key)],
                                       timeout: 30)
            return "✅ مفتاح Azure Speech يعمل في منطقة \(normalizedRegion) — راجع بوابة Azure لمعرفة الاستخدام"
        } catch let error as APIError {
            switch error.status {
            case 401, 403: return "❌ مفتاح Azure Speech أو المنطقة غير صحيحين (HTTP \(error.status))"
            case 429: return "⚠️ المفتاح يعمل لكن وصلت لحد Azure مؤقتاً (429)"
            default: return "⚠️ استجابة Azure Speech غير متوقعة (HTTP \(error.status))"
            }
        } catch {
            return "⚠️ تعذر الاتصال بـ Azure Speech — تحقق من المنطقة والإنترنت"
        }
    }

    private static func xmlEscape(_ value: String) -> String {
        value
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&apos;")
    }
}

// MARK: - Groq Orpheus TTS
// https://console.groq.com/docs/api-reference#audio
// موديلان: canopylabs/orpheus-v1-english وcanopylabs/orpheus-arabic-saudi
// (حلّا محل playai-tts الموقوف). أصوات عربية سعودية: Abdullah, Fahad, Sultan,
// Lulwa, Noura, Aisha — وأصوات إنجليزية: Autumn, Diana, Hannah, Austin, Daniel, Troy.

enum GroqTTS {
    /// يولّد صوتاً عبر Groq Orpheus ويحفظه في ملف WAV.
    /// معرّف الصوت يأتي بصيغة "orpheus-ar-abdullah" أو "orpheus-en-autumn".
    static func synthesize(text: String, voice: String, outputURL: URL) async throws -> Double {
        guard let key = KeychainStore.get("groq") else {
            throw APIError(status: 401, body: "أدخل مفتاح Groq من الإعدادات")
        }
        let lower = voice.lowercased()
        let model: String
        let voiceName: String
        if lower.hasPrefix("orpheus-ar-") {
            model = "canopylabs/orpheus-arabic-saudi"
            voiceName = String(voice.dropFirst("orpheus-ar-".count)).lowercased()
        } else if lower.hasPrefix("orpheus-en-") {
            model = "canopylabs/orpheus-v1-english"
            voiceName = String(voice.dropFirst("orpheus-en-".count)).lowercased()
        } else if ["abdullah", "fahad", "sultan", "lulwa", "noura", "aisha"].contains(lower) {
            model = "canopylabs/orpheus-arabic-saudi"
            voiceName = lower
        } else {
            // أصوات Orpheus الإنجليزية أو أي قيمة أخرى
            model = "canopylabs/orpheus-v1-english"
            voiceName = ["autumn", "diana", "hannah", "austin", "daniel", "troy"].contains(lower) ? lower : "troy"
        }
        let body: [String: Any] = [
            "model": model,
            "voice": voiceName,
            "input": text,
            "response_format": "wav"
        ]
        let payload = try JSONSerialization.data(withJSONObject: body)
        let (data, _) = try await HTTP.withRetry(attempts: 2, baseDelay: 3) {
            try await HTTP.request("POST",
                                   "https://api.groq.com/openai/v1/audio/speech",
                                   headers: ["Authorization": "Bearer \(key)",
                                             "Content-Type": "application/json"],
                                   body: payload,
                                   timeout: 90)
        }
        try data.write(to: outputURL, options: .atomic)
        let duration = CMTimeGetSeconds(AVURLAsset(url: outputURL).duration)
        if duration.isFinite && duration > 0 {
            return duration
        }
        // Orpheus WAV: 44.1kHz mono 16-bit = 88,200 bytes/sec تقريباً بعد رأس الملف.
        return max(0.3, Double(max(0, data.count - 44)) / 88_200.0)
    }
}

// MARK: - Gemini TTS (مجاني بنفس مفتاح Gemini)
// https://ai.google.dev/gemini-api/docs/generate-content/speech-generation
// POST /v1beta/models/{tts-model}:generateContent
// generationConfig: responseModalities ["AUDIO"] + speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName
// المخرجات: inlineData PCM خام (24kHz · 16-bit · mono) — نضيف رأس WAV يدوياً.
// 30 صوتاً جاهزاً تدعم 24 لغة منها العربية، مع تحكم طبيعي بالنبرة عبر النص.

enum GeminiTTS {
    /// gemini-3.1-flash-tts-preview هو الأحدث؛ 2.5-flash-preview-tts احتياط موثوق.
    static let defaultModel = "gemini-3.1-flash-tts-preview"
    static let fallbackModel = "gemini-2.5-flash-preview-tts"

    static func selectedModel() -> String {
        let saved = ModelSelection.selected(purpose: "tts", provider: .gemini, fallback: defaultModel)
        return TranslateService.normalizedGeminiModel(saved)
    }

    /// يولّد صوتاً عبر Gemini TTS ويحفظه في ملف WAV.
    /// `voice` هو اسم صوت Gemini الجاهز (مثل Kore أو Puck).
    static func synthesize(text: String, voice: String, outputURL: URL) async throws -> Double {
        guard let key = KeychainStore.get("gemini") else {
            throw APIError(status: 401, body: "أدخل مفتاح Gemini من الإعدادات")
        }
        let voiceName = voice.isEmpty ? "Kore" : voice
        let body: [String: Any] = [
            "contents": [
                ["parts": [["text": text]]]
            ],
            "generationConfig": [
                "responseModalities": ["AUDIO"],
                "speechConfig": [
                    "voiceConfig": [
                        "prebuiltVoiceConfig": ["voiceName": voiceName]
                    ]
                ]
            ] as [String: Any]
        ]
        let payload = try JSONSerialization.data(withJSONObject: body)

        func request(model: String) async throws -> Data {
            let (data, _) = try await HTTP.withRetry(attempts: 2, baseDelay: 3) {
                try await HTTP.request("POST",
                                       "https://generativelanguage.googleapis.com/v1beta/models/\(model):generateContent",
                                       headers: ["x-goog-api-key": key,
                                                 "Content-Type": "application/json"],
                                       body: payload,
                                       timeout: 120)
            }
            return data
        }

        var model = selectedModel()
        var data: Data
        do {
            data = try await request(model: model)
        } catch let e as APIError where e.status == 404 {
            // الموديل المختار غير متاح لهذا المفتاح — نجرب نسخة TTS الاحتياطية.
            model = fallbackModel
            data = try await request(model: model)
        }

        let json = HTTP.json(from: data)
        if let err = json["error"] as? [String: Any] {
            let msg = (err["message"] as? String) ?? "خطأ غير معروف"
            let code = (err["code"] as? Int) ?? 0
            throw APIError(status: code, body: "Gemini TTS: \(msg)")
        }
        // المخرجات: candidates[0].content.parts[0].inlineAudio.data (base64 PCM)
        var base64Audio = ""
        if let candidates = json["candidates"] as? [[String: Any]],
           let content = candidates.first?["content"] as? [String: Any],
           let parts = content["parts"] as? [[String: Any]] {
            for part in parts {
                if let inline = part["inlineData"] as? [String: Any],
                   let b64 = inline["data"] as? String {
                    base64Audio = b64
                    break
                }
                if let inline = part["inlineAudio"] as? [String: Any],
                   let b64 = inline["data"] as? String {
                    base64Audio = b64
                    break
                }
            }
        }
        guard !base64Audio.isEmpty, let pcm = Data(base64Encoded: base64Audio), !pcm.isEmpty else {
            throw APIError(status: 0, body: "Gemini TTS: لم يصل صوت في الاستجابة")
        }
        // PCM خام 24kHz 16-bit mono — نغلّفه برأس WAV ليقرأه AVFoundation.
        let wav = PCMWAVWrapper.wavData(fromPCM: pcm, sampleRate: 24_000, channels: 1, bitsPerSample: 16)
        try wav.write(to: outputURL, options: .atomic)
        return max(0.3, Double(pcm.count) / 48_000.0)
    }
}

// MARK: - تغليف PCM خام في حاوية WAV

enum PCMWAVWrapper {
    /// يبني ملف WAV كامل (رأس RIFF 44 بايت + البيانات) من PCM خام.
    static func wavData(fromPCM pcm: Data, sampleRate: Int, channels: Int, bitsPerSample: Int) -> Data {
        let byteRate = sampleRate * channels * bitsPerSample / 8
        let blockAlign = channels * bitsPerSample / 8
        let dataSize = pcm.count
        var header = Data()
        func append(_ value: UInt32) {
            withUnsafeBytes(of: value.littleEndian) { header.append(contentsOf: $0) }
        }
        func append16(_ value: UInt16) {
            withUnsafeBytes(of: value.littleEndian) { header.append(contentsOf: $0) }
        }
        header.append(Data("RIFF".utf8))
        append(UInt32(36 + dataSize))
        header.append(Data("WAVE".utf8))
        header.append(Data("fmt ".utf8))
        append(16)                              // chunk size
        append16(1)                             // PCM format
        append16(UInt16(channels))
        append(UInt32(sampleRate))
        append(UInt32(byteRate))
        append16(UInt16(blockAlign))
        append16(UInt16(bitsPerSample))
        header.append(Data("data".utf8))
        append(UInt32(dataSize))
        var wav = header
        wav.append(pcm)
        return wav
    }
}

// MARK: - SiliconFlow CosyVoice TTS
// https://docs.siliconflow.cn/en/api-reference/audio/create-speech

enum SiliconFlowTTS {
    static func synthesize(text: String, voice: String, outputURL: URL) async throws -> Double {
        guard let key = KeychainStore.get("siliconflow") else {
            throw APIError(status: 401, body: "أدخل مفتاح SiliconFlow من الإعدادات")
        }
        let body: [String: Any] = [
            "model": "FunAudioLLM/CosyVoice2-0.5B",
            "input": text,
            "voice": voice,
            "response_format": "mp3"
        ]
        let payload = try JSONSerialization.data(withJSONObject: body)
        let (data, _) = try await HTTP.withRetry(attempts: 2, baseDelay: 2) {
            try await SiliconFlowAPI.request("POST",
                                                path: "/audio/speech",
                                                key: key,
                                                headers: ["Content-Type": "application/json"],
                                                body: payload,
                                                timeout: 60)
        }
        try data.write(to: outputURL, options: .atomic)
        return EdgeTTSClient.approximateMP3Duration(bytes: data.count)
    }
}

// MARK: - ElevenLabs TTS
// https://elevenlabs.io/docs/api-reference/text-to-speech

enum ElevenLabsTTS {
    /// قائمة الأصوات الافتراضية. للحصول على قائمة كاملة، استخدم `fetchVoices()`.
    static let defaultVoices: [DubbingVoice] = [
        DubbingVoice(id: "pNInz6obpgDQGcFmaJgB", name: "Adam", language: "en", gender: .male, naturalness: 5, provider: .elevenlabs),
        DubbingVoice(id: "EXAVITQu4vr4xnSDxMaL", name: "Bella", language: "en", gender: .female, naturalness: 5, provider: .elevenlabs),
        DubbingVoice(id: "ErXwobaYiN019PkySvjV", name: "Antoni", language: "en", gender: .male, naturalness: 5, provider: .elevenlabs),
        DubbingVoice(id: "VR6AewLTigWG4xSOukaG", name: "Arnold", language: "en", gender: .male, naturalness: 4, provider: .elevenlabs),
        DubbingVoice(id: "pMsXgVXv3BLzUgSXRplE", name: "Serena", language: "en", gender: .female, naturalness: 5, provider: .elevenlabs),
        DubbingVoice(id: "TxGEqnHWrfWFTfGW9XjX", name: "Josh", language: "en", gender: .male, naturalness: 5, provider: .elevenlabs),
        // أصوات عربية عبر Multilingual v2
        DubbingVoice(id: "Xb7hH8MSUJpSbSDYk0k2", name: "Alice (Multilingual)", language: "ar", gender: .female, naturalness: 5, provider: .elevenlabs),
        DubbingVoice(id: "iP95p4xoKVk53GoZ742B", name: "Chris (Multilingual)", language: "ar", gender: .male, naturalness: 5, provider: .elevenlabs)
    ]

    static func synthesize(text: String, voice: String, outputURL: URL) async throws -> Double {
        guard let key = KeychainStore.get("elevenlabs") else {
            throw APIError(status: 401, body: "أدخل مفتاح ElevenLabs من الإعدادات")
        }
        let url = "https://api.elevenlabs.io/v1/text-to-speech/\(voice)"
        let body: [String: Any] = [
            "text": text,
            "model_id": "eleven_multilingual_v2",
            "voice_settings": [
                "stability": 0.5,
                "similarity_boost": 0.75,
                "style": 0.4,
                "use_speaker_boost": true
            ]
        ]
        let payload = try JSONSerialization.data(withJSONObject: body)
        let (data, _) = try await HTTP.withRetry(attempts: 2, baseDelay: 2) {
            try await HTTP.request("POST", url,
                                   headers: ["xi-api-key": key,
                                             "Content-Type": "application/json",
                                             "Accept": "audio/mpeg"],
                                   body: payload,
                                   timeout: 90)
        }
        try data.write(to: outputURL, options: .atomic)
        let duration = CMTimeGetSeconds(AVURLAsset(url: outputURL).duration)
        return duration.isFinite && duration > 0
            ? duration
            : EdgeTTSClient.approximateMP3Duration(bytes: data.count)
    }

    /// يجلب قائمة الأصوات المتاحة لحساب المستخدم
    static func fetchVoices() async throws -> [DubbingVoice] {
        guard let key = KeychainStore.get("elevenlabs") else {
            throw APIError(status: 401, body: "أدخل مفتاح ElevenLabs من الإعدادات")
        }
        let (data, _) = try await HTTP.request("GET",
                                               "https://api.elevenlabs.io/v1/voices",
                                               headers: ["xi-api-key": key],
                                               timeout: 30)
        let json = HTTP.json(from: data)
        guard let arr = json["voices"] as? [[String: Any]] else { return defaultVoices }
        var voices: [DubbingVoice] = []
        for v in arr {
            guard let id = v["voice_id"] as? String else { continue }
            let name = (v["name"] as? String) ?? id
            let labels = v["labels"] as? [String: Any] ?? [:]
            let gender: DubbingVoice.Gender = {
                if let g = labels["gender"] as? String {
                    if g.lowercased() == "male" { return .male }
                    if g.lowercased() == "female" { return .female }
                }
                return .neutral
            }()
            voices.append(DubbingVoice(id: id, name: name, language: "multi", gender: gender, naturalness: 5, provider: .elevenlabs))
        }
        return voices.isEmpty ? defaultVoices : voices
    }
}

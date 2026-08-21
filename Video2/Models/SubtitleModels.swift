import Foundation

// MARK: - Cue (سطر ترجمة واحد بتوقيته)

struct SubCue: Codable, Equatable {
    var id: Int
    var start: Double
    var end: Double
    var text: String
    var translated: String?

    /// نص العرض حسب الوضع (أصلي / مترجم / ثنائي اللغة)
    func displayText(mode: SubtitleDisplayMode) -> String {
        switch mode {
        case .off:
            return ""
        case .original:
            return text
        case .translated:
            return translated ?? text
        case .bilingual:
            if let t = translated, !t.isEmpty {
                return t + "\n" + text
            }
            return text
        }
    }
}

// MARK: - أوضاع العرض في المشغّل

enum SubtitleDisplayMode: String, CaseIterable, Identifiable {
    case off, original, translated, bilingual
    var id: String { rawValue }

    var titleAR: String {
        switch self {
        case .off: return "إيقاف الترجمة"
        case .original: return "اللغة الأصلية"
        case .translated: return "المترجمة"
        case .bilingual: return "ثنائية اللغة"
        }
    }

    var icon: String {
        switch self {
        case .off: return "captions.bubble.slash"
        case .original: return "captions.bubble"
        case .translated: return "character.bubble"
        case .bilingual: return "text.bubble"
        }
    }
}

// MARK: - اللغات

enum SubLang: String, CaseIterable, Codable, Identifiable {
    case auto, ar, en, hi, ur, fr, tr, de, es, ru, fa, id
    var id: String { rawValue }

    var nameAR: String {
        switch self {
        case .auto: return "تلقائي (كشف تلقائي)"
        case .ar: return "العربية"
        case .en: return "الإنجليزية"
        case .hi: return "الهندية"
        case .ur: return "الأردية"
        case .fr: return "الفرنسية"
        case .tr: return "التركية"
        case .de: return "الألمانية"
        case .es: return "الإسبانية"
        case .ru: return "الروسية"
        case .fa: return "الفارسية"
        case .id: return "الإندونيسية"
        }
    }

    /// كود اللغة لـ APIs (nil للتلقائي)
    var bcp47: String? {
        switch self {
        case .auto: return nil
        default: return rawValue
        }
    }

    /// الاسم الإنجليزي للاستخدام داخل برومبت الترجمة
    var englishName: String {
        switch self {
        case .auto: return "auto-detected"
        case .ar: return "Arabic"
        case .en: return "English"
        case .hi: return "Hindi"
        case .ur: return "Urdu"
        case .fr: return "French"
        case .tr: return "Turkish"
        case .de: return "German"
        case .es: return "Spanish"
        case .ru: return "Russian"
        case .fa: return "Persian"
        case .id: return "Indonesian"
        }
    }
}

// MARK: - مزودو التفريغ الصوتي (Speech-to-Text)

enum STTProviderKind: String, Codable, CaseIterable, Identifiable {
    case auto, groq, assemblyai, sttai, speechmatics
    var id: String { rawValue }

    var titleAR: String {
        switch self {
        case .auto: return "تلقائي (الأفضل المتاح)"
        case .groq: return "Groq Whisper (سريع جداً)"
        case .assemblyai: return "AssemblyAI (الأقوى للطويل)"
        case .sttai: return "STT.ai (600 دقيقة مجاناً/شهر)"
        case .speechmatics: return "Speechmatics (480 دقيقة مجاناً/شهر)"
        }
    }

    var detailAR: String {
        switch self {
        case .auto:
            return "يختار التطبيق أفضل مزود حسب المفاتيح المتاحة: AssemblyAI للفيديوهات الطويلة، وإلا Groq."
        case .groq:
            return "whisper-large-v3-turbo — أسرع وأرخص خيار، مع تقطيع الفيديو لأجزاء متوازية. مناسب للفيديوهات حتى 5 ساعات وأكثر."
        case .assemblyai:
            return "ملف واحد حتى 10 ساعات بدون تقطيع، أعلى دقة في التوقيتات. يحتاج مفتاحاً ورصيداً."
        case .sttai:
            return "STT.ai Enhanced Whisper — 600 دقيقة شهرية مجانية + 100 دقيقة API. بديل قوي وسريع."
        case .speechmatics:
            return "Speechmatics — 480 دقيقة مجانية شهرياً، دقة عالية لـ 55+ لغة مع دعم اللهجات."
        }
    }

    var keyID: String? {
        switch self {
        case .auto: return nil
        case .groq: return "groq"
        case .assemblyai: return "assemblyai"
        case .sttai: return "sttai"
        case .speechmatics: return "speechmatics"
        }
    }
}

// MARK: - مزودو الترجمة النصية

enum TranslatorKind: String, Codable, CaseIterable, Identifiable {
    case auto, gemini, groqLLM, deepL
    var id: String { rawValue }

    var titleAR: String {
        switch self {
        case .auto: return "تلقائي (الأفضل المتاح)"
        case .gemini: return "Gemini (ترجمة سياقية)"
        case .groqLLM: return "Groq LLM (GPT-OSS 120B — سريع جداً)"
        case .deepL: return "DeepL (500K حرف/شهر مجاناً)"
        }
    }

    var detailAR: String {
        switch self {
        case .auto:
            return "يفضّل Gemini عند توفر مفتاحه (شريحة مجانية سخية)، وإلا Groq Llama."
        case .gemini:
            return "ترجمة طبيعية تفهم السياق والمصطلحات، مناسبة للهندية والإنجليزية إلى العربية."
        case .groqLLM:
            return "GPT-OSS 120B عبر Groq (بديل Llama 3.3 المنتهي) — سريع جداً بنفس مفتاح التفريغ."
        case .deepL:
            return "DeepL — 500 ألف حرف/شهر مجاناً، جودة عالية للترجمة السياقية. يحتاج مفتاح DeepL (deepl)."
        }
    }

    var keyID: String? {
        switch self {
        case .auto: return nil
        case .gemini: return "gemini"
        case .groqLLM: return "groq"
        case .deepL: return "deepl"
        }
    }
}

// MARK: - حالة مهمة الترجمة

enum TranslationPhase: String, Codable {
    case queued
    case preparing
    case extracting
    case transcribing
    case translating
    case saving
    case done
    case paused
    case cancelled
    case failed

    var titleAR: String {
        switch self {
        case .queued: return "في الانتظار"
        case .preparing: return "تحضير"
        case .extracting: return "استخراج الصوت وتقطيعه"
        case .transcribing: return "تفريغ الكلام إلى نص"
        case .translating: return "ترجمة النصوص"
        case .saving: return "حفظ ملفات الترجمة"
        case .done: return "اكتملت الترجمة"
        case .paused: return "متوقفة مؤقتاً"
        case .cancelled: return "ملغاة"
        case .failed: return "فشلت"
        }
    }

    var isBusy: Bool {
        switch self {
        case .queued, .preparing, .extracting, .transcribing, .translating, .saving: return true
        default: return false
        }
    }

    var isFinished: Bool {
        switch self {
        case .done, .cancelled, .failed: return true
        default: return false
        }
    }
}

// MARK: - مهمة ترجمة كاملة (قابلة للحفظ والاستئناف)

struct TranslationJob: Identifiable, Codable, Hashable {
    var id: UUID
    var videoID: UUID
    var videoTitle: String
    var isHLS: Bool
    var sourceLang: SubLang
    var targetLang: SubLang
    var sttProvider: STTProviderKind
    var translator: TranslatorKind

    var state: TranslationPhase
    var progress: Double
    var totalChunks: Int
    var doneChunks: Int
    var totalBatches: Int
    var doneBatches: Int
    var detectedLang: String?
    var cueCount: Int

    /// معرّف جلسة AssemblyAI لاستئناف الاستعلام بدون إعادة الرفع
    var assemblyTranscriptID: String?

    var errorMessage: String?
    var createdAt: Date
    var finishedAt: Date?

    var statusLineAR: String {
        switch state {
        case .extracting:
            return "استخراج الصوت…"
        case .transcribing:
            return totalChunks > 0 ? "التفريغ: \(doneChunks)/\(totalChunks) جزء" : "التفريغ…"
        case .translating:
            return totalBatches > 0 ? "الترجمة: \(doneBatches)/\(totalBatches) دفعة" : "الترجمة…"
        default:
            return state.titleAR
        }
    }
}

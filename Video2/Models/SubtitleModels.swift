import Foundation

// MARK: - Cue (سطر ترجمة واحد بتوقيته)

struct SubCue: Codable, Equatable, Sendable {
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

enum SubLang: String, CaseIterable, Codable, Identifiable, Hashable {
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
    case auto, groq, assemblyai, sttai, speechmatics, siliconflow
    var id: String { rawValue }

    var titleAR: String {
        switch self {
        case .auto: return "تلقائي (الأفضل المتاح)"
        case .groq: return "Groq Whisper (سريع جداً)"
        case .assemblyai: return "AssemblyAI (الأقوى للطويل)"
        case .sttai: return "STT.ai (600 دقيقة مجاناً/شهر)"
        case .speechmatics: return "Speechmatics (480 دقيقة مجاناً/شهر)"
        case .siliconflow: return "SiliconFlow SenseVoice (متعدد اللغات)"
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
        case .siliconflow:
            return "SenseVoice Small من FunAudioLLM — موديل صيني مفتوح متفوق في الهندية والصينية ومتعدد اللغات، يدعم 50+ لغة منها العربية. مجاني تقريباً."
        }
    }

    var keyID: String? {
        switch self {
        case .auto: return nil
        case .groq: return "groq"
        case .assemblyai: return "assemblyai"
        case .sttai: return "sttai"
        case .speechmatics: return "speechmatics"
        case .siliconflow: return "siliconflow"
        }
    }
}

// MARK: - مزودو الترجمة النصية

enum TranslatorKind: String, Codable, CaseIterable, Identifiable {
    // مزودات بدون فيزا: كلها فيها شريحة مجانية ويمكن التسجيل فيها بالبريد/GitHub/Google.
    case auto, gemini, groqLLM, deepL, openRouter, cerebras, sambaNova
    var id: String { rawValue }

    var titleAR: String {
        switch self {
        case .auto: return "تلقائي (الأفضل المتاح)"
        case .gemini: return "Gemini (ترجمة سياقية)"
        case .groqLLM: return "Groq LLM (GPT-OSS 120B — سريع جداً)"
        case .deepL: return "DeepL (500K حرف/شهر مجاناً)"
        case .openRouter: return "OpenRouter (موديلات مجانية كثيرة)"
        case .cerebras: return "Cerebras (سريع جداً — مليون token/يوم)"
        case .sambaNova: return "SambaNova (DeepSeek/Llama — رصيد مجاني)"
        }
    }

    var detailAR: String {
        switch self {
        case .auto:
            return "يبدأ بأفضل مزود عندك ثم ينتقل تلقائياً للتالي لو نفدت حصته المجانية — لا تتوقف المهمة في منتصف الفيديو. الترتيب: Gemini ← Groq ← Cerebras ← SambaNova ← DeepL ← OpenRouter."
        case .gemini:
            return "ترجمة طبيعية تفهم السياق والمصطلحات. للدفعات السريعة يضبط التطبيق التفكير المنخفض تلقائياً. مفتاح Google AI Studio مجاني بدون فيزا."
        case .groqLLM:
            return "GPT-OSS 120B أو Qwen عبر Groq — سريعان جداً بنفس مفتاح التفريغ. شريحة مجانية بدون فيزا."
        case .deepL:
            return "DeepL — 500 ألف حرف/شهر مجاناً، جودة عالية للترجمة السياقية. يحتاج مفتاح DeepL (deepl)."
        case .openRouter:
            return "قائمة حيّة من الموديلات المجانية (‎:free) تُحدَّث كل 30 دقيقة — التطبيق يقرأ ما هو مجاني فعلاً الآن بدون فيزا. حدود الشريحة المجانية الرسمية: 20 طلب/دقيقة و50 طلب/يوم (تصبح 1000/يوم بعد شحن 10$ لمرة واحدة). لهذا هو أبطأ المزودين — لو عندك مفتاح Groq أو Cerebras فالتطبيق يقدّمهما عليه تلقائياً."
        case .cerebras:
            return "مليون token يومياً مجاناً وسريع جداً. Llama 3.3 70B / Qwen3 / GLM. بدون فيزا."
        case .sambaNova:
            return "DeepSeek V3.2 / Llama 3.3 عبر SambaNova — رصيد 5$ مجاني بدون فيزا. سريع جداً."
        }
    }

    var keyID: String? {
        switch self {
        case .auto: return nil
        case .gemini: return "gemini"
        case .groqLLM: return "groq"
        case .deepL: return "deepl"
        case .openRouter: return "openrouter"
        case .cerebras: return "cerebras"
        case .sambaNova: return "sambanova"
        }
    }

    /// مفاتيح/مهام قديمة قد تحوي مزوّدات أُزيلت (siliconflow / qwenMT). بدل أن
    /// يفشل فكّ ترميز jobs.json بالكامل ويُضيّع كل المهام، نُسقطها بهدوء إلى
    /// «تلقائي». الترميز يبقى يستخدم الـ rawValue الافتراضي.
    init(from decoder: Decoder) throws {
        let raw = (try? decoder.singleValueContainer().decode(String.self)) ?? ""
        self = TranslatorKind(rawValue: raw) ?? .auto
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
            // لا نعرض 0/N وحدها أثناء انتظار أول رد من المزود: هي صحيحة حسابياً
            // لكنها توحي بأن المهمة علقت. مدير الترجمة يضع وصف الدفعة المرسلة هنا.
            if let active = errorMessage,
               active.contains("جارٍ إرسال") || active.contains("اكتملت الدفعة") {
                return active
            }
            return totalBatches > 0 ? "الترجمة: \(doneBatches)/\(totalBatches) دفعة" : "الترجمة…"
        default:
            return state.titleAR
        }
    }
}

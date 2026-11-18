package com.ahcrazy.video2.core.model

/** Port of SubtitleModels.swift — languages + every AI provider catalogue. */
enum class SubLang(val code: String, val nameAr: String, val englishName: String) {
    AUTO("auto", "تلقائي (كشف تلقائي)", "auto-detected"),
    AR("ar", "العربية", "Arabic"), EN("en", "الإنجليزية", "English"),
    HI("hi", "الهندية", "Hindi"), UR("ur", "الأردية", "Urdu"),
    FR("fr", "الفرنسية", "French"), TR("tr", "التركية", "Turkish"),
    DE("de", "الألمانية", "German"), ES("es", "الإسبانية", "Spanish"),
    RU("ru", "الروسية", "Russian"), FA("fa", "الفارسية", "Persian"),
    ID("id", "الإندونيسية", "Indonesian");
    val bcp47: String? get() = if (this == AUTO) null else code
    companion object { fun of(code: String?) = entries.firstOrNull { it.code == code } ?: AUTO }
}

data class SubCue(val index: Int, val startSec: Double, val endSec: Double, var original: String = "", var translated: String = "")

enum class STTProvider(val id: String, val titleAr: String, val titleEn: String) {
    AUTO("auto", "تلقائي (الأفضل المتاح)", "Auto (best available)"),
    GROQ("groq", "Groq Whisper (سريع جداً)", "Groq Whisper (fastest)"),
    ASSEMBLYAI("assemblyai", "AssemblyAI (الأقوى للطويل)", "AssemblyAI (best for long)"),
    STTAI("sttai", "STT.ai (600 دقيقة مجاناً/شهر)", "STT.ai (600 free min/mo)"),
    SPEECHMATICS("speechmatics", "Speechmatics (480 دقيقة مجاناً/شهر)", "Speechmatics"),
    DEEPGRAM("deepgram", "Deepgram Nova-3", "Deepgram Nova-3"),
    AZURE("azure", "Azure Speech", "Azure Speech"),
    SILICONFLOW("siliconflow", "SiliconFlow SenseVoice", "SiliconFlow SenseVoice"),
    GEMINI_TRANSCRIBE("gemini", "Gemini Transcribe (85+ لغة)", "Gemini Transcribe"),
    ELEVENLABS_SCRIBE("elevenlabs", "ElevenLabs Scribe (99 لغة)", "ElevenLabs Scribe");
    val keyId: String? get() = if (this == AUTO) null else id
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO }
}

enum class RefinerProvider(val id: String, val titleAr: String, val titleEn: String) {
    AUTO("auto", "تلقائي (الأفضل المتاح)", "Auto"),
    GEMINI("gemini", "Gemini (مراجعة سياقية دقيقة)", "Gemini"),
    GROQ("groq", "Groq GPT-OSS 120B (فائق السرعة)", "Groq GPT-OSS 120B"),
    CEREBRAS("cerebras", "Cerebras (~200K token/يوم)", "Cerebras"),
    SAMBANOVA("sambanova", "SambaNova (DeepSeek V3.2)", "SambaNova"),
    OPENROUTER("openrouter", "OpenRouter (موديلات :free)", "OpenRouter :free"),
    NVIDIA("nvidia", "NVIDIA NIM (Nemotron 3)", "NVIDIA NIM"),
    COHERE("cohere", "Cohere (Command A / Aya)", "Cohere"),
    ZAI("zai", "Z.ai GLM-4.7-Flash (مجاني بالكامل)", "Z.ai GLM-4.7-Flash"),
    OFF("off", "إيقاف المراجعة (ترجمة مباشرة)", "Off (translate directly)");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO }
}

enum class TranslatorProvider(val id: String, val titleAr: String, val titleEn: String) {
    AUTO("auto", "تلقائي (الأفضل + تبديل تلقائي)", "Auto (with failover)"),
    GEMINI("gemini", "Gemini (ترجمة سياقية)", "Gemini"),
    GROQ("groq", "Groq GPT-OSS 120B (سريع جداً)", "Groq GPT-OSS 120B"),
    DEEPL("deepl", "DeepL (جودة عالية)", "DeepL"),
    OPENROUTER("openrouter", "OpenRouter (موديلات مجانية كثيرة)", "OpenRouter"),
    CEREBRAS("cerebras", "Cerebras (سريع جداً)", "Cerebras"),
    SAMBANOVA("sambanova", "SambaNova (DeepSeek/Llama)", "SambaNova"),
    NVIDIA("nvidia", "NVIDIA NIM (Nemotron 3)", "NVIDIA NIM"),
    COHERE("cohere", "Cohere (Command A / Aya — عربي قوي)", "Cohere"),
    ZAI("zai", "Z.ai GLM-4.7-Flash (مجاني بالكامل)", "Z.ai"),
    LARA("lara", "Lara Translate (متخصص)", "Lara Translate"),
    MYMEMORY("mymemory", "MyMemory (بدون مفتاح — 5K كلمة/يوم)", "MyMemory (no key)");
    val keyId: String? get() = when (this) { AUTO, MYMEMORY -> null; else -> id }
    companion object {
        /** Exact failover order of the iOS app. */
        val failoverOrder = listOf(GEMINI, GROQ, NVIDIA, CEREBRAS, SAMBANOVA, COHERE, ZAI, DEEPL, OPENROUTER, LARA, MYMEMORY)
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

enum class DubbingProvider(val id: String, val titleAr: String, val titleEn: String) {
    EDGE("edge", "Microsoft Edge (مجاني)", "Microsoft Edge (free)"),
    GROQ_ORPHEUS("groq", "Groq Orpheus (أصوات عربية سعودية)", "Groq Orpheus"),
    SILICONFLOW("siliconflow", "SiliconFlow CosyVoice", "SiliconFlow CosyVoice"),
    ELEVENLABS("elevenlabs", "ElevenLabs (احترافي)", "ElevenLabs (pro)"),
    AZURE("azure", "Azure Speech Neural", "Azure Neural"),
    GEMINI_TTS("gemini", "Gemini TTS (مجاني — 30 صوت)", "Gemini TTS"),
    AUTO("auto", "تلقائي (الأفضل)", "Auto (best)");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO }
}

data class DubbingVoice(val id: String, val name: String, val language: String)

object DubbingVoices {
    val arabicSaudi = listOf("عبدالله", "فهد", "سلطان", "لولوة", "نورة", "عائشة").map {
        DubbingVoice("ar-SA-${it}", it, "ar-SA")
    }
    val gemini = (1..30).map { DubbingVoice("gemini-voice-$it", "Gemini Voice $it", "24 languages") }
    val edge = listOf(
        DubbingVoice("ar-SA-ZariyahNeural", "Zariyah (ar-SA)", "ar-SA"),
        DubbingVoice("ar-EG-SalmaNeural", "Salma (ar-EG)", "ar-EG"),
        DubbingVoice("en-US-RyanMultilingualNeural", "Ryan (en)", "en-US")
    )
}

enum class TranslationPhase { QUEUED, PREPARING, EXTRACTING, TRANSCRIBING, REFINING, TRANSLATING, SAVING, DONE, PAUSED, CANCELLED, FAILED }

enum class TranslationMode { CLOUD, ON_DEVICE;
    companion object {
        // Only old jobs without this field are implicitly cloud. Unknown new modes fail closed.
        fun fromStored(value: String?) = if (value == null || value == CLOUD.name) CLOUD else ON_DEVICE
    }
}

data class TranslationJob(
    val id: String, val videoId: String, val videoTitle: String,
    val sourceLang: String = "auto", val targetLang: String = "ar",
    val sttProvider: String = "auto", val refinerProvider: String = "auto", val translatorProvider: String = "auto",
    val phase: TranslationPhase = TranslationPhase.QUEUED, val progress: Double = 0.0,
    val cueCount: Int = 0, val message: String? = null, val createdAt: Long = System.currentTimeMillis(),
    val mode: TranslationMode = TranslationMode.CLOUD, val offlineModel: String? = null, val offlineIssue: String? = null,
    val offlineSpeechModel: String? = null, val offlineFastSpeech: Boolean = false,
    val offlineTranscriptFrom: String? = null
)

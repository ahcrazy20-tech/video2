package com.ahcrazy.video2.core.net

import com.ahcrazy.video2.core.model.SubCue
import com.ahcrazy.video2.core.model.TranslatorProvider
import com.ahcrazy.video2.data.AppSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One OpenAI-compatible chat client drives Gemini/Groq/Cerebras/SambaNova/NVIDIA/Cohere/Z.ai/OpenRouter —
 * exactly like the iOS providers, which are all /chat/completions under the hood.
 */
class ChatClient(private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()) {

    data class Endpoint(val baseUrl: String, val defaultModel: String)
    companion object {
        val endpoints = mapOf(
            "gemini" to Endpoint("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash"),
            "groq" to Endpoint("https://api.groq.com/openai/v1", "openai/gpt-oss-120b"),
            "cerebras" to Endpoint("https://api.cerebras.ai/v1", "llama-3.3-70b"),
            "sambanova" to Endpoint("https://api.sambanova.ai/v1", "DeepSeek-V3.2"),
            "nvidia" to Endpoint("https://integrate.api.nvidia.com/v1", "nvidia/nemotron-3-super-120b"),
            "cohere" to Endpoint("https://api.cohere.ai/compatibility/v1", "command-a-03-2025"),
            "zai" to Endpoint("https://api.z.ai/api/paas/v4", "glm-4.7-flash"),
            "openrouter" to Endpoint("https://openrouter.ai/api/v1", "openai/gpt-oss-120b:free"),
            "siliconflow" to Endpoint("https://api.siliconflow.cn/v1", "FunAudioLLM/SenseVoiceSmall")
        )
    }

    fun chat(providerId: String, apiKey: String, system: String, user: String, model: String? = null): String {
        val ep = endpoints[providerId] ?: throw IllegalStateException("مزود غير معروف: $providerId")
        val body = JSONObject().apply {
            put("model", model ?: ep.defaultModel)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
            put("temperature", 0.2)
        }
        val req = Request.Builder().url("${ep.baseUrl}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}: ${text.take(200)}")
            return JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
    }

    /** Live free-model list for OpenRouter (public — mirrors iOS behaviour). */
    fun openRouterFreeModels(): List<String> = runCatching {
        val req = Request.Builder().url("https://openrouter.ai/api/v1/models").build()
        http.newCall(req).execute().use { res ->
            val arr = JSONObject(res.body!!.string()).getJSONArray("data")
            (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }.filter { it.endsWith(":free") }
        }
    }.getOrDefault(emptyList())
}

/** Contextual batch translation with automatic failover — port of TranslateService. */
class TranslateClient(private val settings: AppSettings, private val chat: ChatClient = ChatClient()) {
    fun translateBatch(cues: List<SubCue>, targetEnglishName: String, preferred: TranslatorProvider): List<String> {
        val order = if (preferred == TranslatorProvider.AUTO) TranslatorProvider.failoverOrder
            else listOf(preferred) + TranslatorProvider.failoverOrder.filter { it != preferred }
        var last: Exception? = null
        for (p in order) {
            try { return translateWith(p, cues, targetEnglishName) }
            catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("كل مزودي الترجمة فشلوا")
    }

    private fun translateWith(p: TranslatorProvider, cues: List<SubCue>, target: String): List<String> {
        if (p == TranslatorProvider.MYMEMORY) return myMemory(cues, target)
        val keyId = p.keyId ?: throw IllegalStateException("لا يحتاج مفتاح")
        val key = settings.getKey(keyId) ?: throw IllegalStateException("مفتاح ${p.titleAr} غير محفوظ")
        val numbered = cues.mapIndexed { i, c -> "${i + 1}. ${c.original}" }.joinToString("\n")
        val out = chat.chat(p.id, key,
            system = "You are a professional subtitle translator. Translate each numbered line to $target. " +
                "Keep the SAME numbering, one line per cue, no explanations, preserve tone and context across lines.",
            user = numbered)
        val lines = out.lines().map { it.replace(Regex("^\\d+[.)]\\s*"), "").trim() }
        return cues.indices.map { lines.getOrNull(it)?.ifBlank { cues[it].original } ?: cues[it].original }
    }

    /** MyMemory — no key at all, the always-available last resort (5K words/day). */
    private fun myMemory(cues: List<SubCue>, target: String): List<String> {
        val http = OkHttpClient()
        return cues.map { cue ->
            runCatching {
                val q = java.net.URLEncoder.encode(cue.original, "UTF-8")
                val url = "https://api.mymemory.translated.net/get?q=$q&langpair=auto|${targetCode(target)}"
                http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                    JSONObject(res.body!!.string()).getJSONObject("responseData").getString("translatedText")
                }
            }.getOrDefault(cue.original)
        }
    }
    private fun targetCode(english: String) = when (english) {
        "Arabic" -> "ar"; "English" -> "en"; "Hindi" -> "hi"; "Urdu" -> "ur"; "French" -> "fr"
        "Turkish" -> "tr"; "German" -> "de"; "Spanish" -> "es"; "Russian" -> "ru"; "Persian" -> "fa"; else -> "id"
    }
}

/** Groq Whisper transcription (multipart) — the fast path of STTService. */
class SttClient(private val settings: AppSettings, private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()) {

    fun transcribeGroq(audio: File, langCode: String?): List<SubCue> {
        val key = settings.getKey("groq") ?: throw IllegalStateException("مفتاح Groq غير محفوظ")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.name, audio.asRequestBody("audio/m4a".toMediaType()))
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("response_format", "verbose_json")
            .apply { if (!langCode.isNullOrBlank() && langCode != "auto") addFormDataPart("language", langCode) }
            .build()
        val req = Request.Builder().url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $key").post(body).build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IllegalStateException("Groq STT HTTP ${res.code}: ${text.take(200)}")
            val segs = JSONObject(text).optJSONArray("segments") ?: return emptyList()
            return (0 until segs.length()).map { i ->
                val s = segs.getJSONObject(i)
                SubCue(i, s.optDouble("start"), s.optDouble("end"), original = s.optString("text").trim())
            }
        }
    }
}

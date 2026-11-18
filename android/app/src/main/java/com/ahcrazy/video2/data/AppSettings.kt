package com.ahcrazy.video2.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

private val Context.dataStore by preferencesDataStore("video2_prefs")

/** Preferences (DataStore) + secrets (Keystore-encrypted prefs) — port of @AppStorage + KeychainStore. */
class AppSettings(private val ctx: Context) {
    private object K {
        val LANG = stringPreferencesKey("lang")
        val DOWNLOAD_MAX_HEIGHT = intPreferencesKey("dl.maxHeight")
        val STT_CONCURRENCY = intPreferencesKey("stt.concurrency")
        val ADBLOCK_ENABLED = stringPreferencesKey("adblock.enabled")
        val ADBLOCK_MODE = stringPreferencesKey("adblock.mode")
        val ADBLOCK_FILTER_VIDEO_ADS = stringPreferencesKey("adblock.filterVideoAds")
        val AZURE_REGION = stringPreferencesKey("azure.region")
        fun resolver(type: ResolverType) = stringPreferencesKey("dl.external.${type.name.lowercase()}")
        fun model(purpose: String, provider: String) = stringPreferencesKey("model.$purpose.$provider")
    }

    internal val resolverEndpoints: Flow<Map<ResolverType, String>> = ctx.dataStore.data.map { preferences ->
        ResolverType.entries.mapNotNull { type -> preferences[K.resolver(type)]?.let { type to it } }.toMap()
    }
    internal suspend fun setResolverEndpoint(type: ResolverType, value: String) = ctx.dataStore.edit { it[K.resolver(type)] = value }

    val lang: Flow<String> = ctx.dataStore.data.map { it[K.LANG] ?: "ar" }
    val downloadMaxHeight: Flow<Int> = ctx.dataStore.data.map { it[K.DOWNLOAD_MAX_HEIGHT] ?: 0 }
    val sttConcurrency: Flow<Int> = ctx.dataStore.data.map { it[K.STT_CONCURRENCY] ?: 3 }
    val adBlockEnabled: Flow<Boolean> = ctx.dataStore.data.map { (it[K.ADBLOCK_ENABLED] ?: "true") == "true" }
    val adBlockMode: Flow<String> = ctx.dataStore.data.map { it[K.ADBLOCK_MODE] ?: "balanced" }
    val adBlockFilterVideoAds: Flow<Boolean> = ctx.dataStore.data.map { (it[K.ADBLOCK_FILTER_VIDEO_ADS] ?: "true") == "true" }
    val azureRegion: Flow<String> = ctx.dataStore.data.map { it[K.AZURE_REGION] ?: "eastus" }

    suspend fun setLang(v: String) = ctx.dataStore.edit { it[K.LANG] = v }
    suspend fun setDownloadMaxHeight(v: Int) = ctx.dataStore.edit { it[K.DOWNLOAD_MAX_HEIGHT] = v }
    suspend fun setSttConcurrency(v: Int) = ctx.dataStore.edit { it[K.STT_CONCURRENCY] = v.coerceIn(1, 4) }
    suspend fun setAdBlockEnabled(v: Boolean) = ctx.dataStore.edit { it[K.ADBLOCK_ENABLED] = v.toString() }
    suspend fun setAdBlockMode(v: String) = ctx.dataStore.edit { it[K.ADBLOCK_MODE] = v }
    suspend fun setAdBlockFilterVideoAds(v: Boolean) = ctx.dataStore.edit { it[K.ADBLOCK_FILTER_VIDEO_ADS] = v.toString() }
    suspend fun setAzureRegion(v: String) = ctx.dataStore.edit { it[K.AZURE_REGION] = v }
    suspend fun setModel(purpose: String, provider: String, model: String) =
        ctx.dataStore.edit { it[K.model(purpose, provider)] = model }
    fun modelFlow(purpose: String, provider: String): Flow<String?> =
        ctx.dataStore.data.map { it[K.model(purpose, provider)] }

    // ---- Secrets in Android-Keystore-encrypted storage (port of KeychainStore) ----
    private val secrets by lazy {
        val master = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "video2_secrets", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    fun getKey(id: String): String? = secrets.getString("key.$id", null)?.takeIf { it.isNotBlank() }
    fun hasKey(id: String): Boolean = getKey(id) != null
    fun setKey(id: String, value: String) { secrets.edit().putString("key.$id", value.trim()).apply() }
    fun deleteKey(id: String) { secrets.edit().remove("key.$id").apply() }

    // ---- App lock: store ONLY a SHA-256 hash (improvement over the iOS plain compare) ----
    fun setAppPassword(pw: String): Boolean {
        if (pw.trim().length < 4) return false
        secrets.edit().putString("app-password-hash", sha256(pw.trim())).apply()
        return true
    }
    fun hasAppPassword(): Boolean = secrets.contains("app-password-hash")
    fun checkAppPassword(pw: String): Boolean =
        secrets.getString("app-password-hash", null) == sha256(pw.trim())
    fun removeAppPassword() { secrets.edit().remove("app-password-hash").apply() }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        /** All API-key rows of SettingsView, id → official "get key" page. */
        val apiKeyRows: List<Triple<String, String, String>> = listOf(
            Triple("groq", "مفتاح Groq", "https://console.groq.com/keys"),
            Triple("gemini", "مفتاح Gemini", "https://aistudio.google.com/apikey"),
            Triple("openrouter", "مفتاح OpenRouter", "https://openrouter.ai/settings/keys"),
            Triple("cerebras", "مفتاح Cerebras", "https://cloud.cerebras.ai/"),
            Triple("sambanova", "مفتاح SambaNova", "https://cloud.sambanova.ai/apis"),
            Triple("nvidia", "مفتاح NVIDIA NIM", "https://build.nvidia.com/"),
            Triple("cohere", "مفتاح Cohere", "https://dashboard.cohere.com/api-keys"),
            Triple("zai", "مفتاح Z.ai (GLM)", "https://open.bigmodel.cn/usercenter/apikeys"),
            Triple("lara", "مفتاح Lara Translate", "https://laratranslate.com/"),
            Triple("siliconflow", "مفتاح SiliconFlow", "https://cloud.siliconflow.com/account/ak"),
            Triple("elevenlabs", "مفتاح ElevenLabs", "https://elevenlabs.io/app/settings/api-keys"),
            Triple("assemblyai", "مفتاح AssemblyAI", "https://www.assemblyai.com/app/api-keys"),
            Triple("deepgram", "مفتاح Deepgram", "https://console.deepgram.com/"),
            Triple("azure", "مفتاح Azure Speech", "https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices"),
            Triple("deepl", "مفتاح DeepL", "https://www.deepl.com/pro-api"),
            Triple("sttai", "مفتاح STT.ai", "https://stt.ai/"),
            Triple("speechmatics", "مفتاح Speechmatics", "https://portal.speechmatics.com/"),
            Triple("cloudconvert", "مفتاح CloudConvert", "https://cloudconvert.com/api/v2/user/profile/api-keys"),
            Triple("ffmpegapi", "مفتاح ffmpeg-api.com", "https://ffmpeg-api.com/"),
            Triple("convertapi", "مفتاح ConvertAPI", "https://www.convertapi.com/a/auth")
        )
    }
}

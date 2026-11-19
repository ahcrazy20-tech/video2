package com.ahcrazy.video2.ui.browser

import android.content.Context
import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.media.isMediaFragment
import com.ahcrazy.video2.core.media.isPlayerTrackDelivery
import com.ahcrazy.video2.core.model.MediaKind
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal const val SAVED_BROWSER_TABS = 12
private const val URL_LIMIT = 4_096
private const val SESSION_KEY = "pages.v1"
private val tabId = Regex("[a-zA-Z0-9-]{1,64}")
private val transientParams = setOf("access_token", "id_token", "token", "auth", "authorization", "code", "session", "sessionid",
    "session_id", "signature", "sig", "lsig", "policy", "key-pair-id", "x-amz-signature", "x-goog-signature")

internal data class BrowserPage(val id: String, val url: String, val shieldPaused: Boolean = false)
internal data class BrowserPages(val selectedId: String, val tabs: List<BrowserPage>)

/** Store only top-level page addresses. Never rehydrate capture results, auth, or a WebView from disk. */
internal fun savedBrowserPage(raw: String): String {
    if (raw.length > URL_LIMIT) return ""
    val url = httpMediaUrl(raw) ?: return ""
    return url.takeUnless { target ->
        target.isMediaFragment() || target.isPlayerTrackDelivery() || MediaKind.infer(target.toString(), null) != MediaKind.OTHER ||
            target.queryParameterNames.any { it.lowercase(Locale.ROOT) in transientParams }
    }?.toString().orEmpty()
}

/** Small app-private snapshot. SharedPreferences.apply publishes immediately in-process and writes asynchronously. */
internal class BrowserSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("browser-session", Context.MODE_PRIVATE)

    fun load(): BrowserPages? = runCatching {
        val encoded = prefs.getString(SESSION_KEY, null)?.takeIf { it.length <= SAVED_BROWSER_TABS * (URL_LIMIT + 512) }
            ?: return null
        val root = JSONObject(encoded)
        val pages = root.getJSONArray("tabs")
        val seen = mutableSetOf<String>()
        val tabs = (0 until minOf(pages.length(), SAVED_BROWSER_TABS)).mapNotNull { i ->
            val item = pages.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { tabId.matches(it) && seen.add(it) } ?: return@mapNotNull null
            val url = savedBrowserPage(item.optString("url"))
            BrowserPage(id, url, url.isNotEmpty() && item.optBoolean("shieldPaused"))
        }
        if (tabs.isEmpty()) return null
        val selected = root.optString("selected").takeIf { it in seen } ?: tabs.first().id
        BrowserPages(selected, tabs)
    }.getOrNull()

    fun save(pages: BrowserPages) {
        val encoded = JSONObject().put("selected", pages.selectedId).put("tabs", JSONArray().apply {
            for (tab in pages.tabs.take(SAVED_BROWSER_TABS)) {
                val url = savedBrowserPage(tab.url)
                put(JSONObject().put("id", tab.id).put("url", url).put("shieldPaused", url.isNotEmpty() && tab.shieldPaused))
            }
        }).toString()
        if (encoded != prefs.getString(SESSION_KEY, null)) prefs.edit().putString(SESSION_KEY, encoded).apply()
    }
}

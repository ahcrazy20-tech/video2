package com.ahcrazy.video2.data

import com.ahcrazy.video2.core.util.MagicQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** One unified Magic Search result (port of MagicSearchResult). */
data class MagicResult(
    val id: String, val title: String, val source: String, val pageUrl: String,
    val thumbnail: String? = null, val durationSec: Double? = null, val views: Long? = null,
    val playableUrl: String? = null, val isHls: Boolean = false, val snippet: String? = null
)

/**
 * Parallel aggregate search — port of MagicSearchService.
 * Real no-key APIs: Internet Archive, Dailymotion, PeerTube (SepiaSearch), YouTube via Piped.
 */
class MagicSearchRepository(
    private val http: OkHttpClient = defaultHttpClient()
) {
    companion object {
        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", com.ahcrazy.video2.core.model.DownloadAuth.CHROME_UA)
                        .build()
                )
            }
            .build()
    }

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            return res.body?.string().orEmpty()
        }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // --- Internet Archive: public-domain movies, direct files, multiple qualities ---
    fun searchArchive(q: String): List<MagicResult> {
        val url = "https://archive.org/advancedsearch.php?q=title:(${enc(q)})+AND+mediatype:movies" +
            "&fl[]=identifier&fl[]=title&fl[]=downloads&rows=20&output=json"
        val docs = JSONObject(get(url)).getJSONObject("response").getJSONArray("docs")
        return (0 until docs.length()).map {
            val d = docs.getJSONObject(it); val idf = d.getString("identifier")
            MagicResult(idf, d.optString("title", idf), "archive",
                "https://archive.org/details/$idf", "https://archive.org/services/img/$idf",
                views = d.optLong("downloads").takeIf { d.has("downloads") },
                playableUrl = "https://archive.org/download/$idf")
        }
    }

    // --- Dailymotion: official search API, no key ---
    fun searchDailymotion(q: String): List<MagicResult> {
        val url = "https://api.dailymotion.com/videos?search=${enc(q)}&fields=id,title,duration,thumbnail_360_url,views_total&limit=20"
        val list = JSONObject(get(url)).getJSONArray("list")
        return (0 until list.length()).map {
            val d = list.getJSONObject(it); val id = d.getString("id")
            MagicResult(id, d.optString("title"), "dailymotion", "https://www.dailymotion.com/video/$id",
                d.optString("thumbnail_360_url").ifBlank { null },
                d.optDouble("duration").takeIf { d.has("duration") },
                d.optLong("views_total").takeIf { d.has("views_total") },
                playableUrl = "https://www.dailymotion.com/video/$id")
        }
    }

    // --- PeerTube via SepiaSearch federation index ---
    fun searchPeerTube(q: String): List<MagicResult> {
        val url = "https://sepiasearch.org/api/v1/search/videos?search=${enc(q)}&count=20"
        val data = JSONObject(get(url)).getJSONArray("data")
        return (0 until data.length()).map {
            val d = data.getJSONObject(it)
            MagicResult(d.optString("uuid", d.optString("id")), d.optString("name"), "peertube",
                d.optString("url"), d.optString("thumbnailUrl").ifBlank { null },
                d.optDouble("duration").takeIf { d.has("duration") },
                d.optLong("views").takeIf { d.has("views") })
        }
    }

    // --- YouTube search via Piped public instances (streams are hunted in-browser, like iOS) ---
    fun searchYouTube(q: String): List<MagicResult> {
        val instances = listOf("https://pipedapi.kavin.rocks", "https://pipedapi.adminforge.de", "https://api.piped.private.coffee")
        for (inst in instances) {
            try {
                val arr = JSONObject(get("$inst/search?q=${enc(q)}&filter=videos")).getJSONArray("items")
                return (0 until arr.length()).map {
                    val d = arr.getJSONObject(it); val vid = d.optString("url").substringAfter("v=")
                    MagicResult(vid, d.optString("title"), "youtube", "https://www.youtube.com/watch?v=$vid",
                        d.optString("thumbnail").ifBlank { "https://i.ytimg.com/vi/$vid/hqdefault.jpg" },
                        d.optDouble("duration").takeIf { d.has("duration") && d.optDouble("duration") > 0 },
                        d.optLong("views").takeIf { d.has("views") })
                }
            } catch (_: Exception) { /* try next instance */ }
        }
        return emptyList()
    }

    /** Fire every enabled source in parallel; one dead source never fails the search. */
    suspend fun searchAll(query: MagicQuery, onSource: (String, List<MagicResult>) -> Unit) = coroutineScope {
        val sources = query.sources ?: setOf("archive", "youtube", "dailymotion", "peertube", "vimeo", "web")
        val jobs = mapOf<String, suspend () -> List<MagicResult>>(
            "archive" to { searchArchive(query.terms) },
            "dailymotion" to { searchDailymotion(query.terms) },
            "peertube" to { searchPeerTube(query.terms) },
            "youtube" to { searchYouTube(query.terms) },
            "vimeo" to { emptyList() }, // resolved via page hunt, like iOS player config flow
            "web" to { emptyList() }    // meta-search (DDG/SearXNG…) plugs in here
        )
        jobs.filterKeys { it in sources }.map { (src, fn) ->
            async {
                val results = runCatching { withContext(Dispatchers.IO) { fn() } }.getOrDefault(emptyList())
                    .filter { r ->
                        com.ahcrazy.video2.core.util.MagicDuration.inBounds(r.durationSec, query) &&
                            query.excluded.none { ex -> r.title.contains(ex, true) } &&
                            (query.year == null || r.title.contains(query.year.toString())) &&
                            query.mustContain.all { mc -> r.title.contains(mc, true) }
                    }
                onSource(src, results)
            }
        }.forEach { it.await() }
    }
}

package com.ahcrazy.video2.data.backup

import com.ahcrazy.video2.data.Hls

/** Only the flat, finite bundles the downloader writes. A backup must not turn playback into a request for a remote URI. */
internal object ArchivedHls {
    val playlists = setOf("index.m3u8", "video.m3u8", "audio.m3u8")
    private val segment = Regex("(?:video|audio)-seg-[0-9]{1,6}\\.(?:ts|mp4|m4s|cmfv|cmfa|aac|mp3|ac3|ec3)")
    private val init = Regex("(?:video|audio)-init-[0-9]{1,6}\\.mp4")
    private val key = Regex("(?:video|audio)-key-[0-9]{1,6}\\.bin")
    private val uri = Regex("URI=\"([^\"\\r\\n]+)\"")
    private val assignment = Regex("URI\\s*=", RegexOption.IGNORE_CASE)
    private val extraTags = setOf("#EXT-X-PLAYLIST-TYPE", "#EXT-X-MEDIA", "#EXT-X-STREAM-INF", "#EXT-X-KEY", "#EXT-X-MAP", "#EXT-X-ENDLIST")

    fun isAsset(name: String) = name in playlists || segment.matches(name) || init.matches(name) || key.matches(name)
    fun isKey(name: String) = key.matches(name)

    fun validate(assets: Set<String>, texts: Map<String, String>) {
        val master = texts.keys == playlists
        checkFormat(master || texts.keys == setOf("index.m3u8"))
        val needed = texts.keys.toMutableSet()
        for ((name, text) in texts) {
            val isMaster = name == "index.m3u8" && master
            val lines = text.lineSequence().map { it.trim().trimStart('\uFEFF') }.filter(String::isNotEmpty).iterator()
            checkFormat(lines.hasNext() && lines.next() == "#EXTM3U")
            val track = if (name == "audio.m3u8") "audio" else "video"
            var count = 0
            var ended = false
            var audio = false
            while (lines.hasNext()) {
                val line = lines.next()
                if (line.startsWith("#")) {
                    val tag = line.substringBefore(':')
                    checkFormat(tag in Hls.offlinePassthroughTags || tag in extraTags)
                    val refs = uri.findAll(line).map { it.groupValues[1] }.toList()
                    when (tag) {
                        "#EXT-X-MEDIA" -> {
                            checkFormat(isMaster && refs == listOf("audio.m3u8"))
                            audio = true
                        }
                        "#EXT-X-STREAM-INF" -> checkFormat(isMaster && refs.isEmpty())
                        "#EXT-X-MAP" -> checkFormat(!isMaster && refs.size == 1 && init.matches(refs.single()) && refs.single().startsWith("$track-"))
                        "#EXT-X-KEY" -> {
                            val method = line.substringAfter(':').substringBefore(',')
                            checkFormat(!isMaster && ((method == "METHOD=NONE" && refs.isEmpty()) ||
                                (refs.size == 1 && key.matches(refs.single()) && refs.single().startsWith("$track-"))))
                            // Reject DRM/license playlists even if the archive names are local.
                            checkFormat(!Hls.inspect(line).isProtected)
                        }
                        else -> {
                            checkFormat(refs.isEmpty())
                            if (tag == "#EXT-X-ENDLIST") ended = true
                        }
                    }
                    // Don't let a second, unquoted or malformed URI attribute get past the check.
                    checkFormat(assignment.findAll(line).count() == refs.size)
                    needed += refs
                } else {
                    checkFormat(!ended && if (isMaster) line == "video.m3u8" else segment.matches(line) && line.startsWith("$track-"))
                    needed += line
                    count++
                }
            }
            checkFormat(if (isMaster) count == 1 && audio else count > 0 && ended)
        }
        checkFormat(assets == needed)
    }
}

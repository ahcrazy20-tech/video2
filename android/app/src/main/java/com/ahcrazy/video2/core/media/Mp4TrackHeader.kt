package com.ahcrazy.video2.core.media

/** Read only bounded ISO BMFF initialization data. MIME and an ftyp magic number alone don't identify the tracks. */
internal fun ByteArray.mp4TrackTypes(): Set<String> {
    if (size < 16 || String(this, 4, 4, Charsets.US_ASCII) != "ftyp") return emptySet()
    val movie = mp4Boxes(0, size).firstOrNull { it.type == "moov" } ?: return emptySet()
    return buildSet {
        for (track in mp4Boxes(movie.start, movie.end).filter { it.type == "trak" }) {
            val media = mp4Boxes(track.start, track.end).firstOrNull { it.type == "mdia" } ?: continue
            val handler = mp4Boxes(media.start, media.end).firstOrNull { it.type == "hdlr" } ?: continue
            if (handler.end - handler.start >= 12) add(String(this@mp4TrackTypes, handler.start + 8, 4, Charsets.US_ASCII))
        }
    }
}

private data class Mp4Box(val type: String, val start: Int, val end: Int)

private fun ByteArray.mp4Boxes(start: Int, end: Int) = sequence {
    var pos = start
    while (end - pos >= 8) {
        val limit = uint32(pos)
        val type = String(this@mp4Boxes, pos + 4, 4, Charsets.US_ASCII)
        val header = if (limit == 1L) 16 else 8
        val length = when {
            limit == 0L -> break // This is a prefix, not the complete file; don't infer the size.
            header == 16 -> if (end - pos >= 16 && uint32(pos + 8) == 0L) uint32(pos + 12) else break
            else -> limit
        }
        if (length < header || length > end - pos) break
        val next = pos + length.toInt()
        yield(Mp4Box(type, pos + header, next))
        pos = next
    }
}

private fun ByteArray.uint32(offset: Int): Long = (0..3).fold(0L) { result, i ->
    (result shl 8) or (this[offset + i].toLong() and 0xff)
}

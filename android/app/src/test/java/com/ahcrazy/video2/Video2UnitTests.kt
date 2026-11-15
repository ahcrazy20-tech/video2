package com.ahcrazy.video2

import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.core.model.SubCue
import com.ahcrazy.video2.core.util.MagicQuery
import com.ahcrazy.video2.core.util.SrtCodec
import com.ahcrazy.video2.data.Hls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Video2UnitTests {
    @Test fun mediaKindInfer() {
        assertEquals(MediaKind.HLS, MediaKind.infer("https://x/v.m3u8?t=1", null))
        assertEquals(MediaKind.DASH, MediaKind.infer("https://x/v.mpd", null))
        assertEquals(MediaKind.MKV, MediaKind.infer("https://x/v.mkv", null))
        assertEquals(MediaKind.MP3, MediaKind.infer("https://x/a.mp3", null))
    }

    @Test fun magicQueryArabic() {
        val q = MagicQuery.parse("اسم الفيلم مدة:1:36:00 جودة:1080 استبعد:trailer سنة:1995")
        assertEquals("اسم الفيلم", q.terms)
        assertEquals(5760.0, q.targetDuration!!, 0.1)
        assertEquals(1080, q.preferredHeight)
        assertEquals(listOf("trailer"), q.excluded)
        assertEquals(1995, q.year)
    }

    @Test fun magicQueryArabicDigitsAndEnglish() {
        val q = MagicQuery.parse("Movie duration:١:٣٠ quality:720 min:20")
        assertEquals(5400.0, q.targetDuration!!, 0.1)
        assertEquals(720, q.preferredHeight)
        assertEquals(1200.0, q.minDuration!!, 0.1)
    }

    @Test fun magicQueryDirectUrl() {
        val q = MagicQuery.parse("https://site.com/watch/abc")
        assertEquals("https://site.com/watch/abc", q.directUrl)
    }

    @Test fun srtRoundTrip() {
        val cues = listOf(
            SubCue(0, 1.5, 4.0, "Hello world", "أهلا بالعالم"),
            SubCue(1, 65.0, 68.25, "Second line", "السطر التاني")
        )
        val parsed = SrtCodec.parse(SrtCodec.write(cues, "original"))
        assertEquals(2, parsed.size)
        assertEquals(65.0, parsed[1].startSec, 0.01)
        assertTrue(SrtCodec.write(cues, "bilingual").contains("أهلا بالعالم"))
        assertEquals("00:01:05,000", SrtCodec.srtTime(65.0))
    }

    @Test fun hlsMasterParsingAndPicking() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080
            high.m3u8
        """.trimIndent()
        val variants = Hls.parseMaster(master, "https://cdn.example.com/master.m3u8")
        assertEquals(2, variants.size)
        assertEquals(1080, Hls.pickVariant(variants, 0)?.height)
        assertEquals(360, Hls.pickVariant(variants, 720)?.height)
        assertEquals("https://cdn.example.com/high.m3u8", variants[1].url)
    }
}

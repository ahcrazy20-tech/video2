package com.ahcrazy.video2.data

import com.ahcrazy.video2.core.media.YouTubeVideo

internal object PublisherLinks {
    // Editorial link from Al Jazeera's Nov 14 coverage of this exact episode, not a title-matched mirror.
    // AJ360 may offer in-app offline viewing; this is not a file URL and isn't sent to the downloader.
    fun episodeOnAj360(video: YouTubeVideo?): String? = when (video?.id) {
        "MKdlLz15cJ4" -> "https://www.aljazeera360.com/interstitial/1051253"
        else -> null
    }
}

package com.ahcrazy.video2.core.dubbing

import java.util.Locale

/** Stock, preset Speech voices; no custom voice, style or reference recording. */
internal val azureDubVoices = DubVoices(
    listOf(
        neural("ar-EG-SalmaNeural", "ar-EG", "Salma", "سلمى"),
        neural("ar-EG-ShakirNeural", "ar-EG", "Shakir", "شاكر"),
        neural("ar-SA-ZariyahNeural", "ar-SA", "Zariyah", "زارية"),
        neural("ar-SA-HamedNeural", "ar-SA", "Hamed", "حامد"),
        neural("en-US-JennyNeural", "en-US", "Jenny", "جيني"),
        neural("en-US-GuyNeural", "en-US", "Guy", "جاي")
    ),
    "ar-EG-SalmaNeural",
    DubProvider.AZURE.id,
    "Microsoft Azure Speech Neural"
)

private fun neural(id: String, tag: String, name: String, ar: String) =
    DubVoice(id, tag, true, false, 500, 100, DubProvider.AZURE, name, ar)

/** Accept a region code, not a URL. Never interpolate an arbitrary host for the Speech key. */
internal fun azureSpeechEndpoint(region: String): String? {
    val code = region.trim().lowercase(Locale.ROOT)
    if (!Regex("[a-z][a-z0-9]{1,29}").matches(code)) return null
    val domain = if (code == "usgovarizona" || code == "usgovvirginia") "speech.azure.us" else "speech.microsoft.com"
    return "https://$code.tts.$domain/cognitiveservices/v1"
}

/** Treat SRT text as data, never SSML. Omit invalid XML code points, including lone surrogates. */
internal fun azureDubSsml(voice: DubVoice, text: String): String {
    val preset = azureDubVoices.voices.firstOrNull { it == voice } ?: throw DubFailure(DubIssue.VOICE_CHANGED)
    return buildString {
        append("<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"")
        append(preset.tag).append("\"><voice name=\"").append(preset.id).append("\">")
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when (cp) {
                38 -> append("&amp;")
                60 -> append("&lt;")
                62 -> append("&gt;")
                34 -> append("&quot;")
                39 -> append("&apos;")
                9, 10, 13, in 32..0xD7FF, in 0xE000..0xFFFD, in 0x10000..0x10FFFF -> appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        append("</voice></speak>")
    }
}

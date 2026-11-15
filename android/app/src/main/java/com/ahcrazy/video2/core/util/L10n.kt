package com.ahcrazy.video2.core.util

/** Tiny key-based ar/en strings, mirroring iOS L10n.swift (Arabic default). */
object L10n {
    private val ar = mapOf(
        "tab.browser" to "متصفح", "tab.library" to "المكتبة", "tab.translate" to "الترجمة",
        "tab.downloads" to "التحميلات", "tab.settings" to "إعدادات", "tab.magic" to "بحث سحري",
        "lib.empty" to "المكتبة فاضية", "lib.empty.hint" to "حمّل فيديو من المتصفح أو البحث السحري وهيظهر هنا",
        "lib.continue" to "أكمل المشاهدة", "lib.search" to "ابحث في المكتبة",
        "lib.play" to "تشغيل", "lib.rename" to "إعادة تسمية", "lib.delete" to "حذف",
        "lib.import" to "استيراد من الملفات", "dl.empty" to "مفيش تحميلات دلوقتي",
        "magic.hint" to "اكتب اسم فيلم أو فيديو — أوامر زي: مدة:1:36:00 جودة:1080p استبعد:trailer",
        "magic.search" to "ابحث في كل المصادر", "set.title" to "الإعدادات",
        "pl.speed" to "السرعة", "pl.snapshot" to "لقطة", "pl.sleep" to "مؤقت النوم"
    )
    private val en = mapOf(
        "tab.browser" to "Browser", "tab.library" to "Library", "tab.translate" to "Translate",
        "tab.downloads" to "Downloads", "tab.settings" to "Settings", "tab.magic" to "Magic",
        "lib.empty" to "Library is empty", "lib.empty.hint" to "Download a video from the Browser or Magic Search and it will appear here",
        "lib.continue" to "Continue watching", "lib.search" to "Search library",
        "lib.play" to "Play", "lib.rename" to "Rename", "lib.delete" to "Delete",
        "lib.import" to "Import from Files", "dl.empty" to "No downloads right now",
        "magic.hint" to "Type a movie/video name — commands like: duration:1:36:00 quality:1080 exclude:trailer",
        "magic.search" to "Search all sources", "set.title" to "Settings",
        "pl.speed" to "Speed", "pl.snapshot" to "Snapshot", "pl.sleep" to "Sleep timer"
    )
    fun t(lang: String, key: String): String =
        (if (lang == "en") en[key] ?: ar[key] else ar[key] ?: en[key]) ?: key

    fun fmtBytes(bytes: Long): String {
        if (bytes <= 0) return "—"
        val u = arrayOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble(); var i = 0
        while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
        return String.format("%.1f %s", v, u[i])
    }
}

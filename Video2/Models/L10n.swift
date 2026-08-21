import Foundation
import SwiftUI
import Combine

final class LanguageStore: ObservableObject {
    static let key = "v2.lang"
    @Published var code: String {
        didSet { UserDefaults.standard.set(code, forKey: Self.key) }
    }

    init() {
        if let saved = UserDefaults.standard.string(forKey: Self.key), saved == "en" || saved == "ar" {
            code = saved
        } else {
            code = "ar"
        }
    }

    var isRTL: Bool { code != "en" }

    func t(_ key: String) -> String {
        L10n.string(key, lang: code)
    }
}

enum L10n {
    static func string(_ key: String, lang: String) -> String {
        if lang == "en" { return en[key] ?? ar[key] ?? key }
        return ar[key] ?? en[key] ?? key
    }

    static let ar: [String: String] = [
        "tab.browser": "متصفح",
        "tab.library": "المكتبة",
        "tab.downloads": "التحميلات",
        "tab.settings": "إعدادات",
        "tab.translate": "الترجمة",
        "lib.translate": "ترجمة الفيديو",
        "lib.subs.badge": "مترجم",
        "lib.empty": "المكتبة فارغة",
        "lib.empty.hint": "افتح موقعاً من المتصفح، شغّل الفيديو، ثم استخرج وحمّل للمشاهدة بدون إنترنت.",
        "lib.continue": "متابعة المشاهدة",
        "lib.all": "الكل",
        "lib.search.results": "نتائج البحث",
        "lib.search": "بحث في العناوين",
        "lib.resume": "استكمال من ",
        "lib.rename": "إعادة تسمية",
        "lib.title": "العنوان",
        "lib.save": "حفظ",
        "lib.cancel": "إلغاء",
        "lib.play": "تشغيل",
        "lib.delete": "حذف",
        "addr.placeholder": "ابحث أو اكتب موقعاً",
        "paste.title": "لصق رابط فيديو",
        "paste.download": "تحميل",
        "paste.hint": "ضع رابط MP4 أو m3u8 مباشراً إن لم يُكتشف تلقائياً.",
        "drm.title": "تحذير DRM",
        "drm.hide": "إخفاء",
        "drm.body": "هذا المحتوى محمي بنظام DRM. التطبيق لا يكسر الحماية ولن يتم التحميل. يمكنك المشاهدة داخل المتصفح إن سمح الموقع بذلك.",
        "det.title": "استخراج الفيديو",
        "det.filter": "إخفاء الأجزاء الصغيرة وعرض الفيديو الكامل",
        "det.sources": "المصادر المكتشفة",
        "det.empty": "لا يوجد فيديو كامل بعد. شغّل المقطع في الصفحة، أو عطّل التصفية، أو الصق الرابط يدوياً.",
        "det.duration": "المدة",
        "det.size": "الحجم",
        "det.quality": "الجودة",
        "det.play.offline": "يشغّل أوفلاين",
        "det.play.maybe": "صيغة قد لا تُشغَّل",
        "det.save": "تحميل وحفظ في المكتبة",
        "det.protected": "محمي — لا يمكن التحميل",
        "det.close": "إغلاق",
        "det.clear": "مسح",
        "tabs.title": "التبويبات",
        "tabs.close": "إغلاق",
        "dl.empty": "لا توجد مهام تحميل بعد.",
        "dl.queued": "في الانتظار",
        "dl.running": "جارٍ التحميل",
        "dl.paused": "متوقف",
        "dl.failed": "فشل",
        "dl.done": "اكتمل — في المكتبة",
        "dl.drm": "محظور بسبب DRM",
        "set.device": "الجهاز",
        "set.app": "التطبيق",
        "set.target": "الهدف",
        "set.bundle": "الحزمة",
        "set.lang": "اللغة",
        "set.lang.ar": "العربية",
        "set.lang.en": "English",
        "set.ad": "حماية المتصفح",
        "set.ad.on": "حجب الإعلانات",
        "set.ad.mode": "وضع الحجب",
        "set.ad.balanced": "متوازن (موصى به)",
        "set.ad.strict": "صارم",
        "set.ad.hint": "المتوازن يحجب شبكات الإعلان المعروفة دون قطع سكربتات المواقع. إذا توقف موقع، عطّل الحجب مؤقتاً من هنا.",
        "set.extract": "طرق الاستخراج",
        "set.drm": "DRM",
        "set.drm.body": "FairPlay و Widevine و PlayReady تظهر كتحذير. لا يتم كسر الحماية.",
        "set.build": "البناء بدون ماك",
        "set.build.body": "من GitHub: Actions → Build IPA ثم ثبّت من TrollStore.",
        "set.storage": "التخزين",
        "set.storage.body": "الملفات داخل مجلد المستندات الخاص بالتطبيق فقط.",
        "player.fail": "فشل تشغيل الملف",
        "player.prep": "تعذر تجهيز التشغيل",
        "player.speed": "السرعة",
        "player.normal": "عادي",
        "player.sleep": "إيقاف تلقائي",
        "player.sleep.off": "إلغاء المؤقت",
        "kind.other": "أخرى"
    ]

    static let en: [String: String] = [
        "tab.browser": "Browser",
        "tab.library": "Library",
        "tab.downloads": "Downloads",
        "tab.settings": "Settings",
        "tab.translate": "Translate",
        "lib.translate": "Translate video",
        "lib.subs.badge": "Subtitled",
        "lib.empty": "Library is empty",
        "lib.empty.hint": "Open a site in the browser, play the video, then extract and save it for offline viewing.",
        "lib.continue": "Continue watching",
        "lib.all": "All",
        "lib.search.results": "Search results",
        "lib.search": "Search titles",
        "lib.resume": "Resume from ",
        "lib.rename": "Rename",
        "lib.title": "Title",
        "lib.save": "Save",
        "lib.cancel": "Cancel",
        "lib.play": "Play",
        "lib.delete": "Delete",
        "addr.placeholder": "Search or enter a site",
        "paste.title": "Paste video URL",
        "paste.download": "Download",
        "paste.hint": "Paste a direct MP4 or m3u8 link if nothing was detected.",
        "drm.title": "DRM warning",
        "drm.hide": "Hide",
        "drm.body": "This content is DRM-protected. The app does not bypass protection and will not download it. You can watch in the browser if the site allows it.",
        "det.title": "Extract video",
        "det.filter": "Hide fragments, show full videos",
        "det.sources": "Detected sources",
        "det.empty": "No full video yet. Play it on the page, turn off the filter, or paste a link.",
        "det.duration": "Duration",
        "det.size": "Size",
        "det.quality": "Quality",
        "det.play.offline": "Plays offline",
        "det.play.maybe": "May not play",
        "det.save": "Download to library",
        "det.protected": "Protected — cannot download",
        "det.close": "Close",
        "det.clear": "Clear",
        "tabs.title": "Tabs",
        "tabs.close": "Close",
        "dl.empty": "No downloads yet.",
        "dl.queued": "Queued",
        "dl.running": "Downloading",
        "dl.paused": "Paused",
        "dl.failed": "Failed",
        "dl.done": "Done — in library",
        "dl.drm": "Blocked by DRM",
        "set.device": "Device",
        "set.app": "App",
        "set.target": "Target",
        "set.bundle": "Bundle",
        "set.lang": "Language",
        "set.lang.ar": "العربية",
        "set.lang.en": "English",
        "set.ad": "Browser protection",
        "set.ad.on": "Block ads",
        "set.ad.mode": "Block mode",
        "set.ad.balanced": "Balanced (recommended)",
        "set.ad.strict": "Strict",
        "set.ad.hint": "Balanced blocks known ad networks without breaking site scripts. If a site stalls, turn blocking off here.",
        "set.extract": "Extraction methods",
        "set.drm": "DRM",
        "set.drm.body": "FairPlay, Widevine and PlayReady are shown as warnings. Protection is never bypassed.",
        "set.build": "Build without a Mac",
        "set.build.body": "GitHub Actions → Build IPA, then install with TrollStore.",
        "set.storage": "Storage",
        "set.storage.body": "Files stay in the app Documents folder only.",
        "player.fail": "Could not play the file",
        "player.prep": "Could not prepare playback",
        "player.speed": "Speed",
        "player.normal": "Normal",
        "player.sleep": "Sleep timer",
        "player.sleep.off": "Cancel timer",
        "kind.other": "Other"
    ]
}

# فيديو ٢ — نسخة Android

نسخة أندرويد أصلية (Native) من تطبيق iOS في جذر هذا المستودع، بنفس المميزات ونفس الهوية البصرية.

## التقنية
- **Kotlin + Jetpack Compose (Material 3)** — واجهة عربية RTL افتراضيًا + إنجليزي
- **Media3 ExoPlayer** — تشغيل MP4/HLS/DASH/WebM/MKV (ما كان AVPlayer لا يشغّله)
- **Android WebView + extractor.js** — نفس سكربت استخراج iOS (`fetch/XHR/Performance/youtubei`)
- **OkHttp** للتحميل (ملفات + HLS متوازٍ باستئناف) والبحث و APIs الذكاء الاصطناعي
- تخزين: JSON indexes + DataStore + **Android Keystore** للمفاتيح وكلمة السر (SHA-256، تحسين عن iOS)

## إزاي تبنيه
### من Android Studio
1. افتح مجلد `android/` كمشروع → انتظر Gradle Sync → Run على جهاز/محاكي (Android 8+).
2. لو الـ Gradle Wrapper التنفيذي غير موجود: من Android Studio اختار **File → Sync** وهو هيولّده، أو شغّل مرة `gradle wrapper --gradle-version 8.7` من داخل المجلد.

### من GitHub Actions (من غير أي جهاز عندك)
ادفع الكود وافتح **Actions → Build Android APK → Run workflow**، وبعد النجاح نزّل artifact باسم **Video2-Android** فيه الـ APK جاهز للتثبيت.

## خريطة المميزات → الملفات
| الميزة | التنفيذ |
|---|---|
| التبويبات الستة + قفل التطبيق | `ui/Video2Root.kt` |
| المتصفح + الكشف + الرادار + مانع الإعلانات | `ui/browser/BrowserScreen.kt` + `assets/extractor.js` + `assets/adblock_hosts.txt` |
| التحميل (مباشر + HLS + استئناف) | `data/DownloadManager.kt` |
| المكتبة والمجلدات والاستيراد | `ui/library/LibraryScreen.kt` + `data/LibraryStore.kt` |
| المشغّل (ترجمة/دبلجة/PiP/لقطة/نوم/بحث ترجمة) | `ui/player/PlayerScreen.kt` |
| الترجمة (تفريغ→تدقيق→ترجمة→SRT) مع Checkpointing | `data/TranslationManager.kt` + `core/net/ProviderClients.kt` |
| مراجعة وتعديل الترجمة | `ui/translate/TranslateScreen.kt` (`SubtitleReviewScreen`) |
| الدبلجة | `ui/translate/ExtraScreens.kt` (`DubbingSheet`) |
| تحويل HLS→MP4 محليًا | `ui/translate/ExtraScreens.kt` (`ConversionScreen`) |
| البحث السحري + DSL عربي/إنجليزي | `ui/magic/MagicSearchScreen.kt` + `core/util/MagicQuery.kt` + `data/MagicSearchRepository.kt` |
| الإعدادات و20 مفتاح API | `ui/settings/SettingsScreen.kt` + `data/AppSettings.kt` |
| الموديلات والمزودون (10 تفريغ + 10 تدقيق + 12 ترجمة + 7 دبلجة) | `core/model/SubtitleModels.kt` |

## مفاتيح API
من تبويب الإعدادات — كل مزود ليه زرار «احصل على المفتاح». مفيش مفاتيح مدمجة في التطبيق؛
الخدمات اللي من غير مفتاح: Edge TTS للدبلجة، MyMemory للترجمة، وكل مصادر البحث السحري.

## سياسة DRM
زي الأصل بالظبط: كشف FairPlay/Widevine/PlayReady/HLS مرخّص → تحذير ومنع تحميل. مفيش أي تجاوز حماية.

> ملحوظة: ملف بناء الـ APK على GitHub Actions موجود في `android/CI-WORKFLOW.yml.txt` — انسخ محتواه إلى `.github/workflows/build-android.yml` في مستودعك لتفعيل البناء السحابي.

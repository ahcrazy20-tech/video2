# فيديو ٢ — نسخة Android

## التقاط الفيديو وتحميله في 1.1
1. افتح الصفحة في متصفح التطبيق وشغّل الفيديو. افتح زر **الوسائط المكتشفة** أو **الرادار**.
2. اختر **تحميل** أو، لبث HLS، افتح **الجودة** لاختيار دقة موجودة في قائمة البث.
3. تابِع التقدم من **التحميلات**؛ الملفات المكتملة تظهر في **المكتبة**. لو النظام أوقف التطبيق، افتحه واضغط **استئناف**.

الالتقاط يجمع HTML5، طلبات WebView، استجابات Fetch/XHR والـJSON، وإطارات المشغّلات على WebView حديث.
بث HLS يُحفظ للاستخدام دون إنترنت، مع توقيت القوائم الأصلي، ومقاطع TS/fMP4، ونطاقات البايت، والصوت الافتراضي المنفصل عند وجوده.
الملفات المباشرة تُستأنف من البايت المحفوظ إذا دعم الخادم Range وأعطى ETag ثابت أو Last-Modified؛ وإلا تبدأ من جديد.
في البث الحي يُحفظ الجزء المتاح من القائمة وقت التحميل، مش تسجيل مستمر. DASH غير المحمي للتشغيل فقط.
لا يتجاوز التطبيق تراخيص DRM ولا يضمن التقاط كل موقع.


نسخة أندرويد أصلية (Native) من تطبيق iOS في جذر هذا المستودع، بنفس المميزات ونفس الهوية البصرية.

## التقنية
- **Kotlin + Jetpack Compose (Material 3)** — واجهة عربية RTL افتراضيًا + إنجليزي
- **Media3 ExoPlayer** — تشغيل MP4/HLS/DASH/WebM/MKV (ما كان AVPlayer لا يشغّله)
- **Android WebView + extractor.js** — جمع سلبي من HTML5 و`fetch/XHR/Performance`، مع دعم إطارات المشغّلات
- **OkHttp** للتحميل (ملفات + HLS متوازٍ باستئناف) والبحث و APIs الذكاء الاصطناعي
- تخزين: JSON indexes + DataStore + **Android Keystore** للمفاتيح وكلمة السر (SHA-256، تحسين عن iOS)

## إزاي تبنيه
### من Android Studio
1. افتح مجلد `android/` كمشروع → انتظر Gradle Sync → Run على جهاز/محاكي (Android 8+).
2. لو الـ Gradle Wrapper التنفيذي غير موجود: من Android Studio اختار **File → Sync** وهو هيولّده، أو شغّل مرة `gradle wrapper --gradle-version 8.7` من داخل المجلد.

### من GitHub Actions (من غير أي جهاز عندك)
ادفع الكود وافتح **Actions → Build Android APK → Run workflow**، وبعد النجاح نزّل artifact باسم **Video2-Android** فيه APK تجريبي موقّع وجاهز للتثبيت. الـWorkflow موجود في `.github/workflows/build-android.yml`.

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
زي الأصل بالظبط: كشف FairPlay/Widevine/PlayReady أو HLS بمفتاح مرخّص → تحذير ومنع تحميل. HLS من نوع AES-128 القياسي مدعوم فقط عندما يقدّم الخادم المفتاح العادي ضمن نفس جلسة الوسائط المصرّح بها.

اختبارات الالتقاط: `node --test android/app/src/test/js/extractor.test.cjs` من جذر المستودع. اختبارات Android: `gradle :app:testDebugUnitTest` من `android/`.

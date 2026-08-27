# 🔮 Magic Search — دراسة جدوى وخطة تنفيذ

> تاريخ الاختبار الحي: 2026-08-27 — كل النتائج أدناه مُختبرة فعلياً بالطلب المباشر للـ APIs، مش نظري.

## الحكم النهائي: الفكرة ممكنة ✅

الفكرة **قابلة للتنفيذ بالكامل** داخل Video2، والأهم: التطبيق عندك جاهز بنسبة ~70% منها
لأن المكونات الصعبة (استخراج، تحميل HLS بجودات، استئناف، مكتبة، تشغيل) **موجودة وشغالة**.
الجزء الناقص الوحيد هو **طبقة البحث المجمّع (Aggregated Search)**.

---

## 1) نتائج الاختبار الحي للـ APIs

### ✅ Internet Archive — بدون مفتاح، رسمي 100%
| Endpoint | الحالة | ملاحظات |
|---|---|---|
| `advancedsearch.php?q=...&output=json` | ✅ شغال | 243 نتيجة لـ "night of the living dead" |
| `archive.org/metadata/{identifier}` | ✅ شغال | ملفات بصيغ متعددة + العرض×الطول + المدة + الحجم |

- **بحث**: `https://archive.org/advancedsearch.php?q=title:(QUERY) AND mediatype:movies&fl[]=identifier&fl[]=title&fl[]=downloads&rows=20&output=json`
- **الجودات/التحميل**: `https://archive.org/metadata/{id}` → قائمة ملفات (`mp4`, `mkv`, ...) مع `width/height` و`length` (المدة) و`size` — روابط مباشرة للتحميل فوراً.
- **الاستخدام الأمثل**: أفلام الملكية العامة (Public Domain) — تحميل قانوني كامل بجودات متعددة.

### ✅ Dailymotion — API رسمي بدون مفتاح
| Endpoint | الحالة | ملاحظات |
|---|---|---|
| `api.dailymotion.com/videos?search=...&fields=id,title,duration,thumbnail_360_url` | ✅ شغال | نتائج فورية بمدة وصورة |
| `www.dailymotion.com/player/metadata/video/{id}` | ✅ شغال | مدة + ثامبنيلات بكل المقاسات + **manifest HLS بجودات** |

- الـ HLS اللي بيرجعوه بيحتوي variants للجودات — **و`HLSDownloader` الموجود عندنا أصلاً بيدعم اختيار الجودة 360–1080p**، يعني التحميل بجودة محددة هيفتح من أول يوم.
- ملاحظة: حقل `qualities` القديم في REST API اتشال — المسار الصح دلوقتي هو `player/metadata`.

### ✅ Piped (بحث يوتيوب بدون مفتاح) — شغال مع تحفظ
| Endpoint | الحالة | ملاحظات |
|---|---|---|
| `{instance}/search?q=...&filter=videos` | ✅ شغال | عناوين + **مدة** + ثامبنيل + قناة + مشاهدات |
| `{instance}/streams/{videoID}` | ⚠️ محجوب حالياً | يوتيوب حظر الـ IP: `LOGIN_REQUIRED: Sign in to confirm you're not a bot` |

- النسخة اللي نجحت في الاختبار: `api.piped.private.coffee` (النسخة الرسمية `kavin.rocks` كانت غير متاحة لحظة الاختبار).
- **الخلاصة**: البحث ممتاز ومستقر نسبياً، لكن **استخراج روابط التشغيل من النسخ العامة غير موثوق** (مشكلة معروفة منذ 2024: يوتيوب يحظر IPs السيرفرات العامة). الحل القوي تحت 👇.

### 🚀 الحل القوي ليوتيوب: سيرفر yt-dlp شخصي (Self-hosted)
سيرفر صغير (FastAPI + yt-dlp) على خطة مجانية (Render / Railway / Oracle Free / أي VPS):
```
GET /search?q=...&n=20      → yt-dlp "ytsearch20:QUERY" --flat-playlist -J
GET /formats?url=...        → yt-dlp -J URL → كل الصيغ (جودة/امتداد/حجم/fps)
```
- `yt-dlp` بيدعم **أكثر من 1000 موقع** وبيتحديث باستمرار ضد حظر يوتيوب.
- بيرجع قائمة الصيغ كاملة (1080p/720p/480p/... + الصوت فقط) — تُعرض في التطبيق كأزرار جودة وتُسلَّم مباشرة لـ `DownloadManager.enqueue`.
- بديل جاهز مفتوح المصدر بنفس الفكرة: **Cobalt API** (self-hosted) — JSON API بسيط للتحويل من رابط لفيديو جاهز.

### ➕ APIs إضافية مقترحة (اختيارية لكنها تقوّي التحقق)
| API | يحتاج مفتاح؟ | الفائدة |
|---|---|---|
| **TMDB** | مجاني بعد تسجيل | مطابقة الفيلم بالضبط: بوستر + سنة + **مدة الرسمية** → للتأكد إن النتيجة هي المطلوبة قبل فتحها |
| **YouTube Data API v3** | مجاني (10k وحدة/يوم) | بديل رسمي مستقر للبحث في يوتيوب (بدون Piped) |
| **Wikipedia/Wikidata** | لا | بيانات إضافية للأفلام |

---

## 2) المشكلة الوحيدة في الواجهة: حد الـ 5 تبويبات ⚠️

`RootView` فيه **بالظبط 5 تبويبات** (المتصفح، المكتبة، الترجمة، التحميلات، الإعدادات) —
والكومنت الموجود في الكود ب يحذر: أي تبويب سادس يخلي iOS يظهر تبويب "More" وده كان
بيسبب مشاكل قفز لسفاري. **الحلول:**

1. **(المُوصى به)** زر "بحث سحري" 🧿 في شريط أدوات تبويب المتصفح يفتح **شاشة كاملة (fullScreenCover)** — بلا أي تغيير في التبويبات، والنتيجة اللي تختارها تتفتح في تبويب المتصفح الحالي فيشتغل المستخرج والمستشعر اللي عندك على طول.
2. صفحة البداية داخل المتصفح (زي Start Page في سفاري).
3. دمج "التحميلات" مع "المكتبة" لتحرير مكان لتبويب سادس — تغيير أكبر في UX.

---

## 3) المعمارية المقترحة داخل Video2

```
MagicSearchView (شاشة البحث — fullScreenCover من شريط المتصفح)
      │  يكتب المستخدم: اسم فيديو/فيلم
      ▼
MagicSearchService (ViewModel: ObservableObject)
      │  بحث متوازي في:
      ├─ InternetArchiveProvider  (بدون مفتاح)        → نتائج قانونية بجودات
      ├─ DailymotionProvider      (بدون مفتاح)        → HLS بجودات
      ├─ PipedProvider            (بدون مفتاح، عدّة نسخ مع fallback)
      ├─ YtDlpServerProvider      (اختياري — URL من الإعدادات)
      └─ TMDBProvider             (اختياري — مفتاح مجاني، للمطابقة)
      ▼
[MagicSearchResult] موحّدة: عنوان + مدة + ثامبنيل + المصدر + جودات متاحة
      │
      ├─ 🔍 "تشغيل للتحقق" → فتح الصفحة/الرابط في تبويب المتصفح الحالي
      │      (BrowserModel + Extractor + SmartMediaRadar يشتغلوا تلقائياً)
      │
      └─ ⬇️ "تحميل" → قائمة الجودات (من metadata/API/السيرفر)
             → تحويل لـ DetectedMedia → DownloadManager.enqueue(media:maxHeight:)
```

### الربط بالكود الموجود (مباشر — بدون تعديل جوهرري)
- النتيجة تتحول لـ `DetectedMedia` موجود (`title`, `duration`, `qualityLabel`, `width/height`, `byteSize`, `variants`) — **نفس الموديل**.
- التحميل ينادي `DownloadManager.enqueue(_:auth:maxHeight:)` الموجود — بالجودة المختارة.
- HLS من Dailymotion → `HLSDownloader` بجوداته 360–1080p الموجودة.
- ثامبنيل النتيجة → `AsyncImage` عادي.
- النصوص → `L10n` (ar/en) بنفس النظام.
- الإعدادات الجديدة (سيرفر yt-dlp، مفتاح TMDB، تفعيل/تعطيل مزودين) → `SettingsView` + `KeychainStore` الموجودين.

---

## 4) خطة التنفيذ على مراحل

| المرحلة | المحتوى | الجهد |
|---|---|---|
| **1** | `MagicSearchService` + `MagicSearchView` + مزودي Internet Archive وDailymotion وPiped (بحث + عرض + مدة + ثامبنيل + زر فتح للتحقق + تحميل IA المباشر وتحميل DM عبر HLS) | الأكبر — أساس الميزة |
| **2** | تحويل النتائج لـ `DownloadManager` بجودات + شاشة اختيار الجودة + فتح تلقائي في المتصفح للتحقق | صغير |
| **3** | سيرفر yt-dlp المرجعي (كود FastAPI جاهز للنشر) + مزوده في التطبيق + إعداداته | متوسط |
| **4** | TMDB للمطابقة (بوستر/سنة/مدة رسمية بجانب كل نتيجة) + تحسين الترتيب | صغير |

---

## 5) ملاحظة قانونية مهمة (بنفس سياسة التطبيق الحالية)

التطبيق حالياً **لا يكسر DRM** ويحذر من المحتوى المحمي — وده لازم يفضل كذلك في Magic Search:
- المصادر النظيفة تماماً: Internet Archive (ملكية عامة)، Dailymotion API الرسمي، يوتيوب للمحتوى المرخّص/CC.
- سيرفر yt-dlp للاستخدام الشخصي على جهازك — لكن **تحميل محتوى محمي بحقوق** من مواقع القرصنة مسؤولية المستخدم، والتطبيق يعرض المصدر بوضوح لكل نتيجة.
- المحتوى المشفّر (FairPlay/Widevine) → يظهر نفس تحذير DRM الموجود ولا يُحمَّل.

---

## 6) ما نُفِّذ فعلياً — التحديث الحالي (تشغيل + صيد + تحميل داخل التبويب)

> هذا القسم يوثّق الكود الموجود في المستودع الآن، ويتجاوز بعض افتراضات الخطة الأصلية أعلاه.

### الملفات

| ملف | الدور |
|---|---|
| `Video2/Services/MagicQuery.swift` | محلّل «صيغة البحث»: `مدة: min: max: سنة: موقع: جودة: استبعد: مصدر: ترتيب:` + اقتباسات «…» + رابط ملصوق. يبني نص البحث لكل محرك (مع `site:` وفلاتر المدة)، ويزن/يفلتر النتائج (`accepts`, `distance`). |
| `Video2/Services/MagicSearchService.swift` | المزوّدات (`InternetArchive`, `Piped`, `Invidious`, `Dailymotion`, `PeerTube` عبر sepiasearch، `Vimeo`، `WebSearchProvider` بحث ميتا متعدد المحركات مع Bing Videos) + `MagicSearchStore` (بحث متوازٍ، دمج وفك تكرار وترتيب، كاش مصادر التشغيل، تجهيز مسبق، Now Playing، `importLink`). |
| `Video2/Services/MagicResolver.swift` | تحويل النتيجة إلى `MagicStreamVariant[]`: ملفات الأرشيف، `player/metadata` لداليموشن (توسيع قائمة HLS الأُم إلى جودات)، `config` لفيميو، `api/v1/videos/{uuid}` لـ PeerTube، Piped/Invidious ليوتيوب، `og:video`/`<source>`/JSON-LD لأي صفحة ويب، وويكيميديا كومنز عبر `imageinfo`. |
| `Video2/Services/MagicPageHunter.swift` | «الصيد العميق»: WKWebView غير ظاهر (إطار خارج الشاشة) بنفس `ExtractorScript` + خطّاف `fetch/XHR` لالتقاط `streamingData` من `youtubei/v1/player`، ثم ingest للنتائج مع استبعاد ما عليه DRM. |
| `Video2/Services/MagicStreamProxy.swift` | وسيط HTTP محلي على `127.0.0.1` (منافذ 8770/8771/8772/18770): يمرّر Range والترويسات، ويعيد كتابة قوائم HLS (متداخلة + `EXT-X-MAP` + `EXT-X-KEY`). **للتشغيل فقط**. |
| `Video2/Views/MagicPlayerView.swift` | `MagicPlaybackModel` (AVPlayer + AVPlayerLayer): سلسلة نجات (مباشر ← وسيط ← مصدر آخر ← رسالة بدائل)، تبديل جودة مع حفظ الموضع،سرعات، Now Playing/MPRemoteCommandCenter، شريط «يُشغَّل الآن». |
| `Video2/Views/MagicSearchView.swift` | لوحة البحث + رقائق المدة + رقائق الأوامر + مفاتيح (صيد عميق / تجهيز مسبق)، حالة كل مصدر، بطاقة نتيجة ( badges + أزرار تشغيل/تحميل/صيد/متصفح + رقائق جودات + شريط تقدم التحميل)، ورقة شرح الصيغة، لصق رابط للصيد، و`.fullScreenCover` للمشغّل. |

### مبادئ ثابتة (لا تتغير)
1. **التحميل يمرّ بـ `DownloadManager.enqueueManual` كما هو** — أضيف فقط `kindHint:` (يُستخدم فقط حين الاستنتاج يعطي `.other` حتى لا تُسمّى روابط googlevideo بـ `.bin`) و`job(matchingURL:)` لقراءة التقدم.
2. **لا كسر DRM**: أي `SAMPLE-AES`/FairPlay/Widevine/`drm/`/`cenc`/`cbcs` أو قائمة `protected_delivery` → استبعاد المصدر + رسالة + «افتح في المتصفح». الوسيط المحلي لا يُستخدم في التحميل أبداً.
3. **لا تعديل على نواة المتصفح/الاستخراج**: الصياد نسخة معزولة تستدعي `ExtractorScript` فقط، ولا تلمس `BrowserModel` ولا `SmartMediaRadar`.
4. المحلل يرجّع ملاحظة نصية مترجمة عند الفشل (`resolve.*`, `yt.blocked`, `web.noDirectFile`) بدل الصمت.

### قيود معروفة (مختبَرة حيّاً)
- النسخ العامة لـ Piped/Invidious لا تعطي روابط تشغيل مستقرة (يوتيوب يحظر IPs السيرفرات)؛ لذلك يوتيوب = صيد من الصفحة، وإلا متصفح.
- روابط googlevideo المنتزعة من المتصفح الخفي تنتهي صلاحيتها بعد دقائق: تُستهلك للتشغيل الفوري أو التحميل مباشرة، ولا تُخزَّن.
- `AVPlayer` لا يدعم WebM/DASH: تظهر كخيارات تحميل فقط.

# تحليل شامل — تطبيق Video2 (فيديو ٢)

> **تاريخ التحليل:** 2026-11-15 · **الفرع:** `arena/7fc6647a-video2` · **المنصة الحالية:** iOS فقط
> التحليل مبني على قراءة الكود كاملاً (README + 56 ملف Swift)، مش على وصف تسويقي.

---

## 1) بطاقة تعريف سريعة

| البند | القيمة الفعلية من الكود |
|---|---|
| الاسم | فيديو ٢ — Video2 (`CFBundleDisplayName = فيديو ٢`) |
| Bundle ID | `com.ahcrazy.video2` |
| المنصة | iPhone فقط (`TARGETED_DEVICE_FAMILY = 1`) — **لا يوجد iPad** |
| نظام التشغيل | iOS 16.4+ (`IPHONEOS_DEPLOYMENT_TARGET = 16.4`)، جهاز المطوّر المرجعي iPhone 11 |
| التوزيع | Sideload بدون شهادة عبر **TrollStore** — IPA غير موقّع يُبنى بـ GitHub Actions (`macos-15`, `CODE_SIGNING_ALLOWED=NO`) |
| اللغة / التقنية | Swift 5 + SwiftUI + WebKit (WKWebView) + AVFoundation/AVPlayer — **بدون أي مكتبة خارجية (Zero Dependencies)** |
| حجم الكود | **56 ملف Swift · ≈ 25,819 سطر** (أكبر ملف: `MagicSearchService.swift` بـ 1,678 سطر، ثم `ModelCatalog.swift` بـ 1,551) |
| الواجهة | عربي (افتراضي، RTL على مستوى النظام عبر `AppleLanguages`) + إنجليزي — ملف ترجمة واحد `L10n.swift` (841 سطر، قاموس ar/en) |
| الثيم | داكن فقط (`preferredColorScheme(.dark)`) |
| Backend خاص | **لا يوجد** — كل شيء Client + APIs خارجية بمفاتيح المستخدم |
| اختبارات (Tests) | **لا توجد إطلاقاً** — مفيش أي Test Target |
| الخصوصية | كل الملفات داخل Sandbox التطبيق (`Documents/Videos`)، لا تتبّع، المفاتيح في Keychain |

**باختصار في جملة واحدة:** التطبيق مش "مشغّل فيديو" — هو **4 تطبيقات في تطبيق واحد**:
1. متصفح ويب كامل بمانع إعلانات + مستخرج روابط فيديو،
2. مدير تحميل HLS/MP4 باستئناف وكوكيز الجلسات،
3. محطة ترجمة ودبلجة بالذكاء الاصطناعي (تفريغ صوتي → تدقيق → ترجمة → دبلجة صوتية) بأكتر من **35 مزوّد API**،
4. محرك بحث مجمّع (Magic Search) بيبحث في 6 مصادر + 7 محركات ويب في نفس الوقت وبيشغّل/يتحمّل من غير ما يخرج من التبويب.

---

## 2) المعمارية (Architecture)

```
Video2App (@main)
 └─ StateObjects تُحقن في كل الشاشات (EnvironmentObject):
 │    LibraryStore · DownloadManager · BrowserModel · LanguageStore
 │    TranslationManager · FormatConverter · AppLock
 └─ RootView
      ├─ TabRouter (تنقّل برمجي بين التبويبات)
      ├─ V2TabBar مخصص — 6 تبويبات (حل لمشكلة تبويب "More" في iOS عند >5)
      └─ LockScreenView (تغطي كل حاجة لو التطبيق مقفل)
```

| الطبقة | الملفات | الدور |
|---|---|---|
| **Models** | `Models.swift`, `SubtitleModels.swift`, `L10n.swift` | أنواع البيانات + مزوّدو الترجمة/التفريغ + الترجمة ar/en |
| **Services** | 33 ملف في `Video2/Services/` | كل المنطق: متصفح، تحميل، HLS، ترجمة، دبلجة، بحث سحري، تحويل صيغ... |
| **Views** | 17 ملف في `Video2/Views/` | SwiftUI — شاشة لكل تبويب + مشغّلان + Sheets |

**نمط التصميم:** MVVM خفيف — كل Store هو `ObservableObject` بـ `@Published`، والشاشات بترسم منه مباشرة. التخزين ملفات JSON (فهارس) + `UserDefaults` (تفضيلات) + Keychain (مفاتيح وكلمة سر).

### أهم قرارات معمارية ذكية (لازم تتنقل للأندرويد)
1. **شريط تبويبات مخصص** بدل `TabView` — عشان 6 تبويبات من غير "More".
2. **كل الشاشات حيّة دايماً** (`tabVisible`: opacity/hitTesting بس) — حالة المتصفح والمشغّل مبتضيعش عند التنقل.
3. **خط تحميل واحد لكل المصادر**: زرار التحميل من المتصفح، الرادار، والبحث السحري كله بيعدّي على `DownloadManager.enqueueManual/enqueue` — مفيش منطق تحميل مكرر.
4. **وسيط بث محلي `MagicStreamProxy` (127.0.0.1)** بيمرّر Referer/UA/Cookie ويعيد كتابة Playlists الـ HLS — لأن المشغّل الأصلي مبيبعتش Headers مخصصة.
5. **صيد عميق `MagicPageHunter`**: WebView خفي (بره الشاشة بس "مرئي" لـ WebKit) بنفس الكوكيز و الـ UA، بيحقن نفس سكربت الاستخراج + محرك نقر بيحاكي لمسات حقيقية على أزرار Play لمشغلات (video.js / JW / Plyr / Shaka) عشان المواقع اللي مبتبعتش رابط الفيديو غير بعد نقرة.

---

## 3) خريطة الشاشات (6 تبويبات)

| # | التبويب | الأيقونة | الشاشة | وظيفتها في سطر |
|---|---|---|---|---|
| 0 | متصفح | `safari.fill` | `BrowserView` | متصفح متعدد التبويبات + كشف وسائط + رادار ذكي |
| 1 | المكتبة | `play.square.stack.fill` | `LibraryView` | الفيديوهات المحمّلة + مجلدات + استيراد + تشغيل أوفلاين |
| 2 | الترجمة | `captions.bubble.fill` | `TranslateView` | مهام تفريغ/تدقيق/ترجمة الفيديوهات |
| 3 | التحميلات | `arrow.down.circle.fill` | `DownloadsView` | طابور التحميل (تشغيل/إيقاف/استئناف/حذف) |
| 4 | إعدادات | `gearshape.fill` | `SettingsView` | 1,161 سطر: قفل، لغة، إعلانات، 20 مفتاح API، موديلات، تخزين |
| 5 | بحث سحري | `wand.and.stars` | `MagicSearchView` | بحث مجمّع + تشغيل/تحميل داخل التبويب |

شاشات فرعية (Sheets/Covers): كاشف الوسائط `DetectorSheet`، مدير التبويبات `TabsSheet`، الرادار `SmartMediaRadarView`، المشغّل الأوفلاين `OfflinePlayerView` (903 سطر)، مشغّل البحث السحري `MagicPlayerView` (655 سطر)، مراجعة الترجمة `SubtitleReviewView` (644 سطر)، الدبلجة `DubbingView`، تحويل الصيغ `FormatConversionView`، مهمة ترجمة جديدة `NewTranslationView`، منتقي الموديلات `ModelPickerView`.

---

## 4) تحليل الأنظمة الفرعية بالتفصيل

### 4.1 المتصفح + استخراج الوسائط (`BrowserView` + `BrowserModel` + `WebView` + `ExtractorScript`)
- متعدد التبويبات (فتح/إغلاق/تبديل)، شريط عنوان ذكي: لو النص فيه `.` ومفيهوش مسافات → يفتحه كرابط، غير كده → بحث Google.
- Back/Forward بـ Gestures، شريط تقدم تحميل، تحديث/إيقاف.
- **حقن JavaScript** (`ExtractorScript.swift`) بيلتقط: عناصر `<video>/<source>/currentSrc` + اعتراض `fetch` و `XHR` + `PerformanceObserver` + Hook على رد `youtubei/player` الداخلي ليوتيوب + فحص روابط `.mp4/.m4v/.webm/.mov/.m3u8/.mpd`.
- كل نتيجة بتتبعت لتطبيق عن طريق `webkit.messageHandlers.video2` وبتتخزن في `tab.detected` مع **ترتيب بالنقاط (Scoring)**: فيديو كامل +50، مدعوم من المشغّل +20، HTML5 +25، حسب الدقة لحد +40، ومحمي DRM −80.
- فلترة إعلانات مزدوجة: قوائم نطاقات + أنماط URL (`preroll/vast/vpaid...`) + كشف عناصر إعلانية في DOM.
- **لصق رابط يدوي** للتحميل المباشر، وشارة بعدد الوسائط المكتشفة على زرار الكاشف.
- لقطة مصادقة `BrowserAuth.snapshot`: بتجمع كوكيز الجلسة المرتبطة بنطاق الصفحة/الوسائط + UA + Referer وتبعتهم مع التحميل — فالروابط اللي محتاجة تسجيل دخول بتشتغل.

### 4.2 مانع الإعلانات (`AdBlock.swift` + `adblock-rules.json`)
- قواعد WebKit Content Blocker (ملف JSON) + قائمة ~100 نطاق إعلانات/تتبّع محجوبة (DoubleClick, Taboola, Outbrain, PopAds, PropellerAds, ExoClick...).
- وضعان: **Balanced** (افتراضي) / **Strict** + مفتاح منفصل لتصفية إعلانات الفيديو من نتائج الكشف. شغال افتراضياً.

### 4.3 الرادار الذكي (`SmartMediaRadar` + `SmartMediaRadarView`)
- بيجمّع الوسائط المكتشفة في **مجموعات حسب المصدر**، مع فحص فعلي (`MediaProbe`: مدة، حجم، دقة، جودات HLS) وحالات مفهومة للمستخدم + معاينة + اختيار جودة قبل التحميل/التشغيل.

### 4.4 مدير التحميل (`DownloadManager` 409 سطر + `HLSDownloader` 321 سطر)
- تحميل ملفات مباشرة + **HLS متوازٍ** (عدة Segments معاً) مع اختيار جودة (تلقائي / 360 / 480 / 720 / 1080p كحد أقصى).
- **استئناف حقيقي**: حالة الطابور بتتحفظ (`destRelativePath` + فهرس JSON) وبترجع بعد قفل التطبيق؛ إيقاف/استئناف/إلغاء/حذف/إعادة.
- تمرير Auth (كوكيز + UA + Referer + Origin مشتق) — و `DownloadAuth.safariUA` هو UA الآيفون الافتراضي.
- بيشتغل في الخلفية (`UIBackgroundModes: audio, fetch, processing`) وبيسجّل الفيديو في المكتبة + Thumbnail تلقائياً عند الاكتمال.
- حماية DRM: أي مصدر FairPlay/Widevine/PlayReady أو HLS مشفر بترخيص (`cbcs`/`drm/`) حالته `blockedDRM` ومبيتحملش.

### 4.5 المكتبة (`LibraryStore` + `LibraryView`)
- تخزين `Documents/Videos` + فهرس `LibraryIndex { videos, folders }`.
- مجلدات (إنشاء/نقل/حذف)، بحث، قسم **"أكمل المشاهدة"** (آخر موضع `lastPosition`)، استيراد من تطبيق الملفات (أنواع Movie/Audio مسجلة في Info.plist) — والتطبيق مسجّل كـ Alternate Handler لأي ملف فيديو/صوت.
- قائمة سياق لكل فيديو: تشغيل · ترجمة · مراجعة وتعديل الترجمة · دبلجة/إعادة دبلجة · تصدير SRT · تصدير الدبلجة · نقل لمجلد · إعادة تسمية (في Sheet منفصل باتجاه LTR عشان العناوين اللاتينية متتقلبش) · حذف (بيشيل مجلد الـ HLS كامل مع Segments).
- شارات بصرية: في ترجمة؟ في دبلجة؟ نوع الملف ومدته وحجمه.

### 4.6 المشغّل الأوفلاين (`OfflinePlayerView` — أكتر شاشة ناضجة)
- Play/Pause، تقديم/تأخير **±10 ثوانٍ**، Slider بوقت متبقّي، سرعات تشغيل (بتتحفظ آخر سرعة بين الجلسات)، كتم، ملء الشاشة (Fill)، تدوير قسري أفقي/رأسي، قفل أزرار، **PiP**، **AirPlay** (AVRoutePicker)، **مشاركة**، **لقطة Snapshot** بتتحفظ في ألبوم الصور (إذن `NSPhotoLibraryAddUsageDescription`)، **مؤقّت نوم** 15/30/45/60 د، تكرار Loop.
- **ترجمة فورية فوق الفيديو** بـ 4 أوضاع (أصلي / مترجم / ثنائي / إيقاف) + 4 أحجام خط (14/18/23/28).
- **بحث جوه الترجمة** والقفز لأي جملة بضغطة.
- **قراءة الترجمة بصوت** (Auto-Speak): Edge TTS بدون مفتاح، واحتياطي صوت الجهاز (`AVSpeechSynthesizer`).
- **الدبلجة التلقائية**: لو الفيديو ليه ملف دبلجة، الصوت الأصلي بيتكتم والدبلجة بتتزامن باستمرار (Drift Correction)، وزرار موجة للتبديل.
- Now Playing كامل: أزرار شاشة القفل والسماعات (`MPRemoteCommandCenter`) + معلومات التشغيل.

### 4.7 منظومة الترجمة — قلب التطبيق الأهم (`TranslateService` 1,045 + `TranslationManager` 1,129 + `STTService` 1,273 + `AudioPipeline` 1,187 + `SubtitleRefineService` 523 سطر)
خط إنتاج من 5 مراحل لأي فيديو لحد **5 ساعات وأكتر**:

1. **استخراج الصوت**: HLS بيتحول مؤقتاً لـ MP4 الأول، إعادة ترميز AAC أحادي 16kHz، تقطيع لأجزاء **15 دقيقة (~3.6MB)**.
2. **التفريغ STT — 10 مزوّدين** (تفاصيل الجدول تحت).
3. **مراجعة وتدقيق بالذكاء الاصطناعي — 10 خيارات**: تصحيح أخطاء صوتية (Homophones)، استحضار كلمات ناقصة، حذف هلاوس وتكرار، استحضار سياق — بدفعات مع سياق سابق.
4. **ترجمة سياقية بالدفعات — 12 مزوّد** مع **Failover تلقائي** عند نفاد الحصة (الترتيب الافتراضي: Gemini ← Groq ← NVIDIA ← Cerebras ← SambaNova ← Cohere ← Z.ai ← DeepL ← OpenRouter ← Lara ← MyMemory).
5. **المخرجات**: 3 ملفات SRT (أصلي مُراجَع / مترجم / ثنائي اللغة) + شاشة مراجعة وتعديل سطر-سطر + إعادة تدقيق/ترجمة بضغطة + تصدير ومشاركة.

**الاستئناف (Checkpointing):** كل Chunk مفرَّغ وكل دفعة مترجمة بتتحفظ لحظياً على القرص — قفل التطبيق أو قطع النت بيكمّل من نفس النقطة، وإعادة الترجمة للغة تانية **مبتعيدش التفريغ**. ملفات الصوت المؤقتة بتتمسح تلقائياً.
**اللغات:** مصدر: تلقائي + 11 لغة (عربي، إنجليزي، هندي، أردو، فرنسي، تركي، ألماني، إسباني، روسي، فارسي، إندونيسي) — والهدف نفس القائمة.

### 4.8 الدبلجة (`DubbingService` 695 سطر + `TTSProviders` + `EdgeTTSService` + `LocalTTS`)
- 7 خيارات مزوّد: **Edge TTS** (مجاني بدون مفتاح، الافتراضي) · **Groq Orpheus** (6 أصوات عربية سعودية: عبدالله، فهد، سلطان، لولوة، نورة، عائشة) · **SiliconFlow CosyVoice 2** · **ElevenLabs** (الأعلى جودة، مدفوع) · **Azure Neural** · **Gemini TTS** (30 صوت × 24 لغة) · **تلقائي** (بيختار الأفضل المتاح بمفتاح).
- خيارات: لغة هدف، صوت، مطّ الصوت (Stretch-to-fit) لموافقة التوقيت، توازي بسقف آمن حسب المزود (Edge=1) عشان الكراش في الطوال.
- الناتج M4A واحد مدمج بيتحفظ مع الفيديو وبيشتغل تلقائياً في المشغّل.

### 4.9 البحث السحري (`MagicSearchService` 1,678 + `MagicPageHunter` 681 + `MagicResolver` 715 + `MagicStreamProxy` 439 سطر) ⭐ الميزة المميزة
**بحث متوازٍ في 6 مصادر:**

| المصدر | الطريقة (من الكود) | مفتاح؟ |
|---|---|---|
| Internet Archive | `advancedsearch.php` + `/metadata/{id}` — ملفات مباشرة بعدة جودات | لا |
| Dailymotion | API بحث + `player/metadata` لاستخراج HLS بكل الجودات | لا |
| PeerTube | `sepiasearch.org` (بحث فيدرالي) ثم API المضيف `/api/v1/videos/{uuid}` | لا |
| Vimeo | `player.vimeo.com/video/{id}/config` (Progressive + HLS) | لا |
| YouTube | بحث Piped (نسخ متعددة: kavin.rocks، adminforge، private.coffee...) + Invidious (nadeko، f5.si، jing.rocks...) للتسمية والمدة — والتشغيل بيتصاد من الصفحة نفسها بالمتصفح الخفي | لا |
| كل الويب (Meta) | DuckDuckGo (HTML+Lite) + SearXNG (نسخ متعددة) + Qwant + Brave + Mojeek + Bing Videos + Wikimedia Commons | لا |

**لغة أوامر جوه نفس حقل البحث (DSL عربي + إنجليزي)** — `MagicQuery.swift`:
```
اسم الفيلم  مدة:1:36:00  جودة:1080p  موقع:site.com  استبعد:trailer  سنة:1995  مصدر:archive  ترتيب:views  min:20  max:90
```
معادلات إنجليزية (`duration/hd/source/sort...`)، أرقام عربية (١:٣٦) وصيغ مرنة (`2h28m` / `1س30د`)، عبارات بين "" للبحث الحرفي، ولصق **رابط مباشر** بيصيده فوراً. فلتر المدة بيدي كل نتيجة شارة (مطابق/قريب/مبتور) وبيرتب الأقرب للهدف الأول، وفي أزرار سريعة (+5د، +20د...) للحد الأدنى.

**التشغيل جوه التبويب من غير خروج:** `MagicResolver` بيحوّل النتيجة لمصادر (ملف مباشر أو HLS+جودات) → المشغّل بيبدأ مباشرة، مع تبديل جودة أثناء التشغيل **مع حفظ الموضع**، وسلسلة احتياطي: مباشر ← بروكسي محلي ← الجودة التالية ← بدائل. شريط "يُشغَّل الآن" تحت التبويب بيخلي الصوت شغال بعد قفل المشغّل + أزرار قفل الشاشة.

**الصيد العميق (Deep Hunt):** WebView خفي بنفس كوكيز المتصفح الظاهر، تحضير مبكر (`visibilityState=visible` + تحميل العناصر الكسولة)، محرك نقر في كل الـ Frames على أزرار Play ومشغلات (video.js/JW/Plyr/Shaka...) والبوسترات، حاجز تنقّل ضد النوافذ والإعلانات، وبيعاد تلقائياً عند ضغطة "تشغيل" أو "صيد أعمق" حتى لو «الصيد العميق» مقفول وفشل الحل المباشر.
**التحميل** من البحث السحري بيعدي على نفس `DownloadManager` (وسيط `kindHint:` لتسمية روابط CDN بلا امتداد)، وشريط تقدم بيظهر جوه التبويب نفسه.

### 4.10 تحويل الصيغ (`FormatConverter` + `MPEGTSDemuxer` + `LocalFileServer` + 3 خدمات سحابية)
المهمة الأساسية: HLS → ملف واحد (MP4/MOV/M4V) بسلم احتياطي من 4 درجات:
1. **محلي مباشر**: فك MPEG-TS واستخراج AAC من الـ Segments (مع فك **AES-128** لو المفتاح متاح في الـ Playlist) — بدون سيرفر.
2. **محلي AVFoundation** عبر `LocalFileServer` (خادم HTTP محلي بيخدم الـ Playlist للمشغّل/المحوّل).
3. **CloudConvert** (مع نطاقات بديلة وتشخيص 403) ← 4. **ffmpeg-api.com** ← 5. **ConvertAPI**.
مهام بقائمة وتقدم ومراحل، وبتتسجل كناتج في المكتبة.

### 4.11 كتالوج الموديلات والرصيد (`ModelCatalog` 1,551 سطر + `ModelPickerView`)
- 11 مزود مع ميتاداتا كاملة: قدرات (ترجمة/تفريغ/محادثة/Embedding/صور/TTS/ASR/Realtime)، نوع الفوترة (مجاني/حصة تجريبية/مدفوع/حسب الحساب/متوقف)، وحدود موثقة.
- **جلب حي للموديلات** من API المزود نفسه بعد حفظ المفتاح (أهمها OpenRouter: قائمة `:free` الحالية بتتحدث، ولو موديل مختار اتوقف بيتبدل تلقائياً بواحد حي).
- `ProviderUsageStore`: قسم "الرصيد والحدود" بيعرض حالة 13 مزود (Gemini, Groq, SiliconFlow, DeepL, OpenRouter, Cerebras, SambaNova, Deepgram, Azure, NVIDIA, Cohere, Z.ai, Lara).
- من الإعدادات بتقدر تعرف **الموديل المستخدم فعلياً** لكل مهمة (ترجمة/تدقيق/تفريغ/دبلجة Gemini) وتغيّره يدوياً.

### 4.12 الإعدادات والأمان والتخزين
- **قفل التطبيق بكلمة سر** (4+ أحرف، بتتخزن في Keychain، شاشة قفل فوق كل الشاشات عند الإقلاع/القفل اليدوي).
- **20 صف مفتاح API**، كل صف: حفظ في Keychain + زر اختبار اتصال + زر "احصل على المفتاح" بيفتح الصفحة الرسمية. المفاتيح: Groq, Gemini, OpenRouter, Cerebras, SambaNova, NVIDIA, Cohere, Z.ai, Lara, SiliconFlow, ElevenLabs, AssemblyAI, Deepgram, Azure Speech (+ حقل Region منفصل)، DeepL, STT.ai, Speechmatics, CloudConvert, ffmpeg-api, ConvertAPI. (وMyMemory للترجمة شغال بدون مفتاح كملاذ أخير.)
- تفضيلات: جودة التحميل الافتراضية، توازي التفريغ (1–4، افتراضي 3)، تفضيل Edge TTS، وضع مانع الإعلانات.
- **إدارة تخزين**: تقرير أحجام (فيديوهات/مصغرات/ترجمات/تحويلات/الكل) + تنظيف مؤقتات (ببادئات `v2-, hls-, cc-...`) + حذف ملفات يتيمة (Orphans) مش مرتبطة بأي فيديو مسجل.

---

## 5) جداول المزوّدين (من الكود حرفياً)

### STT — التفريغ الصوتي (10)
| المزود | الميزة من الكود | المفتاح |
|---|---|---|
| Groq Whisper Turbo | أجزاء 15د متوازية — الأسرع والأرخص للطوال (لحد 5 ساعات+) | `groq` |
| Gemini 3.5 Transcribe | 85+ لغة، كشف تلقائي، توقيت كلمة-بكلمة، 15د/جزء | `gemini` |
| ElevenLabs Scribe | 99 لغة، ملف واحد كامل، 10 ساعات/شهر مجاناً | `elevenlabs` |
| AssemblyAI Universal | ملف واحد لحد 10 ساعات بدون تقطيع — الأعلى دقة | `assemblyai` |
| Deepgram Nova-3 | Utterances وتوقيتات مقاطع، يدعم العربية | `deepgram` |
| Azure Speech | أجزاء WAV لمنطقة Speech محددة، F0: 5 ساعات/شهر | `azure` |
| STT.ai | 600 دقيقة/شهر مجاناً | `sttai` |
| Speechmatics | 480 دقيقة/شهر، 55+ لغة ولهجات | `speechmatics` |
| SiliconFlow SenseVoice | 50+ لغة (قوي صيني/هندي)، شبه مجاني | `siliconflow` |
| تلقائي | AssemblyAI للطوال أولاً، وباقي السلسلة بعدها | — |

### التدقيق (Refine) — 10 خيارات
Gemini · Groq (GPT-OSS 120B) · Cerebras (~200K token/يوم) · SambaNova (DeepSeek V3.2) · OpenRouter (`:free`) · NVIDIA NIM (Nemotron 3، 10K طلب/يوم) · Cohere (Command A/Aya) · Z.ai (GLM-4.7-Flash، مجاني بالكامل) · تلقائي · **إيقاف**.

### الترجمة النصية (12)
Gemini · Groq · NVIDIA NIM · Cerebras · SambaNova · Cohere · Z.ai · DeepL (مليون حرف Developer) · OpenRouter · Lara (10K حرف/شهر) · **MyMemory (بدون مفتاح، 5K كلمة/يوم)** · تلقائي (Failover بالترتيب ده).

> ملحوظة مهمة للنسخة الجديدة: معظم المزودين دول **OpenAI-Compatible** (نفس صيغة `/chat/completions`) — ده بيسهّل جداً توحيدهم في Client واحد قابل للإعداد في أندرويد.

---

## 6) نموذج البيانات والتخزين

```swift
SavedVideo   : id, title, sourceURL, pageURL, localRelativePath, thumbnailPath,
               kind (MediaKind), createdAt, duration, fileSize, lastPosition,
               folderID, subtitleFiles [orig/target/bilingual], subtitleTargetLang,
               dubbedAudioPath, dubbedInfo {provider, voice, language, totalDuration}
DetectedMedia: url (id), title, kind, mime, qualityLabel, drm, pageURL,
               extractionMethod, duration, byteSize, width, height, variants[HLS]
DownloadJob  : id, media, state(queued/running/paused/failed/completed/blockedDRM),
               progress, bytesWritten, destRelativePath, preferredMaxHeight, auth
MediaKind    : mp4, mov, m4v, 3gp, hls, dash, webm, ts, aac, mp3, wav, mkv, avi, other
DRMKind      : none, fairplay, widevine, playready, encryptedHLS, unknownProtected
```
- الفهارس JSON على القرص (`LibraryIndex`, Jobs, Translation Jobs) + حفظ عند الخلفية (`scenePhase != .active`).
- أنواع مدعومة للاستخراج: `.mp4 .m4v .webm .mov .m3u8 .mpd` + صوت `.mp3/.aac/.wav`.

## 7) الأمان والخصوصية وسياسة DRM
- ✅ لا كسر DRM نهائياً — كشف FairPlay/Widevine/PlayReady/HLS مرخّص بيعرض تحذير وبيمنع التحميل (تشغيل داخل المتصفح بس لو الموقع سمح).
- ✅ المفاتيح وكلمة السر في **Keychain** فقط؛ الصوت بيتبعت مباشرة من جهاز المستخدم لمزوده (مفيش سيرفر وسيط).
- ⚠️ `NSAllowsArbitraryLoads = true` (بيفتح HTTP) — مبرر لمواقع الفيديو، بس خطر محتاج Network Security Config مضبوط في أندرويد.
- ⚠️ كلمة سر القفل بتتخزن وتتقارن **نصياً** في Keychain (`saved == password`) — مقبولة محلياً لكن الأفضل Hash.
- ⚠️ مفتاح CloudConvert الافتراضي في Info.plist قيمة وهمية `"ضع_الـAPI_Key_هنا"` — الإعدادات هي المصدر الحقيقي.
- ⚠️ UA ثابت `iPhone OS 16_4` — في أندرويد لازم UA أندرويد حديث (Chrome Mobile).

## 8) نقاط القوة ⭐
1. **صفر اعتماديات خارجية** — كل شيء بمكتبات النظام، فمفيش مخاطر Supply Chain.
2. **عمق ميزة الترجمة/الدبلجة نادراً ما يوجد في تطبيق شخصي**: Checkpointing، Failover، 3 مخرجات، مراجعة يدوية، حتى 5–10 ساعات.
3. **البحث السحري فكرة منتج قوية**: DSL عربي، صيد عميق بمحاكاة نقر، بروكسي Headers — بيحل مشكلة "الرابط مش بيشتغل بره المتصفح" فعلياً.
4. **احترام قانوني واضح** (DRM) مع بديل مشروع (Internet Archive = ملكية عامة).
5. عربي أولاً (RTL سيستم-وايد) بشكل صحيح، مش ترجمة مترجمة بعدين.

## 9) نقاط الضعف والحدود والديون التقنية ⚠️
| # | المشكلة | الأثر | حلها في أندرويد |
|---|---|---|---|
| 1 | مفيش اختبارات خالص | أي تعديل مخاطرة | Unit + UI Tests من أول يوم |
| 2 | مشغّل AVPlayer مبيشغلش WebM/MKV/AVI/DASH (بتتوسم `avPlayerSupported=false`) | صيغ كتير "بتتحمل بس متتفرجش" | **Media3/ExoPlayer بيحلها كلها أصلاً** |
| 3 | DASH (`.mpd`) بيترصد بس مش مدعوم تشغيل/تحميل | نتائج ناقصة | ExoPlayer DashMediaSource |
| 4 | `OutputFormat` 3 صيغ بس (MP4/MOV/M4V) والتحويل السحابي معتمد على 3 خدمات خارجية بمفاتيح | هشاشة وتكلفة | Media3 Transformer + FFmpeg محلي |
| 5 | ملفات عملاقة: `SettingsView` 1,161 سطر و `MagicSearchService` 1,678 سطر | صيانة صعبة | تقسيم Modules + Repository Pattern |
| 6 | مفاتيح ومزودين كتير (~35) بواجهات إعداد مرهقة | UX تقيل على المستخدم الجديد | Guided Setup + إخفاء المتقدم |
| 7 | iPhone فقط، iOS 16.4+، TrollStore | جمهور محدود جداً | أندرويد APK مباشر = توزيع أسهل |
| 8 | ميزة معلنة مش منفذة: OpenL Translate (قسم "قادم") في الإعدادات | وعد بلا تنفيذ | تتنفذ أو تتمسح |
| 9 | ترجمة الواجهة لغتين بس (ar/en) رغم إن ترجمة الفيديوهات لـ 11 لغة | تناقض بسيط | إضافة لغات واجهة تدريجياً |

## 10) خطة نقل الفكرة لأندرويد (Mapping)

| iOS (الحالي) | Android (المقترح) |
|---|---|
| SwiftUI + Observation | **Kotlin + Jetpack Compose (Material 3)** |
| WKWebView + UserScripts | Android `WebView` + `evaluateJavascript` / JavaScript Interface (`@JavascriptInterface`) |
| AVPlayer / AVPlayerLayer | **Media3 ExoPlayer** (بيشغل MP4/HLS/DASH/WebM/MKV + PiP أصلاً) |
| URLSession + Background Modes | OkHttp + **WorkManager / Foreground Service** للتحميل |
| Keychain | Android Keystore + EncryptedSharedPreferences (أو DataStore + Tink) |
| Documents Sandbox | App-specific storage (`getExternalFilesDir`) + MediaStore للتصدير |
| AVSpeechSynthesizer / Edge TTS WS | Android TextToSpeech + Edge TTS WebSocket (نفس بروتوكول، بدون مفتاح) |
| MPRemoteCommandCenter / Now Playing | MediaSession + Notification MediaStyle |
| Local HTTP Proxy (HLS Rewrite) | OkHttp Interceptor + NanoHTTPD/OkHttp MockWebServer-style محلي — أو ExoPlayer `DataSource` مخصص بيحقن Headers (أنضف) |
| Hidden WKWebView Hunter | Headless WebView بنفس الكوكيز + JS Injection + نقر مُحاكى (`dispatchTouchEvent`) |
| TrollStore IPA | **APK/AAB من Android Studio + GitHub Actions** |

### أولويات التنفيذ المقترحة
- **MVP (أسبوع 1–2):** التبويبات الستة + متصفح + مستخرج + رادار + تحميل (ملف + HLS) + مكتبة + مشغّل ExoPlayer كامل.
- **V1.1:** البحث السحري (Archive Dailymotion PeerTube Vimeo الأول، والويب/يوتيوب بعدها) + البروكسي + الصياد.
- **V1.2:** خط الترجمة كامل (صوت → STT → تدقيق → ترجمة → SRT) + مراجعة + دبلجة + تحويل صيغ.
- **V1.3:** كتالوج موديلات حي + رصيد + قفل تطبيق + إدارة تخزين.

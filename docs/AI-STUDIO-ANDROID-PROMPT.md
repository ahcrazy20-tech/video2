# برومبت AI Studio — بناء نسخة Android كاملة من Video2 (فيديو ٢)

## إزاي تستخدمه (دقيقة واحدة)

1. افتح **aistudio.google.com** → اختار **Create / Build** (أو Chat بموديل Gemini قوي لو عايز كود مشروع كامل).
2. الصق **البرومبت الرئيسي** اللي تحت كله زي ما هو في رسالة واحدة، وابعت.
3. **مهم وتعرفه من الأول:** AI Studio "Build" بطبعه بيولّد تطبيقات ويب (React). البرومبت مكتوب صراحةً إنه يطلّع **مشروع Android Studio كامل (Kotlin + Jetpack Compose)** ملف-ملف — بعد ما يخلّص، انسخ الملفات في مشروع Android Studio فاضي بنفس الهيكل، أو اطلب منه في رسالة متابعة: `Now zip the full project structure and list every file I must create, then output files 1-10` وكمل على دفعات.
4. لو الـ Build بتاع AI Studio أصر يطلع ويب: استخدم **البرومبت البديل (نسخة المحاكاة)** آخر الملف ده — هيطلعلك نموذج موبايل تفاعلي شكله ووظايفه زي التطبيق بالظبط للتجربة والعرض، وبعدين انقل الكود الحقيقي من البرومبت الرئيسي.
5. رسايل المتابعة الجاهزة (بعد أي رد ناقص): موجودة آخر الملف.

---

## 1) البرومبت الرئيسي — انسخه كله والصقه زي ما هو (English — مقصود)

```text
You are a senior Android engineer. Build a COMPLETE, production-grade Android app
that is a faithful Android port of an existing iOS app called "Video2" (فيديو ٢).
Do NOT build a web app or a mockup with fake screens. Output a real Android Studio
project, file by file, that compiles.

=== TECH STACK (mandatory) ===
- Kotlin, Jetpack Compose + Material 3, single-activity (MainActivity) + Compose Navigation.
- minSdk 26, targetSdk 35, package/applicationId: com.ahcrazy.video2
- Media3 ExoPlayer (player, HLS, DASH, UI) for ALL playback. WebView (AndroidX WebKit) for browsing.
- OkHttp for networking, Kotlin Coroutines + Flow, Room (library/jobs), DataStore (prefs),
  EncryptedSharedPreferences/Android Keystore for API keys and the app-lock password (store a SHA-256 hash, never plain text).
- WorkManager + a Foreground Service for downloads/background translation jobs. MediaSession for lock-screen controls.
- No backend of mine: every AI call goes directly from the device to the provider with the USER's own API key.
- Architecture: MVVM + Repository pattern, Hilt DI (or manual DI if simpler), modules:
  :app, :core (models/network/storage), :feature-browser, :feature-library, :feature-downloads,
  :feature-translate, :feature-magic, :feature-settings. Keep every file focused (<400 lines).

=== DESIGN (match the original exactly) ===
- Dark theme ONLY. Colors: background #0F1219, card #1C212E, accent #F25C57 (coral red),
  gold #F2C259, mint #66D9B8, white text, secondary gray text. Rounded cards 16dp, glass-like surfaces.
- Arabic is the DEFAULT language with full RTL layout; English as second language.
  Implement a Strings system (ar/en dictionaries like the original key-based L10n) switchable from Settings
  without restarting (recreate activity on switch is acceptable). All player/download logic must be language-independent.
- Custom bottom bar with SIX tabs (never a "More" tab), icons + tiny labels (9sp):
  1) Browser (safari/compass icon)  2) Library (play-square-stack)  3) Translate (captions)
  4) Downloads (arrow-down-circle) 5) Settings (gear)  6) Magic Search (wand/sparkles).
- Keep all tab screens alive (save/restore state) like a TabView — switching tabs must not lose WebView or player state.
- App name shown in Arabic UI: فيديو ٢. App Lock screen covers everything when locked.

=== DATA MODELS (create exactly these) ===
- MediaKind: MP4, MOV, M4V, THREE_GP(3gp), HLS, DASH, WEBM, TS, AAC, MP3, WAV, MKV, AVI, OTHER
  with: displayName, fileExtension, isCompleteVideo, isLikelyFragment.
  Infer kind from URL suffix + MIME (m3u8→HLS, mpd→DASH, etc.).
- DRMKind: NONE, FAIRPLAY, WIDEVINE, PLAYREADY, ENCRYPTED_HLS, UNKNOWN_PROTECTED (isProtected = != NONE).
- HLSStreamVariant(url, bandwidth, width, height, codecs) → qualityLabel like "1080p" or "2.5 Mbps".
- DetectedMedia(id=url, url, title, kind, mime, qualityLabel, drm, pageUrl, extractionMethod,
  durationSec, byteSize, width, height, probed, variants) → canDownload = !drm.isProtected && !url.startsWith("blob:") && !startsWith("data:").
- SavedVideo(id, title, sourceUrl, pageUrl, localPath, thumbnailPath, kind, createdAt, duration, fileSize,
  lastPositionSec, folderId, subtitleFiles{orig,target,bilingual}, subtitleTargetLang, dubbedAudioPath,
  dubbedInfo{provider, voice, language, totalDuration}).
- LibraryFolder(id, name, createdAt). LibraryIndex(videos, folders) persisted in Room.
- DownloadJob(id, media, state[QUEUED,RUNNING,PAUSED,FAILED,COMPLETED,BLOCKED_DRM], progress, bytesWritten,
  errorMessage, createdAt, destPath, preferredMaxHeight, auth{userAgent, referer, cookie}) — queue must survive process death.
- Subtitle models: SubCue(id, startSec, endSec, originalText, translatedText), SubLang:
  AUTO, AR, EN, HI, UR, FR, TR, DE, ES, RU, FA, ID (each with Arabic name, English name, BCP-47 code).

=== FEATURE 1 — BROWSER TAB ===
- Multi-tab WebView: open/close/switch tabs (Tabs sheet with title+URL+close), smart address bar
  (text containing "." and no spaces → URL with https://, otherwise Google search), back/forward + swipe gestures,
  progress bar, reload/stop, desktop/mobile UA toggle is NOT needed — use a modern Android Chrome mobile UA.
- Inject a media-extractor JavaScript at document start that: scans <video>/<source>/currentSrc,
  hooks fetch and XMLHttpRequest, uses PerformanceObserver, hooks YouTube's internal youtubei/player response,
  detects .mp4/.m4v/.webm/.mov/.m3u8/.mpd + audio, detects DRM signals (FairPlay/Widevine/PlayReady/encrypted HLS/cbcs),
  and posts results to Kotlin via @JavascriptInterface bridge "video2".
- AdBlock: built-in rule list (ship an adblock-rules asset, ~100 ad/tracker hosts: doubleclick, googlesyndication,
  taboola, outbrain, popads, propellerads, exoclick...) with modes Balanced/Strict (default ON, Balanced),
  WebViewClient.shouldInterceptRequest blocking, JS-side ad-element/ad-URL filtering, and a separate toggle
  "Filter video ads" that drops ad media from detection results.
- Score & sort detected media: complete video +50, playable +20, from html5 +25, height bonus up to +40, DRM −80.
  Enrich each item (probe duration/size/resolution/HLS variants) asynchronously.
- Detector bottom sheet: badge on toolbar button with detected count, "only playable" filter toggle,
  per-item card (title, MIME chips, quality label, duration, size, resolution, URL) with Play / Download /
  Download-at-quality (HLS variants) buttons, Clear-all, Close. DRM banner on top when protected content is found.
- "Paste link" manual download dialog + Smart Media Radar button (Feature 2).
- Session auth snapshot: collect WebView cookies matching page/media hosts + UA + Referer → pass to downloads.

=== FEATURE 2 — SMART MEDIA RADAR ===
- Groups detected media by source/host with status per group, counts, last-scan time, rescan button,
  empty/scanning states, per-source preview player, quality picker (Auto + each HLS variant), Play/Download actions.

=== FEATURE 3 — DOWNLOADS TAB + ENGINE ===
- DownloadManager: direct files AND HLS (parallel segment downloads, e.g. 4-6 workers) with max-height selection
  (Auto/1080/720/480/360, default from Settings), pause/resume/cancel/delete/retry, true RESUME after app restart
  (persist jobs + partial files), progress %, bytes written, speed, error messages, Foreground Service + notification
  with pause/cancel actions, completion → register in Library + generate thumbnail.
- Every download carries its DownloadAuth (User-Agent/Referer/Cookie/derived Origin headers).
- DRM-protected sources are refused with state BLOCKED_DRM and an explanatory message. Never bypass DRM.
- Downloads screen: list of jobs with title, status text+color, progress bar, extraction method; empty state.

=== FEATURE 4 — LIBRARY TAB ===
- Grid/list of SavedVideo (thumbnail, title, duration, size, kind, resume position, subtitle badge, dubbed badge),
  search field, "Continue watching" row (items with lastPosition > 0), folders (create/rename/delete, move video to folder, filter by folder),
  import from Files (ActivityResultContracts.OpenDocument for video/* and audio/* + app registered as handler for video/audio intents),
  context menu per video: Play, Translate, Review/Edit subtitles, Dub (or Re-dub), Export SRT, Export dubbed audio,
  Move to folder, Rename (in a separate bottom sheet with LTR text field for Latin titles), Share, Delete
  (deleting an HLS video deletes its whole segment folder). Persist lastPosition from the player.

=== FEATURE 5 — OFFLINE PLAYER (full screen, ExoPlayer) ===
Must include ALL of: play/pause, seek bar with elapsed + remaining time, ±10s skip buttons, playback speeds
(0.5/0.75/1/1.25/1.5/2 — remember last speed), mute, resize/fill toggle, forced landscape/portrait rotation,
controls-lock mode, auto-hide chrome, Picture-in-Picture, Cast/AirPlay-equivalent (MediaRouter cast button),
Share video, Snapshot frame → save to gallery (MediaStore Pictures), Sleep timer (off/15/30/45/60 min), Loop toggle,
file-size label, MediaSession lock-screen & headset controls (play/pause/next-prev seek), background audio keeps playing.
- Subtitles overlay: modes OFF/ORIGINAL/TRANSLATED/BILINGUAL, font sizes 14/18/23/28sp, rendered from parsed SRT cues synced to position.
- Subtitle search: search inside cues, results list with timestamps, tap a hit → seek to it.
- Auto-speak subtitles: read each cue aloud with Edge TTS (no key, WebSocket protocol) falling back to Android TextToSpeech; toggle button.
- Dubbed audio: if the video has dubbedAudioPath, auto-play it INSTEAD of the original audio (mute original track,
  continuous drift-correction sync), waveform toggle button to switch dub on/off.

=== FEATURE 6 — TRANSLATE TAB (the AI subtitle pipeline) ===
Pipeline for any library video up to 5+ hours, EVERY step checkpointed to disk so killing the app resumes exactly where it stopped:
 1) Extract audio: video→ mono AAC 16kHz (Media3 Transformer/MediaExtractor+MediaMuxer; HLS folders first remuxed to a temp MP4),
    split into 15-minute chunks (~3.6MB each).
 2) Speech-to-Text with word/segment timestamps — provider abstraction with these implementations:
    Groq Whisper (whisper-large-v3-turbo, parallel chunks, concurrency setting 1-4 default 3),
    Gemini Transcribe (same Gemini key, auto language 85+ langs, word timings, 15-min chunks),
    ElevenLabs Scribe (single full file, 99 langs), AssemblyAI (single file up to 10h),
    Deepgram Nova-3 (utterances), Azure Speech (short WAV chunks to user region), STT.ai, Speechmatics,
    SiliconFlow SenseVoice. Plus AUTO = best available by keys (AssemblyAI first for long videos).
 3) AI Refinement/proofreading of the transcript in context batches (fix homophones, complete cut words,
    remove hallucinations/repetitions) — providers: Gemini, Groq GPT-OSS 120B, Cerebras, SambaNova, OpenRouter,
    NVIDIA NIM, Cohere, Z.ai, AUTO chain in that order, OFF.
 4) Contextual batch translation (previous-cues context, glossary-consistent) — providers: Gemini, Groq, NVIDIA NIM (Nemotron),
    Cerebras, SambaNova, Cohere (Command A/Aya), Z.ai GLM-4.7-Flash, DeepL (auto free/pro endpoint by :fx key suffix), OpenRouter,
    Lara Translate, MyMemory (NO key, last-resort always available), AUTO with automatic failover to the next provider on quota errors — a job must never die mid-video because one quota ran out.
 5) Outputs: THREE .srt files (original-refined, translated, bilingual) saved next to the video, registered on SavedVideo.
- Most chat providers are OpenAI-compatible (/chat/completions): implement ONE configurable OpenAICompatibleClient
  (baseUrl, key, model) and configure each provider with it where applicable. OpenRouter must fetch its LIVE free (:free)
  model list from its public API and auto-replace a dead selected model.
- UI: jobs list (video title, provider line, phase, progress %, cue count, status text, pause/resume/cancel/delete/retry),
  New-Job sheet: video picker, source language (AUTO + the 11 SubLang), target language, STT provider picker,
  refiner picker, translator picker, "actually used model" line + model-picker button per role.
  Job phases: QUEUED, PREPARING, EXTRACTING, TRANSCRIBING, REFINING, TRANSLATING, SAVING, DONE, PAUSED, CANCELLED, FAILED.
  Temp audio auto-deleted on completion. Re-translating to another language must REUSE the saved transcript (no re-STT).

=== FEATURE 7 — SUBTITLE REVIEW & EDIT SCREEN ===
- Cue list (index, start→end SRT time, original, translation, "— not translated —" placeholder), display-mode segmented
  control (Bilingual/Translated/Original), tap a cue → edit sheet (timings read-only display, edit original + translation text, Save),
  buttons: "Refine all" (choose refiner provider → progress), "Re-translate" (choose translator+target → progress),
  Export/Share each of the 3 SRT files. SRT parser/writer (SubRip timestamps HH:MM:SS,mmm).

=== FEATURE 8 — DUBBING SCREEN ===
- Inputs: target language, provider, voice, stretch-to-fit toggle (time-stretch audio to cue timings), concurrency slider.
- Providers: Microsoft Edge TTS (FREE, no key — implement its WebSocket wss protocol), Groq Orpheus
  (6 Saudi Arabic voices: Abdullah, Fahd, Sultan, Lulwah, Nourah, Aisha + English voices), SiliconFlow CosyVoice 2,
  ElevenLabs (best quality), Azure Neural TTS, Gemini TTS (30 voices, 24 languages, WAV 24kHz), AUTO
  (ElevenLabs → Azure → Gemini → Orpheus/CosyVoice → Edge). Per-provider safe concurrency caps (Edge=1).
- Generate one merged M4A, save as the video's dubbed audio (DubbedInfo on SavedVideo), progress % + status + cancel,
  result card (total duration, cue count, Play/Delete/Export actions).

=== FEATURE 9 — MAGIC SEARCH TAB (aggregate search + in-tab play/download) ===
- ONE search field that parses a query DSL in Arabic AND English (normalize Arabic-Indic digits too):
  name + tokens: مدة:/duration: (target duration), min:/max: (duration bounds), جودة:/quality:/hd: (preferred height),
  موقع:/site: (host filter, multi with |), استبعد:/exclude: (exclude words, | separated), سنة:/year:,
  مصدر:/source: (restrict sources), ترتيب:/sort: (relevance|duration|views), "quoted phrases" = must-contain,
  and pasting a direct URL = hunt that page/file immediately. Quick chips for min-duration (+5m, +20m...).
- Search ALL sources IN PARALLEL (coroutines), one section per source with live counts, filter chips per source,
  duration-match badges per result (Match / Near / Truncated) sorted nearest-first when a target duration is given:
  * Internet Archive: archive.org/advancedsearch.php + /metadata/{id} → direct files, multiple qualities (no key).
  * Dailymotion: api.dailymotion.com/videos search + /player/metadata/video/{id} → HLS qualities (no key).
  * PeerTube: sepiasearch.org/api/v1/search/videos → host /api/v1/videos/{uuid} → MP4/HLS files (no key).
  * Vimeo: player.vimeo.com/video/{id}/config → progressive + HLS (no key).
  * YouTube: search via Piped instances (pipedapi.kavin.rocks, pipedapi.adminforge.de, api.piped.private.coffee...)
    + Invidious instances (inv.nadeko.net, invidious.f5.si...) for title/duration/thumbnails; playback streams are
    hunted from the page itself by the Deep Hunter below (public instances are blocked by YouTube for streams).
  * Web meta-search: DuckDuckGo html+lite, SearXNG instances, Qwant, Brave, Mojeek, Bing Videos, Wikimedia Commons.
- Result cards: thumbnail, title, source badge, duration, views, quality, snippet; actions: Play (in-tab), Open in Browser tab, Deep Hunt, Download by quality.
- Playback INSIDE the tab: a Resolver turns a result into playable sources (direct file or HLS master + variants).
  Player must send the site's Referer/User-Agent/Cookies — implement a custom ExoPlayer DataSource / local proxy
  (127.0.0.1) that injects headers and rewrites HLS playlists. Quality switching preserves position.
  Fallback chain: direct → proxy → next quality → offer alternatives. Mini "Now Playing" bar pinned under the tab
  keeps audio playing after the player sheet closes + lock-screen controls.
- Deep Hunter: hidden off-screen WebView sharing the browser's cookies+UA, injected with the SAME extractor JS +
  a fetch/XHR hook for youtubei/player, early page preparation (force visibilityState=visible, eager-load lazy
  media), and a click engine in ALL frames that simulates real touches (touch/pointer/mouse+click at element coords)
  on known play buttons/overlays (video.js, JW Player, Plyr, Shaka, poster overlays) on a timed schedule (SPA pages),
  with a navigation guard blocking popups/leaving the settled page. Runs automatically on Play/Deep-Hunt/pasted-link
  and after a failed direct resolve, even if the "Deep Hunt" toggle is off. Toggles: Deep Hunt, Auto-prepare.
- Downloads from Magic Search go through the SAME DownloadManager (with a kindHint for extension-less CDN URLs),
  with inline progress shown inside the Magic tab. DRM-looking sources (drm/, cbcs, license) are excluded and the browser is offered instead.
- Help sheet explaining the query syntax, toast messages, per-source error tolerance (one dead source never fails the whole search).

=== FEATURE 10 — FORMAT CONVERSION ===
- Convert HLS library items to a single MP4 (also MOV/M4V labels) LOCALLY first: parse the playlist, download segments
  if needed, extract/remux MPEG-TS → MP4/AAC (handle AES-128 segment keys from the playlist when present) using
  Media3 Transformer; cloud fallback chain ONLY if local fails and keys exist: CloudConvert → ffmpeg-api.com → ConvertAPI.
- Jobs UI: source→target row, phase (QUEUED/CONVERTING/DONE/FAILED), progress %, output size, cancel/delete; output registered in Library.

=== FEATURE 11 — SETTINGS TAB (must be complete) ===
Sections:
 1) App protection: set/change/remove app password (min 4 chars), "Lock now" button, lock on launch when set.
 2) Language: Arabic / English segmented control.
 3) Device/app info: app name "فيديو ٢", package com.ahcrazy.video2, target device line.
 4) Downloads: default max quality picker (Auto/1080/720/480/360), prefer-Edge-TTS-for-subtitle-narration toggle.
 5) AdBlock: enable toggle, mode Balanced/Strict (disabled when off), filter-video-ads toggle, explanatory hint.
 6) Azure Speech region text field + help links (create resource / usage / pricing) and Deepgram signup/pricing links,
    with the documented free tiers noted (Azure F0: 5h STT + 500K TTS chars/month).
 7) API KEYS — one secure row per provider (password field, Save→Keystore, Test-connection button, "Get key" link
    opening the official page, saved indicator). Keys (id → official keys page):
    groq→console.groq.com/keys, gemini→aistudio.google.com/apikey, openrouter→openrouter.ai/settings/keys,
    cerebras→cloud.cerebras.ai, sambanova→cloud.sambanova.ai/apis, nvidia→build.nvidia.com,
    cohere→dashboard.cohere.com/api-keys, zai→open.bigmodel.cn/usercenter/apikeys, lara→laratranslate.com,
    siliconflow→cloud.siliconflow.com/account/ak, elevenlabs→elevenlabs.io/app/settings/api-keys,
    assemblyai→assemblyai.com/app/api-keys, deepgram→console.deepgram.com, azure→portal.azure.com (Speech resource),
    deepl→deepl.com/pro-api, sttai→stt.ai, speechmatics→portal.speechmatics.com,
    cloudconvert→cloudconvert.com, ffmpegapi→ffmpeg-api.com, convertapi→convertapi.com/a/auth.
    Note under the section: keys live only in the device Keystore; MyMemory translation needs no key.
 8) Balance & limits: provider usage/quota cards (best-effort from provider APIs + documented free-tier text)
    for Gemini, Groq, SiliconFlow, DeepL, OpenRouter, Cerebras, SambaNova, Deepgram, Azure, NVIDIA, Cohere, Z.ai, Lara.
 9) Speech & translation preferences: STT provider picker, Refiner picker, Translator picker (each with its detail text),
    "model actually used" line per role + Model Picker screen (fetches the provider's live model list, shows capability
    + billing kind [Free / Trial quota / Paid / Account-dependent / Deprecated], search, select) for
    translation/refinement/transcription/Gemini-TTS roles, STT concurrency stepper (1-4).
 10) "How the subtitle system works" explainer (5 numbered steps) + resumability note.
 11) Extraction methods list (HTML5 video/source · fetch/XHR · Performance log · HLS m3u8 · MP4/WebM),
     DRM policy text, Build info text, Storage report (Videos/Thumbnails/Subtitles/Conversions/Total sizes)
     + "Purge temporary files" + "Purge orphan files" (files not referenced by any SavedVideo) with result message.

=== FEATURE 12 — MODEL CATALOG ===
- ModelProvider enum: GEMINI, GROQ, SILICONFLOW, OPENROUTER, CEREBRAS, SAMBANOVA, OPENAI_COMPAT,
  ELEVENLABS, NVIDIA, COHERE, ZAI — with Arabic/English names, icons, key id, capabilities
  (TRANSLATION, TRANSCRIPTION, CHAT, EMBEDDING, IMAGE, TTS, ASR, REALTIME) and billing metadata.
- ModelPicker screen per (provider, purpose): live-fetch models when the API supports it, mark free/trial/paid,
  remember selection per purpose (ModelSelection in DataStore), graceful message when a provider can't list models.

=== CROSS-CUTTING (all mandatory) ===
- DRM policy everywhere: detect FairPlay/Widevine/PlayReady/encrypted-HLS → warn, never download/decrypt protected content.
- All user media lives in app-private storage; export/share via FileProvider. No analytics/tracking.
- Empty / loading / error / retry states for EVERY list and job. Toasts + inline banners in the UI language.
- Permissions via manifest + runtime where needed: INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS,
  FOREGROUND_SERVICE(+MEDIA_PLAYBACK/DATA_SYNC types), WAKE_LOCK, REQUEST_INSTALL_PACKAGES not needed,
  media read only via system picker (no broad storage permission). Support Android Picture-in-Picture.
- Deliverables: full Gradle project (settings.gradle.kts, root+module build.gradle.kts with a version catalog),
  AndroidManifest.xml (single MainActivity, PiP support, media intent filters for video/*+audio/*, FileProvider),
  themes, the extractor JS + adblock rules as assets, ALL Kotlin/Compose sources, Room schemas, unit tests for
  (MediaKind.infer, SRT parse/write, MagicQuery DSL parser incl. Arabic tokens+digits, HLS playlist parsing/quality picking).
- After the code, finish with: (1) project tree, (2) how to open & run in Android Studio, (3) a feature-parity
  checklist mapping every feature above → the file(s) implementing it, (4) anything you stubbed and why.

Start with the Gradle/Manifest/core models, then implement feature by feature in this order:
core → browser → downloads → library → player → magic search → translate/dub/review → conversion → settings/catalog.
Write complete files, no "// TODO: implement" placeholders in core flows.
```

---

## 2) رسائل متابعة جاهزة (ابعتها لو الرد وقف في النص)

```text
Continue exactly where you stopped. Output the next files completely, no summaries, no placeholders.
```
```text
Now output the complete Gradle setup (settings.gradle.kts, version catalog libs.versions.toml, root and module
build.gradle.kts), AndroidManifest.xml, themes, and the assets (extractor.js, adblock-rules.json) — fully written.
```
```text
List every file in the final project tree, mark which ones you already gave me in full,
then output the missing ones completely. End with the run instructions and the feature-parity checklist.
```
```text
اكتب لي كود الشاشة/feature اللي لسه ناقص: [حط اسمها] — كامل وقابل للتجميع مع باقي المشروع.
```

## 3) البرومبت البديل — نسخة محاكاة تفاعلية (لو هتستخدم AI Studio Build وعايز تشوف الشكل فورًا)

```text
Build a fully interactive Android-phone prototype of the "Video2" (فيديو ٢) video app as a mobile web app
framed inside a realistic Android phone (status bar, gesture bar, Arabic RTL UI, dark theme:
bg #0F1219, cards #1C212E, coral accent #F25C57, gold #F2C259, mint #66D9B8).
SIX bottom tabs: Browser (multi-tab WebView mock with address bar, detected-media badge and detector sheet listing
found MP4/HLS streams with quality picker and DRM banner), Library (grid with folders, continue-watching,
context menu: translate/dub/edit subtitles/dub/export SRT/rename/delete), Translate (job list with phases
extract→transcribe→refine→translate and a New Job sheet with source/target language and STT/refiner/translator
provider pickers), Downloads (queue with progress, pause/resume), Settings (app lock, language ar/en switch that
actually flips the whole UI, AdBlock toggles, 20 API-key rows, model pickers, storage report),
and Magic Search (one field accepting Arabic commands like "مدة:1:36:00 جودة:1080p موقع:archive.org استبعد:trailer",
parallel results from Internet Archive, Dailymotion, PeerTube, Vimeo, YouTube and Web with duration-match badges,
in-tab player with quality switching and a Now-Playing mini bar, Deep Hunt simulation).
Add a full-screen player (play/pause, ±10s, speeds, subtitle modes original/translated/bilingual with font sizes,
subtitle search, snapshot, sleep timer, PiP, lock), subtitle review/edit screen, dubbing screen with provider
and voice pickers (Edge free, Groq Orpheus Arabic voices, Gemini TTS, ElevenLabs...), format conversion jobs,
and realistic sample data (seed 8 videos, 3 translation jobs, search results). Every button must work with
simulated state; Arabic default with a working Arabic/English toggle. Make it feel like the real Android app.
```

## ملحوظات سريعة قبل ما تبني بجد
- البرومبت الرئيسي بيطلب **Android أصلي (ExoPlayer)** — ودي ميزة: المشغّل الأصلي في iOS مبيعرفش يشغّل MKV/WebM/DASH، وExoPlayer هيشغّلهم كلهم في نسخة أندرويد.
- أي مفتاح API في البرومبت هو **مفتاح المستخدم** — التطبيق مفيهوش مفاتيح مدمجة (ماعدا خدمات بدون مفتاح: Edge TTS, MyMemory, ومصادر البحث السحري).
- خليك على نفس سياسة الأصلي: **ممنوع كسر DRM** — كشف وتحذير بس.

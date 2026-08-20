# فيديو ٢ — Video2

تطبيق iOS شخصي (iPhone 11، iOS 16.4، TrollStore) لمتصفح مدمج، استخراج روابط الفيديو، التحميل، والمكتبة الأوفلاين.

لا يكسر DRM. إذا اكتُشف FairPlay / Widevine / PlayReady أو بث مشفّر بنظام ترخيص، يظهر تحذير داخل التطبيق ولا يُحمَّل المحتوى المحمي.

## المتطلبات

- macOS + Xcode 14 أو أحدث (SDK iOS 16)
- جهاز iPhone 11 بـ iOS 16.4 و TrollStore
- Bundle ID: `com.ahcrazy.video2`

## البناء والتثبيت

1. افتح `Video2.xcodeproj` في Xcode.
2. Signing: Personal Team أو أي شهادة؛ لـ TrollStore يمكن تعطيل التوقيع ثم التوقيع بـ `ldid` مع `Video2/Video2.entitlements`.
3. Product → Archive → Distribute → Export IPA.
4. انقل الـ IPA إلى الجهاز وثبّته من TrollStore.

أوامر تقريبية بعد `xcodebuild`:

```bash
xcodebuild -project Video2.xcodeproj -scheme Video2 \
  -configuration Release -sdk iphoneos \
  CODE_SIGNING_ALLOWED=NO -derivedDataPath build
```

## المسارات المدعومة للاستخراج

- عنصر `<video>` / `<source>` و`currentSrc`
- اعتراض `fetch` و`XMLHttpRequest` و`Performance` للموارد
- ملفات `.mp4` `.m4v` `.webm` `.mov` `.m3u8` `.mpd`
- تحميل HLS (قوائم وتشغيلات محلية)
- لصق رابط يدوي
- تحذير DRM دون تجاوز الحماية

## الخصوصية

كل الملفات تُحفظ داخل sandbox التطبيق (`Documents/Videos`). لا يوجد تتبع.

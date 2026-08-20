import SwiftUI

struct SettingsView: View {
    @State private var adblock = AdBlock.isEnabled

    var body: some View {
        NavigationStack {
            List {
                Section("الجهاز") {
                    LabeledContent("التطبيق", value: "فيديو ٢")
                    LabeledContent("الهدف", value: "iPhone 11 · iOS 16.4 · TrollStore")
                    LabeledContent("الحزمة", value: "com.ahcrazy.video2")
                }
                Section("حماية المتصفح") {
                    Toggle("حجب الإعلانات والتتبع", isOn: $adblock)
                        .onChange(of: adblock) { v in
                            AdBlock.isEnabled = v
                            AdBlock.compileIfNeeded()
                        }
                    Text("ثلاث طبقات: قواعد شبكة WebKit، منع نطاقات الإعلان قبل التحميل، وإخفاء عناصر الإعلان في الصفحة مع تعطيل النوافذ المنبثقة.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Section("طرق الاستخراج") {
                    Text("عنصر HTML5 video/source")
                    Text("اعتراض fetch و XMLHttpRequest")
                    Text("سجل أداء الشبكة (Performance)")
                    Text("قوائم HLS (m3u8) مع الأجزاء محلياً")
                    Text("ملف MP4/WebM مباشر")
                    Text("لصق رابط يدوي")
                }
                Section("DRM") {
                    Text("FairPlay و Widevine و PlayReady وبث SAMPLE-AES المرخّص تظهر كتحذير داخل التطبيق. لا يتم كسر الحماية ولن يُحفظ المحتوى المحمي.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Section("البناء بدون ماك") {
                    Text("من GitHub: Actions → Build IPA → نزّل Video2-TrollStore ثم ثبّت الـ IPA من TrollStore.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Section("التخزين") {
                    Text("الملفات داخل مجلد المستندات الخاص بالتطبيق فقط، جاهزة للتشغيل بدون إنترنت عبر AVPlayer.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("إعدادات")
        }
    }
}

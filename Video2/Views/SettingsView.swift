import SwiftUI

// MARK: - إدارة المفاتيح وإعدادات الترجمة

struct APIKeyRow: View {
    let title: String
    let placeholder: String
    let keyID: String
    let hint: String

    @State private var value: String = ""
    @State private var savedFlash = false

    private var stored: Bool { KeychainStore.has(keyID) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Label(title, systemImage: stored ? "key.fill" : "key")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                if stored {
                    Label("محفوظ", systemImage: "checkmark.circle.fill")
                        .font(.caption2)
                        .foregroundStyle(.green)
                        .labelStyle(.titleAndIcon)
                }
            }
            HStack {
                SecureField(placeholder, text: $value)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.footnote)
                Button(stored ? "تحديث" : "حفظ") {
                    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !trimmed.isEmpty else { return }
                    KeychainStore.set(trimmed, for: keyID)
                    value = ""
                    savedFlash = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { savedFlash = false }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .disabled(value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                if stored {
                    Button(role: .destructive) {
                        KeychainStore.delete(keyID)
                    } label: {
                        Image(systemName: "trash")
                    }
                    .controlSize(.small)
                }
            }
            Text(hint)
                .font(.caption2)
                .foregroundStyle(.secondary)
            if savedFlash {
                Text("تم الحفظ في Keychain ✓")
                    .font(.caption2)
                    .foregroundStyle(.green)
            }
        }
        .padding(.vertical, 2)
    }
}

struct SettingsView: View {
    @State private var adblock = AdBlock.isEnabled

    @AppStorage("stt.provider") private var sttProviderRaw: String = STTProviderKind.auto.rawValue
    @AppStorage("tr.provider") private var translatorRaw: String = TranslatorKind.auto.rawValue
    @AppStorage("gemini.model") private var geminiModel: String = "gemini-2.0-flash"
    @AppStorage("stt.concurrency") private var sttConcurrency: Int = 3
    @AppStorage("sub.fontSize") private var fontSize: Int = 18

    private var sttProvider: STTProviderKind {
        STTProviderKind(rawValue: sttProviderRaw) ?? .auto
    }

    private var translator: TranslatorKind {
        TranslatorKind(rawValue: translatorRaw) ?? .auto
    }

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

                // MARK: الترجمة والمفاتيح
                Section {
                    APIKeyRow(title: "مفتاح Groq",
                              placeholder: "gsk_...",
                              keyID: "groq",
                              hint: "للتفريغ الصوتي (Whisper Turbo) وترجمة Llama — فيه شريحة مجانية.")
                    APIKeyRow(title: "مفتاح Gemini",
                              placeholder: "AIza...",
                              keyID: "gemini",
                              hint: "للترجمة النصية السياقية — شريحة مجانية سخية.")
                    APIKeyRow(title: "مفتاح AssemblyAI",
                              placeholder: "من لوحة التحكم",
                              keyID: "assemblyai",
                              hint: "الخيار الأقوى للفيديوهات الطويلة (حتى 10 ساعات بملف واحد) — رصيد تجريبي عند التسجيل.")
                } header: {
                    Text("مفاتيح ترجمة الفيديو")
                } footer: {
                    Text("تُخزَّن المفاتيح في Keychain على جهازك فقط ولا تُرسل لأي جهة غير المزود نفسه. لو أول مرة: الأسرع تبدأ بمفتاح Groq + مفتاح Gemini (كلاهما مجاني).")
                        .font(.caption2)
                }

                Section("تفضيلات التفريغ والترجمة") {
                    Picker("مزود التفريغ", selection: $sttProviderRaw) {
                        ForEach(STTProviderKind.allCases) { p in
                            Text(p.titleAR).tag(p.rawValue)
                        }
                    }
                    Text(STTProviderKind(rawValue: sttProviderRaw)?.detailAR
                         ?? STTProviderKind.auto.detailAR)
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Picker("مزود الترجمة", selection: $translatorRaw) {
                        ForEach(TranslatorKind.allCases) { p in
                            Text(p.titleAR).tag(p.rawValue)
                        }
                    }
                    Text(TranslateService.resolved(provider: translator).detailAR)
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    TextField("اسم موديل Gemini", text: $geminiModel)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.footnote)
                    Text("الافتراضي gemini-2.0-flash. يمكنك تغييره إلى أي موديل متاح لحسابك مثل gemini-2.5-flash.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)

                    Stepper("توازي التفريغ: \(sttConcurrency)", value: $sttConcurrency, in: 1...4)
                    Text("عدد أجزاء الصوت التي تُفرَّغ معاً في نفس اللحظة. قلّله إذا ظهرت أخطاء تجاوز الحد (429).")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Section("الترجمة في المشغّل") {
                    Picker("حجم خط الترجمة", selection: $fontSize) {
                        Text("صغير").tag(14)
                        Text("متوسط").tag(18)
                        Text("كبير").tag(23)
                        Text("كبير جداً").tag(28)
                    }
                    Text("تظهر الترجمة فوق الفيديو في المشغّل — تحكّم في إظهارها وإخفائها من زر CC أثناء التشغيل. متاح: أصلي / مترجم / ثنائي اللغة.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("كيف تعمل الترجمة؟") {
                    bullet("1", "استخراج الصوت من الفيديو (HLS يُحوَّل أولاً) وتقطيعه لأجزاء 15 دقيقة بحجم صغير (~3.6MB).")
                    bullet("2", "تفريغ الكلام لنص بتوقيتات دقيقة عبر Groq Whisper بالتوازي، أو AssemblyAI بملف واحد للفيديوهات حتى 10 ساعات.")
                    bullet("3", "ترجمة سياقية بالدفعات عبر Gemini أو Llama مع الاحتفاظ بمعنى الجملة كاملة لا كلمة كلمة.")
                    bullet("4", "إنتاج ثلاثة ملفات: النص الأصلي، الترجمة، وثنائي اللغة — كلها SRT قابلة للتصدير.")
                    Text("المهمام قابلة للاستئناف: كل جزء مفرَّغ وكل دفعة مترجمة تُحفَظ لحظياً، فلو انقطع الاتصال أو أغلقت التطبيق تُكمل من نفس النقطة. الفيديو 5 ساعات ≈ 20 جزءاً يكتمل عادة في دقائق معدودة مع Groq.")
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
                    Text("الملفات داخل مجلد المستندات الخاص بالتطبيق فقط، جاهزة للتشغيل بدون إنترنت عبر AVPlayer. ملفات الصوت المؤقتة للترجمة تُحذف تلقائياً بعد اكتمالها.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("إعدادات")
        }
    }

    private func bullet(_ n: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text(n)
                .font(.caption2.bold())
                .frame(width: 20, height: 20)
                .background(Circle().fill(V2Theme.accent.opacity(0.2)))
                .foregroundStyle(V2Theme.accent)
            Text(text)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

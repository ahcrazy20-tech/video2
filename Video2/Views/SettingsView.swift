import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var lang: LanguageStore
    @State private var adblock = AdBlock.isEnabled
    @State private var mode = AdBlock.mode

    @AppStorage("stt.provider") private var sttProviderRaw: String = STTProviderKind.auto.rawValue
    @AppStorage("tr.provider") private var translatorRaw: String = TranslatorKind.auto.rawValue
    @AppStorage("gemini.model") private var geminiModel: String = "gemini-2.0-flash"
    @AppStorage("stt.concurrency") private var sttConcurrency: Int = 3

    var body: some View {
        NavigationStack {
            List {
                Section(lang.t("set.lang")) {
                    Picker(lang.t("set.lang"), selection: $lang.code) {
                        Text(lang.t("set.lang.ar")).tag("ar")
                        Text(lang.t("set.lang.en")).tag("en")
                    }
                    .pickerStyle(.segmented)
                }
                Section(lang.t("set.device")) {
                    LabeledContent(lang.t("set.app"), value: "فيديو ٢")
                    LabeledContent(lang.t("set.target"), value: "iPhone 11 · iOS 16.4 · TrollStore")
                    LabeledContent(lang.t("set.bundle"), value: "com.ahcrazy.video2")
                }
                Section(lang.t("set.ad")) {
                    Toggle(lang.t("set.ad.on"), isOn: $adblock)
                        .onChange(of: adblock) { v in
                            AdBlock.isEnabled = v
                            AdBlock.compileIfNeeded()
                        }
                    Picker(lang.t("set.ad.mode"), selection: $mode) {
                        Text(lang.t("set.ad.balanced")).tag("balanced")
                        Text(lang.t("set.ad.strict")).tag("strict")
                    }
                    .disabled(!adblock)
                    .onChange(of: mode) { v in
                        AdBlock.mode = v
                    }
                    Text(lang.t("set.ad.hint"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
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
                    Text("تُخزَّن المفاتيح في Keychain على جهازك فقط. أسرع بداية: مفتاح Groq + مفتاح Gemini (كلاهما فيه شريحة مجانية).")
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
                    Text(TranslateService.resolved(provider: TranslatorKind(rawValue: translatorRaw) ?? .auto).detailAR)
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    TextField("موديل Gemini (gemini-2.0-flash)", text: $geminiModel)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.footnote)

                    Stepper("توازي التفريغ: \(sttConcurrency)", value: $sttConcurrency, in: 1...4)
                    Text("عدد أجزاء الصوت التي تُفرَّغ معاً. قلّله عند ظهور أخطاء 429.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Section("كيف تعمل الترجمة؟") {
                    howBullet("1", "استخراج الصوت من الفيديو (وHLS يُحوَّل أولاً) وتقطيعه لأجزاء 15 دقيقة صغيرة.")
                    howBullet("2", "تفريغ الكلام بتوقيتات دقيقة عبر Groq بالتوازي، أو AssemblyAI بملف واحد حتى 10 ساعات.")
                    howBullet("3", "ترجمة سياقية بالدفعات عبر Gemini أو Llama — معنى الجملة كاملة لا كلمة كلمة.")
                    howBullet("4", "ثلاثة ملفات SRT: أصلي ومترجم وثنائي اللغة — تظهر فوق المشغّل وتُصدَّر بضغطة.")
                    Text("المهمام قابلة للاستئناف لحظياً: كل جزء مفرَّغ وكل دفعة مترجمة تُحفَظ، فلو انقطع الاتصال تُكمل من نفس النقطة. فيديو 5 ساعات ≈ 20 جزءاً.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section(lang.t("set.extract")) {
                    Text("HTML5 video/source")
                    Text("fetch / XMLHttpRequest")
                    Text("Performance log")
                    Text("HLS m3u8")
                    Text("MP4 / WebM")
                }
                Section(lang.t("set.drm")) {
                    Text(lang.t("set.drm.body"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Section(lang.t("set.build")) {
                    Text(lang.t("set.build.body"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Section(lang.t("set.storage")) {
                    Text(lang.t("set.storage.body"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle(lang.t("tab.settings"))
        }
    }

    private func howBullet(_ n: String, _ text: String) -> some View {
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

// MARK: - صف إدخال مفتاح API (يُخزَّن في Keychain)

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

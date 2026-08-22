import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var lang: LanguageStore
     @EnvironmentObject var appLock: AppLock
    @State private var newPassword = ""
    @State private var passwordMessage: String?
    @State private var adblock = AdBlock.isEnabled
    @State private var mode = AdBlock.mode

    @AppStorage("stt.provider") private var sttProviderRaw: String = STTProviderKind.auto.rawValue
    @AppStorage("tr.provider") private var translatorRaw: String = TranslatorKind.auto.rawValue
    @AppStorage("gemini.model") private var geminiModel: String = "gemini-2.0-flash"
    @AppStorage("stt.concurrency") private var sttConcurrency: Int = 3

    var body: some View {
        NavigationStack {
            List {
                 Section("حماية التطبيق") {
                    SecureField("كلمة سر جديدة (4 أحرف على الأقل)", text: $newPassword)
                    Button(appLock.hasPassword ? "تغيير كلمة السر" : "تفعيل كلمة السر") {
                        passwordMessage = appLock.setPassword(newPassword) ? "تم حفظ كلمة السر" : "كلمة السر قصيرة جداً"
                        if passwordMessage == "تم حفظ كلمة السر" { newPassword = "" }
                    }.disabled(newPassword.count < 4)
                    if appLock.hasPassword {
                        Button("قفل التطبيق الآن") { appLock.lock() }
                        Button("إلغاء كلمة السر", role: .destructive) { appLock.removePassword() }
                    }
                    if let passwordMessage { Text(passwordMessage).font(.caption).foregroundStyle(.secondary) }
                }
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
                              hint: "للتفريغ الصوتي (Whisper Turbo) وترجمة GPT-OSS 120B (بديل Llama 3.3 المنتهي في 16 أغسطس 2026) — فيه شريحة مجانية.")
                    APIKeyRow(title: "مفتاح Gemini",
                              placeholder: "AIza...",
                              keyID: "gemini",
                              hint: "للترجمة النصية السياقية — شريحة مجانية سخية.")
                    APIKeyRow(title: "مفتاح AssemblyAI",
                              placeholder: "من لوحة التحكم",
                              keyID: "assemblyai",
                              hint: "الخيار الأقوى للفيديوهات الطويلة (حتى 10 ساعات بملف واحد) — رصيد تجريبي عند التسجيل.")
                    APIKeyRow(title: "مفتاح DeepL",
                              placeholder: "DeepL-...",
                              keyID: "deepl",
                              hint: "لترجمة نصية عالية الجودة — 500 ألف حرف/شهر مجاناً.")
                    APIKeyRow(title: "مفتاح STT.ai",
                              placeholder: "sttai_...",
                              keyID: "sttai",
                              hint: "تفريغ صوتي — 600 دقيقة شهرية مجانية + 100 دقيقة API.")
                    APIKeyRow(title: "مفتاح Speechmatics",
                              placeholder: "مفتاح Speechmatics API",
                              keyID: "speechmatics",
                              hint: "تفريغ صوتي — 480 دقيقة مجانية شهرياً لدقة عالية بأكثر من 55 لغة.")
                     APIKeyRow(title: "مفتاح CloudConvert",
                              placeholder: "cc-...",
                              keyID: "cloudconvert",
                              hint: "تحويل HLS إلى MP4 عبر الإنترنت — 25 تحويل مجاني يومياً (خيار احتياطي).")
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
                        .environment(\.layoutDirection, .leftToRight)

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
                Section {
                    howBullet("Edge TTS", "مجاناً بالكامل بدون مفتاح — نص إلى كلام عبر Microsoft Edge (مجتمع مفتوح).")
                    howBullet("OpenL Translate Speech", "مجاني (1500 حرف/مرة) — ترجمة صوتية مباشرة من فيديو إلى SRT بدون تفريغ منفصل.")
                } header: {
                    Text("خدمات قادمة قريباً")
                } footer: {
                    Text("DeepL و STT.ai و Speechmatics أُضيفت كمفاتيح في الأعلى. هذه الخدمات المتبقية يمكن إضافتها مستقبلاً كمزودين إضافيين.")
                        .font(.caption2)
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

// MARK: - صف إدخال مفتاح API (يُخزَّن في Keychain) مع زر اختبار

struct APIKeyRow: View {
    let title: String
    let placeholder: String
    let keyID: String
    let hint: String

    @State private var value: String = ""
    @State private var savedFlash = false
    @State private var testing = false
    @State private var testResult: String?

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
                    .environment(\.layoutDirection, .leftToRight)
                if testing {
                    ProgressView()
                        .controlSize(.small)
                        .padding(.horizontal, 4)
                } else {
                    Button("اختبار") {
                        runTest()
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .disabled(stored == false && value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
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
                        testResult = nil
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
            if let r = testResult {
                Text(r)
                    .font(.caption.bold())
                    .foregroundStyle(r.hasPrefix("✅") ? Color.green : (r.hasPrefix("❌") ? Color.red : Color.orange))
            }
        }
        .padding(.vertical, 2)
    }

    private func runTest() {
        let typed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = typed.isEmpty ? (KeychainStore.get(keyID) ?? "") : typed
        guard !key.isEmpty else {
            testResult = "⚠️ اكتب المفتاح أولاً أو احفظه ثم اختبر"
            return
        }
        testing = true
        testResult = nil
        let provider = keyID
        Task {
            let result = await KeyTester.verify(provider: provider, key: key)
            testing = false
            testResult = result
        }
    }
}

// MARK: - اختبار صلاحية المفاتيح

enum KeyTester {
    static func verify(provider: String, key: String) async -> String {
        let url: String
        var headers: [String: String] = [:]
        switch provider {
        case "groq":
            url = "https://api.groq.com/openai/v1/models"
            headers = ["Authorization": "Bearer \(key)"]
        case "gemini":
            url = "https://generativelanguage.googleapis.com/v1beta/models?key=\(key)"
        case "assemblyai":
            url = "https://api.assemblyai.com/v2/transcript?limit=1"
            headers = ["Authorization": key]
        case "deepl":
            url = "https://api-free.deepl.com/v2/usage"
            headers = ["Authorization": "DeepL-Auth-Key \(key)"]
        case "sttai":
            url = "https://api.stt.ai/v1/user"
            headers = ["Authorization": "Bearer \(key)"]
        case "speechmatics":
            url = "https://asr.api.speechmatics.com/v2/user"
            headers = ["api-key": key]
               case "cloudconvert":
            url = "https://api.cloudconvert.com/v2/user"
            headers = ["Authorization": "Bearer \(key)"]
        default:
            return "⚠️ مزود غير معروف"
        }
        do {
            let (_, resp) = try await HTTP.request("GET", url, headers: headers, timeout: 30)
            _ = resp
            return "✅ المفتاح يعمل بنجاح"
        } catch let e as APIError {
            if e.status == 403 {
                let b = e.body.lowercased()
                if b.contains("cloudflare") || b.contains("<html") || b.contains("just a moment") {
                    return "🚫 المفتاح سليم غالباً — الشبكة محجوبة عند المزود. بدّل Wi-Fi/البيانات أو جرّب VPN ثم أعد الاختبار"
                }
                if b.contains("organization") || b.contains("disabled") || b.contains("suspended") || b.contains("blocked") {
                    return "❌ الحساب موقوف أو مقيّد عند المزود (403)"
                }
                return "❌ مرفوض (403) — تأكد من المفتاح، ولو سليم بدّل الشبكة (Wi-Fi/بيانات/VPN) وأعد الاختبار"
            }
            if e.status == 401 {
                return "❌ المفتاح غير صحيح أو منتهي (401)"
            }
            if e.status == 404 {
                return "✅ المفتاح مقبول (تجاوز المصادقة)"
            }
            if e.status == 429 {
                return "⚠️ المفتاح يعمل لكن وصلت لحد الطلبات مؤقتاً — جرب بعد دقيقة"
            }
            return "⚠️ استجابة غير متوقعة (رمز \(e.status))"
        } catch {
            return "⚠️ تعذر الاتصال — تحقق من الإنترنت"
        }
    }
}

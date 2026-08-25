import SwiftUI

// MARK: - شاشة مراجعة وتعديل نصوص التفريغ والترجمة

struct SubtitleReviewView: View {
    let video: SavedVideo
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var lang: LanguageStore
    @EnvironmentObject var translations: TranslationManager
    @Environment(\.dismiss) private var dismiss

    @State private var cues: [SubCue] = []
    @State private var searchText = ""
    @State private var selectedDisplayMode: SubtitleDisplayMode = .bilingual
    @State private var editingCue: SubCue? = nil

    // حالة المراجعة بالذكاء الاصطناعي
    @State private var showRefineSheet = false
    @State private var isRefining = false
    @State private var refineProgress: Double = 0
    @State private var refineStatus: String = ""
    @State private var refineError: String? = nil

    // حالة إعادة الترجمة
    @State private var showRetranslateSheet = false
    @State private var isRetranslating = false
    @State private var retranslateProgress: Double = 0
    @State private var retranslateStatus: String = ""
    @State private var retranslateError: String? = nil

    @State private var toastMessage: String?

    var filteredCues: [SubCue] {
        let q = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return cues }
        return cues.filter { cue in
            cue.text.localizedCaseInsensitiveContains(q) ||
            (cue.translated?.localizedCaseInsensitiveContains(q) ?? false)
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                headerBar
                filterAndStatsBar

                if isRefining || isRetranslating {
                    progressBanner
                }

                if cues.isEmpty {
                    emptyState
                } else {
                    cuesList
                }
            }
            .background(V2Theme.bg.ignoresSafeArea())
            .navigationTitle("مراجعة وتعديل النصوص")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(lang.t("nav.done")) { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button {
                            showRefineSheet = true
                        } label: {
                            Label("تدقيق نصوص التفريغ بالذكاء الاصطناعي", systemImage: "sparkles")
                        }

                        Button {
                            showRetranslateSheet = true
                        } label: {
                            Label("إعادة ترجمة النصوص", systemImage: "globe")
                        }

                        Divider()

                        Button {
                            saveChanges()
                        } label: {
                            Label("حفظ ملفات SRT", systemImage: "square.and.arrow.down")
                        }

                        if let urls = subtitleURLs {
                            if let target = urls.target {
                                ShareLink(item: target) {
                                    Label("مشاركة الترجمة (SRT)", systemImage: "square.and.arrow.up")
                                }
                            }
                            if let orig = urls.orig {
                                ShareLink(item: orig) {
                                    Label("مشاركة النص الأصلي (SRT)", systemImage: "square.and.arrow.up")
                                }
                            }
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .searchable(text: $searchText, prompt: "بحث في النصوص الأصلية والمترجمة")
            .sheet(isPresented: $showRefineSheet) {
                RefineActionSheet(cuesCount: cues.count,
                                  sourceLang: SubLang(rawValue: video.subtitleTargetLang ?? "en") ?? .auto) { provider in
                    runAIRefine(provider: provider)
                }
                .environmentObject(lang)
            }
            .sheet(isPresented: $showRetranslateSheet) {
                RetranslateActionSheet(cuesCount: cues.count,
                                       currentLang: SubLang(rawValue: video.subtitleTargetLang ?? "ar") ?? .ar) { targetLang, translator in
                    runRetranslate(target: targetLang, translator: translator)
                }
                .environmentObject(lang)
            }
            .sheet(item: $editingCue) { cue in
                EditCueSheet(cue: cue) { updated in
                    if let idx = cues.firstIndex(where: { $0.id == updated.id }) {
                        cues[idx] = updated
                        saveChanges(silent: true)
                        showToast("تم تحديث السطر ✓")
                    }
                }
            }
            .overlay(alignment: .bottom) {
                if let msg = toastMessage {
                    Text(msg)
                        .font(.footnote.bold())
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(.ultraThinMaterial, in: Capsule())
                        .padding(.bottom, 20)
                }
            }
            .onAppear {
                loadSubtitles()
            }
        }
    }

    // MARK: - شريط المعلومات العلوي

    private var headerBar: some View {
        VStack(alignment: .leading, spacing: 4) {
            BidiText(text: video.title, font: .headline, lineLimit: 1)
            HStack(spacing: 12) {
                Text("\(cues.count) سطر ترجمة")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let target = video.subtitleTargetLang {
                    Text("اللغة المترجمة: \(SubLang(rawValue: target)?.nameAR ?? target)")
                        .font(.caption)
                        .foregroundStyle(V2Theme.gold)
                }
                Spacer()
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 6)
    }

    private var filterAndStatsBar: some View {
        HStack(spacing: 8) {
            Picker("عرض النص", selection: $selectedDisplayMode) {
                Text("ثنائي").tag(SubtitleDisplayMode.bilingual)
                Text("المترجم").tag(SubtitleDisplayMode.translated)
                Text("الأصلي").tag(SubtitleDisplayMode.original)
            }
            .pickerStyle(.segmented)

            Button {
                showRefineSheet = true
            } label: {
                Label("تدقيق AI", systemImage: "sparkles")
                    .font(.caption.bold())
            }
            .buttonStyle(.borderedProminent)
            .tint(V2Theme.gold)
            .controlSize(.small)
            .disabled(isRefining || isRetranslating || cues.isEmpty)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }

    private var progressBanner: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                ProgressView()
                    .controlSize(.small)
                Text(isRefining ? refineStatus : retranslateStatus)
                    .font(.caption.bold())
                    .foregroundStyle(V2Theme.gold)
                Spacer()
                Text("\(Int((isRefining ? refineProgress : retranslateProgress) * 100))%")
                    .font(.caption.monospacedDigit().bold())
            }
            ProgressView(value: isRefining ? refineProgress : retranslateProgress)
                .tint(V2Theme.accent)
        }
        .padding(10)
        .background(V2Theme.card, in: RoundedRectangle(cornerRadius: 10))
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }

    // MARK: - قائمة الجمل

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "captions.bubble.slash")
                .font(.system(size: 48))
                .foregroundStyle(V2Theme.gold)
            Text("لا توجد نصوص ترجمة محفوظة لهذا الفيديو")
                .font(.headline)
            Text("قم ببدء مهمة ترجمة من شاشة الترجمة أو استيراد ملف ترجمة.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Spacer()
        }
    }

    private var cuesList: some View {
        List {
            ForEach(filteredCues) { cue in
                Button {
                    editingCue = cue
                } label: {
                    CueRow(cue: cue, mode: selectedDisplayMode)
                }
                .buttonStyle(.plain)
                .listRowBackground(V2Theme.card)
            }
        }
        .listStyle(.plain)
    }

    private var subtitleURLs: (orig: URL?, target: URL?, bilingual: URL?)? {
        TranslationManager.subtitleURLs(for: video)
    }

    // MARK: - منطق التحميل والحفظ

    private func loadSubtitles() {
        let urls = TranslationManager.subtitleURLs(for: video)
        var origMap: [Int: String] = [:]
        var trMap: [Int: String] = [:]
        var parsedCues: [SubCue] = []

        if let orig = urls.orig {
            let origCues = SubtitleCodec.parseSRTFile(at: orig)
            for c in origCues { origMap[c.id] = c.text }
            parsedCues = origCues
        }

        if let target = urls.target {
            let trCues = SubtitleCodec.parseSRTFile(at: target)
            for c in trCues { trMap[c.id] = c.text }
            if parsedCues.isEmpty { parsedCues = trCues }
        }

        if let bi = urls.bilingual, parsedCues.isEmpty {
            let biCues = SubtitleCodec.parseSRTFile(at: bi)
            parsedCues = biCues
        }

        for i in parsedCues.indices {
            let id = parsedCues[i].id
            if let t = trMap[id] {
                parsedCues[i].translated = t
            }
            if let o = origMap[id] {
                parsedCues[i].text = o
            }
        }

        cues = parsedCues
    }

    private func saveChanges(silent: Bool = false) {
        translations.saveEditedSubtitles(video: video, cues: cues)
        if !silent {
            showToast("تم حفظ ملفات SRT بنجاح ✓")
        }
    }

    private func showToast(_ text: String) {
        toastMessage = text
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
            if toastMessage == text { toastMessage = nil }
        }
    }

    // MARK: - تشغيل التدقيق الذكي

    private func runAIRefine(provider: SubtitleRefinerKind) {
        guard !cues.isEmpty else { return }
        isRefining = true
        refineProgress = 0
        refineStatus = "بدء تدقيق ومراجعة نصوص التفريغ…"
        refineError = nil

        Task {
            do {
                let refined = try await SubtitleRefineService.refineAll(
                    cues: cues,
                    source: .auto,
                    provider: provider,
                    videoTitle: video.title
                ) { p, text in
                    Task { @MainActor in
                        self.refineProgress = p
                        self.refineStatus = text
                    }
                }
                await MainActor.run {
                    self.cues = refined
                    self.saveChanges(silent: true)
                    self.isRefining = false
                    self.showToast("اكتمل تدقيق النصوص بالذكاء الاصطناعي ✓")
                }
            } catch {
                await MainActor.run {
                    self.isRefining = false
                    self.refineError = error.localizedDescription
                    self.showToast("خطأ أثناء التدقيق: \(error.localizedDescription)")
                }
            }
        }
    }

    // MARK: - تشغيل إعادة الترجمة

    private func runRetranslate(target: SubLang, translator: TranslatorKind) {
        guard !cues.isEmpty else { return }
        isRetranslating = true
        retranslateProgress = 0
        retranslateStatus = "بدء إعادة ترجمة النصوص إلى \(target.nameAR)…"
        retranslateError = nil

        Task {
            do {
                let batches = TranslateService.makeBatches(cues: cues)
                var translationsByStart: [Int: [String]] = [:]
                let chain = TranslateService.failoverChain(from: translator)
                guard !chain.isEmpty else {
                    throw APIError(status: 401, body: "أدخل مفتاح الترجمة من الإعدادات.")
                }

                var config = TranslateService.Config(
                    provider: chain[0],
                    model: TranslateService.modelSelection(for: chain[0]),
                    temperature: 0.15,
                    maxOutputTokens: 4096
                )

                var contextTail: [(String, String)] = []
                for (idx, batch) in batches.enumerated() {
                    if Task.isCancelled { throw CancellationError() }
                    let p = Double(idx) / Double(batches.count)
                    await MainActor.run {
                        self.retranslateProgress = p
                        self.retranslateStatus = "ترجمة الدفعة \(idx + 1) من \(batches.count)…"
                    }
                    let out = try await TranslateService.translateBatch(
                        config: config,
                        batch: batch,
                        contextTail: contextTail,
                        source: .auto,
                        target: target,
                        videoTitle: video.title
                    )
                    translationsByStart[batch.startIndex] = out
                    if let last = out.last, !last.isEmpty, let bLast = batch.texts.last {
                        contextTail.append((bLast, last))
                    }
                }

                await MainActor.run {
                    var updated = self.cues
                    for b in batches {
                        guard let arr = translationsByStart[b.startIndex] else { continue }
                        for (offset, cueID) in b.cueIDs.enumerated() {
                            if let i = updated.firstIndex(where: { $0.id == cueID }) {
                                updated[i].translated = arr[offset].isEmpty ? nil : arr[offset]
                            }
                        }
                    }
                    self.cues = updated
                    var v = self.video
                    v.subtitleTargetLang = target.rawValue
                    self.library.update(v)
                    self.saveChanges(silent: true)
                    self.isRetranslating = false
                    self.showToast("اكتملت الترجمة بنجاح إلى \(target.nameAR) ✓")
                }
            } catch {
                await MainActor.run {
                    self.isRetranslating = false
                    self.retranslateError = error.localizedDescription
                    self.showToast("خطأ أثناء الترجمة: \(error.localizedDescription)")
                }
            }
        }
    }
}

// MARK: - صف سطر ترجمة واحد

private struct CueRow: View {
    let cue: SubCue
    let mode: SubtitleDisplayMode

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text("#\(cue.id + 1)")
                    .font(.caption2.bold())
                    .foregroundStyle(V2Theme.gold)
                Text("\(SubtitleCodec.srtTime(cue.start)) ➔ \(SubtitleCodec.srtTime(cue.end))")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
                Spacer()
                Image(systemName: "pencil.circle")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if mode == .original || mode == .bilingual {
                Text(cue.text)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .lineLimit(3)
            }

            if (mode == .translated || mode == .bilingual), let t = cue.translated, !t.isEmpty {
                Text(t)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(mode == .bilingual ? V2Theme.mint : .primary)
                    .lineLimit(3)
            } else if mode == .translated {
                Text("— لم تتم ترجمته بعد —")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 6)
    }
}

// MARK: - نافذة تعديل سطر واحد

private struct EditCueSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State var cue: SubCue
    let onSave: (SubCue) -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section("التوقيت") {
                    HStack {
                        Text("البداية: \(SubtitleCodec.srtTime(cue.start))")
                        Spacer()
                        Text("النهاية: \(SubtitleCodec.srtTime(cue.end))")
                    }
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(.secondary)
                }

                Section("النص الأصلي / المراجع") {
                    TextEditor(text: $cue.text)
                        .frame(minHeight: 80)
                }

                Section("الترجمة") {
                    TextEditor(text: Binding(
                        get: { cue.translated ?? "" },
                        set: { cue.translated = $0.isEmpty ? nil : $0 }
                    ))
                    .frame(minHeight: 80)
                }
            }
            .navigationTitle("تعديل السطر #\(cue.id + 1)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("إلغاء") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("حفظ") {
                        onSave(cue)
                        dismiss()
                    }
                }
            }
        }
    }
}

// MARK: - نافذة اختيار مزود التدقيق الذكي

private struct RefineActionSheet: View {
    @Environment(\.dismiss) private var dismiss
    let cuesCount: Int
    let sourceLang: SubLang
    let onStart: (SubtitleRefinerKind) -> Void

    @AppStorage("refiner.provider") private var selectedProviderRaw: String = SubtitleRefinerKind.auto.rawValue

    var selectedProvider: SubtitleRefinerKind {
        SubtitleRefinerKind(rawValue: selectedProviderRaw) ?? .auto
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Label("مراجعة وتدقيق بالذكاء الاصطناعي", systemImage: "sparkles")
                            .font(.headline)
                            .foregroundStyle(V2Theme.gold)
                        Text("يقوم النموذج بمراجعة جميع نصوص التفريغ (\(cuesCount) سطر) لتصحيح الأخطاء الصوتية وحذف الهلاوس والتكرارات والتأكد من اكتمال سياق الجمل والكلمات.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                }

                Section("مزود خدمة المراجعة المجاني") {
                    Picker("مزود المراجعة", selection: $selectedProviderRaw) {
                        ForEach(SubtitleRefinerKind.allCases.filter { $0 != .off }) { p in
                            Text(p.titleAR).tag(p.rawValue)
                        }
                    }
                    Text(selectedProvider.detailAR)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Section {
                    Button {
                        onStart(selectedProvider)
                        dismiss()
                    } label: {
                        Label("بدء التدقيق والمراجعة الآن", systemImage: "sparkles")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .navigationTitle("تدقيق نصوص التفريغ")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("إلغاء") { dismiss() }
                }
            }
        }
    }
}

// MARK: - نافذة إعادة الترجمة

private struct RetranslateActionSheet: View {
    @Environment(\.dismiss) private var dismiss
    let cuesCount: Int
    let currentLang: SubLang
    let onStart: (SubLang, TranslatorKind) -> Void

    @State private var targetLang: SubLang
    @AppStorage("tr.provider") private var selectedTranslatorRaw: String = TranslatorKind.auto.rawValue

    init(cuesCount: Int, currentLang: SubLang, onStart: @escaping (SubLang, TranslatorKind) -> Void) {
        self.cuesCount = cuesCount
        self.currentLang = currentLang
        self.onStart = onStart
        _targetLang = State(initialValue: currentLang == .ar ? .en : .ar)
    }

    var selectedTranslator: TranslatorKind {
        TranslatorKind(rawValue: selectedTranslatorRaw) ?? .auto
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Label("إعادة الترجمة السريعة", systemImage: "globe")
                            .font(.headline)
                            .foregroundStyle(V2Theme.gold)
                        Text("تتم إعادة ترجمة النصوص المفرّغة والمراجعة مباشرة (\(cuesCount) سطر) بدون الحاجة لإعادة استخراج الصوت أو التفريغ.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                }

                Section("لغة ومزود الترجمة") {
                    Picker("اللغة الهدف", selection: $targetLang) {
                        ForEach(SubLang.allCases.filter { $0 != .auto }) { lang in
                            Text(lang.nameAR).tag(lang)
                        }
                    }

                    Picker("مزود الترجمة", selection: $selectedTranslatorRaw) {
                        ForEach(TranslatorKind.allCases) { p in
                            Text(p.titleAR).tag(p.rawValue)
                        }
                    }
                    Text(selectedTranslator.detailAR)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Section {
                    Button {
                        onStart(targetLang, selectedTranslator)
                        dismiss()
                    } label: {
                        Label("بدء إعادة الترجمة", systemImage: "globe")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .navigationTitle("إعادة الترجمة")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("إلغاء") { dismiss() }
                }
            }
        }
    }
}

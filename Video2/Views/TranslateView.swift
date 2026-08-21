import SwiftUI
import AVFoundation

// MARK: - شاشة مهام الترجمة

struct TranslateView: View {
    @EnvironmentObject var translations: TranslationManager
    @EnvironmentObject var library: LibraryStore

    @State private var showNewJob = false

    var body: some View {
        NavigationStack {
            Group {
                if translations.jobs.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "captions.bubble")
                            .font(.system(size: 48))
                            .foregroundStyle(V2Theme.gold)
                        Text("لا توجد مهام ترجمة")
                            .font(.title3.bold())
                        Text("اختر فيديو من المكتبة وسيقوم التطبيق بتفريغ كلامه وترجمته إلى ملف ترجمة كامل يظهر فوق المشغّل — حتى الفيديوهات الطويلة جداً.")
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 28)
                        Button {
                            showNewJob = true
                        } label: {
                            Label("ترجمة فيديو جديد", systemImage: "plus.circle.fill")
                                .font(.headline)
                        }
                        .buttonStyle(.borderedProminent)
                        .padding(.top, 6)
                    }
                } else {
                    List {
                        ForEach(translations.jobs) { job in
                            TranslationJobRow(job: job)
                        }
                        .onDelete { indexSet in
                            for i in indexSet.sorted(by: >) {
                                translations.delete(translations.jobs[i].id)
                            }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .background(V2Theme.bg)
            .navigationTitle("الترجمة")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showNewJob = true
                    } label: {
                        Label("مهمة جديدة", systemImage: "plus")
                    }
                    .disabled(library.videos.isEmpty)
                }
            }
            .sheet(isPresented: $showNewJob) {
                NewTranslationView(preselected: nil)
                    .environmentObject(translations)
                    .environmentObject(library)
            }
        }
    }
}

// MARK: - صف مهمة

struct TranslationJobRow: View {
    @EnvironmentObject var translations: TranslationManager
    @EnvironmentObject var library: LibraryStore
    let job: TranslationJob

    private var providerTitle: String {
        switch job.sttProvider {
        case .groq: return "Groq"
        case .assemblyai: return "AssemblyAI"
        case .auto: return "تلقائي"
        }
    }

    private var translatorTitle: String {
        TranslateService.providerName(job.translator)
    }

    private var subtitleFileURL: URL? {
        guard job.state == .done,
              let video = library.videos.first(where: { $0.id == job.videoID }),
              let files = video.subtitleFiles,
              let rel = files["target"] ?? files["bilingual"] ?? files["orig"] else { return nil }
        let url = LibraryStore.documents.appendingPathComponent(rel)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: iconFor(job.state))
                    .foregroundStyle(colorFor(job.state))
                Text(job.videoTitle)
                    .font(.headline)
                    .lineLimit(2)
                Spacer()
                Text("من \(job.sourceLang == .auto ? "تلقائي" : job.sourceLang.nameAR) إلى \(job.targetLang.nameAR)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 6) {
                chip("التفريغ: " + providerTitle)
                chip("الترجمة: " + translatorTitle)
                if job.isHLS { chip("HLS") }
                if let detected = TranslationManager.detectedLangNameAR(job.detectedLang) {
                    chip("اللغة: " + detected)
                }
                if job.state == .done && job.cueCount > 0 {
                    chip("\(job.cueCount) جملة")
                }
            }

            if job.state.isBusy {
                VStack(alignment: .leading, spacing: 4) {
                    ProgressView(value: job.progress)
                    HStack {
                        Text(job.statusLineAR)
                        if let note = job.errorMessage, !note.isEmpty, job.state == .transcribing {
                            Text("· " + note).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text("\(Int(job.progress * 100))%")
                            .foregroundStyle(.secondary)
                    }
                    .font(.caption)
                }
            } else {
                BidiText(text: subtitleTextValue)
                    .font(.caption)
                    .foregroundStyle(job.state == .failed ? Color.red : Color.secondary)
            }

            HStack(spacing: 12) {
                if job.state == .paused || job.state == .cancelled || job.state == .failed {
                    Button {
                        translations.resume(job.id)
                    } label: {
                        Label("استئناف", systemImage: "play.fill")
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
                if job.state == .queued || job.state.isBusy {
                    Button(role: .destructive) {
                        translations.cancel(job.id)
                    } label: {
                        Label("إيقاف", systemImage: "stop.fill")
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
                if let url = subtitleFileURL {
                    ShareLink(item: url) {
                        Label("تصدير SRT", systemImage: "square.and.arrow.up")
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
                Spacer()
                Button(role: .destructive) {
                    translations.delete(job.id)
                } label: {
                    Image(systemName: "trash")
                }
                .controlSize(.small)
            }
            .buttonStyle(.bordered)
        }
        .padding(.vertical, 6)
    }

    private func chip(_ text: String) -> some View {
        Text(text)
            .font(.caption2)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Capsule().fill(V2Theme.card))
    }

    private var subtitleTextValue: String {
        switch job.state {
        case .done:
            return "اكتملت — افتح الفيديو من المكتبة وستظهر الترجمة فوق المشغّل."
        case .paused:
            return job.errorMessage ?? "متوقفة مؤقتاً — استأنف من نفس النقطة."
        case .cancelled:
            return "ملغاة — يمكنك استئنافها."
        case .failed:
            return "خطأ: \(job.errorMessage ?? "غير معروف")"
        case .queued:
            return "في الطابور…"
        default:
            return job.statusLineAR
        }
    }

    private func iconFor(_ s: TranslationPhase) -> String {
        switch s {
        case .done: return "checkmark.circle.fill"
        case .failed: return "exclamationmark.triangle.fill"
        case .paused, .cancelled: return "pause.circle.fill"
        case .queued: return "clock.fill"
        default: return "arrow.triangle.2.circlepath.circle.fill"
        }
    }

    private func colorFor(_ s: TranslationPhase) -> Color {
        switch s {
        case .done: return .green
        case .failed: return .red
        case .paused, .cancelled: return .orange
        case .queued: return .secondary
        default: return V2Theme.accent
        }
    }
}

// MARK: - شاشة مهمة ترجمة جديدة

struct NewTranslationView: View {
    let preselected: SavedVideo?

    @EnvironmentObject var translations: TranslationManager
    @EnvironmentObject var library: LibraryStore
    @Environment(\.dismiss) var dismiss

    @State private var selectedVideoID: UUID? = nil
    @State private var source: SubLang = .auto
    @State private var target: SubLang = .ar
    @State private var stt: STTProviderKind = .auto
    @State private var translator: TranslatorKind = .auto
    @State private var validationError: String? = nil

    private var selectedVideo: SavedVideo? {
        if let p = preselected { return p }
        guard let id = selectedVideoID else { return nil }
        return library.videos.first { $0.id == id }
    }

    private var resolvedSTTKind: STTProviderKind {
        TranslationManager.resolvedSTT(stt)
    }

    private var resolvedTranslatorKind: TranslatorKind {
        TranslateService.resolved(provider: translator)
    }

    private var sttKeyMissing: Bool {
        TranslationManager.sttHasKey(resolvedSTTKind) == nil
    }

    private var translatorKeyMissing: Bool {
        !TranslateService.hasKey(for: translator)
    }

    var body: some View {
        NavigationStack {
            Form {
                if preselected == nil {
                    Section("الفيديو") {
                        if library.videos.isEmpty {
                            Text("المكتبة فارغة — حمّل فيديو أولاً.")
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(library.videos) { v in
                                Button {
                                    selectedVideoID = v.id
                                } label: {
                                    HStack {
                                        VStack(alignment: .leading, spacing: 3) {
                                            Text(v.title).lineLimit(2)
                                                .foregroundStyle(.primary)
                                            Text(v.kind.titleAR)
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                        Spacer()
                                        if v.hasSubtitles {
                                            Image(systemName: "captions.bubble.fill")
                                                .foregroundStyle(V2Theme.gold)
                                        }
                                        if selectedVideoID == v.id {
                                            Image(systemName: "checkmark.circle.fill")
                                                .foregroundStyle(V2Theme.accent)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if let v = preselected {
                    Section("الفيديو") {
                        Text(v.title)
                        Text(v.kind.titleAR).font(.caption).foregroundStyle(.secondary)
                    }
                }

                Section("اللغات") {
                    Toggle("كشف اللغة تلقائياً", isOn: Binding(
                        get: { source == .auto },
                        set: { if $0 { source = .auto } else { source = .en } }))
                    Picker("لغة الفيديو (الأصلية)", selection: $source) {
                        ForEach(SubLang.allCases.filter { $0 != .auto }) { l in
                            Text(l.nameAR).tag(l)
                        }
                    }
                    .disabled(source == .auto)
                    Picker("لغة الترجمة", selection: $target) {
                        ForEach(SubLang.allCases.filter { $0 != .auto }) { l in
                            Text(l.nameAR).tag(l)
                        }
                    }
                }

                Section("مزود التفريغ الصوتي") {
                    Picker("المزود", selection: $stt) {
                        ForEach(STTProviderKind.allCases) { p in
                            Text(p.titleAR).tag(p)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                    Text(resolvedSTTKind.detailAR)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("مزود الترجمة") {
                    Picker("المزود", selection: $translator) {
                        ForEach(TranslatorKind.allCases) { p in
                            Text(p.titleAR).tag(p)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                    Text(TranslateService.resolved(provider: translator).detailAR)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                if let err = validationError {
                    Section {
                        Label(err, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                            .font(.footnote)
                    }
                }

                Section {
                    Button {
                        start()
                    } label: {
                        Text("ابدأ الترجمة")
                            .frame(maxWidth: .infinity)
                            .font(.headline)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(selectedVideo == nil || sttKeyMissing || translatorKeyMissing)

                    if selectedVideo == nil && preselected == nil {
                        Text("اختر فيديو من القائمة أولاً.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    if sttKeyMissing {
                        Text("أدخل مفتاح \(resolvedSTTKind == .assemblyai ? "AssemblyAI" : "Groq") من الإعدادات أولاً.")
                            .font(.caption).foregroundStyle(.orange)
                    }
                    if translatorKeyMissing {
                        Text("أدخل مفتاح Gemini أو Groq للترجمة من الإعدادات أولاً.")
                            .font(.caption).foregroundStyle(.orange)
                    }
                    Text("الفيلم الطويل يُقطَّع تلقائياً لأجزاء 15 دقيقة تُفرَّغ بالتوازي، والمهمة قابلة للاستئناف لو انقطعت. اترك التطبيق مفتوحاً أثناء العمل.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("مهمة ترجمة جديدة")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("إلغاء") { dismiss() }
                }
            }
            .onAppear {
                if let p = preselected { selectedVideoID = p.id }
            }
        }
    }

    private func start() {
        guard let video = selectedVideo else { return }
        if let msg = translations.validationMessage(for: video, target: target) {
            validationError = msg
            return
        }
        translations.startJob(for: video,
                              source: source,
                              target: target,
                              stt: stt,
                              translator: translator)
        dismiss()
    }
}

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

// MARK: - صف مهمة ترجمة

/// بطاقة مهمة واحدة مع التقدم وأزرار الإيقاف والاستئناف.
struct TranslationJobRow: View {
    @EnvironmentObject private var translations: TranslationManager
    let job: TranslationJob

    private var progress: Double {
        min(max(job.progress, 0), 1)
    }

    private var canResume: Bool {
        job.state == .paused || job.state == .failed || job.state == .cancelled
    }

    private var canDelete: Bool {
        job.state == .done || job.state == .paused || job.state == .failed || job.state == .cancelled
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: iconName)
                    .font(.title3)
                    .foregroundStyle(iconColor)
                    .frame(width: 28)

                VStack(alignment: .leading, spacing: 3) {
                    BidiText(text: job.videoTitle, font: .headline, lineLimit: 2)
                    Text(providerLine)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)

                Text("\(Int(progress * 100))%")
                    .font(.caption.monospacedDigit().bold())
                    .foregroundStyle(job.state == .done ? .green : V2Theme.gold)
            }

            ProgressView(value: progress)
                .tint(job.state == .failed ? .red : V2Theme.accent)

            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(job.statusLineAR)
                    .font(.caption)
                    .foregroundStyle(job.state == .failed ? .red : .secondary)
                Spacer()
                if job.cueCount > 0 {
                    Text("\(job.cueCount) سطر")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            if let message = job.errorMessage, !message.isEmpty {
                Text(message)
                    .font(.caption2)
                    .foregroundStyle(job.state == .failed ? .red : .secondary)
                    .lineLimit(3)
            }

            HStack(spacing: 8) {
                if canResume {
                    Button {
                        translations.resume(job.id)
                    } label: {
                        Label("استئناف", systemImage: "play.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                }

                if job.state.isBusy {
                    Button {
                        translations.cancel(job.id)
                    } label: {
                        Label("إيقاف مؤقت", systemImage: "pause.fill")
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }

                if canDelete {
                    Spacer(minLength: 0)
                    Button(role: .destructive) {
                        translations.delete(job.id)
                    } label: {
                        Label("حذف", systemImage: "trash")
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
            }
        }
        .padding(.vertical, 8)
        .listRowBackground(V2Theme.card)
    }

    private var providerLine: String {
        let stt = job.sttProvider.titleAR
        let translator = TranslateService.providerName(job.translator)
        return "\(stt) · \(translator) · \(job.targetLang.nameAR)"
    }

    private var iconName: String {
        switch job.state {
        case .done: return "checkmark.circle.fill"
        case .failed: return "exclamationmark.triangle.fill"
        case .paused, .cancelled: return "pause.circle.fill"
        case .queued: return "clock.fill"
        default: return "waveform.and.mic"
        }
    }

    private var iconColor: Color {
        switch job.state {
        case .done: return .green
        case .failed: return .red
        case .paused, .cancelled: return .orange
        default: return V2Theme.gold
        }
    }
}

// MARK: - إنشاء مهمة ترجمة

/// شاشة إعداد مهمة تفريغ وترجمة لفيديو محفوظ في المكتبة.
struct NewTranslationView: View {
    @EnvironmentObject private var translations: TranslationManager
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.dismiss) private var dismiss

    @AppStorage("stt.provider") private var sttProviderRaw: String = STTProviderKind.auto.rawValue
    @AppStorage("tr.provider") private var translatorRaw: String = TranslatorKind.auto.rawValue

    private let preselected: SavedVideo?
    @State private var selectedVideoID: UUID
    @State private var source: SubLang = .auto
    @State private var target: SubLang = .ar
    @State private var didAttemptStart = false

    init(preselected: SavedVideo?) {
        self.preselected = preselected
        _selectedVideoID = State(initialValue: preselected?.id ?? UUID())
    }

    private var selectedVideo: SavedVideo? {
        if let preselected {
            return preselected
        }
        return library.videos.first { $0.id == selectedVideoID }
    }

    private var sttProvider: STTProviderKind {
        STTProviderKind(rawValue: sttProviderRaw) ?? .auto
    }

    private var translator: TranslatorKind {
        TranslatorKind(rawValue: translatorRaw) ?? .auto
    }

    private var resolvedSTT: STTProviderKind {
        TranslationManager.resolvedSTT(sttProvider)
    }

    private var resolvedTranslator: TranslatorKind {
        TranslateService.resolved(provider: translator)
    }

    private var startError: String? {
        guard let video = selectedVideo else {
            return "اختر فيديو من المكتبة أولاً."
        }
        guard FileManager.default.fileExists(atPath: video.localURL.path) else {
            return "ملف الفيديو غير موجود على الجهاز."
        }
        if source != .auto && source == target {
            return "اختر لغة ترجمة مختلفة عن اللغة الأصلية."
        }
        if TranslationManager.sttHasKey(sttProvider) == nil {
            let name = resolvedSTT.titleAR
            return "أدخل مفتاح \(name) من الإعدادات قبل بدء التفريغ."
        }
        if !TranslateService.hasKey(for: translator) {
            let name = resolvedTranslator == .auto ? "مزود الترجمة" : resolvedTranslator.titleAR
            return "أدخل مفتاح \(name) من الإعدادات قبل بدء الترجمة."
        }
        if let message = translations.validationMessage(for: video, target: target) {
            return message
        }
        return nil
    }

    private var canStart: Bool {
        startError == nil
    }

    var body: some View {
        NavigationStack {
            Form {
                videoSection
                languageSection
                providerSection

                Section {
                    Button {
                        begin()
                    } label: {
                        Label("بدء الترجمة", systemImage: "captions.bubble.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!canStart)

                    if didAttemptStart, let message = startError {
                        Label(message, systemImage: "exclamationmark.triangle.fill")
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                } footer: {
                    Text("يُحفظ كل جزء فور اكتماله، ويمكن إيقاف المهمة واستئنافها لاحقاً من نفس النقطة.")
                        .font(.caption2)
                }
            }
            .scrollContentBackground(.hidden)
            .background(V2Theme.bg)
            .navigationTitle("ترجمة فيديو جديد")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("إلغاء") { dismiss() }
                }
            }
            .onAppear {
                if preselected == nil,
                   !library.videos.contains(where: { $0.id == selectedVideoID }),
                   let first = library.videos.first {
                    selectedVideoID = first.id
                }
            }
        }
    }

    private var videoSection: some View {
        Section("الفيديو") {
            if let preselected {
                VStack(alignment: .leading, spacing: 5) {
                    BidiText(text: preselected.title, font: .headline)
                    Text(preselected.kind.titleAR)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } else if library.videos.isEmpty {
                Label("لا توجد فيديوهات محفوظة في المكتبة.", systemImage: "film.slash")
                    .foregroundStyle(.secondary)
            } else {
                Picker("الفيديو", selection: $selectedVideoID) {
                    ForEach(library.videos) { video in
                        Text(video.title)
                            .lineLimit(1)
                            .tag(video.id)
                    }
                }
            }

            if let video = selectedVideo {
                HStack {
                    Label(video.kind.titleAR, systemImage: "film")
                    Spacer()
                    Text(ByteCountFormatter.string(fromByteCount: video.fileSize, countStyle: .file))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var languageSection: some View {
        Section("اللغات") {
            Picker("اللغة الأصلية", selection: $source) {
                ForEach(SubLang.allCases) { language in
                    Text(language.nameAR).tag(language)
                }
            }

            Picker("لغة الترجمة", selection: $target) {
                ForEach(SubLang.allCases.filter { $0 != .auto }) { language in
                    Text(language.nameAR).tag(language)
                }
            }
        }
    }

    private var providerSection: some View {
        Section("مزودو الخدمة") {
            Picker("التفريغ الصوتي", selection: $sttProviderRaw) {
                ForEach(STTProviderKind.allCases) { provider in
                    Text(provider.titleAR).tag(provider.rawValue)
                }
            }
            Text(sttProvider == .auto ? STTProviderKind.auto.detailAR : sttProvider.detailAR)
                .font(.caption2)
                .foregroundStyle(.secondary)

            Picker("الترجمة النصية", selection: $translatorRaw) {
                ForEach(TranslatorKind.allCases) { provider in
                    Text(provider.titleAR).tag(provider.rawValue)
                }
            }
            Text(translator == .auto ? TranslatorKind.auto.detailAR : translator.detailAR)
                .font(.caption2)
                .foregroundStyle(.secondary)

            if let message = startError, !didAttemptStart {
                Label(message, systemImage: "info.circle")
                    .font(.caption2)
                    .foregroundStyle(.orange)
            }
        }
    }

    private func begin() {
        didAttemptStart = true
        guard canStart, let video = selectedVideo else { return }

        // إذا كانت هناك مهمة متوقفة لنفس الإعدادات، استأنفها بدلاً من إنشاء نسخة ثانية.
        if let paused = translations.jobs.first(where: {
            $0.videoID == video.id &&
            $0.state == .paused &&
            $0.sourceLang == source &&
            $0.targetLang == target
        }) {
            translations.resume(paused.id)
        } else {
            translations.startJob(for: video,
                                  source: source,
                                  target: target,
                                  stt: sttProvider,
                                  translator: translator)
        }
        dismiss()
    }
}

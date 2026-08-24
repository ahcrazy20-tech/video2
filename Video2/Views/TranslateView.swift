import SwiftUI
import AVFoundation

// MARK: - شاشة مهام الترجمة

struct TranslateView: View {
    @EnvironmentObject var translations: TranslationManager
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var lang: LanguageStore
    @State private var showNewJob = false

    var body: some View {
        NavigationStack {
            Group {
                if translations.jobs.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "captions.bubble")
                            .font(.system(size: 48))
                            .foregroundStyle(V2Theme.gold)
                        Text(lang.t("tv.empty.title"))
                            .font(.title3.bold())
                        Text(lang.t("tv.empty.hint"))
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 28)
                        Button {
                            showNewJob = true
                        } label: {
                            Label(lang.t("tv.empty.new"), systemImage: "plus.circle.fill")
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
            .navigationTitle(lang.t("tv.title"))
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showNewJob = true
                    } label: {
                        Label(lang.t("tv.jobs.new"), systemImage: "plus")
                    }
                    .disabled(library.videos.isEmpty)
                }
            }
            .sheet(isPresented: $showNewJob) {
                NewTranslationView(preselected: nil)
                    .environmentObject(translations)
                    .environmentObject(library)
                    .environmentObject(lang)
            }
        }
    }
}

// MARK: - صف مهمة ترجمة

struct TranslationJobRow: View {
    @EnvironmentObject private var translations: TranslationManager
    @EnvironmentObject private var lang: LanguageStore
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

    private func isLiveTranslationStatus(_ message: String) -> Bool {
        job.state == .translating &&
        (message.contains("جارٍ إرسال") || message.contains("اكتملت الدفعة"))
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
                    Text("\(job.cueCount) \(lang.t("tv.lines"))")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            if let message = job.errorMessage, !message.isEmpty, !isLiveTranslationStatus(message) {
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
                        Label(lang.t("tv.resume"), systemImage: "play.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                }

                if job.state.isBusy {
                    Button {
                        translations.cancel(job.id)
                    } label: {
                        Label(lang.t("tv.pause"), systemImage: "pause.fill")
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }

                if canDelete {
                    Spacer(minLength: 0)
                    Button(role: .destructive) {
                        translations.delete(job.id)
                    } label: {
                        Label(lang.t("tv.delete"), systemImage: "trash")
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

struct NewTranslationView: View {
    @EnvironmentObject private var translations: TranslationManager
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var lang: LanguageStore
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
            return lang.t("tv.new.no.videos")
        }
        guard FileManager.default.fileExists(atPath: video.localURL.path) else {
            return lang.t("err.file.notfound")
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
                        Label(lang.t("tv.new.start"), systemImage: "captions.bubble.fill")
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
                    Text(lang.t("tv.new.footer"))
                        .font(.caption2)
                }
            }
            .scrollContentBackground(.hidden)
            .background(V2Theme.bg)
            .navigationTitle(lang.t("tv.new.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(lang.t("nav.cancel")) { dismiss() }
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
        Section(lang.t("tv.new.video")) {
            if let preselected {
                VStack(alignment: .leading, spacing: 5) {
                    BidiText(text: preselected.title, font: .headline)
                    Text(preselected.kind.titleAR)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } else if library.videos.isEmpty {
                Label(lang.t("tv.new.no.videos"), systemImage: "film.slash")
                    .foregroundStyle(.secondary)
            } else {
                Picker(lang.t("tv.new.video"), selection: $selectedVideoID) {
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
        Section(lang.t("tv.new.languages")) {
            Picker(lang.t("tv.new.source.lang"), selection: $source) {
                ForEach(SubLang.allCases) { language in
                    Text(language.nameAR).tag(language)
                }
            }

            Picker(lang.t("tv.new.target.lang"), selection: $target) {
                ForEach(SubLang.allCases.filter { $0 != .auto }) { language in
                    Text(language.nameAR).tag(language)
                }
            }
        }
    }

    private var providerSection: some View {
        Section(lang.t("tv.new.providers")) {
            Picker(lang.t("tv.new.stt"), selection: $sttProviderRaw) {
                ForEach(STTProviderKind.allCases) { provider in
                    Text(provider.titleAR).tag(provider.rawValue)
                }
            }
            Text(sttProvider == .auto ? STTProviderKind.auto.detailAR : sttProvider.detailAR)
                .font(.caption2)
                .foregroundStyle(.secondary)

            Picker(lang.t("tv.new.translator"), selection: $translatorRaw) {
                ForEach(TranslatorKind.allCases) { provider in
                    Text(provider.titleAR).tag(provider.rawValue)
                }
            }
            Text(translator == .auto ? TranslatorKind.auto.detailAR : translator.detailAR)
                .font(.caption2)
                .foregroundStyle(.secondary)

            VStack(alignment: .leading, spacing: 6) {
                Label("سيُستخدم فعلياً", systemImage: "cpu")
                    .font(.caption.bold())
                    .foregroundStyle(V2Theme.gold)
                Text("التفريغ: \(resolvedSTT.titleAR)")
                    .font(.caption2)
                BidiText(text: sttModelName, font: .caption.monospaced(), lineLimit: 2)
                Text("الترجمة: \(resolvedTranslator.titleAR)")
                    .font(.caption2)
                BidiText(text: translatorModelName, font: .caption.monospaced(), lineLimit: 2)
            }
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(V2Theme.card, in: RoundedRectangle(cornerRadius: 10))

            if let message = startError, !didAttemptStart {
                Label(message, systemImage: "info.circle")
                    .font(.caption2)
                    .foregroundStyle(.orange)
            }
        }
    }

    private var sttModelName: String {
        switch resolvedSTT {
        case .groq:
            return ModelSelection.selected(purpose: "stt", provider: .groq, fallback: "whisper-large-v3-turbo")
        case .siliconflow:
            return ModelSelection.selected(purpose: "stt", provider: .siliconflow, fallback: "FunAudioLLM/SenseVoiceSmall")
        case .assemblyai: return "universal"
        case .sttai: return "large-v3-turbo"
        case .speechmatics: return "default"
        case .auto: return "—"
        }
    }

    private var translatorModelName: String {
        switch resolvedTranslator {
        case .gemini:
            return ModelSelection.selected(purpose: "translator", provider: .gemini, fallback: TranslateService.defaultGeminiModel)
        case .groqLLM:
            return ModelSelection.selected(purpose: "translator", provider: .groq, fallback: "openai/gpt-oss-120b")
        case .openRouter:
            return ModelSelection.selected(purpose: "translator", provider: .openRouter, fallback: TranslateService.defaultOpenRouterModel)
        case .cerebras:
            return ModelSelection.selected(purpose: "translator", provider: .cerebras, fallback: TranslateService.defaultCerebrasModel)
        case .sambaNova:
            return ModelSelection.selected(purpose: "translator", provider: .sambaNova, fallback: TranslateService.defaultSambaNovaModel)
        case .deepL: return "DeepL API"
        case .auto: return "—"
        }
    }

    private func begin() {
        didAttemptStart = true
        guard canStart, let video = selectedVideo else { return }

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

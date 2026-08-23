import SwiftUI
import AVFoundation

// MARK: - شاشة الدبلجة العربية

struct DubbingView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var lang: LanguageStore
    let video: SavedVideo
    let onCompleted: (String, DubbingResult) -> Void   // يُستدعى مع مسار ملف الدبلجة والنتيجة

    @ObservedObject private var service: DubbingService
    @State private var targetLang: SubLang = .ar
    @State private var provider: DubbingProvider = .auto
    @State private var selectedVoiceID: String = ""
    @State private var stretchToFit = true
    @State private var concurrency: Double = 3
    @State private var dubbingStarted = false
    @State private var dubbingCompleted = false
    @State private var lastResult: DubbingResult?
    @State private var error: String?

    init(video: SavedVideo, onCompleted: @escaping (String, DubbingResult) -> Void) {
        self.video = video
        self.onCompleted = onCompleted
        self._service = ObservedObject(initialValue: DubbingService.shared)
    }


    private var availableVoices: [DubbingVoice] {
        DubbingVoice.voices(for: targetLang, provider: serviceResolveProvider(provider))
    }

    private var currentVoice: DubbingVoice? {
        if !selectedVoiceID.isEmpty {
            return availableVoices.first { $0.id == selectedVoiceID }
        }
        return DubbingVoice.best(for: targetLang, provider: serviceResolveProvider(provider))
    }

    private var canStart: Bool {
        guard let v = currentVoice else { return false }
        return serviceResolveProvider(provider).isAvailable && v.naturalness > 0
    }

    var body: some View {
        NavigationStack {
            Form {
                headerSection
                languageSection
                providerSection
                voiceSection
                optionsSection
                startSection
            }
            .scrollContentBackground(.hidden)
            .background(V2Theme.bg)
            .navigationTitle("دبلجة \(targetLang.nameAR)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("إلغاء") { dismiss() }
                }
            }
            .onChange(of: provider) { _ in
                selectedVoiceID = ""
            }
            .onChange(of: targetLang) { _ in
                selectedVoiceID = ""
            }
        }
    }

    // MARK: - الأقسام

    private var headerSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                BidiText(text: video.title, font: .headline)
                Text("سيتم توليد ملف صوتي بدبلجة عربية للفيديو، يُشغَّل بدل الصوت الأصلي.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let info = video.dubbedInfo {
                    Label("دبلجة سابقة: \(DateFormatter.localizedString(from: info.createdAt, dateStyle: .short, timeStyle: .short))",
                          systemImage: "waveform.badge.mic")
                        .font(.caption2)
                        .foregroundStyle(V2Theme.gold)
                }
            }
        }
    }

    private var languageSection: some View {
        Section("اللغة المستهدفة") {
            Picker("لغة الدبلجة", selection: $targetLang) {
                ForEach(SubLang.allCases.filter { $0 != .auto }) { l in
                    Text(l.nameAR).tag(l)
                }
            }
        }
    }

    private var providerSection: some View {
        Section("مزود الدبلجة") {
            Picker("المزود", selection: $provider) {
                ForEach(DubbingProvider.allCases) { p in
                    HStack {
                        Text(p.titleAR)
                        if !p.isAvailable && p != .auto {
                            Spacer()
                            Text("يحتاج مفتاح")
                                .font(.caption2)
                                .foregroundStyle(.orange)
                        }
                    }
                    .tag(p)
                }
            }
            Text(provider.detailAR)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private var voiceSection: some View {
        Section("الصوت") {
            if availableVoices.isEmpty {
                Label("لا توجد أصوات لهذه اللغة مع المزود المختار. جرّب مزوداً آخر.",
                      systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.orange)
            } else {
                Picker("الصوت", selection: $selectedVoiceID) {
                    Text("(الأفضل تلقائياً)").tag("")
                    ForEach(availableVoices) { v in
                        HStack {
                            Image(systemName: v.gender == .female ? "person.crop.circle.fill" : "person.crop.circle")
                                .foregroundStyle(v.gender == .female ? .pink : .blue)
                            Text(v.name)
                            Spacer()
                            Text(v.language)
                                .font(.caption2.monospaced())
                                .foregroundStyle(.secondary)
                            stars(v.naturalness)
                        }
                        .tag(v.id)
                    }
                }
                .pickerStyle(.inline)
                .labelsHidden()

                if let v = currentVoice {
                    HStack(spacing: 6) {
                        Text("الصوت المختار:")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        Text(v.displayAR)
                            .font(.caption.weight(.semibold))
                        Spacer()
                        Button {
                            previewVoice(v)
                        } label: {
                            Label("استماع", systemImage: "play.circle.fill")
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    }
                }
            }
        }
    }

    private var optionsSection: some View {
        Section("الخيارات") {
            Toggle("تسريع الصوت ليتناسب مع التوقيت", isOn: $stretchToFit)
            HStack {
                Text("التوازي")
                Spacer()
                Text("\(Int(concurrency))")
                    .font(.body.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            Slider(value: $concurrency, in: 1...6, step: 1)
            Text("تقليل التوازي عند ظهور أخطاء 429. زيادته تسرّع الدبلجة لكن تستهلك رصيد أكثر.")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private var startSection: some View {
        if service.inProgress {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        ProgressView(value: service.progress)
                            .progressViewStyle(.linear)
                        Text("\(Int(service.progress * 100))%")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    Text(service.statusText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button(role: .destructive) {
                        service.cancel()
                    } label: {
                        Label("إلغاء", systemImage: "stop.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
            }
        } else if dubbingCompleted, let result = lastResult {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Label("اكتملت الدبلجة", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                        .font(.headline)
                    HStack {
                        Image(systemName: "waveform")
                        Text("المدة: \(formatTime(result.totalDuration))")
                        Spacer()
                        Text("عدد الجمل: \(result.cuesCount)")
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    HStack(spacing: 8) {
                        Button {
                            playPreview(url: result.audioFileURL)
                        } label: {
                            Label("استماع", systemImage: "play.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        ShareLink(item: result.audioFileURL) {
                            Label("مشاركة", systemImage: "square.and.arrow.up")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                    Button {
                        attachToVideo(result)
                    } label: {
                        Label("اعتماد الدبلجة وربطها بالفيديو", systemImage: "checkmark.seal.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
        } else {
            Section {
                Button {
                    startDubbing()
                } label: {
                    Label("بدء الدبلجة", systemImage: "waveform.badge.mic.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!canStart || !hasTranslations)
                if !hasTranslations {
                    Label("أتمم الترجمة أولاً — الدبلجة تحتاج جملاً مترجمة.",
                          systemImage: "exclamationmark.triangle")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
                if let err = error {
                    Label(err, systemImage: "xmark.octagon")
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            } footer: {
                Text("سيُحفظ الملف الصوتي في مجلد الدبلجة داخل التطبيق، ويمكنك تشغيله بدلاً من الصوت الأصلي من المشغّل.")
                    .font(.caption2)
            }
        }
    }

    // MARK: - الأفعال

    private var hasTranslations: Bool {
        guard let files = video.subtitleFiles else { return false }
        guard let targetPath = files["target"] else { return false }
        let url = LibraryStore.documents.appendingPathComponent(targetPath)
        guard let content = try? String(contentsOf: url, encoding: .utf8) else { return false }
        return content.contains("-->") && content.count > 50
    }

    private func startDubbing() {
        guard let srtURL = subtitleFileForDubbing() else { return }
        let parsedCues = SubtitleCodec.parseSRTFile(at: srtURL)
        guard !parsedCues.isEmpty else { return }
        // حوّل النص إلى translated لأن ملف SRT الهدف يحوي الترجمة (وليس الأصلي)
        let cues = parsedCues.map { c -> SubCue in
            var nc = c
            nc.translated = c.text
            return nc
        }
        // جهّز مجلد الدبلجة
        let dubbingDir = LibraryStore.documents
            .appendingPathComponent("Dubbing", isDirectory: true)
            .appendingPathComponent(video.id.uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: dubbingDir, withIntermediateDirectories: true)
        let outURL = dubbingDir.appendingPathComponent("dubbed-\(targetLang.rawValue).m4a")
        dubbingStarted = true
        error = nil
        Task {
            do {
                let req = DubbingRequest(cues: cues,
                                         targetLang: targetLang,
                                         provider: provider,
                                         voice: currentVoice,
                                         stretchToFit: stretchToFit,
                                         maxConcurrent: Int(concurrency))
                let result = try await service.dub(request: req, outputURL: outURL)
                await MainActor.run {
                    lastResult = result
                    dubbingCompleted = true
                }
            } catch {
                await MainActor.run {
                    self.error = error.localizedDescription
                }
            }
        }
    }

    private func subtitleFileForDubbing() -> URL? {
        guard let files = video.subtitleFiles else { return nil }
        // الأولوية: ثنائي اللغة (يحتوي النصين)، فالترجمة، فالأصلي
        let key: String
        switch targetLang {
        case .ar: key = "target"
        default: key = "target"
        }
        guard let rel = files[key] else { return nil }
        let url = LibraryStore.documents.appendingPathComponent(rel)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    private func attachToVideo(_ result: DubbingResult) {
        let rel = "Dubbing/\(video.id.uuidString)/\(result.audioFileURL.lastPathComponent)"
        onCompleted(rel, result)
        dismiss()
    }

    private func previewVoice(_ v: DubbingVoice) {
        Task {
            do {
                let sample = "السلام عليكم، اسمي \(v.name). هذا اختبار للصوت المختار."
                let tempURL = FileManager.default.temporaryDirectory
                    .appendingPathComponent("preview-\(UUID().uuidString).mp3")
                _ = try await service.preview(text: sample, voice: v, outputURL: tempURL)
                await MainActor.run { playPreview(url: tempURL) }
            } catch {
                self.error = "تعذر تشغيل المعاينة: \(error.localizedDescription)"
            }
        }
    }

    private func playPreview(url: URL) {
        // استخدم مشغّل سريع بسيط
        do {
            let player = try AVAudioPlayer(contentsOf: url)
            player.prepareToPlay()
            player.play()
        } catch {
            self.error = "تعذر تشغيل الملف: \(error.localizedDescription)"
        }
    }

    private func stars(_ n: Int) -> some View {
        HStack(spacing: 1) {
            ForEach(0..<5, id: \.self) { i in
                Image(systemName: i < n ? "star.fill" : "star")
                    .font(.system(size: 8))
                    .foregroundStyle(i < n ? V2Theme.gold : .gray)
            }
        }
    }

    private func formatTime(_ t: Double) -> String {
        let n = Int(t)
        if n >= 3600 { return String(format: "%d:%02d:%02d", n / 3600, (n % 3600) / 60, n % 60) }
        return String(format: "%d:%02d", n / 60, n % 60)
    }

    private func serviceResolveProvider(_ p: DubbingProvider) -> DubbingProvider {
        // لاختيار الأصوات، نستخدم نفس منطق الـ service
        switch p {
        case .auto:
            if DubbingProvider.elevenlabs.isAvailable { return .elevenlabs }
            if DubbingProvider.siliconflow.isAvailable { return .siliconflow }
            if DubbingProvider.groqPlayAI.isAvailable { return .groqPlayAI }
            return .edge
        default: return p
        }
    }
}

// MARK: - امتداد لـ DubbingService

extension DubbingService {
    /// معاينة صوت بدون دبلجة كاملة
    @MainActor
    func preview(text: String, voice: DubbingVoice, outputURL: URL) async throws -> Double {
        switch voice.provider {
        case .edge:
            return try await EdgeTTSClient.synthesizeAndSave(text: text, voice: voice.id, outputURL: outputURL)
        case .groqPlayAI:
            return try await GroqTTS.synthesize(text: text, voice: voice.id, outputURL: outputURL)
        case .siliconflow:
            return try await SiliconFlowTTS.synthesize(text: text, voice: voice.id, outputURL: outputURL)
        case .elevenlabs:
            return try await ElevenLabsTTS.synthesize(text: text, voice: voice.id, outputURL: outputURL)
        case .auto:
            return try await EdgeTTSClient.synthesizeAndSave(text: text, voice: voice.id, outputURL: outputURL)
        }
    }
}

import SwiftUI

// MARK: - شاشة تحويل صيغ الفيديو

struct FormatConversionView: View {
    @EnvironmentObject var converter: FormatConverter
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var lang: LanguageStore
    @State private var showConvertPicker = false
    @State private var targetVideo: SavedVideo?

    var body: some View {
        NavigationStack {
            Group {
                if converter.jobs.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "arrow.triangle.2.circlepath")
                            .font(.system(size: 48))
                            .foregroundStyle(V2Theme.gold)
                        Text(lang.t("fc.empty.title"))
                            .font(.title3.bold())
                        Text(lang.t("fc.empty.hint"))
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 28)
                        Button {
                            showConvertPicker = true
                        } label: {
                            Label(lang.t("fc.empty.new"), systemImage: "plus.circle.fill")
                                .font(.headline)
                        }
                        .buttonStyle(.borderedProminent)
                        .padding(.top, 6)
                    }
                } else {
                    List {
                        ForEach(converter.jobs) { job in
                            ConversionJobRow(job: job)
                        }
                        .onDelete { indexSet in
                            for i in indexSet.sorted(by: >) {
                                converter.delete(converter.jobs[i].id)
                            }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .background(V2Theme.bg)
            .navigationTitle(lang.t("fc.title"))
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showConvertPicker = true
                    } label: {
                        Label(lang.t("fc.jobs.new"), systemImage: "plus")
                    }
                    .disabled(library.videos.isEmpty)
                }
            }
            .sheet(isPresented: $showConvertPicker) {
                ConvertPickerView()
                    .environmentObject(converter)
                    .environmentObject(library)
                    .environmentObject(lang)
            }
        }
    }
}

// MARK: - اختيار فيديو وصيغة للتحويل

struct ConvertPickerView: View {
    @EnvironmentObject var converter: FormatConverter
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var lang: LanguageStore
    @Environment(\.dismiss) var dismiss

    @State private var selectedVideoID: UUID?
    @State private var selectedFormat: OutputFormat = .mp4

    let initialVideo: SavedVideo?

    init(initialVideo: SavedVideo? = nil) {
        self.initialVideo = initialVideo
    }

    var selectedVideo: SavedVideo? {
        guard let id = selectedVideoID else { return nil }
        return library.videos.first { $0.id == id }
    }

    var canConvert: Bool {
        guard let video = selectedVideo else { return false }
        return video.kind.fileExtension != selectedFormat.fileExtension
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(lang.t("fc.pick.video")) {
                    if library.videos.isEmpty {
                        Label(lang.t("fc.pick.no.videos"), systemImage: "film.slash")
                            .foregroundStyle(.secondary)
                    } else {
                        Picker(lang.t("fc.pick.video"), selection: $selectedVideoID) {
                            ForEach(library.videos) { video in
                                Text(video.title)
                                    .lineLimit(1)
                                    .tag(Optional(video.id))
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

                Section(lang.t("fc.pick.format")) {
                    Picker(lang.t("fc.pick.to"), selection: $selectedFormat) {
                        ForEach(OutputFormat.allCases) { format in
                            Text(format.titleAR).tag(format)
                        }
                    }
                    .pickerStyle(.segmented)

                    Text(lang.t("fc.pick.info"))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                if let video = selectedVideo {
                    Section {
                        if video.kind.fileExtension == selectedFormat.fileExtension {
                            Label(lang.t("fc.pick.same"), systemImage: "checkmark.circle")
                                .foregroundStyle(.secondary)
                        } else {
                            Text("\(video.kind.titleAR) ← \(selectedFormat.titleAR)")
                                .font(.subheadline.bold())
                        }
                    }
                }

                Section {
                    Button {
                        if let video = selectedVideo, canConvert {
                            converter.convert(video: video, to: selectedFormat)
                            dismiss()
                        }
                    } label: {
                        Label(lang.t("fc.pick.start"), systemImage: "arrow.triangle.2.circlepath")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!canConvert)
                } footer: {
                    Text(lang.t("fc.pick.footer"))
                        .font(.caption2)
                }
            }
            .scrollContentBackground(.hidden)
            .background(V2Theme.bg)
            .navigationTitle(lang.t("fc.pick.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(lang.t("nav.cancel")) { dismiss() }
                }
            }
            .onAppear {
                if selectedVideoID == nil {
                    selectedVideoID = initialVideo?.id ?? library.videos.first?.id
                }
            }
        }
    }
}

// MARK: - صف مهمة تحويل

struct ConversionJobRow: View {
    @EnvironmentObject private var converter: FormatConverter
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var lang: LanguageStore
    let job: ConversionJob

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: iconName)
                    .font(.title3)
                    .foregroundStyle(iconColor)
                    .frame(width: 28)

                VStack(alignment: .leading, spacing: 3) {
                    BidiText(text: job.videoTitle, font: .headline, lineLimit: 2)
                    Text("\(job.sourceKind.titleAR) → \(job.outputFormat.titleAR)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)

                Text("\(Int(min(1, max(0, job.progress)) * 100))%")
                    .font(.caption.monospacedDigit().bold())
                    .foregroundStyle(job.phase == .done ? .green : V2Theme.gold)
            }

            ProgressView(value: min(max(job.progress, 0), 1))
                .tint(job.phase == .failed ? .red : V2Theme.accent)

            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(job.phase.titleAR)
                    .font(.caption)
                    .foregroundStyle(job.phase == .failed ? .red : .secondary)
                Spacer()
                if let size = job.outputSize, size > 0 {
                    Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            if let message = job.errorMessage, !message.isEmpty {
                Text(message)
                    .font(.caption2)
                    .foregroundStyle(.red)
                    .lineLimit(3)
            }

            HStack(spacing: 8) {
                if job.phase == .done {
                    Button {
                        converter.replaceOriginal(job.id)
                    } label: {
                        Label(lang.t("fc.replace"), systemImage: "arrow.triangle.2.circlepath")
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)

                    if let rel = job.outputRelativePath {
                        let url = LibraryStore.documents.appendingPathComponent(rel)
                        if FileManager.default.fileExists(atPath: url.path) {
                            ShareLink(item: url) {
                                Label(lang.t("fc.share"), systemImage: "square.and.arrow.up")
                            }
                            .controlSize(.small)
                        }
                    }
                }

                Spacer(minLength: 0)

                Button(role: .destructive) {
                    converter.delete(job.id)
                } label: {
                    Label(lang.t("nav.delete"), systemImage: "trash")
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
        }
        .padding(.vertical, 8)
        .listRowBackground(V2Theme.card)
    }

    private var iconName: String {
        switch job.phase {
        case .done: return "checkmark.circle.fill"
        case .failed: return "exclamationmark.triangle.fill"
        case .cancelled: return "xmark.circle.fill"
        case .queued: return "clock.fill"
        case .converting: return "arrow.triangle.2.circlepath"
        }
    }

    private var iconColor: Color {
        switch job.phase {
        case .done: return .green
        case .failed: return .red
        case .cancelled: return .orange
        default: return V2Theme.gold
        }
    }
}

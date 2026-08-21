import SwiftUI
import AVKit
import AVFoundation
import CoreMedia

struct LibraryView: View {
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var translations: TranslationManager
    @State private var query = ""
    @State private var playing: SavedVideo?
    @State private var translateVideo: SavedVideo?

    var filtered: [SavedVideo] {
        let q = query.trimmingCharacters(in: .whitespaces)
        if q.isEmpty { return library.videos }
        return library.videos.filter { $0.title.localizedCaseInsensitiveContains(q) }
    }

    var body: some View {
        NavigationStack {
            Group {
                if filtered.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "film")
                            .font(.system(size: 48))
                            .foregroundStyle(V2Theme.gold)
                        Text("المكتبة فارغة").font(.title3.bold())
                        Text("افتح موقعاً من المتصفح، شغّل الفيديو، ثم استخرج وحمّل للمشاهدة بدون إنترنت.")
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 28)
                    }
                } else {
                    List {
                        ForEach(filtered) { v in
                            Button { playing = v } label: {
                                HStack(spacing: 12) {
                                    RoundedRectangle(cornerRadius: 10)
                                        .fill(V2Theme.card)
                                        .frame(width: 88, height: 56)
                                        .overlay(Image(systemName: v.hasSubtitles ? "captions.bubble" : "play.fill"))
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(v.title).font(.headline).lineLimit(2)
                                        Text(byteString(v.fileSize) + " · " + v.kind.titleAR)
                                            .font(.caption).foregroundStyle(.secondary)
                                        if let lang = v.subtitleTargetLang,
                                           let name = TranslationManager.detectedLangNameAR(lang) {
                                            Label("ترجمة " + name, systemImage: "captions.bubble.fill")
                                                .font(.caption2)
                                                .foregroundStyle(V2Theme.gold)
                                        }
                                    }
                                }
                            }
                            .swipeActions {
                                Button(role: .destructive) { library.delete(v) } label: { Text("حذف") }
                                Button {
                                    translateVideo = v
                                } label: {
                                    Label("ترجمة", systemImage: "captions.bubble")
                                }
                                .tint(V2Theme.gold)
                            }
                            .contextMenu {
                                Button {
                                    playing = v
                                } label: {
                                    Label("تشغيل", systemImage: "play.fill")
                                }
                                Button {
                                    translateVideo = v
                                } label: {
                                    Label("ترجمة الفيديو", systemImage: "captions.bubble")
                                }
                                if v.hasSubtitles,
                                   let files = v.subtitleFiles,
                                   let rel = files["target"] ?? files["orig"] {
                                    let url = LibraryStore.documents.appendingPathComponent(rel)
                                    if FileManager.default.fileExists(atPath: url.path) {
                                        ShareLink(item: url) {
                                            Label("مشاركة ملف الترجمة SRT", systemImage: "square.and.arrow.up")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .background(V2Theme.bg)
            .navigationTitle("المكتبة")
            .searchable(text: $query, prompt: "بحث في العناوين")
            .sheet(item: $playing) { v in
                PlayerScreen(video: v)
                    .environmentObject(library)
            }
            .sheet(item: $translateVideo) { v in
                NewTranslationView(preselected: v)
                    .environmentObject(translations)
                    .environmentObject(library)
            }
        }
    }

    private func byteString(_ n: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: n, countStyle: .file)
    }
}

// MARK: - مشغّل الفيديو مع طبقة الترجمة

struct PlayerScreen: View {
    let video: SavedVideo
    @EnvironmentObject var library: LibraryStore
    @Environment(\.dismiss) var dismiss
    @State private var player: AVPlayer?

    // الترجمة
    @State private var origCues: [SubCue] = []
    @State private var trCues: [SubCue] = []
    @State private var currentText: String = ""
    @State private var timeObserver: Any? = nil
    @AppStorage("sub.mode") private var modeRaw: String = SubtitleDisplayMode.translated.rawValue
    @AppStorage("sub.fontSize") private var fontSize: Int = 18

    private var mode: SubtitleDisplayMode {
        SubtitleDisplayMode(rawValue: modeRaw) ?? .translated
    }

    private var hasSubtitles: Bool {
        !origCues.isEmpty || !trCues.isEmpty
    }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                if let player {
                    VideoPlayer(player: player)
                        .ignoresSafeArea()
                } else {
                    ProgressView()
                }

                // طبقة الترجمة فوق الفيديو وتحت أزرار التحكم
                if mode != .off, !currentText.isEmpty {
                    SubtitleOverlay(text: currentText, fontSize: fontSize)
                        .padding(.bottom, 84)
                }
            }
            .onAppear {
                let p = AVPlayer(url: video.localURL)
                if video.lastPosition > 1 {
                    p.seek(to: CMTime(seconds: video.lastPosition, preferredTimescale: 600))
                }
                p.play()
                player = p
                loadSubtitles()
                attachObserver(p)
            }
            .onDisappear {
                if let t = player?.currentTime().seconds, t.isFinite {
                    library.updatePosition(id: video.id, position: t)
                }
                if let o = timeObserver, let p = player {
                    p.removeTimeObserver(o)
                }
                timeObserver = nil
                player?.pause()
            }
            .navigationTitle(video.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("إغلاق") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    if hasSubtitles {
                        Menu {
                            Picker("وضع الترجمة", selection: $modeRaw) {
                                ForEach(availableModes) { m in
                                    Label(m.titleAR, systemImage: m.icon).tag(m.rawValue)
                                }
                            }
                            Divider()
                            Picker("حجم الخط", selection: $fontSize) {
                                Text("صغير").tag(14)
                                Text("متوسط").tag(18)
                                Text("كبير").tag(23)
                                Text("كبير جداً").tag(28)
                            }
                        } label: {
                            Image(systemName: mode == .off ? "captions.bubble" : "captions.bubble.fill")
                        }
                    }
                }
            }
        }
    }

    private var availableModes: [SubtitleDisplayMode] {
        var modes: [SubtitleDisplayMode] = [.off]
        if !origCues.isEmpty { modes.append(.original) }
        if !trCues.isEmpty { modes.append(.translated) }
        if !origCues.isEmpty && !trCues.isEmpty { modes.append(.bilingual) }
        return modes
    }

    private func loadSubtitles() {
        let urls = TranslationManager.subtitleURLs(for: video)
        if let o = urls.orig {
            origCues = SubtitleCodec.parseSRTFile(at: o)
        }
        if let t = urls.target {
            trCues = SubtitleCodec.parseSRTFile(at: t)
        } else if let b = urls.bilingual {
            // ملف ثنائي: السطر الأول الترجمة والثاني الأصل — نفصل الترجمة منه
            let cues = SubtitleCodec.parseSRTFile(at: b)
            trCues = cues.compactMap { c in
                let lines = c.text.components(separatedBy: "\n")
                guard let first = lines.first, !first.isEmpty else { return nil }
                var copy = c
                copy.text = first
                return copy
            }
        }
        // أول وضع متاح إذا كان المحفوظ غير متاح
        if mode != .off && !availableModes.contains(mode) {
            modeRaw = (availableModes.first { $0 != .off } ?? .off).rawValue
        }
    }

    private func attachObserver(_ p: AVPlayer) {
        let interval = CMTime(seconds: 0.25, preferredTimescale: 600)
        timeObserver = p.addPeriodicTimeObserver(forInterval: interval, queue: .main) { time in
            guard mode != .off else {
                if !currentText.isEmpty { currentText = "" }
                return
            }
            let t = time.seconds
            guard t.isFinite else { return }
            let text = SubtitleOverlayRenderer.text(original: origCues, translated: trCues,
                                                     mode: mode, time: t)
            if text != currentText {
                currentText = text ?? ""
            }
        }
    }
}

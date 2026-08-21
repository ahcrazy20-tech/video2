import SwiftUI
import AVKit
import AVFoundation
import CoreMedia

struct LibraryView: View {
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var lang: LanguageStore
    @EnvironmentObject var translations: TranslationManager
    @State private var query = ""
    @State private var playing: SavedVideo?
    @State private var renameTarget: SavedVideo?
    @State private var renameText = ""
    @State private var thumbTick = 0
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
                        Text(lang.t("lib.empty")).font(.title3.bold())
                        Text(lang.t("lib.empty.hint"))
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 28)
                    }
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 18) {
                            if query.isEmpty, !library.continueWatching.isEmpty {
                                Text(lang.t("lib.continue")).font(.headline).padding(.horizontal, 16)
                                ScrollView(.horizontal, showsIndicators: false) {
                                    HStack(spacing: 12) {
                                        ForEach(library.continueWatching) { v in
                                            continueCard(v)
                                        }
                                    }
                                    .padding(.horizontal, 16)
                                }
                            }
                            Text(query.isEmpty ? lang.t("lib.all") : lang.t("lib.search.results"))
                                .font(.headline)
                                .padding(.horizontal, 16)
                            ForEach(filtered) { v in
                                row(v)
                                    .padding(.horizontal, 16)
                            }
                        }
                        .padding(.vertical, 8)
                    }
                }
            }
            .background(V2Theme.bg.ignoresSafeArea())
            .navigationTitle(lang.t("tab.library"))
            .searchable(text: $query, prompt: Text(lang.t("lib.search")))
            .fullScreenCover(item: $playing) { v in
                OfflinePlayerView(video: v)
                    .environmentObject(library)
            }
            .sheet(item: $translateVideo) { v in
                NewTranslationView(preselected: v)
                    .environmentObject(translations)
                    .environmentObject(library)
            }
            .alert(lang.t("lib.rename"), isPresented: Binding(get: { renameTarget != nil }, set: { if !$0 { renameTarget = nil } })) {
                TextField(lang.t("lib.title"), text: $renameText)
                Button(lang.t("lib.save")) {
                    if let t = renameTarget { library.rename(t, title: renameText) }
                    renameTarget = nil
                }
                Button(lang.t("lib.cancel"), role: .cancel) { renameTarget = nil }
            }
            .onReceive(NotificationCenter.default.publisher(for: .v2ThumbReady)) { _ in
                thumbTick += 1
            }
        }
    }

    private func continueCard(_ v: SavedVideo) -> some View {
        Button { playing = v } label: {
            VStack(alignment: .leading, spacing: 6) {
                poster(v, width: 220, height: 124)
                Text(v.title).font(.caption.bold()).lineLimit(2).frame(width: 220, alignment: .leading)
            }
        }
        .buttonStyle(.plain)
    }

    private func row(_ v: SavedVideo) -> some View {
        Button { playing = v } label: {
            HStack(spacing: 12) {
                poster(v, width: 128, height: 74)
                VStack(alignment: .leading, spacing: 5) {
                    Text(v.title).font(.headline).lineLimit(2).foregroundStyle(.primary)
                    Text(meta(v)).font(.caption).foregroundStyle(.secondary)
                    if v.lastPosition > 8 {
                        Text(lang.t("lib.resume") + fmt(v.lastPosition)).font(.caption2).foregroundStyle(V2Theme.gold)
                    }
                    if v.hasSubtitles {
                        Label(lang.t("lib.subs.badge"), systemImage: "captions.bubble.fill")
                            .font(.caption2)
                            .foregroundStyle(V2Theme.mint)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(10)
            .background(V2Theme.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(lang.t("lib.play")) { playing = v }
            Button {
                translateVideo = v
            } label: {
                Label(lang.t("lib.translate"), systemImage: "captions.bubble")
            }
            if v.hasSubtitles,
               let files = v.subtitleFiles,
               let rel = files["target"] ?? files["orig"] {
                let url = LibraryStore.documents.appendingPathComponent(rel)
                if FileManager.default.fileExists(atPath: url.path) {
                    ShareLink(item: url) {
                        Label("SRT", systemImage: "square.and.arrow.up")
                    }
                }
            }
            Button(lang.t("lib.rename")) {
                renameText = v.title
                renameTarget = v
            }
            Button(lang.t("lib.delete"), role: .destructive) { library.delete(v) }
        }
    }

    private func poster(_ v: SavedVideo, width: CGFloat, height: CGFloat) -> some View {
        ZStack(alignment: .bottom) {
            Group {
                if let u = library.thumbURL(v), let img = Thumbnailer.cached(u.path) {
                    Image(uiImage: img).resizable().scaledToFill()
                } else {
                    LinearGradient(colors: [Color(red: 0.18, green: 0.16, blue: 0.28), V2Theme.card], startPoint: .topLeading, endPoint: .bottomTrailing)
                        .overlay(Image(systemName: "play.fill").font(.title2).foregroundStyle(.white.opacity(0.9)))
                }
            }
            .id("\(v.id)-\(thumbTick)")
            if let d = v.duration, d > 0, v.lastPosition > 8 {
                ProgressView(value: min(1, v.lastPosition / d))
                    .tint(V2Theme.accent)
                    .padding(.horizontal, 4)
                    .padding(.bottom, 2)
            }
            VStack {
                HStack {
                    Spacer()
                    if let d = v.duration, d > 0 {
                        Text(fmt(d))
                            .font(.caption2.bold())
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(.black.opacity(0.65), in: Capsule())
                            .padding(6)
                    }
                }
                Spacer()
            }
        }
        .frame(width: width, height: height)
        .clipped()
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private func meta(_ v: SavedVideo) -> String {
        var parts = [v.kind.titleAR, ByteCountFormatter.string(fromByteCount: v.fileSize, countStyle: .file)]
        if let d = v.duration, d > 0 { parts.insert(fmt(d), at: 0) }
        return parts.joined(separator: " · ")
    }

    private func fmt(_ s: Double) -> String {
        let n = Int(max(0, s))
        if n >= 3600 { return String(format: "%d:%02d:%02d", n / 3600, (n % 3600) / 60, n % 60) }
        return String(format: "%d:%02d", n / 60, n % 60)
    }
}

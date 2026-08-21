import SwiftUI
import AVKit
import AVFoundation
import CoreMedia

struct LibraryView: View {
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var lang: LanguageStore
    @EnvironmentObject var translations: TranslationManager
    @EnvironmentObject var converter: FormatConverter
    @State private var query = ""
    @State private var playing: SavedVideo?
    @State private var renameTarget: SavedVideo?
    @State private var renameText = ""
    @State private var thumbTick = 0
    @State private var translateVideo: SavedVideo?
    @State private var convertVideo: SavedVideo?

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
            .sheet(item: $convertVideo) { v in
                ConvertPickerView(initialVideo: v)
                    .environmentObject(converter)
                    .environmentObject(library)
            }
            .onReceive(NotificationCenter.default.publisher(for: .v2ThumbReady)) { _ in
                thumbTick += 1
            }
        }
        .sheet(item: $renameTarget) { v in
            renameSheet(v)
                .presentationDetents([.medium])
        }
    }

    /// إعادة التسمية في sheet بخانة كتابة LTR — تمنع تقلّب ترتيب الكلمات في العناوين اللاتينية
    private func renameSheet(_ v: SavedVideo) -> some View {
        NavigationStack {
            Form {
                Section(lang.t("lib.title")) {
                    TextField(lang.t("lib.title"), text: $renameText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .environment(\.layoutDirection, .leftToRight)
                    Text("اكتب الاسم الجديد ثم اضغط حفظ — خانة الكتابة بالاتجاه الإنجليزي حتى لا تتقلب الكلمات.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle(lang.t("lib.rename"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(lang.t("lib.cancel")) { renameTarget = nil }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(lang.t("lib.save")) {
                        let t = renameText.trimmingCharacters(in: .whitespacesAndNewlines)
                        if !t.isEmpty { library.rename(v, title: t) }
                        renameTarget = nil
                    }
                }
            }
        }
        .onAppear { renameText = v.title }
    }

    private func continueCard(_ v: SavedVideo) -> some View {
        Button { playing = v } label: {
            VStack(alignment: .leading, spacing: 6) {
                poster(v, width: 220, height: 124)
                BidiText(text: v.title, font: .caption.bold())
                    .frame(width: 220, alignment: .leading)
            }
        }
        .buttonStyle(.plain)
    }

    private func row(_ v: SavedVideo) -> some View {
        Button { playing = v } label: {
            HStack(spacing: 12) {
                poster(v, width: 128, height: 74)
                VStack(alignment: .leading, spacing: 5) {
                    BidiText(text: v.title).foregroundStyle(.primary)
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
                        Label("تصدير SRT", systemImage: "square.and.arrow.up")
                    }
                }
            }
            Button(lang.t("lib.rename")) {
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
        // النوع بالعربي أولاً حتى يبقى ترتيب السطر ثابتاً في الواجهة العربية
        var parts = [v.kind.titleAR]
        if let d = v.duration, d > 0 { parts.append(fmt(d)) }
        parts.append(ByteCountFormatter.string(fromByteCount: v.fileSize, countStyle: .file))
        return parts.joined(separator: " · ")
    }

    private func fmt(_ s: Double) -> String {
        let n = Int(max(0, s))
        if n >= 3600 { return String(format: "%d:%02d:%02d", n / 3600, (n % 3600) / 60, n % 60) }
        return String(format: "%d:%02d", n / 60, n % 60)
    }
}

/// نص يُعرض باتجاهه الطبيعي: عربي يمين-لليسار، لاتيني يسار-لليمين
/// — يمنع تقلّب ترتيب كلمات العناوين الإنجليزية/المختلطة في الواجهة العربية
struct BidiText: View {
    let text: String
    var font: Font = .headline
    var lineLimit: Int? = 2

    private var startsRTL: Bool {
        for scalar in text.unicodeScalars {
            switch scalar.value {
            case 0x0600...0x08FF, 0xFB50...0xFDFF, 0xFE70...0xFEFF:
                return true
            case 0x0041...0x005A, 0x0061...0x007A:
                return false
            default:
                continue
            }
        }
        return false
    }

    var body: some View {
        Text(text)
            .font(font)
            .lineLimit(lineLimit)
            .environment(\.layoutDirection, startsRTL ? .rightToLeft : .leftToRight)
    }
}

import SwiftUI
import AVKit
import AVFoundation
import CoreMedia

struct LibraryView: View {
    @EnvironmentObject var library: LibraryStore
    @State private var query = ""
    @State private var playing: SavedVideo?

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
                                        .overlay(Image(systemName: "play.fill"))
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(v.title).font(.headline).lineLimit(2)
                                        Text(byteString(v.fileSize) + " · " + v.kind.titleAR)
                                            .font(.caption).foregroundStyle(.secondary)
                                        Text(v.extractionMethod).font(.caption2).foregroundStyle(.tertiary)
                                    }
                                }
                            }
                            .swipeActions {
                                Button(role: .destructive) { library.delete(v) } label: { Text("حذف") }
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
        }
    }

    private func byteString(_ n: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: n, countStyle: .file)
    }
}

struct PlayerScreen: View {
    let video: SavedVideo
    @EnvironmentObject var library: LibraryStore
    @Environment(\.dismiss) var dismiss
    @State private var player: AVPlayer?

    var body: some View {
        NavigationStack {
            Group {
                if let player {
                    VideoPlayer(player: player)
                        .ignoresSafeArea()
                } else {
                    ProgressView()
                }
            }
            .onAppear {
                let p = AVPlayer(url: video.localURL)
                if video.lastPosition > 1 {
                    p.seek(to: CMTime(seconds: video.lastPosition, preferredTimescale: 600))
                }
                p.play()
                player = p
            }
            .onDisappear {
                if let t = player?.currentTime().seconds, t.isFinite {
                    library.updatePosition(id: video.id, position: t)
                }
                player?.pause()
            }
            .navigationTitle(video.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("إغلاق") { dismiss() } } }
        }
    }
}

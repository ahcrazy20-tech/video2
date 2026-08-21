import Foundation
import Combine

final class LibraryStore: ObservableObject {
    static let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    static let videosDir = documents.appendingPathComponent("Videos", isDirectory: true)
    static let thumbsDir = documents.appendingPathComponent("Thumbs", isDirectory: true)
    private static let indexURL = documents.appendingPathComponent("library.json")

    @Published var videos: [SavedVideo] = []

    func load() {
        try? FileManager.default.createDirectory(at: Self.videosDir, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: Self.thumbsDir, withIntermediateDirectories: true)
        guard let data = try? Data(contentsOf: Self.indexURL) else { return }
        videos = (try? JSONDecoder().decode([SavedVideo].self, from: data)) ?? []
        videos.sort { $0.createdAt > $1.createdAt }
        DispatchQueue.global(qos: .utility).async {
            self.videos.prefix(40).forEach { Thumbnailer.make(for: $0) }
        }
    }

    func saveIndex() {
        let data = try? JSONEncoder().encode(videos)
        try? data?.write(to: Self.indexURL, options: .atomic)
    }

    func add(_ video: SavedVideo) {
        videos.insert(video, at: 0)
        saveIndex()
        Thumbnailer.make(for: video)
    }

    func setThumbnail(id: UUID, relative: String) {
        guard let i = videos.firstIndex(where: { $0.id == id }) else { return }
        videos[i].thumbnailRelativePath = relative
        saveIndex()
    }

    func thumbURL(_ video: SavedVideo) -> URL? {
        if let t = video.thumbnailRelativePath {
            return Self.documents.appendingPathComponent(t)
        }
        let guess = Self.thumbsDir.appendingPathComponent("\(video.id.uuidString).jpg")
        return FileManager.default.fileExists(atPath: guess.path) ? guess : nil
    }

    func update(_ video: SavedVideo) {
        guard let i = videos.firstIndex(where: { $0.id == video.id }) else { return }
        videos[i] = video
        saveIndex()
    }

    func delete(_ video: SavedVideo) {
        try? FileManager.default.removeItem(at: video.localURL)
        if let t = video.thumbnailRelativePath {
            try? FileManager.default.removeItem(at: Self.documents.appendingPathComponent(t))
        }
        videos.removeAll { $0.id == video.id }
        saveIndex()
    }

    func updatePosition(id: UUID, position: Double, duration: Double? = nil) {
        guard let i = videos.firstIndex(where: { $0.id == id }) else { return }
        videos[i].lastPosition = position
        if let duration, duration.isFinite, duration > 0 {
            videos[i].duration = duration
        }
        saveIndex()
    }

    func rename(_ video: SavedVideo, title: String) {
        guard let i = videos.firstIndex(where: { $0.id == video.id }) else { return }
        videos[i].title = title
        saveIndex()
    }

    var continueWatching: [SavedVideo] {
        videos.filter { v in
            v.lastPosition > 8 && (v.duration ?? 99999) - v.lastPosition > 8
        }.sorted { $0.lastPosition > $1.lastPosition }
    }
}

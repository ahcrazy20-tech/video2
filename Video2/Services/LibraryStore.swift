import Foundation
import Combine

final class LibraryStore: ObservableObject {
    static let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    static let videosDir = documents.appendingPathComponent("Videos", isDirectory: true)
    static let thumbsDir = documents.appendingPathComponent("Thumbs", isDirectory: true)
    private static let indexURL = documents.appendingPathComponent("library.json")

    @Published var videos: [SavedVideo] = []
    @Published var folders: [LibraryFolder] = []

    func load() {
        try? FileManager.default.createDirectory(at: Self.videosDir, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: Self.thumbsDir, withIntermediateDirectories: true)
        guard let data = try? Data(contentsOf: Self.indexURL) else { return }
        if let idx = try? JSONDecoder().decode(LibraryIndex.self, from: data) {
            videos = idx.videos
            folders = idx.folders
        } else if let list = try? JSONDecoder().decode([SavedVideo].self, from: data) {
            videos = list
            folders = []
        }
        videos.sort { $0.createdAt > $1.createdAt }
        folders.sort { $0.createdAt < $1.createdAt }
        DispatchQueue.global(qos: .utility).async {
            self.videos.prefix(40).forEach { Thumbnailer.make(for: $0) }
        }
    }

    func saveIndex() {
        let idx = LibraryIndex(videos: videos, folders: folders)
        guard let data = try? JSONEncoder().encode(idx) else { return }
        try? data.write(to: Self.indexURL, options: .atomic)
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
        if video.kind == .hls || video.localURL.pathExtension.lowercased() == "m3u8" {
            let folder = video.localURL.deletingLastPathComponent()
            if folder.lastPathComponent != "Videos" {
                try? FileManager.default.removeItem(at: folder)
            } else {
                try? FileManager.default.removeItem(at: video.localURL)
            }
        } else {
            try? FileManager.default.removeItem(at: video.localURL)
        }
        if let t = video.thumbnailRelativePath {
            try? FileManager.default.removeItem(at: Self.documents.appendingPathComponent(t))
        }
        let thumbsGuess = Self.thumbsDir.appendingPathComponent("\(video.id.uuidString).jpg")
        try? FileManager.default.removeItem(at: thumbsGuess)
        let subs = Self.documents.appendingPathComponent("Subtitles/\(video.id.uuidString)", isDirectory: true)
        try? FileManager.default.removeItem(at: subs)
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

    func addFolder(named name: String) -> LibraryFolder? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if folders.contains(where: { $0.name.caseInsensitiveCompare(trimmed) == .orderedSame }) {
            return folders.first { $0.name.caseInsensitiveCompare(trimmed) == .orderedSame }
        }
        let folder = LibraryFolder(id: UUID(), name: trimmed, createdAt: Date())
        folders.append(folder)
        saveIndex()
        return folder
    }

    func renameFolder(_ id: UUID, name: String) {
        guard let i = folders.firstIndex(where: { $0.id == id }) else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        folders[i].name = trimmed
        saveIndex()
    }

    func deleteFolder(_ id: UUID) {
        for i in videos.indices where videos[i].folderID == id {
            videos[i].folderID = nil
        }
        folders.removeAll { $0.id == id }
        saveIndex()
    }

    func setFolder(_ video: SavedVideo, _ folderID: UUID?) {
        guard let i = videos.firstIndex(where: { $0.id == video.id }) else { return }
        videos[i].folderID = folderID
        saveIndex()
    }

    func videos(in filter: LibraryFilter) -> [SavedVideo] {
        switch filter {
        case .all: return videos
        case .unfiled: return videos.filter { $0.folderID == nil }
        case .folder(let id): return videos.filter { $0.folderID == id }
        }
    }

    @discardableResult
    func importFiles(_ urls: [URL]) -> Int {
        var added = 0
        for url in urls {
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            do {
                let id = UUID()
                let ext = url.pathExtension.isEmpty ? "mp4" : url.pathExtension
                let dest = Self.videosDir.appendingPathComponent("\(id.uuidString).\(ext)")
                if FileManager.default.fileExists(atPath: dest.path) {
                    try FileManager.default.removeItem(at: dest)
                }
                try FileManager.default.copyItem(at: url, to: dest)
                let kind = MediaKind.infer(url: dest.lastPathComponent, mime: nil)
                let bytes = (try? dest.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
                let title = url.deletingPathExtension().lastPathComponent
                add(SavedVideo(
                    id: id,
                    title: title.isEmpty ? "ملف مستورد" : title,
                    sourceURL: url.absoluteString,
                    pageURL: nil,
                    localRelativePath: "Videos/\(id.uuidString).\(ext)",
                    thumbnailRelativePath: nil,
                    kind: kind,
                    createdAt: Date(),
                    duration: nil,
                    fileSize: bytes,
                    lastPosition: 0,
                    extractionMethod: "import-files"
                ))
                added += 1
            } catch {
                continue
            }
        }
        return added
    }

    var continueWatching: [SavedVideo] {
        videos.filter { v in
            v.lastPosition > 8 && (v.duration ?? 99999) - v.lastPosition > 8
        }.sorted { $0.lastPosition > $1.lastPosition }
    }
}

enum LibraryFilter: Hashable {
    case all
    case unfiled
    case folder(UUID)
}

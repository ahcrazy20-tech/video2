import Foundation

enum StorageManager {
    struct Report {
        var videos: Int64
        var thumbs: Int64
        var translations: Int64
        var conversions: Int64
        var other: Int64

        var total: Int64 { videos + thumbs + translations + conversions + other }

        func line(_ bytes: Int64) -> String {
            ByteCountFormatter.string(fromByteCount: max(0, bytes), countStyle: .file)
        }
    }

    static func report() -> Report {
        Report(
            videos: folderSize(LibraryStore.videosDir),
            thumbs: folderSize(LibraryStore.thumbsDir),
            translations: folderSize(TranslationManager.root),
            conversions: folderSize(FormatConverter.root),
            other: folderSize(LibraryStore.documents.appendingPathComponent("Subtitles", isDirectory: true))
        )
    }

    @discardableResult
    static func purgeTemporary() -> Int64 {
        var removed: Int64 = 0
        let tmp = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        let prefixes = ["v2-", "merge-", "cc-", "ffmpeg-api-", "hls-", "audio-"]
        if let items = try? FileManager.default.contentsOfDirectory(at: tmp, includingPropertiesForKeys: [.fileSizeKey, .isDirectoryKey]) {
            for item in items {
                let name = item.lastPathComponent
                guard prefixes.contains(where: { name.hasPrefix($0) }) else { continue }
                removed += deleteItem(item)
            }
        }

        // بقايا صوت الترجمة بعد اكتمال المهمة
        if let jobs = try? FileManager.default.contentsOfDirectory(at: TranslationManager.root, includingPropertiesForKeys: [.isDirectoryKey]) {
            for dir in jobs where (try? dir.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true {
                removed += deleteItem(dir.appendingPathComponent("chunks", isDirectory: true))
                removed += deleteItem(dir.appendingPathComponent("hls-source.mp4"))
                removed += deleteItem(dir.appendingPathComponent("hls-source.m4a"))
                removed += deleteItem(dir.appendingPathComponent("merged.ts"))
            }
        }
        return removed
    }

    @discardableResult
    static func purgeOrphans(videos: [SavedVideo]) -> Int64 {
        var removed: Int64 = 0
        let knownFiles = Set(videos.map { $0.localURL.standardizedFileURL.path })
        let knownFolders = Set(videos.compactMap { v -> String? in
            if v.kind == .hls || v.localURL.pathExtension.lowercased() == "m3u8" {
                return v.localURL.deletingLastPathComponent().standardizedFileURL.path
            }
            return nil
        })
        let knownIDs = Set(videos.map { $0.id.uuidString })

        if let items = try? FileManager.default.contentsOfDirectory(at: LibraryStore.videosDir, includingPropertiesForKeys: [.isDirectoryKey]) {
            for item in items {
                let path = item.standardizedFileURL.path
                let isDir = (try? item.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true
                if isDir {
                    if !knownFolders.contains(path) {
                        removed += deleteItem(item)
                    }
                } else if !knownFiles.contains(path) {
                    removed += deleteItem(item)
                }
            }
        }

        if let thumbs = try? FileManager.default.contentsOfDirectory(at: LibraryStore.thumbsDir, includingPropertiesForKeys: nil) {
            for thumb in thumbs {
                let stem = thumb.deletingPathExtension().lastPathComponent
                if !knownIDs.contains(stem) {
                    removed += deleteItem(thumb)
                }
            }
        }

        let subsRoot = LibraryStore.documents.appendingPathComponent("Subtitles", isDirectory: true)
        if let subs = try? FileManager.default.contentsOfDirectory(at: subsRoot, includingPropertiesForKeys: [.isDirectoryKey]) {
            for dir in subs {
                if !knownIDs.contains(dir.lastPathComponent) {
                    removed += deleteItem(dir)
                }
            }
        }
        return removed
    }

    static func folderSize(_ url: URL) -> Int64 {
        let keys: Set<URLResourceKey> = [.fileSizeKey, .isDirectoryKey]
        guard let e = FileManager.default.enumerator(at: url, includingPropertiesForKeys: Array(keys)) else { return 0 }
        var total: Int64 = 0
        for case let f as URL in e {
            if let v = try? f.resourceValues(forKeys: keys), v.isDirectory != true {
                total += Int64(v.fileSize ?? 0)
            }
        }
        return total
    }

    @discardableResult
    private static func deleteItem(_ url: URL) -> Int64 {
        let size: Int64
        if (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true {
            size = folderSize(url)
        } else {
            size = Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        guard FileManager.default.fileExists(atPath: url.path) else { return 0 }
        try? FileManager.default.removeItem(at: url)
        return size
    }
}

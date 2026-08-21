import AVFoundation
import UIKit
import CoreMedia

enum Thumbnailer {
    private static var cache = NSCache<NSString, UIImage>()
    private static let queue = DispatchQueue(label: "video2.thumbs", qos: .utility)

    static func cached(_ path: String) -> UIImage? {
        if let img = cache.object(forKey: path as NSString) { return img }
        guard FileManager.default.fileExists(atPath: path),
              let img = UIImage(contentsOfFile: path) else { return nil }
        cache.setObject(img, forKey: path as NSString)
        return img
    }

    static func make(for video: SavedVideo) {
        if video.thumbnailRelativePath != nil { return }
        let dest = LibraryStore.thumbsDir.appendingPathComponent("\(video.id.uuidString).jpg")
        if FileManager.default.fileExists(atPath: dest.path) {
            notify(dest)
            return
        }
        queue.async {
            if video.kind == .hls {
                let folder = video.localURL.deletingLastPathComponent()
                let first = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil))?
                    .first(where: { ["ts", "mp4", "m4s"].contains($0.pathExtension.lowercased()) })
                if let first { capture(url: first, at: 0.3, dest: dest) }
                return
            }
            capture(url: video.localURL, at: min(2, (video.duration ?? 4) * 0.1), dest: dest)
        }
    }

    private static func capture(url: URL, at seconds: Double, dest: URL) {
        let asset = AVURLAsset(url: url)
        let gen = AVAssetImageGenerator(asset: asset)
        gen.appliesPreferredTrackTransform = true
        gen.maximumSize = CGSize(width: 480, height: 270)
        let t = CMTime(seconds: max(0.1, seconds), preferredTimescale: 600)
        gen.generateCGImagesAsynchronously(forTimes: [NSValue(time: t)]) { _, image, _, _, _ in
            guard let image else { return }
            let ui = UIImage(cgImage: image)
            guard let data = ui.jpegData(compressionQuality: 0.72) else { return }
            try? data.write(to: dest, options: .atomic)
            cache.setObject(ui, forKey: dest.path as NSString)
            notify(dest)
        }
    }

    private static func notify(_ dest: URL) {
        DispatchQueue.main.async {
            NotificationCenter.default.post(name: .v2ThumbReady, object: dest.lastPathComponent)
        }
    }
}

extension Notification.Name {
    static let v2ThumbReady = Notification.Name("v2ThumbReady")
}

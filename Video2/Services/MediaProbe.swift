import Foundation
import AVFoundation

enum MediaProbe {
    static func enrich(_ media: DetectedMedia) async -> DetectedMedia {
        var m = media
        if m.probed { return m }
        guard let url = URL(string: m.url), url.scheme == "http" || url.scheme == "https" else {
            m.probed = true
            return m
        }
        if m.isFragment {
            m.probed = true
            return m
        }

        if m.byteSize == nil || m.mime == nil || m.mime?.isEmpty == true {
            if let head = await head(url) {
                if m.byteSize == nil { m.byteSize = head.length }
                if (m.mime == nil || m.mime?.isEmpty == true), let t = head.type { m.mime = t }
                m.kind = MediaKind.infer(url: m.url, mime: m.mime)
            }
        }

        if m.duration == nil || m.duration == 0, m.kind.avPlayerSupported || m.kind == .hls {
            if let d = await assetDuration(url) {
                m.duration = d
            }
        }
        m.probed = true
        return m
    }

    private static func head(_ url: URL) async -> (length: Int64?, type: String?)? {
        var req = URLRequest(url: url)
        req.httpMethod = "HEAD"
        req.timeoutInterval = 8
        req.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        do {
            let (_, res) = try await URLSession.shared.data(for: req)
            guard let http = res as? HTTPURLResponse else { return nil }
            let lenHdr = http.value(forHTTPHeaderField: "Content-Length")
            var length = lenHdr.flatMap { Int64($0) }
            if let range = http.value(forHTTPHeaderField: "Content-Range"),
               let total = range.split(separator: "/").last,
               let n = Int64(total) {
                length = n
            }
            let type = http.value(forHTTPHeaderField: "Content-Type")
            return (length, type)
        } catch {
            return nil
        }
    }

    private static func assetDuration(_ url: URL) async -> Double? {
        let asset = AVURLAsset(url: url)
        do {
            let d = try await withTimeout(seconds: 8) {
                try await asset.load(.duration)
            }
            let s = d.seconds
            return s.isFinite && s > 0 ? s : nil
        } catch {
            return nil
        }
    }

    private static func withTimeout<T>(seconds: Double, _ work: @escaping () async throws -> T) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await work() }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                throw URLError(.timedOut)
            }
            let first = try await group.next()!
            group.cancelAll()
            return first
        }
    }
}

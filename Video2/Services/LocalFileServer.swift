import Foundation
import Network

enum HLSPlaylistFix {
    static func ensureVOD(playlist url: URL) {
        guard var text = try? String(contentsOf: url, encoding: .utf8) else { return }
        var changed = false
        if !text.contains("#EXTM3U") {
            text = "#EXTM3U\n" + text
            changed = true
        }
        if !text.uppercased().contains("EXT-X-PLAYLIST-TYPE") {
            text = text.replacingOccurrences(of: "#EXTM3U", with: "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD")
            changed = true
        }
        if !text.contains("#EXT-X-ENDLIST") {
            if !text.hasSuffix("\n") { text += "\n" }
            text += "#EXT-X-ENDLIST\n"
            changed = true
        }
        if changed {
            try? text.write(to: url, atomically: true, encoding: .utf8)
        }
    }
}

/// AVPlayer على iOS لا يشغّل HLS من file:// — نخدم المجلد عبر HTTP محلي.
final class LocalFileServer {
    static let shared = LocalFileServer()
    private var listener: NWListener?
    private var root: URL?
    private(set) var port: UInt16 = 8765
    private let queue = DispatchQueue(label: "video2.httpserver", qos: .userInitiated)

    func playbackURL(for video: SavedVideo) throws -> URL {
        if video.kind == .hls || video.localURL.pathExtension.lowercased() == "m3u8" {
            let folder = video.localURL.deletingLastPathComponent()
            HLSPlaylistFix.ensureVOD(playlist: video.localURL)
            try bind(root: folder)
            return URL(string: "http://127.0.0.1:\(port)/index.m3u8")!
        }
        return video.localURL
    }

    func bind(root: URL) throws {
        self.root = root
        if listener != nil { return }
        var lastError: Error?
        for candidate in [UInt16(8765), 8766, 8767, 18765] {
            do {
                let l = try NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: candidate)!)
                let ready = DispatchSemaphore(value: 0)
                l.stateUpdateHandler = { state in
                    if case .ready = state { ready.signal() }
                    if case .failed = state { ready.signal() }
                }
                l.newConnectionHandler = { [weak self] conn in self?.accept(conn) }
                l.start(queue: queue)
                _ = ready.wait(timeout: .now() + 2)
                listener = l
                port = candidate
                return
            } catch {
                lastError = error
            }
        }
        throw lastError ?? HLSError.network
    }

    private func accept(_ conn: NWConnection) {
        conn.start(queue: queue)
        receive(conn, buffer: Data())
    }

    private func receive(_ conn: NWConnection, buffer: Data) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { conn.cancel(); return }
            if error != nil { conn.cancel(); return }
            var buf = buffer
            if let data { buf.append(data) }
            if let range = buf.range(of: Data("\r\n\r\n".utf8)) {
                let head = String(data: buf.subdata(in: 0..<range.lowerBound), encoding: .utf8) ?? ""
                self.respond(conn, header: head)
                return
            }
            if isComplete { conn.cancel(); return }
            self.receive(conn, buffer: buf)
        }
    }

    private func respond(_ conn: NWConnection, header: String) {
        guard let root else { conn.cancel(); return }
        let first = header.components(separatedBy: "\r\n").first ?? ""
        let parts = first.split(separator: " ")
        let rawPath = parts.count >= 2 ? String(parts[1]) : "/"
        let decoded = rawPath.removingPercentEncoding ?? rawPath
        var rel = decoded
        if let q = rel.firstIndex(of: "?") { rel = String(rel[..<q]) }
        if rel.hasPrefix("/") { rel.removeFirst() }
        if rel.isEmpty { rel = "index.m3u8" }
        let file = root.appendingPathComponent(rel).standardizedFileURL
        guard file.path.hasPrefix(root.standardizedFileURL.path) else {
            send(conn, status: 403, type: "text/plain", body: Data("forbidden".utf8), range: nil)
            return
        }
        guard let data = try? Data(contentsOf: file) else {
            send(conn, status: 404, type: "text/plain", body: Data("not found".utf8), range: nil)
            return
        }
        let type = mime(file.pathExtension)
        var byteRange: Range<Int>?
        if let line = header.split(separator: "\r\n").first(where: { $0.lowercased().hasPrefix("range:") }) {
            let spec = line.drop { $0 != ":" }.dropFirst().trimmingCharacters(in: .whitespaces)
            if spec.hasPrefix("bytes=") {
                let nums = spec.dropFirst(6).split(separator: "-")
                let start = Int(nums.first ?? "0") ?? 0
                let end = nums.count > 1 ? (Int(nums[1]) ?? (data.count - 1)) : (data.count - 1)
                let s = min(max(0, start), data.count)
                let e = min(max(s, end + 1), data.count)
                byteRange = s..<e
            }
        }
        send(conn, status: byteRange == nil ? 200 : 206, type: type, body: data, range: byteRange)
    }

    private func send(_ conn: NWConnection, status: Int, type: String, body: Data, range: Range<Int>?) {
        let slice: Data
        var extra = ""
        if let range {
            slice = body.subdata(in: range)
            extra = "Content-Range: bytes \(range.lowerBound)-\(range.upperBound - 1)/\(body.count)\r\n"
        } else {
            slice = body
        }
        var head = "HTTP/1.1 \(status) \(status == 206 ? "Partial Content" : "OK")\r\n"
        head += "Content-Type: \(type)\r\n"
        head += "Content-Length: \(slice.count)\r\n"
        head += extra
        head += "Accept-Ranges: bytes\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        var packet = Data(head.utf8)
        packet.append(slice)
        conn.send(content: packet, completion: .contentProcessed { _ in
            conn.cancel()
        })
    }

    private func mime(_ ext: String) -> String {
        switch ext.lowercased() {
        case "m3u8": return "application/vnd.apple.mpegurl"
        case "ts", "mts": return "video/mp2t"
        case "m4s", "mp4", "m4v": return "video/mp4"
        case "aac": return "audio/aac"
        case "mp3": return "audio/mpeg"
        case "bin", "key": return "application/octet-stream"
        default: return "application/octet-stream"
        }
    }
}

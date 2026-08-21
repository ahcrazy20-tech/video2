import Foundation

enum HLSError: LocalizedError {
    case badPlaylist
    case drmProtected(DRMKind)
    case network

    var errorDescription: String? {
        switch self {
        case .badPlaylist: return "تعذر قراءة قائمة HLS."
        case .drmProtected(let k): return k.messageAR
        case .network: return "فشل تنزيل أجزاء البث."
        }
    }
}

enum HLSInspector {
    static func inspect(playlist: String) -> DRMKind {
        let u = playlist.uppercased()
        if u.contains("EXT-X-KEY:METHOD=SAMPLE-AES") || u.contains("SAMPLE-AES-CTR") {
            return .encryptedHLS
        }
        if u.contains("COM.APPLE.STREAMINGKEYDELIVERY") || u.contains("FAIRPLAY") || u.contains("SKD://") {
            return .fairplay
        }
        if u.contains("WIDEVINE") {
            return .widevine
        }
        if u.contains("EXT-X-SESSION-KEY") && u.contains("SAMPLE-AES") {
            return .encryptedHLS
        }
        return .none
    }

    static func isMaster(_ text: String) -> Bool {
        text.contains("#EXT-X-STREAM-INF")
    }

    static func mediaURLs(from text: String, base: URL) -> [URL] {
        var urls: [URL] = []
        for rawLine in text.components(separatedBy: .newlines) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix("#") { continue }
            if let u = URL(string: line, relativeTo: base)?.absoluteURL {
                urls.append(u)
            }
        }
        return urls
    }

    static func bestVariant(from master: String, base: URL) -> URL? {
        var best: (bw: Int, url: URL)?
        let lines = master.components(separatedBy: .newlines)
        var pendingBW = 0
        for raw in lines {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("#EXT-X-STREAM-INF") {
                pendingBW = 0
                if let r = line.range(of: "BANDWIDTH=") {
                    let rest = line[r.upperBound...]
                    pendingBW = Int(rest.prefix { $0.isNumber }) ?? 0
                }
            } else if !line.isEmpty && !line.hasPrefix("#") {
                if let u = URL(string: line, relativeTo: base)?.absoluteURL {
                    if best == nil || pendingBW >= (best?.bw ?? 0) {
                        best = (pendingBW, u)
                    }
                }
            }
        }
        return best?.url
    }
}

final class HLSDownloader {
    func download(masterURL: URL, destFolder: URL, progress: @escaping (Double) -> Void) async throws -> URL {
        let (data, _) = try await URLSession.shared.data(from: masterURL)
        guard let text = String(data: data, encoding: .utf8) else { throw HLSError.badPlaylist }
        let drm = HLSInspector.inspect(playlist: text)
        if drm.isProtected { throw HLSError.drmProtected(drm) }

        var mediaPlaylist = text
        var mediaURL = masterURL
        if HLSInspector.isMaster(text) {
            guard let variant = HLSInspector.bestVariant(from: text, base: masterURL) else { throw HLSError.badPlaylist }
            mediaURL = variant
            let (d2, _) = try await URLSession.shared.data(from: variant)
            guard let t2 = String(data: d2, encoding: .utf8) else { throw HLSError.badPlaylist }
            let drm2 = HLSInspector.inspect(playlist: t2)
            if drm2.isProtected { throw HLSError.drmProtected(drm2) }
            mediaPlaylist = t2
        }

        try FileManager.default.createDirectory(at: destFolder, withIntermediateDirectories: true)
        let segs = HLSInspector.mediaURLs(from: mediaPlaylist, base: mediaURL)
        if segs.isEmpty { throw HLSError.badPlaylist }

        var rewritten = ""
        let lines = mediaPlaylist.components(separatedBy: .newlines)
        var segIndex = 0
        let total = max(segs.count, 1)

        for raw in lines {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty {
                rewritten += "\n"
                continue
            }
            if line.hasPrefix("#") {
                if line.uppercased().contains("EXT-X-KEY:METHOD=AES-128") {
                    rewritten += try await rewriteAESKeyLine(line, base: mediaURL, folder: destFolder) + "\n"
                } else {
                    rewritten += line + "\n"
                }
                continue
            }
            let remote = URL(string: line, relativeTo: mediaURL)!.absoluteURL
            let name = String(format: "seg_%04d%@", segIndex, (remote.pathExtension.isEmpty ? ".ts" : ".\(remote.pathExtension)"))
            let local = destFolder.appendingPathComponent(name)
            let (segData, _) = try await URLSession.shared.data(from: remote)
            try segData.write(to: local, options: .atomic)
            rewritten += name + "\n"
            segIndex += 1
            progress(Double(segIndex) / Double(total))
        }

        if !rewritten.contains("#EXTM3U") { rewritten = "#EXTM3U\n" + rewritten }
        if !rewritten.uppercased().contains("EXT-X-PLAYLIST-TYPE") {
            rewritten = rewritten.replacingOccurrences(of: "#EXTM3U", with: "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD")
        }
        if !rewritten.contains("#EXT-X-ENDLIST") { rewritten += "#EXT-X-ENDLIST\n" }
        let playlistURL = destFolder.appendingPathComponent("index.m3u8")
        try rewritten.write(to: playlistURL, atomically: true, encoding: .utf8)
        return playlistURL
    }

    /// مفتاح AES-128 الثابت في HLS العادي (ليس FairPlay). يُحفظ محلياً للتشغيل الأوفلاين.
    private func rewriteAESKeyLine(_ line: String, base: URL, folder: URL) async throws -> String {
        guard let uriRange = line.range(of: "URI=\"") else { return line }
        let rest = line[uriRange.upperBound...]
        guard let end = rest.firstIndex(of: "\"") else { return line }
        let uriStr = String(rest[..<end])
        if uriStr.lowercased().hasPrefix("skd:") {
            throw HLSError.drmProtected(.fairplay)
        }
        guard let keyURL = URL(string: uriStr, relativeTo: base)?.absoluteURL else { return line }
        let (keyData, _) = try await URLSession.shared.data(from: keyURL)
        let keyName = "key.bin"
        try keyData.write(to: folder.appendingPathComponent(keyName), options: .atomic)
        return line.replacingOccurrences(of: uriStr, with: keyName)
    }
}

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

    static func variants(from master: String, base: URL) -> [HLSStreamVariant] {
        guard isMaster(master) else { return [] }
        var out: [HLSStreamVariant] = []
        let lines = master.components(separatedBy: .newlines)
        var pendingBW = 0
        var pendingW: Int?
        var pendingH: Int?
        var pendingCodecs: String?
        for raw in lines {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("#EXT-X-STREAM-INF") {
                pendingBW = intAttr(line, key: "BANDWIDTH") ?? 0
                pendingCodecs = stringAttr(line, key: "CODECS")
                pendingW = nil
                pendingH = nil
                if let res = stringAttr(line, key: "RESOLUTION") {
                    let parts = res.lowercased().split(separator: "x")
                    if parts.count == 2 {
                        pendingW = Int(parts[0])
                        pendingH = Int(parts[1])
                    }
                }
            } else if !line.isEmpty && !line.hasPrefix("#") {
                if let u = URL(string: line, relativeTo: base)?.absoluteURL {
                    out.append(HLSStreamVariant(
                        url: u.absoluteString,
                        bandwidth: pendingBW,
                        width: pendingW,
                        height: pendingH,
                        codecs: pendingCodecs
                    ))
                }
            }
        }
        var seen = Set<String>()
        return out.filter { seen.insert($0.url).inserted }
            .sorted { ($0.height ?? 0, $0.bandwidth) > ($1.height ?? 0, $1.bandwidth) }
    }

    static func pickVariant(_ variants: [HLSStreamVariant], maxHeight: Int?) -> HLSStreamVariant? {
        guard !variants.isEmpty else { return nil }
        guard let maxH = maxHeight, maxH > 0 else {
            return variants.max(by: { ($0.height ?? 0, $0.bandwidth) < ($1.height ?? 0, $1.bandwidth) })
        }
        let fit = variants.filter { ($0.height ?? Int.max) <= maxH }
        if let best = fit.max(by: { ($0.height ?? 0, $0.bandwidth) < ($1.height ?? 0, $1.bandwidth) }) {
            return best
        }
        return variants.min(by: { ($0.height ?? Int.max, $0.bandwidth) < ($1.height ?? Int.max, $1.bandwidth) })
    }

    static func bestVariant(from master: String, base: URL) -> URL? {
        let vars = variants(from: master, base: base)
        guard let best = pickVariant(vars, maxHeight: nil) else { return nil }
        return URL(string: best.url)
    }

    private static func intAttr(_ line: String, key: String) -> Int? {
        guard let r = line.range(of: "\(key)=") else { return nil }
        let rest = line[r.upperBound...]
        return Int(rest.prefix { $0.isNumber })
    }

    private static func stringAttr(_ line: String, key: String) -> String? {
        guard let r = line.range(of: "\(key)=") else { return nil }
        var rest = line[r.upperBound...]
        if rest.hasPrefix("\"") {
            rest = rest.dropFirst()
            guard let end = rest.firstIndex(of: "\"") else { return nil }
            return String(rest[..<end])
        }
        let value = rest.prefix { $0 != "," && !$0.isWhitespace }
        return value.isEmpty ? nil : String(value)
    }
}

final class HLSDownloader {
    var segmentConcurrency: Int = 6

    func download(masterURL: URL,
                  destFolder: URL,
                  auth: DownloadAuth? = nil,
                  maxHeight: Int? = nil,
                  progress: @escaping (Double) -> Void) async throws -> URL {
        let data = try await fetch(masterURL, auth: auth)
        guard let text = String(data: data, encoding: .utf8) else { throw HLSError.badPlaylist }
        let drm = HLSInspector.inspect(playlist: text)
        if drm.isProtected { throw HLSError.drmProtected(drm) }

        var mediaPlaylist = text
        var mediaURL = masterURL
        if HLSInspector.isMaster(text) {
            let vars = HLSInspector.variants(from: text, base: masterURL)
            let picked = HLSInspector.pickVariant(vars, maxHeight: maxHeight)
            guard let variantURL = picked.flatMap({ URL(string: $0.url) }) ?? HLSInspector.bestVariant(from: text, base: masterURL) else {
                throw HLSError.badPlaylist
            }
            mediaURL = variantURL
            let d2 = try await fetch(variantURL, auth: auth)
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
        var planned: [(remote: URL, local: URL, name: String)] = []
        var segIndex = 0

        for raw in lines {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty {
                rewritten += "\n"
                continue
            }
            if line.hasPrefix("#") {
                if line.uppercased().contains("EXT-X-KEY:METHOD=AES-128") {
                    rewritten += try await rewriteAESKeyLine(line, base: mediaURL, folder: destFolder, auth: auth) + "\n"
                } else if line.uppercased().hasPrefix("#EXT-X-MAP:") {
                    rewritten += try await rewriteMapLine(line, base: mediaURL, folder: destFolder, auth: auth) + "\n"
                } else {
                    rewritten += line + "\n"
                }
                continue
            }
            let remote = URL(string: line, relativeTo: mediaURL)!.absoluteURL
            let name = String(format: "seg_%04d%@", segIndex, (remote.pathExtension.isEmpty ? ".ts" : ".\(remote.pathExtension)"))
            let local = destFolder.appendingPathComponent(name)
            planned.append((remote, local, name))
            rewritten += name + "\n"
            segIndex += 1
        }

        if !rewritten.contains("#EXTM3U") { rewritten = "#EXTM3U\n" + rewritten }
        if !rewritten.uppercased().contains("EXT-X-PLAYLIST-TYPE") {
            rewritten = rewritten.replacingOccurrences(of: "#EXTM3U", with: "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD")
        }
        if !rewritten.contains("#EXT-X-ENDLIST") { rewritten += "#EXT-X-ENDLIST\n" }
        let playlistURL = destFolder.appendingPathComponent("index.m3u8")
        try rewritten.write(to: playlistURL, atomically: true, encoding: .utf8)

        let total = max(planned.count, 1)
        let already = planned.filter { fileLooksComplete($0.local) }.count
        progress(Double(already) / Double(total))

        try await downloadSegments(planned, auth: auth) { done in
            progress(Double(done) / Double(total))
        }
        progress(1)
        return playlistURL
    }

    private func downloadSegments(_ planned: [(remote: URL, local: URL, name: String)],
                                  auth: DownloadAuth?,
                                  progress: @escaping (Int) -> Void) async throws {
        let pending = planned.filter { !fileLooksComplete($0.local) }
        if pending.isEmpty {
            progress(planned.count)
            return
        }
        var doneCount = planned.count - pending.count
        progress(doneCount)

        try await withThrowingTaskGroup(of: Void.self) { group in
            var next = 0
            let limit = min(max(1, segmentConcurrency), pending.count)

            func enqueue(_ i: Int) {
                let item = pending[i]
                group.addTask { [auth] in
                    if Task.isCancelled { throw CancellationError() }
                    if self.fileLooksComplete(item.local) { return }
                    let data = try await self.fetch(item.remote, auth: auth)
                    try data.write(to: item.local, options: .atomic)
                }
            }

            while next < limit {
                enqueue(next)
                next += 1
            }

            for try await _ in group {
                // جسم الحلقة يعمل تسلسلياً داخل مهمة واحدة — لا حاجة لقفل
                doneCount += 1
                progress(doneCount)
                if Task.isCancelled {
                    group.cancelAll()
                    throw CancellationError()
                }
                if next < pending.count {
                    enqueue(next)
                    next += 1
                }
            }
        }
    }

    private func fileLooksComplete(_ url: URL) -> Bool {
        guard let sz = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize else { return false }
        return sz > 32
    }

    private func rewriteMapLine(_ line: String, base: URL, folder: URL, auth: DownloadAuth?) async throws -> String {
        guard let start = line.range(of: "URI=\"") else { return line }
        let rest = line[start.upperBound...]
        guard let end = rest.firstIndex(of: "\"") else { return line }
        let uri = String(rest[..<end])
        guard let remote = URL(string: uri, relativeTo: base)?.absoluteURL else { return line }
        let name = "init\(remote.pathExtension.isEmpty ? ".mp4" : ".\(remote.pathExtension)")"
        let dest = folder.appendingPathComponent(name)
        if !fileLooksComplete(dest) {
            guard let data = try? await fetch(remote, auth: auth) else {
                print("[HLSDownloader] ⚠️ init segment fetch failed — leaving EXT-X-MAP URI as-is")
                return line
            }
            try? data.write(to: dest, options: .atomic)
        }
        return line.replacingOccurrences(of: uri, with: name)
    }

    private func rewriteAESKeyLine(_ line: String, base: URL, folder: URL, auth: DownloadAuth?) async throws -> String {
        guard let uriRange = line.range(of: "URI=\"") else { return line }
        let rest = line[uriRange.upperBound...]
        guard let end = rest.firstIndex(of: "\"") else { return line }
        let uriStr = String(rest[..<end])
        if uriStr.lowercased().hasPrefix("skd:") {
            throw HLSError.drmProtected(.fairplay)
        }
        guard let keyURL = URL(string: uriStr, relativeTo: base)?.absoluteURL else { return line }
        let dest = folder.appendingPathComponent("key.bin")
        if !fileLooksComplete(dest) {
            guard let keyData = try? await fetch(keyURL, auth: auth) else {
                print("[HLSDownloader] ⚠️ AES key fetch failed — leaving URI as-is")
                return line
            }
            try? keyData.write(to: dest, options: .atomic)
        }
        return line.replacingOccurrences(of: uriStr, with: "key.bin")
    }

    private func fetch(_ url: URL, auth: DownloadAuth?, attempts: Int = 3, timeout: TimeInterval = 30) async throws -> Data {
        var lastError: Error = URLError(.unknown)
        for _ in 0..<max(1, attempts) {
            if Task.isCancelled { throw CancellationError() }
            do {
                var req = URLRequest(url: url)
                req.timeoutInterval = timeout
                req.cachePolicy = .reloadIgnoringLocalCacheData
                auth?.apply(to: &req)
                let (data, response) = try await URLSession.shared.data(for: req)
                if let hr = response as? HTTPURLResponse, !(200..<299).contains(hr.statusCode) {
                    lastError = URLError(.badServerResponse)
                    try await Task.sleep(nanoseconds: 400_000_000)
                    continue
                }
                return data
            } catch {
                if Task.isCancelled { throw CancellationError() }
                lastError = error
                try await Task.sleep(nanoseconds: 500_000_000)
            }
        }
        throw lastError
    }
}

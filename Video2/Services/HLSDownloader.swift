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
        // نحدّ التقدّم إلى ~5 مرات/ثانية — دفعُ تحديث بعد كل segment يُغرق الـ main
        // actor عشرات المرات في الثانية فيجمّد التطبيق أثناء تنزيل HLS طويل.
        var lastProgress = Date.distantPast

        for raw in lines {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty {
                rewritten += "\n"
                continue
            }
            if line.hasPrefix("#") {
                if line.uppercased().contains("EXT-X-KEY:METHOD=AES-128") {
                    rewritten += try await rewriteAESKeyLine(line, base: mediaURL, folder: destFolder) + "\n"
                } else if line.uppercased().hasPrefix("#EXT-X-MAP:") {
                    // fMP4 HLS يحتاج init segment؛ ترك URI remote يجعل النسخة
                    // الأوفلاين تفشل حتى لو تم تنزيل كل ملفات m4s.
                    rewritten += try await rewriteMapLine(line, base: mediaURL, folder: destFolder) + "\n"
                } else {
                    rewritten += line + "\n"
                }
                continue
            }
            if Task.isCancelled { throw CancellationError() }
            let remote = URL(string: line, relativeTo: mediaURL)!.absoluteURL
            let name = String(format: "seg_%04d%@", segIndex, (remote.pathExtension.isEmpty ? ".ts" : ".\(remote.pathExtension)"))
            let local = destFolder.appendingPathComponent(name)
            let segData = try await fetch(remote)
            try segData.write(to: local, options: .atomic)
            rewritten += name + "\n"
            segIndex += 1
            let now = Date()
            if segIndex == total || now.timeIntervalSince(lastProgress) >= 0.2 {
                lastProgress = now
                progress(Double(segIndex) / Double(total))
            }
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

    private func rewriteMapLine(_ line: String, base: URL, folder: URL) async throws -> String {
        guard let start = line.range(of: "URI=\"") else { return line }
        let rest = line[start.upperBound...]
        guard let end = rest.firstIndex(of: "\"") else { return line }
        let uri = String(rest[..<end])
        guard let remote = URL(string: uri, relativeTo: base)?.absoluteURL else { return line }
        guard let data = try? await fetch(remote) else {
            // فشل جلب init segment (يحتاج cookies/ترويسات خاصة غالباً) — لا نُسقط التحميل كلّه.
            // نُبقي الـ URI كما هو حتى يُحفظ الفيديو بدل أن يفشل التحميل بأكمله.
            print("[HLSDownloader] ⚠️ init segment fetch failed — leaving EXT-X-MAP URI as-is (download continues)")
            return line
        }
        let name = "init\(remote.pathExtension.isEmpty ? ".mp4" : ".\(remote.pathExtension)")"
        try? data.write(to: folder.appendingPathComponent(name), options: .atomic)
        return line.replacingOccurrences(of: uri, with: name)
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
        guard let keyData = try? await fetch(keyURL) else {
            // تعذّر جلب المفتاح (قد يحتاج ترويسات/دخول) — لا نُسقط التحميل؛ نُبقي الـ URI
            // بعيداً حتى يُحفظ الفيديو (قد لا يُشغَّل أوفلاين لكن التحميل يكتمل).
            print("[HLSDownloader] ⚠️ AES key fetch failed — leaving URI as-is")
            return line
        }
        let keyName = "key.bin"
        try? keyData.write(to: folder.appendingPathComponent(keyName), options: .atomic)
        return line.replacingOccurrences(of: uriStr, with: keyName)
    }

    /// جلب بيانات من شبكة مع مهلة لكل طلب + إعادة محاولة (حتى لا يُعلّق تحميل
    /// سلسلة كبيرة بسبب segment علّق، ومع احترام الإلغاء).
    private func fetch(_ url: URL, attempts: Int = 3, timeout: TimeInterval = 30) async throws -> Data {
        var lastError: Error = URLError(.unknown)
        for _ in 0..<max(1, attempts) {
            if Task.isCancelled { throw CancellationError() }
            do {
                var req = URLRequest(url: url)
                req.timeoutInterval = timeout
                req.cachePolicy = .reloadIgnoringLocalCacheData
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

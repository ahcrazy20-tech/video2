import Foundation

// MARK: - صيد الفيديو (Magic Resolver)
//
// يأخذ نتيجة البحث (أرشيف / يوتيوب / داليموشن / بيروتيوب / فيميو / أي موقع على الويب)
// ويحوّلها إلى «مصادر تشغيل» جاهزة: روابط ملفات مباشرة أو قوائم HLS مع الجودات.
//
// مبادئ:
// • لا يُكسر DRM: إن اكتُشف تشفير/ترخيص تُرجع النتيجة فارغة مع سبب، ويُعرض المتصفح كبديل.
// • لا يغيّر خط التحميل: التحميل يمرّ عبر DownloadManager.enqueueManual كما هو.
// • فشل مصدر لا يوقف الباقي: كل محاولة network داخل do-catch/try?.

struct MagicStreamVariant: Identifiable, Hashable {
    var url: String
    var label: String
    var kind: MediaKind
    var sizeBytes: Int64?
    var height: Int?
    var pageURL: String?
    /// ترويسات مطلوبة من السيرفر البعيد (Referer / Origin / UA).
    var headers: [String: String] = [:]
    /// المرور بالوسيط المحلي إجبارياً (قوائم HLS أو مصادر بالترويسات).
    var needsProxy: Bool = false
    var downloadable: Bool = true

    var id: String { url }

    var isPlayableByEngine: Bool { kind.avPlayerSupported }
    var isHLS: Bool { kind == .hls }

    var sizeText: String {
        guard let sizeBytes, sizeBytes > 0 else { return "" }
        return ByteCountFormatter.string(fromByteCount: sizeBytes, countStyle: .file)
    }

    var qualityText: String {
        if let height, height > 0 { return "\(height)p" }
        return kind.titleAR
    }

    /// عنوان التشغيل الفعلي: مباشر إن أمكن، وإلا عبر الوسيط المحلي.
    func playbackURL(forceProxy: Bool = false) -> String? {
        if isHLS {
            // القوائم تحتاج إعادة كتابة للقطع — نمرّرها دائماً على الوسيط
            return MagicStreamProxy.shared.register(url, headers: headers)
        }
        guard forceProxy || needsProxy || !headers.isEmpty else { return url }
        return MagicStreamProxy.shared.register(url, headers: headers)
    }

    /// ترويسات التحميل — تُسلَّم كما هي لخط التحميل الأصلي.
    var downloadAuth: DownloadAuth {
        var auth = DownloadAuth.default
        auth.referer = pageURL
        if let ua = headers["User-Agent"] { auth.userAgent = ua }
        if let cookie = headers["Cookie"] { auth.cookie = cookie }
        return auth
    }
}

struct MagicResolution {
    var variants: [MagicStreamVariant] = []
    /// مفتاح رسالة نصية تُعرض عند الفشل (لها ترجمة في L10n).
    var note: String?
    /// لا يوجد مصدر قابل للتشغيل — يُنصح بفتح الصفحة في المتصفح.
    var needsBrowser: Bool = false

    var playable: [MagicStreamVariant] { variants.filter { $0.isPlayableByEngine } }
    var downloadable: [MagicStreamVariant] { variants.filter { $0.downloadable } }
    var isEmpty: Bool { variants.isEmpty }

    /// أفضل مصدر تشغيل، مع مراعاة الجودة المفضّلة إن حُدّدت في صيغة البحث.
    func best(preferredHeight: Int? = nil) -> MagicStreamVariant? {
        let list = playable
        guard !list.isEmpty else { return nil }
        if let want = preferredHeight {
            let close = ranked(list.filter { ($0.height ?? 0) > 0 && abs(($0.height ?? 0) - want) <= 260 })
            if let pick = close.first { return pick }
        }
        return ranked(list).first
    }

    private func ranked(_ list: [MagicStreamVariant]) -> [MagicStreamVariant] {
        list.sorted { a, b in
            let ha = a.height ?? 0, hb = b.height ?? 0
            if ha != hb { return ha > hb }
            if a.isHLS != b.isHLS { return a.isHLS }
            return (a.sizeBytes ?? 0) > (b.sizeBytes ?? 0)
        }
    }
}

enum MagicResolver {

    /// تحويل نتيجة بحث إلى مصادر تشغيل/تحميل.
    /// - Parameter deep: يفتح الصفحة في متصفح خفي لاصطياد ما تولّده السكربتات فقط.
    static func resolve(_ result: MagicSearchResult, deep: Bool = false) async -> MagicResolution {
        var out: MagicResolution
        switch result.source {
        case .archive: out = archive(result)
        case .dailymotion: out = await dailymotion(result)
        case .youtube: out = await youtube(result)
        case .peertube: out = await peerTube(result)
        case .vimeo: out = await vimeo(result)
        case .web: out = await webpage(result)
        }
        if deep && out.playable.isEmpty {
            let hunted = await MagicPageHunter.hunt(url: result.pageURL, title: result.title)
            if !hunted.isEmpty {
                out.variants.append(contentsOf: hunted)
                out.note = nil
                out.needsBrowser = false
            }
        }
        var seen = Set<String>()
        out.variants = out.variants.filter { seen.insert($0.url).inserted }
        if out.variants.isEmpty {
            out.needsBrowser = true
            if out.note == nil { out.note = "resolve.failed" }
        }
        return out
    }

    /// رابط يلصقه المستخدم: ملف وسائط مباشر أو صفحة يُصيد منها.
    static func resolveURL(_ urlString: String, title: String) async -> MagicResolution {
        let inferred = MediaKind.infer(url: urlString, mime: nil)
        let lower = urlString.lowercased()
        if inferred.isCompleteVideo || lower.contains("videoplayback") {
            let kind: MediaKind = inferred == .other ? .mp4 : inferred
            return MagicResolution(variants: [
                MagicStreamVariant(url: urlString, label: kind.titleAR, kind: kind,
                                   pageURL: nil, needsProxy: kind == .hls, downloadable: true)
            ])
        }
        let fake = MagicSearchResult(id: "url-\(urlString)", title: title, duration: nil,
                                     thumbnailURL: nil, source: .web, pageURL: urlString,
                                     uploader: nil, views: nil, snippet: nil, downloads: [])
        var out = await webpage(fake)
        if out.playable.isEmpty {
            let hunted = await MagicPageHunter.hunt(url: urlString, title: title)
            if !hunted.isEmpty {
                out.variants.append(contentsOf: hunted)
                out.note = nil
                out.needsBrowser = false
            }
        }
        if out.variants.isEmpty {
            out.needsBrowser = true
            out.note = "web.noDirectFile"
        }
        return out
    }

    // MARK: - أرشيف الإنترنت (ملفات مباشرة في نتيجة البحث أصلاً)

    static func archive(_ result: MagicSearchResult) -> MagicResolution {
        var variants: [MagicStreamVariant] = []
        for option in result.downloads {
            let raw = MediaKind.infer(url: option.url, mime: nil)
            variants.append(MagicStreamVariant(
                url: option.url,
                label: option.label,
                kind: raw == .other ? .mp4 : raw,
                sizeBytes: option.sizeBytes,
                height: option.height,
                pageURL: result.pageURL,
                downloadable: true
            ))
        }
        return MagicResolution(variants: variants)
    }

    // MARK: - داليموشن (player metadata → قوائم HLS بالجودات)

    static func dailymotion(_ result: MagicSearchResult) async -> MagicResolution {
        guard let id = capture(result.pageURL, pattern: "/video/([A-Za-z0-9]+)") else {
            return MagicResolution(note: "resolve.badLink", needsBrowser: true)
        }
        return await dailymotion(id: id, result: result)
    }

    static func dailymotion(id: String, result: MagicSearchResult) async -> MagicResolution {
        guard let url = URL(string: "https://www.dailymotion.com/player/metadata/video/\(id)") else {
            return MagicResolution(needsBrowser: true)
        }
        guard let obj = (try? await MagicNet.json(url)) as? [String: Any] else {
            return MagicResolution(note: "resolve.failed", needsBrowser: true)
        }
        if (obj["protected_delivery"] as? NSNumber)?.boolValue == true
            || (obj["is_password_protected"] as? NSNumber)?.boolValue == true {
            return MagicResolution(note: "resolve.protected", needsBrowser: true)
        }
        let headers = ["Referer": "https://www.dailymotion.com/", "Origin": "https://www.dailymotion.com"]
        var variants: [MagicStreamVariant] = []
        if let qualities = obj["qualities"] as? [String: Any] {
            for (name, raw) in qualities {
                guard let arr = raw as? [[String: Any]] else { continue }
                for entry in arr {
                    guard let u = entry["url"] as? String, u.hasPrefix("http") else { continue }
                    let type = (entry["type"] as? String) ?? ""
                    let isHLS = type.contains("mpegurl") || u.contains(".m3u8")
                    let kind: MediaKind = isHLS ? .hls : MediaKind.infer(url: u, mime: type)
                    variants.append(MagicStreamVariant(
                        url: u,
                        label: "داليموشن · \(labelForQuality(name))",
                        kind: kind == .other ? .mp4 : kind,
                        sizeBytes: nil,
                        height: heightFromQualityName(name),
                        pageURL: result.pageURL,
                        headers: headers,
                        needsProxy: isHLS,
                        downloadable: kind == .hls || kind == .mp4
                    ))
                }
            }
        }
        // فتح القائمة الأُم لاستخراج الجودات كخيارات مستقلة
        if let master = variants.first(where: { $0.isHLS }) {
            let expanded = await expandHLSMaster(master: master)
            if !expanded.isEmpty {
                variants = [master] + expanded    // «تلقائي» + جودات مفردة
            }
        }
        if variants.isEmpty { return MagicResolution(note: "resolve.failed", needsBrowser: true) }
        return MagicResolution(variants: variants)
    }

    private static func labelForQuality(_ name: String) -> String {
        switch name.lowercased() {
        case "auto", "autoadaptive": return "تلقائي"
        case "sd": return "SD"
        case "hd720": return "720p"
        case "hd1080": return "1080p"
        case "uv": return "2K"
        default: return name.uppercased()
        }
    }

    private static func heightFromQualityName(_ name: String) -> Int? {
        let n = name.lowercased()
        if n.contains("1080") { return 1080 }
        if n.contains("720") { return 720 }
        if n == "sd" { return 480 }
        let digits = String(n.filter { $0.isNumber })
        return digits.isEmpty ? nil : Int(digits)
    }

    // MARK: - يوتيوب (Piped → Invidious، مع متصفح خفي في الوضع العميق)

    static func youtube(_ result: MagicSearchResult) async -> MagicResolution {
        guard let id = capture(result.pageURL, pattern: "[?&]v=([A-Za-z0-9_-]{6,})")
            ?? capture(result.pageURL, pattern: "youtu\\.be/([A-Za-z0-9_-]{6,})")
            ?? capture(result.pageURL, pattern: "shorts/([A-Za-z0-9_-]{6,})") else {
            return MagicResolution(note: "resolve.badLink", needsBrowser: true)
        }
        var out = await youtubeViaPiped(id: id, page: result.pageURL)
        if out.playable.isEmpty {
            let inv = await youtubeViaInvidious(id: id, page: result.pageURL)
            if !inv.variants.isEmpty { out = inv }
        }
        if out.variants.isEmpty {
            return MagicResolution(note: "yt.blocked", needsBrowser: true)
        }
        return out
    }

    private static func youtubeViaPiped(id: String, page: String?) async -> MagicResolution {
        var variants: [MagicStreamVariant] = []
        for base in PipedProvider.instances {
            var obj: [String: Any]?
            if let url = URL(string: "\(base)/streams/\(id)") {
                obj = (try? await MagicNet.json(url)) as? [String: Any]
            }
            if obj == nil, let url = URL(string: "\(base)/streams"),
               let decoded = (try? await MagicNet.postJSON(url, rawBody: "{\"videoId\":\"\(id)\"}")) as? [String: Any] {
                obj = decoded
            }
            guard let obj else { continue }
            let headers = ["Referer": page ?? "https://www.youtube.com/", "Origin": "https://www.youtube.com"]

            if let hls = obj["hls"] as? String, hls.hasPrefix("http") {
                variants.append(MagicStreamVariant(url: hls, label: "يوتيوب · HLS تلقائي", kind: .hls,
                                                   pageURL: page, downloadable: true))
            }
            for item in (obj["video"] as? [[String: Any]]) ?? [] {
                guard let u = item["url"] as? String, u.hasPrefix("http") else { continue }
                if (item["videoOnly"] as? NSNumber)?.boolValue == true { continue }
                let quality = (item["quality"] as? String) ?? ""
                let format = ((item["format"] as? String) ?? "").uppercased()
                let kind: MediaKind = format.contains("WEBM") ? .webm : .mp4
                variants.append(MagicStreamVariant(
                    url: u,
                    label: "يوتيوب · \(quality.isEmpty ? kind.titleAR : quality)",
                    kind: kind,
                    sizeBytes: nil,
                    height: parseQualityHeight(quality),
                    pageURL: page,
                    headers: headers,
                    downloadable: true
                ))
            }
            if !variants.isEmpty { break }
        }
        if variants.isEmpty { return MagicResolution(note: "yt.blocked", needsBrowser: true) }
        return MagicResolution(variants: variants)
    }

    private static func youtubeViaInvidious(id: String, page: String?) async -> MagicResolution {
        var variants: [MagicStreamVariant] = []
        for base in InvidiousProvider.instances {
            guard let url = URL(string: "\(base)/api/v1/videos/\(id)?fields=title,lengthSeconds,formatStreams,hlsUrl") else { continue }
            guard let obj = (try? await MagicNet.json(url)) as? [String: Any] else { continue }
            if let hls = (obj["hlsUrl"] as? [String: Any])?["url"] as? String, hls.hasPrefix("http") {
                variants.append(MagicStreamVariant(url: hls, label: "يوتيوب · HLS تلقائي", kind: .hls,
                                                   pageURL: page, downloadable: true))
            }
            for item in (obj["formatStreams"] as? [[String: Any]]) ?? [] {
                guard let u = item["url"] as? String, u.hasPrefix("http") else { continue }
                let container = ((item["container"] as? String) ?? "").lowercased()
                if container.contains("webm") { continue }
                let quality = (item["quality"] as? String) ?? ""
                variants.append(MagicStreamVariant(
                    url: u,
                    label: "يوتيوب · \(quality.isEmpty ? "MP4" : quality)",
                    kind: .mp4,
                    height: parseQualityHeight(quality),
                    pageURL: page,
                    downloadable: true
                ))
            }
            if !variants.isEmpty { break }
        }
        if variants.isEmpty { return MagicResolution(note: "yt.blocked", needsBrowser: true) }
        return MagicResolution(variants: variants)
    }

    private static func parseQualityHeight(_ text: String) -> Int? {
        let digits = String(text.lowercased().prefix { $0.isNumber })
        return digits.isEmpty ? nil : Int(digits)
    }

    // MARK: - PeerTube (API النسخة الخاصة بالمضيف)

    static func peerTube(_ result: MagicSearchResult) async -> MagicResolution {
        guard let url = URL(string: result.pageURL), let host = url.host else {
            return MagicResolution(note: "resolve.badLink", needsBrowser: true)
        }
        let uuid = uuidLike(from: result.pageURL)
        guard !uuid.isEmpty,
              let api = URL(string: "https://\(host)/api/v1/videos/\(uuid)?fields=uuid,name,duration,files,streamingPlaylists,live,downloadEnabled") else {
            return MagicResolution(note: "resolve.badLink", needsBrowser: true)
        }
        guard let obj = (try? await MagicNet.json(api)) as? [String: Any] else {
            return MagicResolution(note: "resolve.failed", needsBrowser: true)
        }
        var variants: [MagicStreamVariant] = []
        let downloadEnabled = (obj["downloadEnabled"] as? NSNumber)?.boolValue ?? true

        func add(_ raw: String?, label: String, kindHint: MediaKind?, height: Int?, size: Int64?, downloadable: Bool) {
            guard let raw, raw.hasPrefix("http") else { return }
            let inferred = kindHint ?? MediaKind.infer(url: raw, mime: nil)
            variants.append(MagicStreamVariant(
                url: raw,
                label: label,
                kind: inferred == .other ? .mp4 : inferred,
                sizeBytes: size,
                height: height,
                pageURL: result.pageURL,
                downloadable: downloadable
            ))
        }

        for f in (obj["files"] as? [[String: Any]]) ?? [] {
            let res = (f["resolution"] as? [String: Any])?["label"] as? String
            let ext = ((f["extname"] as? String) ?? "").replacingOccurrences(of: ".", with: "")
            add(f["fileUrl"] as? String,
                label: "PeerTube · \(res ?? ext.uppercased())",
                kindHint: ext.isEmpty ? nil : MediaKind.infer(url: "file.\(ext)", mime: nil),
                height: res.flatMap { Int(String($0.filter { $0.isNumber })) },
                size: (f["size"] as? NSNumber)?.int64Value,
                downloadable: true)
        }
        for pl in (obj["streamingPlaylists"] as? [[String: Any]]) ?? [] {
            add(pl["playlistUrl"] as? String, label: "PeerTube · HLS تلقائي", kindHint: .hls,
                height: nil, size: nil, downloadable: true)
            for f in (pl["files"] as? [[String: Any]]) ?? [] {
                let res = (f["resolution"] as? [String: Any])?["label"] as? String
                let h = res.flatMap { Int(String($0.filter { $0.isNumber })) }
                let size = (f["size"] as? NSNumber)?.int64Value
                add(f["playlistUrl"] as? String, label: "PeerTube · HLS \(res ?? "")", kindHint: .hls,
                    height: h, size: size, downloadable: downloadEnabled)
                add(f["fileUrl"] as? String, label: "PeerTube · \(res ?? "MP4")", kindHint: .mp4,
                    height: h, size: size, downloadable: downloadEnabled)
            }
        }
        if variants.isEmpty { return MagicResolution(note: "resolve.failed", needsBrowser: true) }
        return MagicResolution(variants: variants)
    }

    // MARK: - فيميو (player config — بدون مفاتيح)

    static func vimeo(_ result: MagicSearchResult) async -> MagicResolution {
        guard let id = capture(result.pageURL, pattern: "vimeo\\.com/(?:video/)?(\\d+)") else {
            return MagicResolution(note: "resolve.badLink", needsBrowser: true)
        }
        return await vimeoConfig(id: id, page: result.pageURL)
    }

    static func vimeoConfig(id: String, page: String?) async -> MagicResolution {
        guard let url = URL(string: "https://player.vimeo.com/video/\(id)/config") else {
            return MagicResolution(needsBrowser: true)
        }
        guard let obj = (try? await MagicNet.json(url)) as? [String: Any],
              let request = obj["request"] as? [String: Any],
              let files = request["files"] as? [String: Any] else {
            return MagicResolution(note: "resolve.failed", needsBrowser: true)
        }
        let headers = ["Referer": "https://player.vimeo.com/", "Origin": "https://player.vimeo.com"]
        var variants: [MagicStreamVariant] = []
        for item in (files["progressive"] as? [[String: Any]]) ?? [] {
            guard let u = item["url"] as? String, u.hasPrefix("http") else { continue }
            let lower = u.lowercased()
            if lower.contains("drm/") || lower.contains("cenc") || lower.contains("cbcs") { continue }
            let quality = (item["quality"] as? String) ?? ""
            variants.append(MagicStreamVariant(
                url: u,
                label: "Vimeo · \(quality.isEmpty ? "MP4" : quality)",
                kind: .mp4,
                height: intOf(item["height"]) ?? parseQualityHeight(quality),
                pageURL: page,
                headers: headers,
                needsProxy: true,
                downloadable: true
            ))
        }
        if let cdns = (files["hls"] as? [String: Any])?["cdns"] as? [String: Any] {
            for (_, cdn) in cdns {
                guard let dict = cdn as? [String: Any], let u = dict["url"] as? String, u.hasPrefix("http") else { continue }
                let lower = u.lowercased()
                if lower.contains("drm/") || lower.contains("cbcs") || lower.contains("cenc") { continue }
                variants.append(MagicStreamVariant(url: u, label: "Vimeo · HLS تلقائي", kind: .hls,
                                                   pageURL: page, headers: headers, needsProxy: true,
                                                   downloadable: true))
                break
            }
        }
        if variants.isEmpty { return MagicResolution(note: "resolve.protected", needsBrowser: true) }
        return MagicResolution(variants: variants)
    }

    // MARK: - أي صفحة على الويب

    static func webpage(_ result: MagicSearchResult) async -> MagicResolution {
        let page = result.pageURL
        let lower = page.lowercased()
        let inferred = MediaKind.infer(url: page, mime: nil)
        if inferred.isCompleteVideo || lower.contains(".m3u8") || lower.contains("videoplayback") {
            let kind: MediaKind = inferred == .other ? .mp4 : inferred
            return MagicResolution(variants: [
                MagicStreamVariant(url: page, label: kind.titleAR, kind: kind, pageURL: nil,
                                   needsProxy: kind == .hls, downloadable: true)
            ])
        }
        if let host = URL(string: page)?.host?.lowercased(),
           host.contains("wikimedia.org") || host.contains("wikipedia.org") {
            if let v = await commonsVariant(for: page) { return MagicResolution(variants: [v]) }
        }
        if let vid = capture(page, pattern: "vimeo\\.com/(?:video/)?(\\d+)") {
            let res = await vimeoConfig(id: vid, page: page)
            if !res.variants.isEmpty { return res }
        }
        if let vid = capture(page, pattern: "dailymotion\\.com/video/([A-Za-z0-9]+)") {
            let res = await dailymotion(id: vid, result: result)
            if !res.variants.isEmpty { return res }
        }
        if let peer = peerTubeHostAndID(page) {
            let res = await peerTube(host: peer.host, uuid: peer.uuid, page: page)
            if !res.variants.isEmpty { return res }
        }
        guard let url = MagicStreamProxy.parse(page) else {
            return MagicResolution(note: "resolve.badLink", needsBrowser: true)
        }
        guard let html = try? await MagicNet.html(url) else {
            return MagicResolution(note: "resolve.fetchFailed", needsBrowser: true)
        }
        let found = await scrape(html: html, base: page, title: result.title)
        if found.isEmpty { return MagicResolution(note: "web.noDirectFile", needsBrowser: true) }
        return MagicResolution(variants: found)
    }

    /// PeerTube من رابط صفحة ويب فقط.
    private static func peerTubeHostAndID(_ page: String) -> (host: String, uuid: String)? {
        guard let url = URL(string: page), let host = url.host?.lowercased() else { return nil }
        guard page.contains("/videos/watch/") || page.contains("/videos/embed/") || page.contains("/w/") else { return nil }
        let uuid = uuidLike(from: page)
        guard !uuid.isEmpty else { return nil }
        return (host, uuid)
    }

    private static func peerTube(host: String, uuid: String, page: String) async -> MagicResolution {
        let result = MagicSearchResult(id: "pt-\(uuid)", title: "", duration: nil, thumbnailURL: nil,
                                      source: .peertube, pageURL: page, uploader: nil, views: nil,
                                      snippet: nil, downloads: [])
        return await peerTube(result)
    }

    /// استخراج روابط الوسائط من HTML (بدون تنفيذ JS).
    static func scrape(html: String, base: String, title: String) async -> [MagicStreamVariant] {
        var collected: [String] = []

        func collect(_ pattern: String, _ group: Int = 1) {
            guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else { return }
            let ns = html as NSString
            re.enumerateMatches(in: html, range: NSRange(location: 0, length: ns.length)) { m, _, _ in
                guard let m, m.numberOfRanges > group else { return }
                let raw = ns.substring(with: m.range(at: group))
                let cleaned = Self.unescape(raw).trimmingCharacters(in: .whitespacesAndNewlines)
                if cleaned.isEmpty || cleaned.hasPrefix("blob:") || cleaned.hasPrefix("data:") { return }
                collected.append(cleaned)
            }
        }

        collect("<meta[^>]+property=[\"']og:video(?::secure_url|:url)?[\"'][^>]*content=[\"']([^\"']+)", 1)
        collect("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]*property=[\"']og:video[\"']", 1)
        collect("<meta[^>]+name=[\"']twitter:player:stream[\"'][^>]*content=[\"']([^\"']+)", 1)
        collect("<source[^>]+src=[\"']([^\"']+\\.(?:mp4|m3u8|webm|mov|m4v)[^\"']*)", 1)
        collect("\"(?:contentUrl|content_url|playbackUrl|playback_url|videoUrl|video_url|fileUrl|file_url|video_src|hlsUrl|hls_url)\"\\s*:\\s*\"([^\"]+)\"", 1)
        collect("(https?://[^\\s\"'<>\\\\)+]+\\.(?:mp4|m3u8|mov|webm|mkv|m4v|avi)(?:\\?[^\\s\"'<>]*)?)", 1)

        guard let baseURL = URL(string: base) else { return [] }
        var seen = Set<String>()
        var out: [MagicStreamVariant] = []
        for raw in collected {
            guard let abs = URL(string: raw, relativeTo: baseURL)?.absoluteURL else { continue }
            let s = abs.absoluteString
            guard s.hasPrefix("http"), !seen.contains(Self.canonical(s)) else { continue }
            if AdBlock.filterVideoAds && AdBlock.isAdURL(s) { continue }
            let kind = MediaKind.infer(url: s, mime: nil)
            if kind == .other || kind == .dash || kind == .ts { continue }
            seen.insert(Self.canonical(s))
            out.append(MagicStreamVariant(
                url: s,
                label: "\(kind.titleAR) · \(abs.host ?? "مصدر")",
                kind: kind == .other ? .mp4 : kind,
                sizeBytes: nil,
                height: nil,
                pageURL: base,
                headers: ["Referer": base],
                needsProxy: kind == .hls,
                downloadable: true
            ))
            if out.count >= 12 { break }
        }
        guard !out.isEmpty else { return [] }

        // HEAD خفيف لمعرفة الحجم، حتى تُستبعد الملفات الصغيرة (إعلانات/مقتطفات)
        out = await withTaskGroup(of: (Int, Int64?).self) { group -> [MagicStreamVariant] in
            for (i, v) in out.enumerated() {
                group.addTask { (i, await Self.headSize(v.url, referer: base)) }
            }
            var sizes: [Int: Int64] = [:]
            for await (i, size) in group { if let size { sizes[i] = size } }
            var merged = out
            for i in merged.indices {
                if merged[i].sizeBytes == nil { merged[i].sizeBytes = sizes[i] }
            }
            merged.removeAll { ($0.sizeBytes ?? 0) > 0 && ($0.sizeBytes ?? 0) < 300_000 && !$0.isHLS }
            merged.sort { a, b in
                let sa = a.sizeBytes ?? 0, sb = b.sizeBytes ?? 0
                if sa != sb { return sa > sb }
                return a.isPlayableByEngine && !b.isPlayableByEngine
            }
            return Array(merged.prefix(10))
        }
        return out
    }

    private static func headSize(_ raw: String, referer: String?) async -> Int64? {
        guard let url = MagicStreamProxy.parse(raw) else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.timeoutInterval = 8
        req.cachePolicy = .reloadIgnoringLocalCacheData
        req.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 16_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.4 Mobile/15E148 Safari/604.1",
                     forHTTPHeaderField: "User-Agent")
        if let referer { req.setValue(referer, forHTTPHeaderField: "Referer") }
        guard let (_, resp) = try? await MagicNet.session.data(for: req),
              let http = resp as? HTTPURLResponse else { return nil }
        if let total = http.value(forHTTPHeaderField: "Content-Range")?.split(separator: "/").last,
           let n = Int64(total) { return n }
        return nil
    }

    // MARK: - ويكيميديا كومنز: رابط الملف المباشر

    private static func commonsVariant(for page: String) async -> MagicStreamVariant? {
        guard let rawTitle = capture(page, pattern: "/wiki/([^?#]+)") else { return nil }
        let title = (rawTitle.removingPercentEncoding ?? rawTitle)
        var comps = URLComponents(string: "https://commons.wikimedia.org/w/api.php")
        comps?.queryItems = [
            URLQueryItem(name: "action", value: "query"),
            URLQueryItem(name: "titles", value: title),
            URLQueryItem(name: "prop", value: "imageinfo"),
            URLQueryItem(name: "iiprop", value: "url|mime|size"),
            URLQueryItem(name: "format", value: "json"),
            URLQueryItem(name: "redirects", value: "1"),
            URLQueryItem(name: "origin", value: "*"),
        ]
        guard let url = comps?.url,
              let obj = (try? await MagicNet.json(url)) as? [String: Any],
              let pages = (obj["query"] as? [String: Any])?["pages"] as? [String: Any],
              let page0 = pages.values.first as? [String: Any],
              let info = (page0["imageinfo"] as? [[String: Any]])?.first,
              let fileURL = info["url"] as? String,
              let mime = info["mime"] as? String else { return nil }
        guard mime.hasPrefix("video/") else { return nil }
        let kind = MediaKind.infer(url: fileURL, mime: mime)
        guard kind != .other else { return nil }
        return MagicStreamVariant(
            url: fileURL,
            label: "Commons · \(mime.replacingOccurrences(of: "video/", with: "").uppercased())",
            kind: kind == .other ? .mp4 : kind,
            sizeBytes: (info["size"] as? NSNumber)?.int64Value,
            pageURL: page,
            downloadable: true
        )
    }

    // MARK: - أدوات

    /// يفتح قائمة HLS الأُم ويحوّل كل جودة إلى خيار مستقل.
    static func expandHLSMaster(master: MagicStreamVariant) async -> [MagicStreamVariant] {
        guard let url = MagicStreamProxy.parse(master.url) else { return [] }
        var req = URLRequest(url: url)
        req.timeoutInterval = 12
        req.cachePolicy = .reloadIgnoringLocalCacheData
        req.setValue(DownloadAuth.safariUA, forHTTPHeaderField: "User-Agent")
        for (k, v) in master.headers { req.setValue(v, forHTTPHeaderField: k) }
        guard let (data, resp) = try? await MagicNet.session.data(for: req),
              let http = resp as? HTTPURLResponse, http.statusCode < 400,
              let text = String(data: data, encoding: .utf8) else { return [] }
        // لا نكسر أي حماية: قائمة مشفّرة → لا خيارات
        if HLSInspector.inspect(playlist: text).isProtected { return [] }
        guard HLSInspector.isMaster(text) else { return [] }
        var out: [MagicStreamVariant] = []
        for v in HLSInspector.variants(from: text, base: url) {
            out.append(MagicStreamVariant(
                url: v.url,
                label: "\(master.label) · \(v.qualityLabel)",
                kind: .hls,
                sizeBytes: nil,
                height: v.height,
                pageURL: master.pageURL,
                headers: master.headers,
                needsProxy: true,
                downloadable: true
            ))
        }
        return Array(out.prefix(8))
    }

    static func capture(_ text: String, pattern: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern),
              let m = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              m.numberOfRanges > 1,
              let r = Range(m.range(at: 1), in: text) else { return nil }
        return String(text[r])
    }

    static func unescape(_ raw: String) -> String {
        var s = raw.replacingOccurrences(of: "\\/", with: "/")
        let entities: [String: String] = [
            "&amp;": "&", "&#38;": "&", "&quot;": "\"", "&#34;": "\"",
            "&#x2F;": "/", "&#47;": "/", "&apos;": "'", "&#39;": "'", "&nbsp;": " ",
        ]
        for (k, v) in entities { s = s.replacingOccurrences(of: k, with: v) }
        return s
    }

    static func canonical(_ raw: String) -> String {
        guard var comps = URLComponents(string: raw) else { return raw.lowercased() }
        comps.fragment = nil
        comps.host = comps.host?.lowercased()
        return comps.string ?? raw.lowercased()
    }

    static func uuidLike(from text: String) -> String {
        if let u = capture(text, pattern: "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})") { return u }
        return capture(text, pattern: "/([A-Za-z0-9]{22})") ?? ""
    }

    private static func intOf(_ any: Any?) -> Int? {
        if let n = any as? NSNumber { return n.intValue > 0 ? n.intValue : nil }
        if let s = any as? String, let v = Int(s), v > 0 { return v }
        return nil
    }
}

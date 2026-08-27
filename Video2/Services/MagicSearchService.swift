import Foundation
import Combine

// MARK: - البحث السحري (Magic Search)
//
// طبقة بحث مجمّعة: تكتب اسم فيديو/فيلم فيبحث التطبيق في عدة مصادر في وقت واحد
// (أرشيف الإنترنت، يوتيوب عبر Piped، داليموشن، والويب عبر محركات متعددة
// لكل المواقع وكل المناطق) ويعرض نتائج موحّدة بالمدة والجودات.
//
// مهم: هذه الطبقة للبحث والعرض فقط. التحميل يتم عبر خط التحميل الأصلي
// نفسه دون أي تعديل: نتائج الأرشيف تُسلَّم لـ DownloadManager.enqueueManual،
// وباقي النتائج تُفتح في المتصفح ليقوم المستخرج ورادار الوسائط بعملهما المعتاد.

// MARK: النماذج الموحدة

enum MagicSource: String, CaseIterable, Hashable {
    case archive, youtube, dailymotion, web

    var labelKey: String {
        switch self {
        case .archive: return "magic.source.archive"
        case .youtube: return "magic.source.youtube"
        case .dailymotion: return "magic.source.dailymotion"
        case .web: return "magic.source.web"
        }
    }

    var icon: String {
        switch self {
        case .archive: return "books.vertical.fill"
        case .youtube: return "play.rectangle.fill"
        case .dailymotion: return "play.circle.fill"
        case .web: return "globe"
        }
    }
}

struct MagicDownloadOption: Identifiable, Hashable {
    var url: String
    var label: String
    var sizeBytes: Int64?
    var width: Int?
    var height: Int?

    var id: String { url }

    var sizeText: String {
        guard let sizeBytes, sizeBytes > 0 else { return "" }
        return ByteCountFormatter.string(fromByteCount: sizeBytes, countStyle: .file)
    }
}

struct MagicSearchResult: Identifiable, Hashable {
    var id: String
    var title: String
    var duration: Double?
    var thumbnailURL: String?
    var source: MagicSource
    var pageURL: String
    var uploader: String?
    var views: Int?
    var snippet: String?
    var isShort: Bool = false
    var downloads: [MagicDownloadOption] = []
}

// MARK: أدوات المدة

enum MagicDuration {

    /// تحويل الأرقام العربية/الفارسية إلى لاتينية.
    static func normalizeDigits(_ s: String) -> String {
        let map: [Character: String] = [
            "٠": "0", "١": "1", "٢": "2", "٣": "3", "٤": "4",
            "٥": "5", "٦": "6", "٧": "7", "٨": "8", "٩": "9",
            "۰": "0", "۱": "1", "۲": "2", "۳": "3", "۴": "4",
            "۵": "5", "۶": "6", "۷": "7", "۸": "8", "۹": "9",
        ]
        var out = ""
        for ch in s {
            if let repl = map[ch] { out += repl } else { out.append(ch) }
        }
        return out
    }

    /// تحليل نص المدة إلى ثوانٍ. الصيغ المدعومة:
    /// "2:28" (س:د) — "1:30:00" (س:د:ث) — "148" (دقائق) — "2h28m" — "1س 30د" — "90د"
    static func parse(_ raw: String) -> Double? {
        let t = normalizeDigits(raw)
            .replacingOccurrences(of: "ساعة", with: "h")
            .replacingOccurrences(of: "دقيقة", with: "m")
            .replacingOccurrences(of: "ثانية", with: "s")
            .replacingOccurrences(of: " ", with: "")
            .lowercased()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return nil }

        if t.contains(":") {
            let parts = t.split(separator: ":").compactMap { Double($0) }
            if parts.count == 3 { return parts[0] * 3600 + parts[1] * 60 + parts[2] }
            if parts.count == 2 { return parts[0] * 3600 + parts[1] * 60 } // س:د
            if parts.count == 1 { return parts[0] * 60 }
            return nil
        }

        var hours = 0.0, minutes = 0.0, seconds = 0.0, matched = false
        if let m = firstMatch(pattern: "(\\d+(?:\\.\\d+)?)h", in: t) { hours = m; matched = true }
        if let m = firstMatch(pattern: "(\\d+(?:\\.\\d+)?)m", in: t) { minutes = m; matched = true }
        if let m = firstMatch(pattern: "(\\d+(?:\\.\\d+)?)s", in: t) { seconds = m; matched = true }
        if matched { return hours * 3600 + minutes * 60 + seconds }

        // حروف عربية: "1س" "30د" "45ث"
        if let m = firstMatch(pattern: "(\\d+(?:\\.\\d+)?)س", in: t) { hours = m; matched = true }
        if let m = firstMatch(pattern: "(\\d+(?:\\.\\d+)?)د", in: t) { minutes = m; matched = true }
        if let m = firstMatch(pattern: "(\\d+(?:\\.\\d+)?)ث", in: t) { seconds = m; matched = true }
        if matched { return hours * 3600 + minutes * 60 + seconds }

        // رقم مجرد = دقائق
        if let v = Double(t) { return v * 60 }
        return nil
    }

    private static func firstMatch(pattern: String, in text: String) -> Double? {
        guard let re = try? NSRegularExpression(pattern: pattern),
              let m = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              m.numberOfRanges > 1,
              let r = Range(m.range(at: 1), in: text) else { return nil }
        return Double(text[r])
    }

    /// "1:36:12" أو "12:34" أو "0:45".
    static func text(_ seconds: Double?) -> String {
        guard let seconds, seconds.isFinite, seconds > 0 else { return "—" }
        let s = Int(seconds.rounded())
        let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
        if h > 0 { return String(format: "%d:%02d:%02d", h, m, sec) }
        return String(format: "%d:%02d", m, sec)
    }

    /// طول ملف في أرشيف الإنترنت: قد يكون ثوانٍ ("109.79") أو "1:36:12" أو "34:11"،
    /// وقد يأتي رقماً (NSNumber) وليس نصاً.
    static func parseArchiveLength(_ raw: Any?) -> Double? {
        if let n = raw as? NSNumber { return n.doubleValue }
        guard let s = raw as? String, !s.isEmpty else { return nil }
        if s.contains(":") {
            let parts = s.split(separator: ":").compactMap { Double($0) }
            if parts.count == 3 { return parts[0] * 3600 + parts[1] * 60 + parts[2] }
            if parts.count == 2 { return parts[0] * 60 + parts[1] }
            return nil
        }
        return Double(s)
    }
}

// MARK: الشبكة

enum MagicNet {
    static let session: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 12
        cfg.timeoutIntervalForResource = 24
        cfg.httpAdditionalHeaders = [
            "User-Agent": DownloadAuth.safariUA,
            "Accept-Language": "en-US,en;q=0.9,ar;q=0.8",
        ]
        return URLSession(configuration: cfg)
    }()

    /// `URLComponents.asURL()` غير موجود في Swift — الخاصية هي `.url`.
    static func makeURL(_ comps: URLComponents) throws -> URL {
        guard let url = comps.url else { throw URLError(.badURL) }
        return url
    }

    static func json(_ url: URL) async throws -> Any {
        var req = URLRequest(url: url)
        req.setValue("application/json,text/javascript;q=0.9,*/*;q=0.8", forHTTPHeaderField: "Accept")
        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return try JSONSerialization.jsonObject(with: data)
    }

    static func html(_ url: URL, referer: String? = nil) async throws -> String {
        var req = URLRequest(url: url)
        req.setValue("text/html,application/xhtml+xml;q=0.9,*/*;q=0.8", forHTTPHeaderField: "Accept")
        if let referer { req.setValue(referer, forHTTPHeaderField: "Referer") }
        return try await string(req)
    }

    static func postForm(_ url: URL, fields: [String: String], referer: String? = nil) async throws -> String {
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue("text/html,application/xhtml+xml;q=0.9,*/*;q=0.8", forHTTPHeaderField: "Accept")
        if let referer { req.setValue(referer, forHTTPHeaderField: "Referer") }
        req.httpBody = formEncode(fields)
        return try await string(req)
    }

    private static func string(_ req: URLRequest) async throws -> String {
        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        let raw = String(data: data, encoding: .utf8)
            ?? String(data: data, encoding: .isoLatin1)
            ?? ""
        if raw.count > 400_000 { return String(raw.prefix(400_000)) }
        return raw
    }

    private static let formAllowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    private static func formEncode(_ fields: [String: String]) -> Data {
        let s = fields.map { k, v in
            let ek = k.addingPercentEncoding(withAllowedCharacters: formAllowed) ?? k
            let ev = v.addingPercentEncoding(withAllowedCharacters: formAllowed) ?? v
            return "\(ek)=\(ev)"
        }.joined(separator: "&")
        return Data(s.utf8)
    }
}

// MARK: - المزوّد 1: أرشيف الإنترنت (بدون مفتاح، ملفات مباشرة بجودات)

enum InternetArchiveProvider {

    static func search(query: String) async throws -> [MagicSearchResult] {
        var comps = URLComponents(string: "https://archive.org/advancedsearch.php")!
        comps.queryItems = [
            URLQueryItem(name: "q", value: "(\(query)) AND (mediatype:(movies) OR mediatype:(video))"),
            URLQueryItem(name: "fl[]", value: "identifier"),
            URLQueryItem(name: "fl[]", value: "title"),
            URLQueryItem(name: "fl[]", value: "downloads"),
            URLQueryItem(name: "rows", value: "20"),
            URLQueryItem(name: "page", value: "1"),
            URLQueryItem(name: "output", value: "json"),
            URLQueryItem(name: "sort[]", value: "downloads desc"),
        ]
        let url = try MagicNet.makeURL(comps)
        let obj = try await MagicNet.json(url)
        let docs = ((obj as? [String: Any])?["response"] as? [String: Any])?["docs"] as? [[String: Any]] ?? []
        let entries: [(String, String)] = docs.prefix(12).compactMap { (d: [String: Any]) -> (String, String)? in
            guard let id = d["identifier"] as? String else { return nil }
            return (id, (d["title"] as? String) ?? id)
        }
        guard !entries.isEmpty else { return [] }

        // جلب تفاصيل (الملفات والمدة) لأفضل النتائج بالتوازي
        return await withTaskGroup(of: MagicSearchResult?.self) { group in
            for (id, title) in entries {
                group.addTask {
                    try? await item(identifier: id, title: title)
                }
            }
            var out: [MagicSearchResult] = []
            for await r in group {
                if let r { out.append(r) }
            }
            // حافظ على ترتيب الأكثر تحميلاً
            let order = entries.map { $0.0 }
            out.sort { a, b in
                let ia = order.firstIndex(of: String(a.id.dropFirst(3))) ?? 0
                let ib = order.firstIndex(of: String(b.id.dropFirst(3))) ?? 0
                return ia < ib
            }
            return out
        }
    }

    private static func item(identifier: String, title: String) async throws -> MagicSearchResult {
        let obj = try await MagicNet.json(URL(string: "https://archive.org/metadata/\(identifier)")!)
        let files = (obj as? [String: Any])?["files"] as? [[String: Any]] ?? []
        let videoExts: Set<String> = ["mp4", "m4v", "mkv", "webm", "avi", "mov", "mpeg", "mpg"]
        var options: [MagicDownloadOption] = []
        var bestDuration: Double?

        for f in files {
            guard let name = f["name"] as? String else { continue }
            let ext = (name as NSString).pathExtension.lowercased()
            let fmt = ((f["format"] as? String) ?? "").lowercased()
            let isVideo = videoExts.contains(ext) || fmt.contains("mpeg") || fmt.contains("matroska") || fmt.contains("quicktime")
            guard isVideo else { continue }
            let size = numberOf(f["size"])
            guard size > 200_000 else { continue } // تجاهل المصغرات والملفات الصغيرة جداً

            let width = intOf(f["width"])
            let height = intOf(f["height"])
            if let len = MagicDuration.parseArchiveLength(f["length"]) {
                bestDuration = max(bestDuration ?? 0, len)
            }
            let quality = height.map { "\($0)p" } ?? ext.uppercased()
            let encodedName = name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? name
            options.append(MagicDownloadOption(
                url: "https://archive.org/download/\(identifier)/\(encodedName)",
                label: "\(quality) · \(ext.uppercased())",
                sizeBytes: size > 0 ? Int64(size) : nil,
                width: width,
                height: height
            ))
        }
        guard !options.isEmpty else { throw URLError(.cannotParseResponse) }

        options.sort { a, b in
            let ha = a.height ?? 0, hb = b.height ?? 0
            if ha != hb { return ha > hb }
            return (a.sizeBytes ?? 0) > (b.sizeBytes ?? 0)
        }

        return MagicSearchResult(
            id: "ia-\(identifier)",
            title: title,
            duration: bestDuration,
            thumbnailURL: "https://archive.org/services/img/\(identifier)",
            source: .archive,
            pageURL: "https://archive.org/details/\(identifier)",
            uploader: nil,
            views: nil,
            snippet: nil,
            downloads: options
        )
    }

    private static func numberOf(_ any: Any?) -> Double {
        if let n = any as? NSNumber { return n.doubleValue }
        if let s = any as? String { return Double(s) ?? 0 }
        return 0
    }

    private static func intOf(_ any: Any?) -> Int? {
        let v = numberOf(any)
        return v > 0 ? Int(v) : nil
    }
}

// MARK: - المزوّد 2: يوتيوب عبر Piped (بحث فقط بدون مفتاح، مع تجاوز للأخطاء)

enum PipedProvider {

    // نسخ متعددة: إن فشلت واحدة جرّب التالية (البحث عادة يعمل، والاستخراج يتقلب)
    static let instances: [String] = [
        "https://api.piped.private.coffee",
        "https://pipedapi.kavin.rocks",
        "https://piped-api.codespace.cz",
        "https://pipedapi.ducks.party",
        "https://pipedapi.reallyaweso.me",
        "https://api.piped.yt",
        "https://pipedapi.adminforge.de",
    ]

    static func search(query: String) async throws -> [MagicSearchResult] {
        var lastError: Error?
        var anyEmpty = false

        for base in instances {
            do {
                guard let url = URL(string: "\(base)/search?q=\(escaped(query))&filter=videos") else { continue }
                let obj = try await MagicNet.json(url)
                let items = (obj as? [String: Any])?["items"] as? [[String: Any]] ?? []
                if items.isEmpty { anyEmpty = true; continue }
                return items.prefix(20).compactMap { mapItem($0) }
            } catch {
                lastError = error
            }
        }
        if anyEmpty { return [] }
        throw lastError ?? URLError(.cannotConnectToHost)
    }

    private static func mapItem(_ item: [String: Any]) -> MagicSearchResult? {
        guard let path = item["url"] as? String, path.contains("v=") else { return nil }
        let videoID = path.split(separator: "v=").last.map { String($0).split(separator: "&").first.map(String.init) ?? String($0) } ?? ""
        guard !videoID.isEmpty else { return nil }
        let duration = (item["duration"] as? NSNumber)?.doubleValue
        let shortDuration = (duration ?? 0) > 0 && (duration ?? 0) < 61
        return MagicSearchResult(
            id: "yt-\(videoID)",
            title: (item["title"] as? String) ?? "",
            duration: duration,
            thumbnailURL: "https://i.ytimg.com/vi/\(videoID)/hqdefault.jpg",
            source: .youtube,
            pageURL: "https://www.youtube.com/watch?v=\(videoID)",
            uploader: item["uploaderName"] as? String,
            views: (item["views"] as? NSNumber)?.intValue,
            snippet: item["uploadedDate"] as? String,
            isShort: (item["isShort"] as? NSNumber)?.boolValue ?? shortDuration,
            downloads: []
        )
    }

    private static func escaped(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? s
    }
}

// MARK: - المزوّد 3: داليموشن (API رسمي بدون مفتاح)

enum DailymotionProvider {

    static func search(query: String) async throws -> [MagicSearchResult] {
        var comps = URLComponents(string: "https://api.dailymotion.com/videos")!
        comps.queryItems = [
            URLQueryItem(name: "search", value: query),
            URLQueryItem(name: "limit", value: "15"),
            URLQueryItem(name: "fields", value: "id,title,duration,thumbnail_360_url,owner.screenname,views_total"),
        ]
        let obj = try await MagicNet.json(try MagicNet.makeURL(comps))
        let list = (obj as? [String: Any])?["list"] as? [[String: Any]] ?? []
        return list.compactMap { mapItem($0) }
    }

    private static func mapItem(_ item: [String: Any]) -> MagicSearchResult? {
        guard let id = item["id"] as? String else { return nil }
        let owner = item["owner"] as? [String: Any]
        let uploader = (item["owner.screenname"] as? String) ?? (owner?["screenname"] as? String)
        return MagicSearchResult(
            id: "dm-\(id)",
            title: (item["title"] as? String) ?? "",
            duration: (item["duration"] as? NSNumber)?.doubleValue,
            thumbnailURL: item["thumbnail_360_url"] as? String,
            source: .dailymotion,
            pageURL: "https://www.dailymotion.com/video/\(id)",
            uploader: uploader,
            views: (item["views_total"] as? NSNumber)?.intValue,
            snippet: nil,
            downloads: []
        )
    }
}

// MARK: - المزوّد 4: الويب بالكامل (كل المواقع، كل المناطق)
//
// سابقًا: DuckDuckGo فقط + نتيجة واحدة لكل نطاق + حد 12.
// الآن: محركات متعددة بالتوازي، كل المناطق، بدون حصر على مواقع معروفة،
// مع فكّ التكرار بالرابط لا بالنطاق حتى تظهر صفحات من أي موقع.

enum WebSearchProvider {

    static func search(query: String) async throws -> [MagicSearchResult] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return [] }
        let videoQ = q + " (video OR فيديو OR watch OR film OR فيلم)"

        let lists = await withTaskGroup(of: [MagicSearchResult].self) { group -> [[MagicSearchResult]] in
            group.addTask { (try? await duckDuckGo(query: q, offset: 0)) ?? [] }
            group.addTask { (try? await duckDuckGo(query: q, offset: 30)) ?? [] }
            group.addTask { (try? await duckDuckGo(query: videoQ, offset: 0)) ?? [] }
            group.addTask { (try? await duckDuckGoLite(query: q)) ?? [] }
            group.addTask { (try? await searxNG(query: q, videos: false)) ?? [] }
            group.addTask { (try? await searxNG(query: q, videos: true)) ?? [] }
            group.addTask { (try? await qwant(query: q, videos: false)) ?? [] }
            group.addTask { (try? await qwant(query: q, videos: true)) ?? [] }
            group.addTask { (try? await brave(query: q)) ?? [] }
            group.addTask { (try? await mojeek(query: q)) ?? [] }
            group.addTask { (try? await sepiasearch(query: q)) ?? [] }
            group.addTask { (try? await wikimedia(query: q)) ?? [] }
            var out: [[MagicSearchResult]] = []
            for await list in group { out.append(list) }
            return out
        }
        let merged = dedupe(lists.flatMap { $0 })
        if merged.isEmpty { throw URLError(.cannotParseResponse) }
        return merged
    }

    // MARK: DuckDuckGo (كل المناطق kl=wt-wt)

    private static func duckDuckGo(query: String, offset: Int) async throws -> [MagicSearchResult] {
        let fields = ["q": query, "s": "\(offset)", "kl": "wt-wt", "kp": "-1"]
        let postURL = URL(string: "https://html.duckduckgo.com/html/")!
        if let html = try? await MagicNet.postForm(postURL, fields: fields, referer: "https://html.duckduckgo.com/"),
           !html.isEmpty {
            let parsed = results(fromHTML: html, linkClass: "result__a", snippetClass: "result__snippet")
            if !parsed.isEmpty { return parsed }
            let generic = genericResults(html)
            if !generic.isEmpty { return generic }
        }
        var comps = URLComponents(string: "https://html.duckduckgo.com/html/")!
        comps.queryItems = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "s", value: "\(offset)"),
            URLQueryItem(name: "kl", value: "wt-wt"),
        ]
        let html = try await MagicNet.html(try MagicNet.makeURL(comps), referer: "https://html.duckduckgo.com/")
        let parsed = results(fromHTML: html, linkClass: "result__a", snippetClass: "result__snippet")
        if !parsed.isEmpty { return parsed }
        return genericResults(html)
    }

    private static func duckDuckGoLite(query: String) async throws -> [MagicSearchResult] {
        var comps = URLComponents(string: "https://lite.duckduckgo.com/lite/")!
        comps.queryItems = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "kl", value: "wt-wt"),
        ]
        let html = try await MagicNet.html(try MagicNet.makeURL(comps), referer: "https://lite.duckduckgo.com/")
        let parsed = results(fromHTML: html, linkClass: "result-link", snippetClass: "result-snippet")
        if !parsed.isEmpty { return parsed }
        return genericResults(html)
    }

    // MARK: SearXNG — ميتا بحث (Google/Bing/Wiki/… حسب النسخة)

    private static let searxInstances: [String] = [
        "https://searx.be",
        "https://priv.au",
        "https://opnxng.com",
        "https://searx.tiekoetter.com",
        "https://search.sapti.me",
        "https://baresearch.org",
        "https://search.inetol.net",
        "https://search.ononoki.org",
    ]

    private static func searxNG(query: String, videos: Bool) async throws -> [MagicSearchResult] {
        let picked = await withTaskGroup(of: [MagicSearchResult].self) { group -> [MagicSearchResult] in
            for base in searxInstances.prefix(5) {
                group.addTask { (try? await searxOnce(base: base, query: query, videos: videos)) ?? [] }
            }
            var best: [MagicSearchResult] = []
            for await list in group {
                if list.count > best.count { best = list }
                if best.count >= 10 {
                    group.cancelAll()
                    break
                }
            }
            return best
        }
        if picked.isEmpty { throw URLError(.cannotParseResponse) }
        return picked
    }

    private static func searxOnce(base: String, query: String, videos: Bool) async throws -> [MagicSearchResult] {
        let category = videos ? "videos" : "general"
        var comps = URLComponents(string: "\(base)/search")!
        comps.queryItems = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "format", value: "json"),
            URLQueryItem(name: "language", value: "all"),
            URLQueryItem(name: "safesearch", value: "0"),
            URLQueryItem(name: "categories", value: category),
        ]
        if let obj = try? await MagicNet.json(try MagicNet.makeURL(comps)) {
            let rows = (obj as? [String: Any])?["results"] as? [[String: Any]] ?? []
            let mapped = rows.compactMap { mapJSONResult($0) }
            if !mapped.isEmpty { return mapped }
        }
        var htmlComps = URLComponents(string: "\(base)/search")!
        htmlComps.queryItems = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "language", value: "all"),
            URLQueryItem(name: "categories", value: category),
        ]
        let html = try await MagicNet.html(try MagicNet.makeURL(htmlComps))
        let articles = results(fromHTML: html, linkClass: "url_wrapper", snippetClass: "content")
        if !articles.isEmpty { return articles }
        return genericResults(html)
    }

    // MARK: Qwant API (ويب + فيديو، بدون مفتاح)

    private static func qwant(query: String, videos: Bool) async throws -> [MagicSearchResult] {
        let locale = query.unicodeScalars.contains(where: { (0x0600...0x06FF).contains(Int($0.value)) }) ? "ar_EG" : "en_US"
        let path = videos ? "videos" : "web"
        var comps = URLComponents(string: "https://api.qwant.com/v3/search/\(path)")!
        comps.queryItems = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "count", value: "10"),
            URLQueryItem(name: "locale", value: locale),
            URLQueryItem(name: "offset", value: "0"),
            URLQueryItem(name: "device", value: "smartphone"),
            URLQueryItem(name: "safesearch", value: "0"),
        ]
        let obj = try await MagicNet.json(try MagicNet.makeURL(comps))
        let data = (obj as? [String: Any])?["data"] as? [String: Any]
        let result = data?["result"] as? [String: Any]
        var items: [[String: Any]] = []
        if let mainline = (result?["items"] as? [String: Any])?["mainline"] as? [[String: Any]] {
            for block in mainline {
                if let nested = block["items"] as? [[String: Any]] { items.append(contentsOf: nested) }
            }
        }
        if items.isEmpty, let flat = result?["items"] as? [[String: Any]] { items = flat }
        let mapped = items.compactMap { mapJSONResult($0) }
        if mapped.isEmpty { throw URLError(.cannotParseResponse) }
        return mapped
    }

    // MARK: Brave / Mojeek HTML

    private static func brave(query: String) async throws -> [MagicSearchResult] {
        var comps = URLComponents(string: "https://search.brave.com/search")!
        comps.queryItems = [URLQueryItem(name: "q", value: query), URLQueryItem(name: "source", value: "web")]
        let html = try await MagicNet.html(try MagicNet.makeURL(comps))
        let parsed = results(fromHTML: html, linkClass: "heading-serpresult", snippetClass: "snippet-description")
        if !parsed.isEmpty { return parsed }
        return genericResults(html)
    }

    private static func mojeek(query: String) async throws -> [MagicSearchResult] {
        var comps = URLComponents(string: "https://www.mojeek.com/search")!
        comps.queryItems = [URLQueryItem(name: "q", value: query)]
        let html = try await MagicNet.html(try MagicNet.makeURL(comps))
        let parsed = results(fromHTML: html, linkClass: "ob", snippetClass: "s")
        if !parsed.isEmpty { return parsed }
        return genericResults(html)
    }

    // MARK: PeerTube (SepiaSearch) + Wikimedia Commons فيديو

    private static func sepiasearch(query: String) async throws -> [MagicSearchResult] {
        var comps = URLComponents(string: "https://sepiasearch.org/api/v1/search/videos")!
        comps.queryItems = [
            URLQueryItem(name: "search", value: query),
            URLQueryItem(name: "count", value: "20"),
        ]
        let obj = try await MagicNet.json(try MagicNet.makeURL(comps))
        let rows = (obj as? [String: Any])?["data"] as? [[String: Any]] ?? []
        let mapped: [MagicSearchResult] = rows.compactMap { row in
            let page = (row["url"] as? String) ?? (row["embedUrl"] as? String)
            guard let page, page.hasPrefix("http") else { return nil }
            let title = (row["name"] as? String) ?? page
            let account = row["account"] as? [String: Any]
            let duration: Double? = {
                if let n = row["duration"] as? NSNumber { return n.doubleValue }
                if let i = row["duration"] as? Int { return Double(i) }
                return nil
            }()
            return makeResult(
                title: title,
                url: page,
                snippet: row["description"] as? String,
                uploader: account?["displayName"] as? String,
                thumbnail: (row["thumbnailUrl"] as? String) ?? (row["previewUrl"] as? String),
                duration: duration,
                views: (row["views"] as? NSNumber)?.intValue
            )
        }
        if mapped.isEmpty { throw URLError(.cannotParseResponse) }
        return mapped
    }

    private static func wikimedia(query: String) async throws -> [MagicSearchResult] {
        var comps = URLComponents(string: "https://commons.wikimedia.org/w/api.php")!
        comps.queryItems = [
            URLQueryItem(name: "action", value: "query"),
            URLQueryItem(name: "list", value: "search"),
            URLQueryItem(name: "srsearch", value: "\(query) filetype:video"),
            URLQueryItem(name: "srnamespace", value: "6"),
            URLQueryItem(name: "srlimit", value: "12"),
            URLQueryItem(name: "format", value: "json"),
            URLQueryItem(name: "origin", value: "*"),
        ]
        let obj = try await MagicNet.json(try MagicNet.makeURL(comps))
        let hits = ((obj as? [String: Any])?["query"] as? [String: Any])?["search"] as? [[String: Any]] ?? []
        let mapped: [MagicSearchResult] = hits.compactMap { h in
            guard let title = h["title"] as? String else { return nil }
            let encoded = title.replacingOccurrences(of: " ", with: "_")
                .addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? title
            return makeResult(
                title: title.replacingOccurrences(of: "File:", with: ""),
                url: "https://commons.wikimedia.org/wiki/\(encoded)",
                snippet: clean(h["snippet"] as? String ?? ""),
                uploader: "Wikimedia Commons",
                thumbnail: nil,
                duration: nil,
                views: nil
            )
        }
        if mapped.isEmpty { throw URLError(.cannotParseResponse) }
        return mapped
    }

    // MARK: JSON → نتيجة

    private static func mapJSONResult(_ item: [String: Any]) -> MagicSearchResult? {
        let url = (item["url"] as? String)
            ?? (item["href"] as? String)
            ?? (item["link"] as? String)
            ?? (item["iframe_src"] as? String)
        guard let url, let resolved = resolveHref(url) ?? (url.hasPrefix("http") ? url : nil) else { return nil }
        let title = (item["title"] as? String) ?? (item["name"] as? String) ?? ""
        let snippet = (item["content"] as? String)
            ?? (item["desc"] as? String)
            ?? (item["description"] as? String)
            ?? (item["snippet"] as? String)
        let thumb = (item["thumbnail"] as? String)
            ?? (item["thumbnailUrl"] as? String)
            ?? (item["img"] as? String)
        let duration: Double? = {
            if let n = item["duration"] as? NSNumber, n.doubleValue > 0 { return n.doubleValue }
            if let s = item["duration"] as? String { return MagicDuration.parseArchiveLength(s) ?? MagicDuration.parse(s) }
            if let s = item["length"] as? String { return MagicDuration.parseArchiveLength(s) ?? MagicDuration.parse(s) }
            return nil
        }()
        let uploader = (item["engine"] as? String) ?? (item["source"] as? String) ?? (item["channel"] as? String)
        return makeResult(title: title, url: resolved, snippet: snippet, uploader: uploader, thumbnail: thumb, duration: duration, views: nil)
    }

    // MARK: HTML parsers

    private struct Anchor { var href: String; var text: String }

    private static func results(fromHTML html: String, linkClass: String, snippetClass: String) -> [MagicSearchResult] {
        let links = allAnchors(html, cssClass: linkClass)
        let snippets = allAnchors(html, cssClass: snippetClass).map(\.text)
        var out: [MagicSearchResult] = []
        var seen = Set<String>()
        for (i, anchor) in links.enumerated() {
            guard let resolved = resolveHref(anchor.href) else { continue }
            let key = canonicalize(resolved)
            guard seen.insert(key).inserted else { continue }
            let snippet = i < snippets.count ? snippets[i] : nil
            if let r = makeResult(title: anchor.text, url: resolved, snippet: snippet, uploader: nil, thumbnail: nil, duration: nil, views: nil) {
                out.append(r)
            }
            if out.count >= 30 { break }
        }
        return out
    }

    private static func genericResults(_ html: String) -> [MagicSearchResult] {
        guard let re = try? NSRegularExpression(
            pattern: #"<a[^>]+href=["']([^"']+)["'][^>]*>(.*?)</a>"#,
            options: [.caseInsensitive, .dotMatchesLineSeparators]
        ) else { return [] }
        var seen = Set<String>()
        var out: [MagicSearchResult] = []
        re.enumerateMatches(in: html, range: NSRange(html.startIndex..., in: html)) { m, _, _ in
            guard let m,
                  let hrefR = Range(m.range(at: 1), in: html),
                  let textR = Range(m.range(at: 2), in: html) else { return }
            let title = clean(String(html[textR]))
            guard title.count >= 8, title.count <= 220 else { return }
            let lower = title.lowercased()
            if ["cached", "similar", "translate", "sign in", "log in", "privacy", "cookie"].contains(where: { lower.contains($0) }) { return }
            guard let resolved = resolveHref(String(html[hrefR])) else { return }
            let key = canonicalize(resolved)
            guard seen.insert(key).inserted else { return }
            if let r = makeResult(title: title, url: resolved, snippet: nil, uploader: nil, thumbnail: nil, duration: nil, views: nil) {
                out.append(r)
            }
        }
        return Array(out.prefix(30))
    }

    private static func allAnchors(_ html: String, cssClass: String) -> [Anchor] {
        guard let re = try? NSRegularExpression(
            pattern: "<a([^>]*" + NSRegularExpression.escapedPattern(for: cssClass) + "[^>]*)>(.*?)</a>",
            options: [.dotMatchesLineSeparators, .caseInsensitive]
        ) else { return [] }
        var out: [Anchor] = []
        re.enumerateMatches(in: html, range: NSRange(html.startIndex..., in: html)) { m, _, _ in
            guard let m,
                  let attrsRange = Range(m.range(at: 1), in: html),
                  let textRange = Range(m.range(at: 2), in: html) else { return }
            let attrs = String(html[attrsRange])
            let text = clean(String(html[textRange]))
            var href = ""
            if let hre = try? NSRegularExpression(pattern: #"href=["']([^"']+)["']"#),
               let hm = hre.firstMatch(in: attrs, range: NSRange(attrs.startIndex..., in: attrs)),
               let hr = Range(hm.range(at: 1), in: attrs) {
                href = String(attrs[hr])
            }
            out.append(Anchor(href: href, text: text))
        }
        return out
    }

    /// فك روابط التحويل (DDG uddg=، /url?q=، …) أو الرابط المباشر.
    private static func resolveHref(_ raw: String) -> String? {
        var href = raw
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&#x2F;", with: "/")
            .replacingOccurrences(of: "&#47;", with: "/")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if href.hasPrefix("//") { href = "https:" + href }
        if let range = href.range(of: "uddg=") {
            let rest = String(href[range.upperBound...])
            let encoded = rest.split(separator: "&").first.map(String.init) ?? rest
            if let decoded = encoded.removingPercentEncoding, decoded.hasPrefix("http") {
                return decoded
            }
        }
        if let comps = URLComponents(string: href), let items = comps.queryItems {
            for key in ["uddg", "url", "u", "target"] {
                if let v = items.first(where: { $0.name == key })?.value {
                    let decoded = v.removingPercentEncoding ?? v
                    if decoded.hasPrefix("http") { return decoded }
                }
            }
            if let v = items.first(where: { $0.name == "q" })?.value {
                let decoded = v.removingPercentEncoding ?? v
                if decoded.hasPrefix("http") { return decoded }
            }
        }
        if href.hasPrefix("http") { return href }
        return nil
    }

    private static func clean(_ html: String) -> String {
        var s = html.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        let entities: [String: String] = [
            "&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": "\"",
            "&#x27;": "'", "&#39;": "'", "&nbsp;": " ", "&hellip;": "…", "&mdash;": "—",
            "&#x2F;": "/",
        ]
        for (k, v) in entities { s = s.replacingOccurrences(of: k, with: v) }
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func isSearchChrome(_ host: String) -> Bool {
        let h = host.lowercased()
        if h.hasPrefix("ad.") || h.hasPrefix("ads.") || h.hasPrefix("doubleclick.") { return true }
        let blocked: [String] = [
            "duckduckgo.com", "google.com", "google.ae", "google.co.uk", "google.fr",
            "bing.com", "yahoo.com", "brave.com", "mojeek.com", "startpage.com",
            "qwant.com", "searx.be", "priv.au", "opnxng.com", "sepiasearch.org",
        ]
        for b in blocked {
            if h == b || h.hasSuffix("." + b) { return true }
        }
        if h.contains("searx") || h.contains("searxng") { return true }
        return false
    }

    private static func canonicalize(_ raw: String) -> String {
        guard var comps = URLComponents(string: raw) else { return raw.lowercased() }
        comps.scheme = comps.scheme?.lowercased()
        comps.host = comps.host?.lowercased()
        if let host = comps.host, host.hasPrefix("www.") {
            comps.host = String(host.dropFirst(4))
        }
        comps.fragment = nil
        let drop: Set<String> = ["utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "fbclid", "gclid", "yclid"]
        comps.queryItems = comps.queryItems?.filter { !drop.contains($0.name.lowercased()) }
        if comps.queryItems?.isEmpty == true { comps.queryItems = nil }
        if comps.path.count > 1, comps.path.hasSuffix("/") {
            comps.path.removeLast()
        }
        return comps.string ?? raw.lowercased()
    }

    private static func makeResult(
        title: String,
        url: String,
        snippet: String?,
        uploader: String?,
        thumbnail: String?,
        duration: Double?,
        views: Int?
    ) -> MagicSearchResult? {
        guard url.hasPrefix("http"),
              let host = URL(string: url)?.host?.lowercased(),
              !isSearchChrome(host) else { return nil }
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let snippetClean: String? = {
            guard let snippet else { return nil }
            let c = clean(snippet)
            return c.isEmpty ? nil : c
        }()
        return MagicSearchResult(
            id: "web-\(canonicalize(url))",
            title: trimmed.isEmpty ? host : trimmed,
            duration: duration,
            thumbnailURL: thumbnail,
            source: .web,
            pageURL: url,
            uploader: uploader,
            views: views,
            snippet: snippetClean,
            downloads: []
        )
    }

    /// فك التكرار بالرابط (كل المواقع)، مع سقف لطيف لكل نطاق حتى لا يغرق مصدر واحد القائمة.
    private static func dedupe(_ raw: [MagicSearchResult], limit: Int = 60) -> [MagicSearchResult] {
        var seenURL = Set<String>()
        var perHost: [String: Int] = [:]
        var preferred: [MagicSearchResult] = []
        var overflow: [MagicSearchResult] = []
        for r in raw {
            let key = canonicalize(r.pageURL)
            guard seenURL.insert(key).inserted else { continue }
            let host = URL(string: r.pageURL)?.host?.lowercased() ?? ""
            let n = perHost[host, default: 0]
            if n < 5 {
                perHost[host] = n + 1
                preferred.append(r)
            } else {
                overflow.append(r)
            }
        }
        var out = preferred
        if out.count < limit {
            out.append(contentsOf: overflow.prefix(limit - out.count))
        }
        return Array(out.prefix(limit))
    }
}

// MARK: - مخزن الحالة

final class MagicSearchStore: ObservableObject {

    enum Phase { case idle, running, done }

    struct ProviderStatus: Identifiable {
        let source: MagicSource
        var phase: Phase
        var count: Int = 0
        var failed: Bool = false

        var id: String { source.rawValue }
    }

    @Published var query = ""
    @Published var durationText = ""
    @Published var minChip: Double?
    @Published var phase: Phase = .idle
    @Published var results: [MagicSearchResult] = []
    @Published var statuses: [ProviderStatus] = []
    @Published var ranOnce = false

    private var searchTask: Task<Void, Never>?

    var targetDuration: Double? { MagicDuration.parse(durationText) }

    /// الحد الأدنى الفعلي: من الزر السريع أو 70% من المدة الهدف (استبعاد المقاطع المبتورة).
    var effectiveMinDuration: Double? {
        let fromTarget = targetDuration.map { $0 * 0.7 } ?? 0
        let chip = minChip ?? 0
        let m = max(fromTarget, chip)
        return m > 0 ? m : nil
    }

    @MainActor
    func search() {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty, phase != .running else { return }
        phase = .running
        results = []
        statuses = MagicSource.allCases.map { ProviderStatus(source: $0, phase: .running) }

        let target = targetDuration
        let minDuration = effectiveMinDuration
        searchTask?.cancel()
        searchTask = Task { [weak self] in
            let (buckets, failures) = await Self.runProviders(query: q)
            guard let self, !Task.isCancelled else { return }
            self.results = Self.merge(buckets: buckets, target: target, minDuration: minDuration)
            self.statuses = zip(MagicSource.allCases, zip(buckets, failures)).map { src, pair in
                let (list, failed) = pair
                return ProviderStatus(source: src, phase: .done, count: list.count, failed: failed && list.isEmpty)
            }
            self.phase = .done
            self.ranOnce = true
        }
    }

    /// تشغيل المزودين الأربعة بالتوازي مع التقاط أخطاء كل مزود على حدة.
    static func runProviders(query: String) async -> ([[MagicSearchResult]], [Bool]) {
        await withTaskGroup(of: (Int, [MagicSearchResult], Bool).self) { group in
            group.addTask {
                do { return (0, try await InternetArchiveProvider.search(query: query), false) }
                catch { return (0, [], true) }
            }
            group.addTask {
                do { return (1, try await PipedProvider.search(query: query), false) }
                catch { return (1, [], true) }
            }
            group.addTask {
                do { return (2, try await DailymotionProvider.search(query: query), false) }
                catch { return (2, [], true) }
            }
            group.addTask {
                do { return (3, try await WebSearchProvider.search(query: query), false) }
                catch { return (3, [], true) }
            }
            var buckets = [[MagicSearchResult](), [], [], []]
            var failures = [false, false, false, false]
            for await (idx, list, failed) in group {
                buckets[idx] = list
                failures[idx] = failed
            }
            return (buckets, failures)
        }
    }

    /// دمج النتائج: فلترة المدة المطلوبة ثم الترتيب (الأقرب للمدة أولاً أو تبادلي بين المصادر).
    static func merge(buckets: [[MagicSearchResult]], target: Double?, minDuration: Double?) -> [MagicSearchResult] {
        let minDur = minDuration ?? 0

        let filtered: [[MagicSearchResult]] = buckets.map { bucket in
            bucket.filter { r in
                if r.isShort { return false } // استبعاد Shorts نهائياً
                guard minDur > 0, let d = r.duration else { return true }
                return d >= minDur * 0.95 // سماحية 5%
            }
        }

        if let target {
            var all = filtered.flatMap { $0 }
            all.sort { a, b in
                let da = a.duration.map { abs($0 - target) } ?? .infinity
                let db = b.duration.map { abs($0 - target) } ?? .infinity
                if da != db { return da < db }
                return MagicSource.allCases.firstIndex(of: a.source)! < MagicSource.allCases.firstIndex(of: b.source)!
            }
            return all
        }

        // دمج تبادلي (واحد من كل مصدر بالتناوب) لضمان ظهور تنوع المصادر
        var out: [MagicSearchResult] = []
        var idx = 0
        var remaining = true
        while remaining {
            remaining = false
            for bucket in filtered where idx < bucket.count {
                out.append(bucket[idx])
                remaining = true
            }
            idx += 1
        }
        return out
    }

    /// نوع شارة المدة لنتيجة ما (مطابقة/قريبة/مبتورة/غير معروفة).
    enum DurationBadge { case exact, close, short, unknown, plain }

    func badge(for r: MagicSearchResult) -> DurationBadge {
        guard let target = targetDuration else {
            guard let minDur = effectiveMinDuration, minDur > 0 else { return .plain }
            guard let d = r.duration else { return .unknown }
            return d >= minDur ? .plain : .short
        }
        guard let d = r.duration else { return .unknown }
        if abs(d - target) <= max(60, target * 0.1) { return .exact }
        if abs(d - target) <= target * 0.25 { return .close }
        if d < target * 0.75 { return .short }
        return .plain
    }
}

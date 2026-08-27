import Foundation
import Combine

/// A read-only intelligence layer over the existing detector and downloader.
///
/// The radar deliberately does not download or rewrite media URLs. It groups and
/// enriches the candidates already found by BrowserModel, then hands the selected
/// candidate to the existing DownloadManager pipeline.
enum SmartMediaRadar {

    static func groups(from media: [DetectedMedia]) -> [SmartMediaGroup] {
        var buckets: [String: [DetectedMedia]] = [:]
        var order: [String] = []

        for candidate in media where !candidate.isFragment {
            let key = groupKey(for: candidate)
            if buckets[key] == nil {
                order.append(key)
                buckets[key] = []
            }
            // The browser already de-duplicates exact URLs, but keeping this guard
            // makes the radar safe when a page changes while its sheet is open.
            if !(buckets[key]?.contains(where: { $0.url == candidate.url }) ?? false) {
                buckets[key, default: []].append(candidate)
            }
        }

        return order.compactMap { key in
            guard let sources = buckets[key], !sources.isEmpty else { return nil }
            let sorted = sources.sorted { score($0) > score($1) }
            let first = sorted[0]
            return SmartMediaGroup(
                id: key,
                title: displayTitle(for: first),
                pageURL: first.pageURL,
                sources: sorted
            )
        }
        .sorted { lhs, rhs in
            let left = lhs.recommended.map(score) ?? Int.min
            let right = rhs.recommended.map(score) ?? Int.min
            if left != right { return left > right }
            return lhs.title.localizedCaseInsensitiveCompare(rhs.title) == .orderedAscending
        }
    }

    static func score(_ media: DetectedMedia) -> Int {
        if media.drm.isProtected || media.url.hasPrefix("blob:") || media.url.hasPrefix("data:") {
            return -10_000
        }

        var value = 0
        if media.canDownload { value += 100 }
        if media.kind.isCompleteVideo { value += 80 }
        if media.kind.avPlayerSupported { value += 30 }
        if media.kind == .hls { value += 20 }
        if media.kind == .dash { value -= 30 }
        if media.kind == .mp3 || media.kind == .aac || media.kind == .wav { value -= 15 }
        if media.extractionMethod.localizedCaseInsensitiveContains("html5") { value += 20 }
        if let duration = media.duration, duration > 5 { value += 15 }
        if let height = media.height, height > 0 { value += min(height / 10, 50) }
        if let variants = media.variants, !variants.isEmpty { value += 10 }
        if media.probed { value += 5 }
        return value
    }

    static func isDownloadableByExistingPipeline(_ media: DetectedMedia) -> Bool {
        // DASH is intentionally excluded here because the current DownloadManager
        // reports it as unsupported/protected. Leaving it visible is useful, but
        // the radar must not offer a misleading one-tap download button.
        media.canDownload && media.kind != .dash
    }

    static func status(for media: DetectedMedia) -> SmartMediaStatus {
        if media.drm.isProtected || media.url.hasPrefix("blob:") || media.url.hasPrefix("data:") {
            return .protected
        }
        if media.kind == .dash {
            return .dashUnsupported
        }
        if isDownloadableByExistingPipeline(media) {
            return .ready
        }
        return .detected
    }

    private static func groupKey(for media: DetectedMedia) -> String {
        let page = canonicalPage(media.pageURL)
        let title = normalizedTitle(media.title)

        // A page title plus its page URL is the safest useful logical grouping for
        // YouTube and similar sites: multiple quality/audio URLs become one card,
        // while sources from different pages remain separate.
        if !page.isEmpty && !title.isEmpty {
            return "page|\(page)|\(title)"
        }

        if media.kind == .hls {
            return "hls|\(canonicalResource(media.url))"
        }
        return "source|\(canonicalResource(media.url))"
    }

    private static func displayTitle(for media: DetectedMedia) -> String {
        let title = media.title.trimmingCharacters(in: .whitespacesAndNewlines)
        if !title.isEmpty { return title }
        if let host = URL(string: media.url)?.host, !host.isEmpty { return host }
        return "Media source"
    }

    private static func normalizedTitle(_ raw: String) -> String {
        raw.split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
    }

    private static func canonicalPage(_ raw: String?) -> String {
        guard let raw, let url = URL(string: raw),
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        }
        components.fragment = nil
        return components.string?.lowercased() ?? raw.lowercased()
    }

    private static func canonicalResource(_ raw: String) -> String {
        guard let url = URL(string: raw), var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return raw.lowercased()
        }
        components.fragment = nil
        return components.string?.lowercased() ?? raw.lowercased()
    }
}

struct SmartMediaGroup: Identifiable {
    let id: String
    let title: String
    let pageURL: String?
    let sources: [DetectedMedia]

    var recommended: DetectedMedia? {
        sources.max { SmartMediaRadar.score($0) < SmartMediaRadar.score($1) }
    }

    var downloadableCount: Int {
        sources.filter(SmartMediaRadar.isDownloadableByExistingPipeline).count
    }
}

enum SmartMediaStatus {
    case ready
    case protected
    case dashUnsupported
    case detected
}

/// Auth-aware, metadata-only probing for the new radar.
///
/// It makes HEAD/playlist requests with the same User-Agent, Referer, Origin and
/// relevant cookies as the current browser tab. It never bypasses DRM and never
/// replaces the existing detector's probing or download path.
enum SmartMediaProbe {

    static func enrich(_ media: DetectedMedia, auth: DownloadAuth) async -> DetectedMedia {
        var result = media
        guard let url = URL(string: media.url),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else {
            result.probed = true
            return result
        }

        if media.drm.isProtected {
            result.probed = true
            return result
        }

        if media.kind == .hls || url.pathExtension.lowercased() == "m3u8" || media.url.localizedCaseInsensitiveContains(".m3u8") {
            result = await enrichHLS(result, url: url, auth: auth)
        } else if media.byteSize == nil || media.mime == nil || media.mime?.isEmpty == true {
            if let metadata = await head(url, auth: auth) {
                if result.byteSize == nil { result.byteSize = metadata.length }
                if result.mime?.isEmpty != false { result.mime = metadata.type }
                result.kind = MediaKind.infer(url: result.url, mime: result.mime)
            }
        }

        result.probed = true
        return result
    }

    private static func enrichHLS(_ media: DetectedMedia, url: URL, auth: DownloadAuth) async -> DetectedMedia {
        var result = media
        guard let playlist = await get(url, auth: auth),
              let text = String(data: playlist.data, encoding: .utf8) else {
            return result
        }

        result.mime = playlist.type ?? result.mime ?? "application/vnd.apple.mpegurl"
        let drm = HLSInspector.inspect(playlist: text)
        if drm.isProtected {
            result.drm = drm
            return result
        }

        if HLSInspector.isMaster(text) {
            let variants = HLSInspector.variants(from: text, base: url)
            if !variants.isEmpty {
                result.variants = variants
                if result.qualityLabel?.isEmpty != false {
                    result.qualityLabel = variants.first?.qualityLabel
                }
                if result.height == nil { result.height = variants.first?.height }
                if result.width == nil { result.width = variants.first?.width }

                // Inspect one representative media playlist with the same auth
                // context. This catches protection declared only on the selected
                // rendition and gives the card a useful duration when available.
                if let first = variants.first,
                   let variantURL = URL(string: first.url),
                   let rendition = await get(variantURL, auth: auth),
                   let renditionText = String(data: rendition.data, encoding: .utf8) {
                    let renditionDRM = HLSInspector.inspect(playlist: renditionText)
                    if renditionDRM.isProtected {
                        result.drm = renditionDRM
                    } else if result.duration == nil || result.duration == 0 {
                        let duration = playlistDuration(renditionText)
                        if duration > 0 { result.duration = duration }
                    }
                }
            }
        } else if result.duration == nil || result.duration == 0 {
            let duration = playlistDuration(text)
            if duration > 0 { result.duration = duration }
        }
        return result
    }

    private static func get(_ url: URL, auth: DownloadAuth) async -> (data: Data, type: String?)? {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 12
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/vnd.apple.mpegurl, application/x-mpegURL, text/plain;q=0.8, */*;q=0.1", forHTTPHeaderField: "Accept")
        auth.apply(to: &request)

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse,
                  (200..<300).contains(http.statusCode) else { return nil }
            return (data, http.value(forHTTPHeaderField: "Content-Type"))
        } catch {
            return nil
        }
    }

    private static func head(_ url: URL, auth: DownloadAuth) async -> (length: Int64?, type: String?)? {
        var request = URLRequest(url: url)
        request.httpMethod = "HEAD"
        request.timeoutInterval = 10
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        auth.apply(to: &request)

        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse,
                  (200..<400).contains(http.statusCode) else { return nil }
            var length = http.value(forHTTPHeaderField: "Content-Length").flatMap(Int64.init)
            if let range = http.value(forHTTPHeaderField: "Content-Range"),
               let total = range.split(separator: "/").last,
               let value = Int64(total) {
                length = value
            }
            return (length, http.value(forHTTPHeaderField: "Content-Type"))
        } catch {
            return nil
        }
    }

    private static func playlistDuration(_ text: String) -> Double {
        text.components(separatedBy: .newlines).reduce(0) { total, line in
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard trimmed.uppercased().hasPrefix("#EXTINF:") else { return total }
            let raw = trimmed.dropFirst("#EXTINF:".count)
            let value = raw.split(separator: ",").first.flatMap { Double($0) } ?? 0
            return total + max(0, value)
        }
    }
}

@MainActor
final class SmartMediaRadarModel: ObservableObject {
    @Published private(set) var media: [DetectedMedia]
    @Published private(set) var isRefreshing = false
    @Published private(set) var lastUpdated: Date?

    let tab: BrowserTab
    private var refreshTask: Task<Void, Never>?

    init(tab: BrowserTab) {
        self.tab = tab
        self.media = tab.detected
    }

    var groups: [SmartMediaGroup] {
        SmartMediaRadar.groups(from: media)
    }

    func syncFromTab(_ incoming: [DetectedMedia]) {
        media = incoming.map { candidate in
            guard let cached = media.first(where: { $0.url == candidate.url }) else { return candidate }
            var merged = candidate
            if merged.byteSize == nil { merged.byteSize = cached.byteSize }
            if merged.mime?.isEmpty != false { merged.mime = cached.mime }
            if merged.duration == nil || merged.duration == 0 { merged.duration = cached.duration }
            if merged.width == nil { merged.width = cached.width }
            if merged.height == nil { merged.height = cached.height }
            if merged.qualityLabel?.isEmpty != false { merged.qualityLabel = cached.qualityLabel }
            if (merged.variants ?? []).isEmpty { merged.variants = cached.variants }
            if merged.drm == .none { merged.drm = cached.drm }
            merged.probed = merged.probed || cached.probed
            return merged
        }
    }

    func refresh() {
        refreshTask?.cancel()
        let candidates = tab.detected.filter { !$0.isFragment }
        syncFromTab(tab.detected)

        guard !candidates.isEmpty else {
            isRefreshing = false
            lastUpdated = Date()
            return
        }

        isRefreshing = true
        refreshTask = Task { [weak self] in
            guard let self else { return }
            var enriched: [DetectedMedia] = []
            enriched.reserveCapacity(candidates.count)

            for candidate in candidates {
                if Task.isCancelled { return }
                let auth = await BrowserAuth.snapshot(
                    webView: self.tab.webView,
                    pageURL: self.tab.urlString,
                    mediaURL: candidate.url
                )
                let result = await SmartMediaProbe.enrich(candidate, auth: auth)
                enriched.append(result)
            }

            guard !Task.isCancelled else { return }
            self.media = enriched
            self.lastUpdated = Date()
            self.isRefreshing = false
        }
    }

    func cancel() {
        refreshTask?.cancel()
        refreshTask = nil
        isRefreshing = false
    }
}

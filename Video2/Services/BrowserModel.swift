import Foundation
import Combine
import WebKit

final class BrowserTab: Identifiable, ObservableObject {
    let id = UUID()
    @Published var title: String = "تبويب جديد"
    @Published var urlString: String = ""
    @Published var isLoading = false
    @Published var estimatedProgress: Double = 0
    @Published var detected: [DetectedMedia] = []
    @Published var drmAlert: DRMKind = .none
    let webView: WKWebView

    init() {
        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        cfg.allowsPictureInPictureMediaPlayback = true
        cfg.preferences.javaScriptCanOpenWindowsAutomatically = true
        let uc = cfg.userContentController
        AdBlock.attach(to: uc)
        webView = WKWebView(frame: .zero, configuration: cfg)
        webView.allowsBackForwardNavigationGestures = true
        webView.customUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.4 Mobile/15E148 Safari/604.1"
        AdBlock.applyRules(to: webView)
    }

    func load(_ raw: String) {
        var t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return }
        if !t.contains("://") {
            if t.contains(".") && !t.contains(" ") {
                t = "https://" + t
            } else {
                let q = t.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? t
                t = "https://www.google.com/search?q=\(q)"
            }
        }
        urlString = t
        detected = []
        drmAlert = .none
        if let url = URL(string: t) {
            webView.load(URLRequest(url: url))
        }
    }
}

final class BrowserModel: ObservableObject {
    @Published var tabs: [BrowserTab]
    @Published var selectedID: UUID
    @Published var showDetector = false
    @Published var showDRMBanner = false
    @Published var lastDRM: DRMKind = .none

    var current: BrowserTab {
        tabs.first(where: { $0.id == selectedID }) ?? tabs[0]
    }

    init() {
        let t = BrowserTab()
        tabs = [t]
        selectedID = t.id
    }

    func newTab() {
        let t = BrowserTab()
        tabs.append(t)
        selectedID = t.id
    }

    func close(_ id: UUID) {
        tabs.removeAll { $0.id == id }
        if tabs.isEmpty {
            newTab()
            return
        }
        if selectedID == id {
            selectedID = tabs[0].id
        }
    }

    func ingest(media: DetectedMedia, tab: BrowserTab) {
        if let i = tab.detected.firstIndex(where: { $0.url == media.url }) {
            var old = tab.detected[i]
            if media.drm.isProtected { old.drm = media.drm }
            if old.duration == nil || old.duration == 0 { old.duration = media.duration }
            if old.height == nil { old.height = media.height }
            if old.width == nil { old.width = media.width }
            if (old.qualityLabel ?? "").isEmpty { old.qualityLabel = media.qualityLabel }
            tab.detected[i] = old
            return
        }
        if media.isFragment { return }
        tab.detected.insert(media, at: 0)
        tab.detected.sort { score($0) > score($1) }
        if media.drm.isProtected {
            lastDRM = media.drm
            showDRMBanner = true
            tab.drmAlert = media.drm
        }
        Task { @MainActor in
            let enriched = await MediaProbe.enrich(media)
            if let i = tab.detected.firstIndex(where: { $0.url == enriched.url }) {
                tab.detected[i] = enriched
                tab.detected.sort { self.score($0) > self.score($1) }
            }
        }
    }

    private func score(_ m: DetectedMedia) -> Int {
        var s = 0
        if m.kind.isCompleteVideo { s += 50 }
        if m.kind.avPlayerSupported { s += 20 }
        if (m.duration ?? 0) > 5 { s += 15 }
        if m.extractionMethod.contains("html5") { s += 25 }
        if let h = m.height { s += min(h / 10, 40) }
        if m.drm.isProtected { s -= 80 }
        return s
    }

    func ingestDRM(_ kind: DRMKind, tab: BrowserTab) {
        tab.drmAlert = kind
        lastDRM = kind
        showDRMBanner = true
        for i in tab.detected.indices {
            if tab.detected[i].drm == .none {
                tab.detected[i].drm = kind
            }
        }
    }
}

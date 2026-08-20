import SwiftUI
import WebKit

struct WebView: UIViewRepresentable {
    @ObservedObject var tab: BrowserTab
    var model: BrowserModel

    func makeCoordinator() -> Coordinator { Coordinator(tab: tab, model: model) }

    func makeUIView(context: Context) -> WKWebView {
        let wv = tab.webView
        wv.navigationDelegate = context.coordinator
        wv.uiDelegate = context.coordinator
        wv.configuration.userContentController.removeScriptMessageHandler(forName: "video2")
        wv.configuration.userContentController.add(context.coordinator, name: "video2")
        context.coordinator.bindProgress(wv)
        return wv
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        context.coordinator.tab = tab
        context.coordinator.model = model
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
        var tab: BrowserTab
        var model: BrowserModel
        private var progressObs: NSKeyValueObservation?
        private var titleObs: NSKeyValueObservation?
        private var urlObs: NSKeyValueObservation?

        init(tab: BrowserTab, model: BrowserModel) {
            self.tab = tab
            self.model = model
        }

        func bindProgress(_ wv: WKWebView) {
            progressObs = wv.observe(\.estimatedProgress) { [weak self] w, _ in
                DispatchQueue.main.async { self?.tab.estimatedProgress = w.estimatedProgress }
            }
            titleObs = wv.observe(\.title) { [weak self] w, _ in
                DispatchQueue.main.async { self?.tab.title = w.title ?? "تبويب" }
            }
            urlObs = wv.observe(\.url) { [weak self] w, _ in
                DispatchQueue.main.async { self?.tab.urlString = w.url?.absoluteString ?? self?.tab.urlString ?? "" }
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            if AdBlock.isEnabled, AdBlock.hostIsAd(navigationAction.request.url?.host) {
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            tab.isLoading = true
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            tab.isLoading = false
            webView.evaluateJavaScript(ExtractorScript.source, completionHandler: nil)
            if AdBlock.isEnabled {
                webView.evaluateJavaScript(AdBlock.cosmeticJS, completionHandler: nil)
            }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            tab.isLoading = false
        }

        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            if AdBlock.isEnabled, AdBlock.hostIsAd(navigationAction.request.url?.host) {
                return nil
            }
            if navigationAction.targetFrame == nil {
                webView.load(navigationAction.request)
            }
            return nil
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let body = message.body as? [String: Any] else { return }
            let type = body["type"] as? String ?? ""
            DispatchQueue.main.async {
                if type == "drm" {
                    let raw = body["drm"] as? String ?? "unknownProtected"
                    let kind = DRMKind(rawValue: raw) ?? .unknownProtected
                    self.model.ingestDRM(kind, tab: self.tab)
                    return
                }
                if type == "media", let item = body["item"] as? [String: Any] {
                    let url = item["url"] as? String ?? ""
                    let drmRaw = item["drm"] as? String ?? "none"
                    let kindRaw = item["kind"] as? String ?? "other"
                    let media = DetectedMedia(
                        url: url,
                        title: (item["title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (body["title"] as? String ?? self.tab.title),
                        kind: MediaKind(rawValue: kindRaw) ?? .other,
                        mime: item["mime"] as? String,
                        qualityLabel: item["qualityLabel"] as? String,
                        drm: DRMKind(rawValue: drmRaw) ?? .none,
                        pageURL: body["page"] as? String,
                        extractionMethod: item["extractionMethod"] as? String ?? "js"
                    )
                    self.model.ingest(media: media, tab: self.tab)
                }
            }
        }
    }
}

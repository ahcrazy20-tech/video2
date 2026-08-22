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

        // إزالة الـ handler القديم (لو موجود) قبل إضافة الجديد
        wv.configuration.userContentController.removeScriptMessageHandler(forName: "video2")
        wv.configuration.userContentController.add(context.coordinator, name: "video2")

        context.coordinator.bindProgress(wv)

        // تحميل الصفحة الافتراضية لو مفيش URL
        if wv.url == nil {
            wv.load(URLRequest(url: URL(string: "https://www.google.com")!))
        }

        return wv
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        context.coordinator.tab = tab
        context.coordinator.model = model
        // لا نحتاج نعمل أي حاجة هنا - الـ webView نفسه بيتعامل مع التحديثات
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
            // إزالة الـ observers القديمة أولاً
            progressObs?.invalidate()
            titleObs?.invalidate()
            urlObs?.invalidate()

            progressObs = wv.observe(\.estimatedProgress) { [weak self] w, _ in
                DispatchQueue.main.async { self?.tab.estimatedProgress = w.estimatedProgress }
            }
            titleObs = wv.observe(\.title) { [weak self] w, _ in
                DispatchQueue.main.async { self?.tab.title = w.title ?? "تبويب" }
            }
            urlObs = wv.observe(\.url) { [weak self] w, _ in
                DispatchQueue.main.async { self?.tab.urlString = w.url?.absoluteString ?? "" }
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            if AdBlock.isEnabled, AdBlock.hostIsAd(navigationAction.request.url?.host) {
                decisionHandler(.cancel)
                return
            }
            // السماح بالـ navigation
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            tab.isLoading = true
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            tab.isLoading = false
            webView.evaluateJavaScript(ExtractorScript.source, completionHandler: nil)
            if AdBlock.isEnabled, AdBlock.mode == "strict" {
                webView.evaluateJavaScript(AdBlock.cosmeticJS, completionHandler: nil)
            }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            tab.isLoading = false
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
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
                    let dur = (item["duration"] as? NSNumber)?.doubleValue
                    let w = (item["width"] as? NSNumber)?.intValue
                    let h = (item["height"] as? NSNumber)?.intValue
                    let media = DetectedMedia(
                        url: url,
                        title: (item["title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (body["title"] as? String ?? self.tab.title),
                        kind: MediaKind(rawValue: kindRaw) ?? MediaKind.infer(url: url, mime: item["mime"] as? String),
                        mime: item["mime"] as? String,
                        qualityLabel: item["qualityLabel"] as? String,
                        drm: DRMKind(rawValue: drmRaw) ?? .none,
                        pageURL: body["page"] as? String,
                        extractionMethod: item["extractionMethod"] as? String ?? "js",
                        duration: (dur ?? 0) > 0 ? dur : nil,
                        byteSize: nil,
                        width: (w ?? 0) > 0 ? w : nil,
                        height: (h ?? 0) > 0 ? h : nil,
                        probed: false
                    )
                    self.model.ingest(media: media, tab: self.tab)
                }
            }
        }
    }
}

import Foundation
import WebKit

// MARK: - الصياد الخفي (Magic Page Hunter)
//
// متصفح WKWebView غير ظاهر داخل تبويب البحث: يفتح صفحة النتيجة، يحقن نفس سكربت
// الاستخراج المستخدم في المتصفح (+ خطّاف لردّ يوتيوب الداخلي youtubei/player
// الذي يحمل روابط التشغيل)، ويجمع روابط الوسائط التي تولّدها سكربتات الصفحة.
//
// القراءة فقط: لا يحمّل ولا يخزّن شيئاً، ولا يمسّ تبويب المتصفح ولا BrowserModel.
// كل شيء داخل نسخة WKWebView خاصة به، وتُغلق بعد ثوانٍ.

final class MagicPageHunter: NSObject, WKNavigationDelegate, WKScriptMessageHandler {

    /// إبقاء الصياد حيّاً أثناء العمل (WKWebView يحتاج مرجعاً قوياً).
    private static var retain: [MagicPageHunter] = []

    private let pageURL: String
    private let pageTitle: String
    private let timeout: TimeInterval
    private var webView: WKWebView?
    private var continuation: CheckedContinuation<[MagicStreamVariant], Never>?
    private var collected: [MagicStreamVariant] = []
    private var seen = Set<String>()
    private var settled = false
    private var timerWork: DispatchWorkItem?
    private var settleWork: DispatchWorkItem?

    private init(url: String, title: String, timeout: TimeInterval) {
        self.pageURL = url
        self.pageTitle = title
        self.timeout = timeout
    }

    /// يفتح الصفحة ويرجع مصادر التشغيل التي ظهرت فيها.
    static func hunt(url: String, title: String, timeout: TimeInterval = 13) async -> [MagicStreamVariant] {
        guard url.hasPrefix("http"), MagicStreamProxy.parse(url) != nil else { return [] }
        return await withCheckedContinuation { (cont: CheckedContinuation<[MagicStreamVariant], Never>) in
            DispatchQueue.main.async {
                let hunter = MagicPageHunter(url: url, title: title, timeout: timeout)
                retain.append(hunter)
                hunter.continuation = cont
                hunter.start()
            }
        }
    }

    // MARK: التشغيل

    private func start() {
        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        cfg.preferences.javaScriptCanOpenWindowsAutomatically = false
        let uc = cfg.userContentController
        uc.add(self, name: "video2")
        uc.addUserScript(WKUserScript(source: Self.hookScript, injectionTime: .atDocumentEnd, forMainFrameOnly: false))
        uc.addUserScript(WKUserScript(source: ExtractorScript.source, injectionTime: .atDocumentEnd, forMainFrameOnly: false))

        // إطار صغير خارج الشاشة: WKWebView يعمل بدون النافذة، وهذا يكفي لطلبات
        // الشبكة التي تولّدها سكربتات الصفحة (لا نحتاج عرض شيء).
        let wv = WKWebView(frame: CGRect(x: -4000, y: 0, width: 360, height: 200), configuration: cfg)
        wv.navigationDelegate = self
        wv.isHidden = true
        webView = wv

        if let url = MagicStreamProxy.parse(pageURL) {
            var req = URLRequest(url: url)
            req.timeoutInterval = 12
            wv.load(req)
        } else {
            finish()
            return
        }

        let work = DispatchWorkItem { [weak self] in self?.finish() }
        timerWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + timeout, execute: work)
    }

    private func kick() {
        // يحفّز العناصر على التشغيل الصامت حتى تجلب الصفحة قوائم الوسائط
        webView?.evaluateJavaScript(Self.kickScript, completionHandler: nil)
    }

    private func ingest(_ item: [String: Any], page: String) {
        let raw = (item["url"] as? String) ?? ""
        guard raw.hasPrefix("http"), let abs = URL(string: raw, relativeTo: URL(string: page))?.absoluteURL else { return }
        let key = MagicResolver.canonical(abs.absoluteString)
        guard !seen.contains(key) else { return }
        let drmRaw = (item["drm"] as? String) ?? "none"
        if DRMKind(rawValue: drmRaw)?.isProtected == true { return }
        let mime = (item["mime"] as? String) ?? ""
        let kindRaw = (item["kind"] as? String) ?? ""
        var kind = MediaKind(rawValue: kindRaw) ?? .other
        if kind == .other || kindRaw.isEmpty { kind = MagicPageHunter.guessKind(url: abs.absoluteString, mime: mime) }
        if kind == .ts || kind == .dash || kind == .other || kind == .aac || kind == .mp3 || kind == .wav { return }
        let height = (item["height"] as? NSNumber)?.intValue
        let dur = (item["duration"] as? NSNumber)?.doubleValue
        let qualityLabel = (item["qualityLabel"] as? String) ?? ""
        let needsProxy = kind == .hls
        let variant = MagicStreamVariant(
            url: abs.absoluteString,
            label: "\(kind.titleAR)\(qualityLabel.isEmpty ? "" : " · \(qualityLabel)") · صيد من الصفحة",
            kind: kind,
            sizeBytes: nil,
            height: (height ?? 0) > 0 ? height : nil,
            pageURL: page,
            headers: ["Referer": page, "User-Agent": DownloadAuth.safariUA],
            needsProxy: needsProxy,
            downloadable: true
        )
        seen.insert(key)
        collected.append(variant)
        // إن وجد مصدر يعمل فعلاً نمنح الصفحة مهلة قصيرة ثم نكتفي بما جاء
        if variant.isPlayableByEngine, settleWork == nil {
            let w = DispatchWorkItem { [weak self] in self?.finish() }
            settleWork = w
            DispatchQueue.main.asyncAfter(deadline: .now() + 4, execute: w)
        }
    }

    private func finish() {
        guard !settled else { return }
        settled = true
        timerWork?.cancel()
        settleWork?.cancel()
        let cont = continuation
        continuation = nil
        DispatchQueue.main.async { [weak self] in
            guard let self else { cont?.resume(returning: []); return }
            self.teardown()
            MagicPageHunter.retain.removeAll { $0 === self }
            cont?.resume(returning: self.collected)
        }
    }

    deinit {
        teardown()
    }

    private func teardown() {
        webView?.stopLoading()
        webView?.configuration.userContentController.removeScriptMessageHandler(forName: "video2")
        webView?.removeFromSuperview()
        webView?.navigationDelegate = nil
        webView = nil
    }

    // MARK: WKNavigationDelegate

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        kick()
        webView.evaluateJavaScript(ExtractorScript.source, completionHandler: nil)
        // مهلة إضافية للسكربتات البطيئة ثم نكتفي
        let extra = DispatchWorkItem { [weak self] in self?.finish() }
        self.timerWork?.cancel()
        self.timerWork = extra
        DispatchQueue.main.asyncAfter(deadline: .now() + 7, execute: extra)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError: Error) {
        if collected.isEmpty { finish() }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError: Error) {
        if collected.isEmpty { finish() }
    }

    // MARK: رسائل السكربت

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any] else { return }
        let type = body["type"] as? String ?? ""
        guard type == "media" else { return }
        let page = (body["page"] as? String) ?? pageURL
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.settled else { return }
            if let item = body["item"] as? [String: Any] {
                self.ingest(item, page: page)
            }
        }
    }

    // MARK: أدوات

    /// استنباط الصيغة من الرابط/الـ mime، مع التعامل مع روابط googlevideo.
    static func guessKind(url: String, mime: String) -> MediaKind {
        let u = url.lowercased()
        if u.contains("mime=video%2fmp4") || u.contains("mime=video/mp4") || u.contains("&sparams=") { return .mp4 }
        let inferred = MediaKind.infer(url: u, mime: mime.isEmpty ? nil : mime)
        if inferred != .other { return inferred }
        if u.contains("videoplayback") { return .mp4 }
        return .other
    }

    // MARK: السكربتات

    /// خطّاف على fetch/XHR لالتقاط ردّ يوتيوب الداخلي (streamingData) الذي يحمل
    /// روابط التشغيل الصريحة — وهو ما لا يظهر في وسوم <video> عند استخدام MSE.
    static let hookScript = """
    (function() {
      if (window.__v2hunt) return;
      window.__v2hunt = true;
      function post(item) {
        try {
          webkit.messageHandlers.video2.postMessage({ type: 'media', item: item, page: location.href, title: document.title || '' });
        } catch (e) {}
      }
      function emitPlayer(json, via) {
        try {
          var sd = json && json.streamingData;
          if (!sd) return;
          var secs = 0;
          try { secs = Number((json.videoDetails && json.videoDetails.lengthSeconds) || 0); } catch (e) {}
          var formats = sd.formats || [];
          for (var i = 0; i < formats.length; i++) {
            var f = formats[i];
            if (!f || !f.url) continue;
            var isMp4 = (f.mimeType || '').indexOf('mp4') >= 0;
            post({
              url: f.url,
              title: document.title || '',
              kind: isMp4 ? 'mp4' : 'other',
              mime: f.mimeType || '',
              qualityLabel: f.qualityLabel || f.quality || '',
              width: f.width || 0,
              height: f.height || 0,
              duration: secs,
              drm: 'none',
              extractionMethod: 'innertube-' + via
            });
          }
          if (sd.hlsManifestUrl) {
            post({ url: sd.hlsManifestUrl, title: document.title || '', kind: 'hls', mime: 'application/vnd.apple.mpegurl',
                   qualityLabel: 'HLS', duration: secs, drm: 'none', extractionMethod: 'innertube-' + via });
          }
        } catch (e) {}
      }
      function looksLikePlayer(u) {
        if (!u || typeof u !== 'string') return false;
        return u.indexOf('/youtubei/v1/player') >= 0 || u.indexOf('/get_video_info') >= 0 || u.indexOf('/player?') >= 0;
      }
      var origFetch = window.fetch;
      if (origFetch) {
        window.fetch = function() {
          var args = arguments;
          var url = '';
          try { var input = args[0]; url = (typeof input === 'string') ? input : (input && input.url) || ''; } catch (e) {}
          var p = origFetch.apply(this, args);
          if (looksLikePlayer(url)) {
            try {
              p.then(function(res) {
                try { res.clone().text().then(function(t) { emitPlayer(JSON.parse(t), 'fetch'); }, function() {}); } catch (e) {}
              }, function() {});
            } catch (e) {}
          }
          return p;
        };
      }
      var origOpen = XMLHttpRequest.prototype.open;
      var origSend = XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.open = function(method, url) {
        try { this.__v2url = String(url || ''); } catch (e) {}
        return origOpen.apply(this, arguments);
      };
      XMLHttpRequest.prototype.send = function() {
        try {
          var x = this;
          if (looksLikePlayer(x.__v2url)) {
            x.addEventListener('load', function() {
              try {
                if (!x.responseType || x.responseType === 'text') { emitPlayer(JSON.parse(x.responseText || '{}'), 'xhr'); }
              } catch (e) {}
            });
          }
        } catch (e) {}
        return origSend.apply(this, arguments);
      };
      true;
    })();
    true;
    """

    /// محاولة تشغيل صامتة للعناصر حتى تطلب الصفحة قوائم الوسائط الخاصة بها.
    static let kickScript = """
    (function() {
      function kick() {
        var vids = document.querySelectorAll('video');
        for (var i = 0; i < vids.length; i++) {
          var v = vids[i];
          try {
            v.muted = true;
            v.playsInline = true;
            var p = v.play && v.play();
            if (p && p.catch) { p.catch(function() {}); }
          } catch (e) {}
        }
        var btns = document.querySelectorAll('[class*="play"], [aria-label*="play"], [aria-label*="تشغيل"], button');
        var clicked = 0;
        for (var j = 0; j < btns.length && clicked < 2; j++) {
          var b = btns[j];
          var label = (b.className || '') + ' ' + (b.getAttribute('aria-label') || '');
          if (/play|تشغيل/i.test(label) && b.tagName === 'BUTTON') { try { b.click(); clicked++; } catch (e) {} }
        }
      }
      kick();
      setTimeout(kick, 800);
      setTimeout(kick, 2200);
    })();
    true;
    """
}

import Foundation
import UIKit
import WebKit

// MARK: - الصياد الخفي (Magic Page Hunter)
//
// متصفح WKWebView حقيقي داخل تبويب البحث، لكن خارج نافذة العرض: يفتح صفحة النتيجة
// بنفس إعدادات المتصفح الظاهر (نفس UA ونفس مخزن الكوكيز)، ويُحقن بنفس
// ExtractorScript المستخدم في المتصفح + خطّاف على ردود youtubei/player، إضافة إلى
// «محرك النقر» الذي يحاكي لمسة المستخدم على أزرار التشغيل/البوستر — لأن كثيراً من
// المواقع لا تطلب ملف الفيديو إلا بعد نقرة حقيقية.
//
// الفروق عن النسخة القديمة (كلها لجعل الصيد مثل المتصفح تماماً):
// • العرض خارج الشاشة لكن داخل نافذة حقيقية وغير مخفي → WebKit يعدّ الصفحة «مرئية»
//   فتعمل مؤقتاتها ورسمها وعناصرها الكسولة (lazy) بشكل طبيعي.
// • سكربت documentStart يجعل visibilityState=visible ويعطّل تعطيل IntersectionObserver
//   (يُشغّل المشغّلات الكسولة) ويحوّل data-src إلى src فوراً.
// • محرك النقر يعمل في كل الإطارات (main + iframes) ويكرر المحاولة على جدول زمني،
//   ولا ينتظر didFinish (صفحات SPA قد لا تُكمل التحميل أبداً).
// • أحداث لمس واقعية (touch/pointer/mouse + click عند إحداثيات العنصر) تصل حتى
//   للأوفرلايات فوق الفيديو (بوستر التشغيل) لا للأزرار فقط.
// • حاجز تنقّل: يمنع النوافذ المنبثقة وأي انتقال من الصفحة الأم بعد استقرارها،
//   حتى لا تؤدي نقرة محاكاة إلى مغادرة الصفحة نحو إعلان.
// • الكوكيز المشتركة مع المتصفح تُلحق بمصادر الصيد (مثل BrowserAuth تماماً).
//
// القراءة فقط: لا يحمّل ولا يخزّن شيئاً، ولا يمسّ تبويب المتصفح ولا BrowserModel.
// كل شيء داخل نسخة WKWebView خاصة به، وتُغلق بعد ثوانٍ.

final class MagicPageHunter: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {

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
    private var committed = false
    private var timerWork: DispatchWorkItem?
    private var settleWork: DispatchWorkItem?
    private var kickWorks: [DispatchWorkItem] = []

    private init(url: String, title: String, timeout: TimeInterval) {
        self.pageURL = url
        self.pageTitle = title
        self.timeout = timeout
    }

    /// يفتح الصفحة ويرجع مصادر التشغيل التي ظهرت فيها.
    static func hunt(url: String, title: String, timeout: TimeInterval = 15) async -> [MagicStreamVariant] {
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
        // نفس مخزن بيانات المتصفح الظاهر → كوكيز الجلسة مشتركة (مواقع كثيرة تطلبها).
        cfg.websiteDataStore = .default()
        let uc = cfg.userContentController
        uc.add(self, name: "video2")
        uc.addUserScript(WKUserScript(source: Self.prepScript, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        uc.addUserScript(WKUserScript(source: Self.hookScript, injectionTime: .atDocumentEnd, forMainFrameOnly: false))
        uc.addUserScript(WKUserScript(source: ExtractorScript.source, injectionTime: .atDocumentEnd, forMainFrameOnly: false))
        uc.addUserScript(WKUserScript(source: Self.kickEngineScript, injectionTime: .atDocumentEnd, forMainFrameOnly: false))

        let wv = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 720), configuration: cfg)
        wv.navigationDelegate = self
        wv.uiDelegate = self
        // نفس UA للمتصفح الظاهر حتى يعاملنا الموقع معاملة واحدة.
        wv.customUserAgent = DownloadAuth.safariUA
        webView = wv
        parkOffscreen(wv)

        if let url = MagicStreamProxy.parse(pageURL) {
            var req = URLRequest(url: url)
            req.timeoutInterval = 12
            wv.load(req)
        } else {
            finish()
            return
        }

        // نهاية صارمة مهما حدث — ثم رفسات تحفيز من طرف Swift أيضاً (لا نعتمد على
        // didFinish وحده: صفحات SPA قد لا تُكمل التحميل أبداً).
        let work = DispatchWorkItem { [weak self] in self?.finish() }
        timerWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + timeout, execute: work)
        for delay in [TimeInterval(2.5), 5.5, 8.5, 11.5] {
            scheduleNudge(after: delay)
        }
    }

    /// يضع الصياد خارج الشاشة لكن داخل نافذة حقيقية وغير مخفي:
    /// WebKit يعدّ الصفحة «مرئية» فتشغّل مؤقتاتها ورسمها وعناصرها الكسولة
    /// (WKWebView مخفي أو بلا نافذة = صفحة «hidden» تُخنق وتتوقف عن العمل).
    private func parkOffscreen(_ wv: WKWebView) {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
        let host = windows.first(where: { $0.isKeyWindow }) ?? windows.first
        wv.frame = CGRect(x: -8000, y: 0, width: 390, height: 720)
        wv.isHidden = false
        wv.isUserInteractionEnabled = false
        host?.addSubview(wv)
    }

    private func scheduleNudge(after delay: TimeInterval) {
        let work = DispatchWorkItem { [weak self] in
            self?.webView?.evaluateJavaScript(Self.nudgeScript, completionHandler: nil)
        }
        kickWorks.append(work)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func ingest(_ item: [String: Any], page: String) {
        let raw = (item["url"] as? String) ?? ""
        guard raw.hasPrefix("http"), let abs = URL(string: raw, relativeTo: URL(string: page))?.absoluteURL else { return }
        if AdBlock.filterVideoAds && AdBlock.isAdURL(abs.absoluteString) { return }
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
        if let dur, dur > 0 && dur <= 5.0 && AdBlock.isAdURL(abs.absoluteString) { return }
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
            DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: w)
        }
    }

    private func finish() {
        guard !settled else { return }
        settled = true
        timerWork?.cancel()
        settleWork?.cancel()
        kickWorks.forEach { $0.cancel() }
        kickWorks.removeAll()
        let cont = continuation
        continuation = nil
        let collected = self.collected
        DispatchQueue.main.async { [weak self] in
            guard let self else { cont?.resume(returning: []); return }
            self.teardown()
            MagicPageHunter.retain.removeAll { $0 === self }
            guard !collected.isEmpty, cont != nil else {
                cont?.resume(returning: collected)
                return
            }
            Task { @MainActor in
                let enriched = await Self.attachCookies(to: collected)
                cont?.resume(returning: enriched)
            }
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
        webView?.uiDelegate = nil
        webView = nil
    }

    // MARK: WKNavigationDelegate

    /// حاجز التنقّل: الصيد قراءة فقط لصفحة النتيجة.
    /// يمنع النوافذ المنبثقة، وأي رابط ينقل الصفحة الأم بعد استقرارها (قد تنتج عن
    /// نقرة المحاكاة أو إعلان) — بينما يسمح بكل ما تحتاجه الصفحة نفسها: إطارات
    /// مضمنة، طلبات، وإعادة توجيه من السيرفر.
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if navigationAction.targetFrame == nil {
            decisionHandler(.cancel)
            return
        }
        if AdBlock.isEnabled, let host = navigationAction.request.url?.host, AdBlock.hostIsAd(host) {
            decisionHandler(.cancel)
            return
        }
        if navigationAction.targetFrame?.isMainFrame == true,
           navigationAction.navigationType == .linkActivated, committed {
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        committed = true
        scheduleNudge(after: 1.2)
        scheduleNudge(after: 3.5)
        scheduleNudge(after: 6.5)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        committed = true
        webView.evaluateJavaScript(Self.nudgeScript, completionHandler: nil)
        // مهلة إضافية للسكربتات البطيئة ثم نكتفي
        let extra = DispatchWorkItem { [weak self] in self?.finish() }
        timerWork?.cancel()
        timerWork = extra
        DispatchQueue.main.asyncAfter(deadline: .now() + 7, execute: extra)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        if collected.isEmpty { finish() }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if collected.isEmpty { finish() }
    }

    // MARK: WKUIDelegate — لا نوافذ منبثقة إطلاقاً

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        nil
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

    /// يلحق كوكيز الجلسة (نفس مخزن المتصفح) بمصادر الصيد — كثير من سيرفرات CDN
    /// يربط رابط الملف بكوكيز الجلسة، وهذا نفس ما يفعله مسار المتصفح (BrowserAuth).
    @MainActor
    private static func attachCookies(to variants: [MagicStreamVariant]) async -> [MagicStreamVariant] {
        let cookies: [HTTPCookie] = await withCheckedContinuation { cont in
            WKWebsiteDataStore.default().httpCookieStore.getAllCookies { cont.resume(returning: $0) }
        }
        guard !cookies.isEmpty else { return variants }
        return variants.map { v in
            guard let host = URL(string: v.url)?.host?.lowercased() else { return v }
            let pageHost = URL(string: v.pageURL ?? "")?.host?.lowercased()
            let relevant = cookies.filter { c in
                let d = c.domain.trimmingCharacters(in: CharacterSet(charactersIn: ".")).lowercased()
                guard !d.isEmpty else { return false }
                if host == d || host.hasSuffix("." + d) { return true }
                if let ph = pageHost, ph == d || ph.hasSuffix("." + d) { return true }
                return false
            }
            guard !relevant.isEmpty else { return v }
            var copy = v
            copy.headers["Cookie"] = relevant.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
            return copy
        }
    }

    // MARK: السكربتات

    /// تحضير قبل سكربتات الصفحة (documentStart، كل الإطارات):
    /// الصفحة «مرئية» دائماً + عناصر lazy تُحمّل فوراً + IntersectionObserver لا يُعطّل
    /// شيئاً — هذه أكبر أسباب فشل الصيد في مواقع تعمل تماماً في المتصفح الظاهر.
    /// (تعمل فقط داخل WKWebView الصيّاد المنعزل، وليس في تبويب المتصفح أبداً.)
    static let prepScript = """
    (function() {
      if (window.__v2prep) return;
      window.__v2prep = true;
      try { Object.defineProperty(document, 'visibilityState', { configurable: true, get: function() { return 'visible'; } }); } catch (e) {}
      try { Object.defineProperty(document, 'webkitVisibilityState', { configurable: true, get: function() { return 'visible'; } }); } catch (e) {}
      try { Object.defineProperty(document, 'hidden', { configurable: true, get: function() { return false; } }); } catch (e) {}
      try { Object.defineProperty(document, 'webkitHidden', { configurable: true, get: function() { return false; } }); } catch (e) {}
      try {
        var OrigIO = window.IntersectionObserver;
        if (OrigIO && !window.__v2io) {
          window.__v2io = true;
          var PatchedIO = function(cb, opts) {
            var inst = null;
            try { inst = new OrigIO(cb, opts); } catch (e) { return null; }
            var origObserve = inst.observe;
            inst.observe = function(el) {
              try {
                setTimeout(function() {
                  var rect;
                  try { rect = el.getBoundingClientRect(); } catch (e) { rect = { top: 0, left: 0, right: 320, bottom: 180, width: 320, height: 180 }; }
                  cb([{ target: el, isIntersecting: true, intersectionRatio: 1, rootBounds: null,
                        boundingClientRect: rect, intersectionRect: rect,
                        time: (performance && performance.now) ? performance.now() : Date.now() }], inst);
                }, 80);
              } catch (e) {}
              try { return origObserve.apply(inst, arguments); } catch (e) {}
            };
            return inst;
          };
          PatchedIO.prototype = OrigIO.prototype;
          window.IntersectionObserver = PatchedIO;
        }
      } catch (e) {}
      var lazyRounds = 0;
      function forceLazy() {
        try {
          document.querySelectorAll('[loading="lazy"]').forEach(function(el) { try { el.loading = 'eager'; } catch (e) {} });
          document.querySelectorAll('iframe[data-src],iframe[data-lazy-src],iframe[data-original],video[data-src],video[data-lazy-src],source[data-src],img[data-src]').forEach(function(el) {
            try {
              var s = el.getAttribute('data-src') || el.getAttribute('data-lazy-src') || el.getAttribute('data-original');
              if (s && !el.src) { el.src = s; }
            } catch (e) {}
          });
        } catch (e) {}
      }
      forceLazy();
      var t = setInterval(function() { forceLazy(); lazyRounds++; if (lazyRounds >= 14) clearInterval(t); }, 1000);
    })();
    true;
    """

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

    /// محرك النقر (documentEnd، كل الإطارات بما فيها iframes المشغّل):
    /// يحاكي لمسة مستخدم حقيقية (touch/pointer/mouse/click بإحداثيات العنصر) على
    /// أزرار التشغيل المعروفة وعلى ما يغطي الفيديو (بوستر/أوفرلاي)، ويشغّل
    /// العناصر صامتة عبر واجهات jwplayer/videojs/plyr، ويكرر ذلك على جدول زمني
    /// لأن كثيراً من المواقع لا تطلب ملف الفيديو إلا بعد نقرة.
    static let kickEngineScript = """
    (function() {
      if (window.__v2kick) return;
      window.__v2kick = true;

      var BAD = /download|تحميل|share|مشاركة|subscribe|اشترك|login|log in|sign in|register|signup|إغلاق|close|skip|تخطي|banner|advert|اعلان|إعلان|captcha/;
      var PLAYISH = /(^|[^a-z])(play|resume)([^a-z]|$)|big.?play|play.?button|playbutton|play.?btn|play.?overlay|overlay.?play|play.?icon|تشغيل|شغّل|شغل|ابدأ|إبدأ|شاهد|مشاهدة|انقر هنا|اضغط هنا|watch now|start video/;

      function vis(el) {
        try {
          var r = el.getBoundingClientRect();
          var w = window.innerWidth || document.documentElement.clientWidth || 0;
          var h = window.innerHeight || document.documentElement.clientHeight || 0;
          return r.width >= 8 && r.height >= 8 && r.bottom > 0 && r.right > 0 && r.top < h && r.left < w;
        } catch (e) { return false; }
      }

      function tagOf(el) {
        try {
          var cls = (typeof el.className === 'string') ? el.className : '';
          return ((el.id || '') + ' ' + cls + ' ' + (el.getAttribute('aria-label') || '') + ' ' +
                  (el.getAttribute('title') || '') + ' ' + (el.getAttribute('data-play') || ''));
        } catch (e) { return ''; }
      }

      function inAd(el) {
        try {
          return !!el.closest && el.closest('.video-ads, .ytp-ad-module, .ad-container, .ad-slot, .ad-banner, .ima-ad, ins.adsbygoogle, [class*="popup-ad"], [id*="popunder"], [data-ad], [data-is-ad="true"]');
        } catch (e) { return false; }
      }

      function okEl(el) {
        if (!el || !el.tagName || !vis(el) || inAd(el)) return false;
        var t = (tagOf(el) + ' ' + (el.textContent || '').trim().substring(0, 40)).toLowerCase();
        if (BAD.test(t)) return false;
        return true;
      }

      var KNOWN = [
        '.vjs-big-play-button', '.jw-display-icon-display', '.jw-display-icon-playback',
        '.jw-icon-playback', '.plyr__control--overlaid', '.shaka-play-button',
        '.bigplay-button', '.bigplayBtn', '.big-play-button', '.big-play-btn', '.bigplay',
        '.play-overlay', '.overlay-play', '.player-overlay-play',
        '.mejs__overlay-button', '.mejs-overlay-button',
        '.bmpui-ui-playbacktoggle-overlay button', '.amp-playpause-icon',
        '[class*="bigplay"]', '[class*="big-play"]'
      ];

      function candidates() {
        var out = [];
        var set = new Set();
        function add(el) { if (el && el.tagName && !set.has(el)) { set.add(el); out.push(el); } }
        for (var i = 0; i < KNOWN.length; i++) {
          try { document.querySelectorAll(KNOWN[i]).forEach(add); } catch (e) {}
        }
        try { document.querySelectorAll('[aria-label],[title],[class],[id]').forEach(function(el) { if (PLAYISH.test(tagOf(el)) && okEl(el)) add(el); }); } catch (e) {}
        try {
          document.querySelectorAll('button, a, div[role="button"], span[role="button"]').forEach(function(el) {
            var t = (el.textContent || '').trim().toLowerCase();
            if (t && t.length <= 16 && PLAYISH.test(t) && okEl(el)) add(el);
          });
        } catch (e) {}
        try {
          document.querySelectorAll('video').forEach(function(v) {
            add(v);
            var r = v.getBoundingClientRect();
            if (r.width >= 40 && r.height >= 40) {
              var cx = Math.max(1, Math.min(r.left + r.width / 2, (window.innerWidth || r.width) - 1));
              var cy = Math.max(1, Math.min(r.top + r.height / 2, (window.innerHeight || r.height) - 1));
              var top = document.elementFromPoint(cx, cy);
              if (top && top !== v && !(top.contains && top.contains(v))) add(top);
            }
          });
        } catch (e) {}
        return out.filter(okEl).slice(0, 6);
      }

      function mkTouch(target, x, y) {
        try {
          return new Touch({
            identifier: Date.now() % 100000, target: target,
            clientX: x, clientY: y,
            pageX: x + (window.pageXOffset || 0), pageY: y + (window.pageYOffset || 0),
            screenX: x, screenY: y,
            radiusX: 4, radiusY: 4, rotationAngle: 0, force: 0.4
          });
        } catch (e) { return null; }
      }

      function tap(el) {
        try {
          var r = el.getBoundingClientRect();
          if (r.width < 2 || r.height < 2) return;
          var x = Math.round(Math.max(1, Math.min(r.left + r.width / 2, (window.innerWidth || r.width) - 1)));
          var y = Math.round(Math.max(1, Math.min(r.top + r.height / 2, (window.innerHeight || r.height) - 1)));
          var target = el;
          try { var hit = document.elementFromPoint(x, y); if (hit && hit !== el) target = hit; } catch (e) {}
          var base = { bubbles: true, cancelable: true, composed: true, view: window, clientX: x, clientY: y, screenX: x, screenY: y, button: 0 };
          var pdown = { bubbles: true, cancelable: true, composed: true, view: window, clientX: x, clientY: y, screenX: x, screenY: y,
                        button: 0, buttons: 1, pointerId: 1, pointerType: 'touch', isPrimary: true, width: 1, height: 1, pressure: 0.4 };
          var pup = { bubbles: true, cancelable: true, composed: true, view: window, clientX: x, clientY: y, screenX: x, screenY: y,
                      button: 0, buttons: 0, pointerId: 1, pointerType: 'touch', isPrimary: true, width: 1, height: 1, pressure: 0 };
          var t = mkTouch(target, x, y);
          if (t) {
            try { target.dispatchEvent(new TouchEvent('touchstart', { bubbles: true, cancelable: true, composed: true, touches: [t], targetTouches: [t], changedTouches: [t] })); } catch (e) {}
          }
          try { target.dispatchEvent(new PointerEvent('pointerover', pdown)); } catch (e) {}
          try { target.dispatchEvent(new MouseEvent('mouseover', base)); } catch (e) {}
          try { target.dispatchEvent(new PointerEvent('pointerdown', pdown)); } catch (e) {}
          try { target.dispatchEvent(new MouseEvent('mousedown', Object.assign({}, base, { buttons: 1 }))); } catch (e) {}
          try { if (target.focus) target.focus(); } catch (e) {}
          if (t) {
            try { target.dispatchEvent(new TouchEvent('touchend', { bubbles: true, cancelable: true, composed: true, touches: [], targetTouches: [], changedTouches: [t] })); } catch (e) {}
          }
          try { target.dispatchEvent(new PointerEvent('pointerup', pup)); } catch (e) {}
          try { target.dispatchEvent(new MouseEvent('mouseup', base)); } catch (e) {}
          try { target.dispatchEvent(new MouseEvent('click', base)); } catch (e) {}
          try { el.click(); } catch (e) {}
        } catch (e) {}
      }

      function kickVideos() {
        try {
          document.querySelectorAll('video').forEach(function(v) {
            try {
              v.muted = true;
              v.playsInline = true;
              try { v.setAttribute('playsinline', ''); } catch (e) {}
              if (v.paused) { var p = v.play(); if (p && p.catch) p.catch(function() {}); }
            } catch (e) {}
          });
        } catch (e) {}
        try {
          if (window.jwplayer) {
            document.querySelectorAll('.jwplayer').forEach(function(el) {
              try { var p = window.jwplayer(el); if (p && p.play) p.play(); } catch (e) {}
            });
          }
        } catch (e) {}
        try {
          if (window.videojs) {
            document.querySelectorAll('video-js, .video-js, video[id]').forEach(function(el) {
              try { var p = window.videojs(el); if (p && p.play) p.play(); } catch (e) {}
            });
          }
        } catch (e) {}
        try {
          document.querySelectorAll('.plyr').forEach(function(el) {
            try { if (el.plyr && el.plyr.play) el.plyr.play(); } catch (e) {}
          });
        } catch (e) {}
      }

      function clickOnce() {
        var list = candidates();
        var done = 0;
        for (var i = 0; i < list.length && done < 4; i++) {
          var el = list[i];
          var n = 0;
          try { n = parseInt(el.getAttribute('data-v2try') || '0', 10) || 0; } catch (e) {}
          if (n >= 2) continue;
          try { el.setAttribute('data-v2try', String(n + 1)); } catch (e) {}
          tap(el);
          done++;
        }
      }

      function kick() {
        kickVideos();
        clickOnce();
      }
      window.__v2kickOnce = kick;

      var delays = [0, 700, 1800, 3200, 5200, 8000, 11500];
      for (var d = 0; d < delays.length; d++) { setTimeout(kick, delays[d]); }
      setInterval(function() { kickVideos(); }, 4000);

      try {
        var pending = false;
        var mo = new MutationObserver(function(muts) {
          for (var i = 0; i < muts.length; i++) {
            var added = muts[i].addedNodes;
            for (var j = 0; j < added.length; j++) {
              var n = added[j];
              if (!n || n.nodeType !== 1 || !n.tagName) continue;
              var tn = n.tagName.toUpperCase();
              if (tn === 'VIDEO' || tn === 'IFRAME' || tn === 'SOURCE') {
                if (!pending) { pending = true; setTimeout(function() { pending = false; kick(); }, 400); }
                return;
              }
              try { if (n.querySelector && (n.querySelector('video') || n.querySelector('iframe') || n.querySelector('[class*="play"]'))) { if (!pending) { pending = true; setTimeout(function() { pending = false; kick(); }, 400); } return; } } catch (e) {}
            }
          }
        });
        mo.observe(document.documentElement || document.body || document, { childList: true, subtree: true });
      } catch (e) {}
    })();
    true;
    """

    /// رفسة تحفيز من طرف Swift (الإطار الأم فقط): تعيد استخدام محرك النقر إن كان
    /// مثبتاً + تشغيل صامت للفيديوهات — تأميناً إضافياً فوق جدول السكربت الداخلي.
    static let nudgeScript = """
    (function() {
      try { if (window.__v2kickOnce) window.__v2kickOnce(); } catch (e) {}
      try {
        document.querySelectorAll('video').forEach(function(v) {
          try {
            v.muted = true;
            v.playsInline = true;
            if (v.paused) { var p = v.play(); if (p && p.catch) p.catch(function() {}); }
          } catch (e) {}
        });
      } catch (e) {}
      true;
    })();
    true;
    """
}

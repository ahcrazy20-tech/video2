import Foundation
import WebKit

enum AdBlock {
    static let defaultsKey = "v2.adblock.enabled"

    static var isEnabled: Bool {
        get {
            if UserDefaults.standard.object(forKey: defaultsKey) == nil { return true }
            return UserDefaults.standard.bool(forKey: defaultsKey)
        }
        set { UserDefaults.standard.set(newValue, forKey: defaultsKey) }
    }

    static let listIdentifier = "video2.adblock.v1"

    static let hosts: Set<String> = [
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagservices.com", "googletagmanager.com", "adservice.google.com",
        "amazon-adsystem.com", "adsystem.com", "ads-twitter.com",
        "scorecardresearch.com", "outbrain.com", "taboola.com", "revcontent.com",
        "mgid.com", "popads.net", "popcash.net", "propellerads.com",
        "adsterra.com", "exoclick.com", "exosrv.com", "juicyads.com",
        "trafficjunky.net", "adtng.com", "tsyndicate.com", "ad-maven.com",
        "moatads.com", "adsrvr.org", "adnxs.com", "adform.net",
        "adsafeprotected.com", "advertising.com", "smartadserver.com",
        "openx.net", "rubiconproject.com", "pubmatic.com", "casalemedia.com",
        "criteo.com", "criteo.net", "2mdn.net", "admob.com", "zedo.com",
        "media.net", "teads.tv", "spotxchange.com", "bidswitch.net",
        "hilltopads.net", "clickadu.com", "trafficstars.com", "ad-delivery.net",
        "creativecdn.com", "liadm.com", "3lift.com", "sharethrough.com",
        "indexww.com", "smaato.net", "onetag-sys.com"
    ]

    static func hostIsAd(_ host: String?) -> Bool {
        guard let host = host?.lowercased() else { return false }
        if hosts.contains(host) { return true }
        return hosts.contains { host == $0 || host.hasSuffix("." + $0) }
    }

    static func compileIfNeeded() {
        guard isEnabled else {
            WKContentRuleListStore.default().removeContentRuleList(forIdentifier: listIdentifier) { _ in }
            return
        }
        guard let url = Bundle.main.url(forResource: "adblock-rules", withExtension: "json"),
              let json = try? String(contentsOf: url, encoding: .utf8) else { return }
        WKContentRuleListStore.default().compileContentRuleList(forIdentifier: listIdentifier, encodedContentRuleList: json) { list, error in
            if let error { print("AdBlock compile:", error.localizedDescription) }
            _ = list
        }
    }

    static func attach(to controller: WKUserContentController) {
        controller.removeAllUserScripts()
        let extract = WKUserScript(source: ExtractorScript.source, injectionTime: .atDocumentEnd, forMainFrameOnly: false)
        controller.addUserScript(extract)
        if isEnabled {
            let cosmetic = WKUserScript(source: cosmeticJS, injectionTime: .atDocumentEnd, forMainFrameOnly: false)
            let early = WKUserScript(source: antiPopJS, injectionTime: .atDocumentStart, forMainFrameOnly: false)
            controller.addUserScript(early)
            controller.addUserScript(cosmetic)
            WKContentRuleListStore.default().lookUpContentRuleList(forIdentifier: listIdentifier) { list, _ in
                if let list {
                    DispatchQueue.main.async {
                        controller.add(list)
                    }
                }
            }
        }
    }

    static func applyRules(to webView: WKWebView) {
        let uc = webView.configuration.userContentController
        uc.removeAllContentRuleLists()
        guard isEnabled else { return }
        WKContentRuleListStore.default().lookUpContentRuleList(forIdentifier: listIdentifier) { list, _ in
            if let list {
                DispatchQueue.main.async { uc.add(list) }
            } else {
                compileIfNeeded()
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
                    WKContentRuleListStore.default().lookUpContentRuleList(forIdentifier: listIdentifier) { l2, _ in
                        if let l2 { DispatchQueue.main.async { uc.add(l2) } }
                    }
                }
            }
        }
    }

    /// طبقة تجميلية: إخفاء عناصر إعلان شائعة في الصفحة.
    static let cosmeticJS = """
    (function(){
      if (window.__v2adcss) return; window.__v2adcss = true;
      var css = `
        iframe[src*="ads"], iframe[id*="google_ads"], iframe[id*="ad_"],
        ins.adsbygoogle, .adsbygoogle, #ads, .ad-container, .ad-banner,
        .advertisement, .advert, [id*="taboola"], [class*="taboola"],
        [id*="outbrain"], [class*="outbrain"], [class*="sponsored"],
        [id*="google_ads"], [class*="GoogleActiveView"],
        [class*="popup-ad"], [id*="popunder"], [class*="pop-under"],
        [class*="exo-"], [id*="ad-wrapper"], [class*="ad-wrapper"],
        [data-ad], [data-ads], .trc_rbox, .OUTBRAIN, .mgbox {
          display: none !important; visibility: hidden !important;
          pointer-events: none !important; height: 0 !important; max-height: 0 !important;
        }
      `;
      function inject(){
        if (!document.documentElement) return;
        if (document.getElementById('v2-adcss')) return;
        var s = document.createElement('style');
        s.id = 'v2-adcss'; s.textContent = css;
        document.documentElement.appendChild(s);
      }
      inject();
      new MutationObserver(inject).observe(document.documentElement || document, {childList:true, subtree:true});
    })();
    true;
    """

    /// طبقة نوافذ منبثقة: تعطيل window.open المشبوه قبل التحميل.
    static let antiPopJS = """
    (function(){
      if (window.__v2antipop) return; window.__v2antipop = true;
      var orig = window.open;
      window.open = function(u, n, f) {
        try {
          var s = String(u || '');
          if (!s || /ads|doubleclick|pop|click|offer|tracker|affiliate/i.test(s)) return null;
        } catch (e) { return null; }
        return orig ? orig.apply(window, arguments) : null;
      };
    })();
    true;
    """
}

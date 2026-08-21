import Foundation
import WebKit

enum AdBlock {
    static let defaultsKey = "v2.adblock.enabled"
    static let modeKey = "v2.adblock.mode"
    static let listIdentifier = "video2.adblock.v4"

    static var isEnabled: Bool {
        get {
            if UserDefaults.standard.object(forKey: defaultsKey) == nil { return true }
            return UserDefaults.standard.bool(forKey: defaultsKey)
        }
        set { UserDefaults.standard.set(newValue, forKey: defaultsKey) }
    }

    /// balanced (default) or strict
    static var mode: String {
        get { UserDefaults.standard.string(forKey: modeKey) ?? "balanced" }
        set { UserDefaults.standard.set(newValue, forKey: modeKey) }
    }

    static let hosts: Set<String> = [
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagservices.com", "adservice.google.com",
        "amazon-adsystem.com", "ads-twitter.com",
        "scorecardresearch.com", "outbrain.com", "taboola.com", "revcontent.com",
        "mgid.com", "popads.net", "popcash.net", "propellerads.com",
        "adsterra.com", "exoclick.com", "exosrv.com", "juicyads.com",
        "trafficjunky.net", "adtng.com", "tsyndicate.com", "ad-maven.com",
        "moatads.com", "adsrvr.org", "adnxs.com", "adform.net",
        "adsafeprotected.com", "advertising.com", "smartadserver.com",
        "openx.net", "rubiconproject.com", "pubmatic.com", "casalemedia.com",
        "criteo.com", "criteo.net", "2mdn.net", "zedo.com",
        "media.net", "teads.tv", "spotxchange.com", "bidswitch.net",
        "hilltopads.net", "clickadu.com", "trafficstars.com", "ad-delivery.net",
        "creativecdn.com", "3lift.com", "sharethrough.com",
        "indexww.com", "smaato.net", "onetag-sys.com",
        "adtrafficquality.google", "doubleverify.com",
        "adfox.ru", "ads.yahoo.com", "quantserve.com",
        "demdex.net", "krxd.net", "bluekai.com", "adroll.com", "flashtalking.com",
        "serving-sys.com", "fwmrm.net", "freewheel.tv",
        "propeller-tracking.com", "popmyads.com", "onclickmega.com",
        "adexchangeclear.com", "adtrue.com", "sc-static.net",
        "snapads.com", "outbrainimg.com", "zemanta.com", "nativo.com",
        "buysellads.com", "carbonads.net", "adzerk.net"
    ]

    static func hostIsAd(_ host: String?) -> Bool {
        guard let host = host?.lowercased() else { return false }
        if host == "localhost" || host.hasPrefix("127.") { return false }
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
        controller.addUserScript(WKUserScript(source: ExtractorScript.source, injectionTime: .atDocumentEnd, forMainFrameOnly: false))
        guard isEnabled else { return }
        controller.addUserScript(WKUserScript(source: antiPopJS, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        if mode == "strict" {
            controller.addUserScript(WKUserScript(source: cosmeticJS, injectionTime: .atDocumentEnd, forMainFrameOnly: false))
        }
        WKContentRuleListStore.default().lookUpContentRuleList(forIdentifier: listIdentifier) { list, _ in
            if let list { DispatchQueue.main.async { controller.add(list) } }
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
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                    WKContentRuleListStore.default().lookUpContentRuleList(forIdentifier: listIdentifier) { l2, _ in
                        if let l2 { DispatchQueue.main.async { uc.add(l2) } }
                    }
                }
            }
        }
    }

    static let antiPopJS = """
    (function(){
      if (window.__v2antipop) return; window.__v2antipop = true;
      var orig = window.open;
      window.open = function(u){
        try {
          var s = String(u||'');
          if (/doubleclick|popads|popunder|exoclick|propellerads|taboola|outbrain|clickadu/i.test(s)) return null;
        } catch(e){}
        return orig ? orig.apply(window, arguments) : null;
      };
    })();
    true;
    """

    static let cosmeticJS = """
    (function(){
      if (window.__v2adcss) return; window.__v2adcss = true;
      var css = `
        iframe[src*="doubleclick"], iframe[id*="google_ads"],
        ins.adsbygoogle, .adsbygoogle,
        [id*="taboola"], [class*="taboola"], .OUTBRAIN, [id*="outbrain"],
        .trc_rbox, [class*="popup-ad"], [id*="popunder"] {
          display: none !important;
        }
      `;
      function style(){
        if (!document.documentElement) return;
        var s = document.getElementById('v2-adcss');
        if (!s) {
          s = document.createElement('style');
          s.id = 'v2-adcss';
          document.documentElement.appendChild(s);
        }
        s.textContent = css;
      }
      style();
    })();
    true;
    """
}

import Foundation
import WebKit

enum AdBlock {
    static let defaultsKey = "v2.adblock.enabled"
    static let modeKey = "v2.adblock.mode"
    static let filterVideoAdsKey = "v2.adblock.filterVideoAds"
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

    /// تصفية إعلانات الفيديو المزعجة ومقاطع الإعلانات القصيرة تلقائياً
    static var filterVideoAds: Bool {
        get {
            if UserDefaults.standard.object(forKey: filterVideoAdsKey) == nil { return true }
            return UserDefaults.standard.bool(forKey: filterVideoAdsKey)
        }
        set { UserDefaults.standard.set(newValue, forKey: filterVideoAdsKey) }
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
        "media.net", "teads.tv", "teads.com", "spotxchange.com", "spotx.tv", "bidswitch.net",
        "hilltopads.net", "clickadu.com", "trafficstars.com", "ad-delivery.net",
        "creativecdn.com", "3lift.com", "triplelift.com", "sharethrough.com",
        "indexww.com", "smaato.net", "onetag-sys.com",
        "adtrafficquality.google", "doubleverify.com",
        "adfox.ru", "ads.yahoo.com", "quantserve.com",
        "demdex.net", "krxd.net", "bluekai.com", "adroll.com", "flashtalking.com",
        "serving-sys.com", "fwmrm.net", "freewheel.tv", "innovid.com",
        "propeller-tracking.com", "popmyads.com", "onclickmega.com",
        "adexchangeclear.com", "adtrue.com", "sc-static.net",
        "snapads.com", "outbrainimg.com", "zemanta.com", "nativo.com",
        "buysellads.com", "carbonads.net", "adzerk.net",
        "connatix.com", "aniview.com", "springserve.com", "vidazoo.com",
        "primis.tech", "vi-serve.com", "unruly.co", "vungle.com",
        "inmobi.com", "applovin.com", "chartboost.com", "yieldmo.com",
        "adcolony.com", "videohub.tv", "imasdk.googleapis.com"
    ]

    static func hostIsAd(_ host: String?) -> Bool {
        guard let host = host?.lowercased() else { return false }
        if host == "localhost" || host.hasPrefix("127.") { return false }
        return hosts.contains { host == $0 || host.hasSuffix("." + $0) }
    }

    /// التحقق مما إذا كان رابط الوسائط أو الفيديو ينتمي إلى إعلان مزعج أو شبكة إعلانات
    static func isAdURL(_ urlString: String?) -> Bool {
        guard let urlString, !urlString.isEmpty else { return false }
        let lower = urlString.lowercased()
        if lower.hasPrefix("blob:") || lower.hasPrefix("data:") { return false }

        // فحص نطاق المضيف
        if let host = URL(string: lower)?.host, hostIsAd(host) {
            return true
        }

        // فحص كلمات الإعلانات في النطاق والمسار
        let adDomainsAndKeywords = [
            "doubleclick", "googlesyndication", "googleadservices", "pagead",
            "vungle", "teads", "connatix", "aniview", "spotxchange", "spotx.tv",
            "serving-sys", "flashtalking", "innovid", "springserve", "vidazoo",
            "primis", "vi-serve", "exoclick", "trafficjunky", "adsterra",
            "popads", "popcash", "clickadu", "hilltopads", "trafficstars",
            "outbrain", "taboola", "revcontent", "mgid", "adnxs",
            "smartadserver", "ad-delivery", "ad-maven", "videohub", "moatads",
            "imasdk.googleapis.com", "ima3.js"
        ]
        for kw in adDomainsAndKeywords {
            if lower.contains(kw) { return true }
        }

        // فحص معاملات واستعلامات إعلانات الفيديو (VAST, VPAID, Pre-roll, Mid-roll, Ad breaks)
        let adParamPatterns = [
            "adformat=", "ad_type=", "ad_unit=", "ad_tag=", "adtag=",
            "ad_slot=", "ad_break=", "ad_system=", "ad_provider=", "ad_flags=",
            "/ad/", "/ads/", "/advert/", "/adverts/", "/advertising/", "/advertisement/",
            "/videoads/", "/video_ads/", "/advideo/", "/ad_video/", "/ad-video/",
            "instream_ad", "outstream_ad", "/outstream/",
            "/preroll", "/midroll", "/postroll", "/vast", "/vpaid", "/daast", "/vmap",
            "creative_id=", "placement_id=", "campaign_id=",
            "&adformat=", "?adformat=", "ctier=a&", "&ctier=a"
        ]
        for pat in adParamPatterns {
            if lower.contains(pat) { return true }
        }

        return false
    }

    /// التحقق مما إذا كان الفيديو المستخرج عبارة عن إعلان مزعج (عبر الرابط أو الخصائص أو المدة أو الأبعاد)
    static func isAdMedia(_ media: DetectedMedia) -> Bool {
        guard filterVideoAds else { return false }
        if isAdURL(media.url) { return true }

        // فحص العنوان ومصدر الاستخراج
        let titleLower = media.title.lowercased()
        if titleLower.contains("advertisement") || titleLower.contains("sponsored") ||
           titleLower == "ad" || titleLower.hasPrefix("ad ") || titleLower.hasSuffix(" ad") ||
           titleLower.contains("إعلان") || titleLower.contains("اعلان") {
            return true
        }

        // مقاطع الإعلانات القصيرة جداً (أقل من 6 ثوانٍ) التي تأتي من سكربتات غير معرّفة أو بأبعاد إعلانات قياسية
        if let d = media.duration, d > 0 && d <= 6.0 {
            if let w = media.width, let h = media.height {
                // مقاسات البانرات الإعلانية الشائعة
                if (w == 300 && h == 250) || (w == 320 && h == 50) || (w == 728 && h == 90) || (w == 300 && h == 600) {
                    return true
                }
                if w <= 10 || h <= 10 { return true } // tracking pixel
            }
            if media.extractionMethod.contains("performance") || media.extractionMethod.contains("fetch") {
                if media.byteSize != nil && (media.byteSize ?? 0) < 600_000 && !media.url.contains("youtube") && !media.url.contains("vimeo") {
                    return true
                }
            }
        }

        return false
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
          if (/doubleclick|popads|popunder|exoclick|propellerads|taboola|outbrain|clickadu|hilltopads|trafficstars|adsterra/i.test(s)) return null;
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
        .trc_rbox, [class*="popup-ad"], [id*="popunder"],
        .video-ads, .ytp-ad-module, .videoAdUi, [class*="ima-ad"], #player-ads,
        [class*="ad-container"], [class*="ad-overlay"], [id*="ad-overlay"] {
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

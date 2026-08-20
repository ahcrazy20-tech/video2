import Foundation

enum ExtractorScript {
    /// يحقن في كل إطار: يجمع مصادر الفيديو، يلتقط fetch/XHR، ويكتشف إشارات DRM دون كسرها.
    static let source = """
    (function() {
      if (window.__v2installed) return;
      window.__v2installed = true;
      const found = new Map();

      function kindFrom(url, mime) {
        const u = (url || '').split('?')[0].toLowerCase();
        const m = (mime || '').toLowerCase();
        if (u.endsWith('.m3u8') || m.includes('mpegurl')) return 'hls';
        if (u.endsWith('.mpd') || m.includes('dash')) return 'dash';
        if (u.endsWith('.webm') || m.includes('webm')) return 'webm';
        if (u.match(/\\.(mp4|m4v|mov)$/) || m.includes('mp4')) return 'mp4';
        return 'other';
      }

      function drmFromPage() {
        try {
          if (navigator.requestMediaKeySystemAccess) {
            /* وجود EME لا يعني أن الفيديو الحالي محمي، نعلّم لاحقاً من الأحداث */
          }
        } catch (e) {}
        const html = document.documentElement ? document.documentElement.innerHTML : '';
        const low = html.toLowerCase();
        if (low.includes('fairplay') || low.includes('com.apple.fps')) return 'fairplay';
        if (low.includes('widevine') || low.includes('com.widevine.alpha')) return 'widevine';
        if (low.includes('playready')) return 'playready';
        return 'none';
      }

      function emit(item) {
        if (!item || !item.url) return;
        try {
          const abs = new URL(item.url, location.href).href;
          item.url = abs;
        } catch (e) { return; }
        if (item.url.startsWith('blob:') || item.url.startsWith('data:')) {
          item.drm = item.drm && item.drm !== 'none' ? item.drm : 'unknownProtected';
        }
        const key = item.url;
        const prev = found.get(key);
        if (prev && prev.drm !== 'none') item.drm = prev.drm;
        found.set(key, item);
        try {
          webkit.messageHandlers.video2.postMessage({ type: 'media', item: item, page: location.href, title: document.title || '' });
        } catch (e) {}
      }

      function scanVideos() {
        document.querySelectorAll('video, audio, source').forEach(function(el) {
          const url = el.currentSrc || el.src || el.getAttribute('src');
          if (!url) return;
          emit({
            url: url,
            title: document.title || 'فيديو',
            kind: kindFrom(url, el.getAttribute('type')),
            mime: el.getAttribute('type') || '',
            qualityLabel: (el.videoHeight ? el.videoHeight + 'p' : ''),
            drm: 'none',
            extractionMethod: 'html5-element'
          });
        });
      }

      function hookNetwork() {
        const origFetch = window.fetch;
        if (origFetch) {
          window.fetch = function() {
            const args = arguments;
            try {
              const input = args[0];
              const url = (typeof input === 'string') ? input : (input && input.url);
              consider(url, 'fetch-hook');
            } catch (e) {}
            return origFetch.apply(this, args).then(function(res) {
              try { consider(res.url, 'fetch-response'); } catch (e) {}
              return res;
            });
          };
        }
        const origOpen = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function(method, url) {
          try { consider(url, 'xhr-hook'); } catch (e) {}
          return origOpen.apply(this, arguments);
        };
      }

      function consider(url, method) {
        if (!url || typeof url !== 'string') return;
        const u = url.toLowerCase();
        const hit = /\\.(m3u8|mp4|m4v|mov|webm|mpd|ts|m4s)(\\?|$)/.test(u)
          || u.includes('m3u8') || u.includes('mime=video') || u.includes('videourl');
        if (!hit) return;
        let kind = kindFrom(url, '');
        if (u.includes('.m3u8')) kind = 'hls';
        emit({
          url: url,
          title: document.title || 'فيديو',
          kind: kind,
          mime: '',
          qualityLabel: '',
          drm: 'none',
          extractionMethod: method
        });
      }

      function scanPerformance() {
        try {
          performance.getEntriesByType('resource').forEach(function(e) {
            consider(e.name, 'performance-timeline');
          });
        } catch (e) {}
      }

      document.addEventListener('encrypted', function() {
        try {
          webkit.messageHandlers.video2.postMessage({
            type: 'drm',
            drm: 'unknownProtected',
            page: location.href,
            title: document.title || ''
          });
        } catch (e) {}
      }, true);

      const obs = new MutationObserver(function() { scanVideos(); });
      obs.observe(document.documentElement || document, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });

      hookNetwork();
      scanVideos();
      scanPerformance();
      setInterval(function() { scanVideos(); scanPerformance(); }, 2500);

      const pageDRM = drmFromPage();
      if (pageDRM !== 'none') {
        try {
          webkit.messageHandlers.video2.postMessage({ type: 'drm', drm: pageDRM, page: location.href, title: document.title || '' });
        } catch (e) {}
      }
    })();
    true;
    """
}

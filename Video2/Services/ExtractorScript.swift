import Foundation

enum ExtractorScript {
    static let source = """
    (function() {
      if (window.__v2installed) return;
      window.__v2installed = true;
      const found = new Map();

      function isAdURL(url) {
        if (!url || typeof url !== 'string') return false;
        const u = url.toLowerCase();
        if (/doubleclick|googlesyndication|googleadservices|pagead|vungle|teads|connatix|aniview|spotxchange|spotx\\.tv|serving-sys|flashtalking|innovid|springserve|vidazoo|primis|vi-serve|exoclick|trafficjunky|adsterra|popads|popcash|clickadu|hilltopads|trafficstars|outbrain|taboola|revcontent|mgid|adnxs|smartadserver|ad-delivery|ad-maven|videohub|moatads|imasdk\\.googleapis/i.test(u)) return true;
        if (/[?&/](adformat|ad_type|ad_unit|ad_tag|adtag|ad_slot|ad_break|ad_system|preroll|midroll|postroll|vast|vpaid|daast|vmap|videoads|advideo|instream_ad|outstream)[/=?&_]/i.test(u)) return true;
        return false;
      }

      function isAdElement(el) {
        if (!el) return false;
        try {
          if (el.closest && el.closest('.video-ads, .ytp-ad-module, .videoAdUi, .ad-container, .ad-slot, .ad-banner, .ima-ad, ins.adsbygoogle, [class*="popup-ad"], [id*="popunder"], [class*="ad-overlay"], [id*="ad-overlay"], [data-ad], [data-ad-slot], [data-ad-client], [data-ad-unit], [data-is-ad="true"]')) {
            return true;
          }
          if (el.getAttribute && (el.getAttribute('data-ad') || el.getAttribute('data-is-ad') || el.getAttribute('data-ad-slot'))) {
            return true;
          }
          if (el.videoWidth && el.videoHeight) {
            if ((el.videoWidth <= 2 && el.videoHeight <= 2) || (el.videoWidth <= 320 && el.videoHeight <= 60)) return true;
          }
        } catch (e) {}
        return false;
      }

      function kindFrom(url, mime) {
        const u = (url || '').split('?')[0].toLowerCase();
        const m = (mime || '').toLowerCase();
        if (u.endsWith('.m3u8') || m.includes('mpegurl')) return 'hls';
        if (u.endsWith('.mpd') || m.includes('dash')) return 'dash';
        if (u.endsWith('.webm') || m.includes('webm')) return 'webm';
        if (u.endsWith('.mkv')) return 'mkv';
        if (u.endsWith('.avi')) return 'avi';
        if (u.endsWith('.mov')) return 'mov';
        if (u.endsWith('.m4v')) return 'm4v';
        if (u.endsWith('.3gp') || u.endsWith('.3gpp')) return '3gp';
        if (u.endsWith('.ts') || u.endsWith('.m4s')) return 'ts';
        if (u.endsWith('.mp3') || m.includes('mpeg') && m.includes('audio')) return 'mp3';
        if (u.endsWith('.aac') || m.includes('aac')) return 'aac';
        if (u.endsWith('.wav')) return 'wav';
        if (u.match(/\\.(mp4)$/) || m.includes('mp4') || m.includes('video')) return 'mp4';
        return 'other';
      }

      function emit(item) {
        if (!item || !item.url || isAdURL(item.url)) return;
        try {
          const abs = new URL(item.url, location.href).href;
          item.url = abs;
          if (isAdURL(item.url)) return;
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
          if (isAdElement(el)) return;
          const url = el.currentSrc || el.src || el.getAttribute('src');
          if (!url || isAdURL(url)) return;
          var dur = 0;
          try { if (el.duration && isFinite(el.duration)) dur = el.duration; } catch (e) {}
          emit({
            url: url,
            title: document.title || 'فيديو',
            kind: kindFrom(url, el.getAttribute('type')),
            mime: el.getAttribute('type') || '',
            qualityLabel: (el.videoHeight ? el.videoHeight + 'p' : ''),
            width: el.videoWidth || 0,
            height: el.videoHeight || 0,
            duration: dur,
            drm: 'none',
            extractionMethod: el.tagName === 'AUDIO' ? 'html5-audio' : 'html5-element'
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
        if (!url || typeof url !== 'string' || isAdURL(url)) return;
        const u = url.toLowerCase();
        const hit = /\\.(m3u8|mp4|m4v|mov|webm|mpd|mkv|avi|3gp|3gpp|mp3|aac|wav|ogg)(\\?|$)/.test(u)
          || u.includes('m3u8') || u.includes('mime=video') || u.includes('videourl');
        if (!hit) return;
        if (/\\.(ts|m4s)(\\?|$)/.test(u) && !u.includes('.m3u8')) return;
        emit({
          url: url,
          title: document.title || 'فيديو',
          kind: kindFrom(url, ''),
          mime: '',
          qualityLabel: '',
          duration: 0,
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
    })();
    true;
    """
}

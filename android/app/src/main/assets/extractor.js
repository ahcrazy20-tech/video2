// Passive, frame-local media detector. Never clicks controls, rewrites URLs, or reads credentials.
(function () {
  'use strict';
  if (!/^https?:$/.test(location.protocol)) return;
  if (window.__v2Scan) { window.__v2Scan(); return; }
  var items = new Map();
  var outbox = [];
  var roots = new Set();
  var queued = false;
  var maxJson = 256 * 1024;
  var ext = /\.(m3u8|mpd|mp4|m4v|mov|webm|mkv|avi|3gp|3gpp|m4a|mp3|aac|wav|ogg|opus|flac)$/i;
  var fragment = /\.(ts|m4s|cmfa|cmfv|srt|vtt)$/i;
  var mediaSelector = 'video,audio,video source,audio source';
  var metadataSelector = 'meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"],meta[name="twitter:player:stream"]';

  function asUrl(raw, doc) {
    if (typeof raw !== 'string' || !raw || raw.length > 16384) return null;
    try {
      var u = new URL(raw, (doc || document).baseURI || location.href);
      if (!/^https?:$/.test(u.protocol) || u.username || u.password || fragment.test(u.pathname)) return null;
      if (/\/videoplayback$/i.test(u.pathname) && (u.searchParams.has('range') || u.searchParams.has('sq'))) return null;
      u.hash = '';
      return u;
    } catch (_) { return null; }
  }

  function looksMedia(u, type) {
    type = String(type || u.searchParams.get('mime') || '').toLowerCase().split(';')[0].trim();
    return ext.test(u.pathname) || /^(video|audio)\//.test(type) && !/^video\/mp2t$/.test(type) || /mpegurl|dash\+xml/.test(type);
  }

  function send(doc, body) {
    body.page = doc.URL || location.href;
    body.title = (doc.title || '').slice(0, 512);
    var str = JSON.stringify(body);
    if (window.Video2Media && typeof window.Video2Media.postMessage === 'function') {
      try { window.Video2Media.postMessage(str); return; } catch (_) {}
    }
    if (outbox.length >= 80) outbox.shift();
    outbox.push(str);
  }
  window.__v2Drain = function () { return outbox.splice(0, outbox.length); };

  function emit(raw, method, type, info, doc, force) {
    doc = doc || document;
    var u = asUrl(raw, doc);
    type = String(type || '').slice(0, 150);
    if (!u || (!force && !looksMedia(u, type))) return;
    info = info || {};
    var key = u.href;
    var old = items.get(key) || {};
    var item = {
      url: key, method: method, mime: type || old.mime || '',
      duration: Number(info.duration) > 0 && Number.isFinite(Number(info.duration)) ? Number(info.duration) : old.duration || 0,
      height: Number(info.height) || old.height || 0, width: Number(info.width) || old.width || 0,
      ad: !!info.ad || !!old.ad, drm: info.drm || old.drm || 'NONE'
    };
    if (/^html5/.test(old.method || '') && !/^html5/.test(method)) item.method = old.method;
    if (old.drm && old.drm !== 'NONE') item.drm = old.drm;
    if (old.method && ['mime','duration','height','width','ad','drm'].every(function (name) { return old[name] === item[name]; }) &&
        (old.method === item.method || !/^html5/.test(item.method))) return;
    if (items.size >= 180 && !items.has(key)) items.delete(items.keys().next().value);
    items.set(key, item);
    send(doc, { type: 'media', item: item });
  }

  function isAd(el) {
    try {
      return !!el.closest('.video-ads,.ytp-ad-module,.videoAdUi,.ad-container,.ad-slot,.ad-banner,.ima-ad,[data-ad],[data-ad-slot],[data-is-ad="true"]') ||
        (el.videoWidth > 0 && el.videoWidth <= 320 && el.videoHeight > 0 && el.videoHeight <= 60);
    } catch (_) { return false; }
  }

  function inspect(el) {
    var doc = el.ownerDocument;
    var media = el.tagName === 'SOURCE' ? el.parentElement : el;
    if (!media || !/^(VIDEO|AUDIO)$/.test(media.tagName)) return;
    var info = { duration: media.duration, height: media.videoHeight, width: media.videoWidth, ad: isAd(media) };
    var src = el.currentSrc || el.getAttribute('src') || el.getAttribute('data-src') || el.getAttribute('data-hls') || el.getAttribute('data-video');
    emit(src, media.tagName === 'AUDIO' ? 'html5-audio' : 'html5-video', el.getAttribute('type'), info, doc, true);
  }

  function readObject(value, method, doc) {
    var budget = 280;
    function walk(o, depth, typed) {
      if (!o || typeof o !== 'object' || depth > 6 || budget-- <= 0) return;
      if (Array.isArray(o)) {
        for (var i = 0; i < Math.min(o.length, 32); i++) walk(o[i], depth + 1, typed);
        return;
      }
      if (o.streamingData && typeof o.streamingData === 'object') {
        // Only already-available, complete formats. No deciphering signatures or adaptive chunk URLs.
        var sd = o.streamingData;
        if (Array.isArray(sd.formats)) sd.formats.slice(0, 20).forEach(function (f) {
          if (f) emit(f.url, method, f.mimeType, f, doc, false);
        });
        emit(sd.hlsManifestUrl, method, 'application/vnd.apple.mpegurl', o, doc, true);
        emit(sd.dashManifestUrl, method, 'application/dash+xml', o, doc, true);
      }
      var mime = typeof o.mimeType === 'string' ? o.mimeType : (typeof o.type === 'string' ? o.type : '');
      Object.keys(o).slice(0, 50).forEach(function (key) {
        if (key === 'streamingData' || /^adaptiveFormats$/i.test(key)) return;
        var v = o[key];
        if (typeof v === 'string') {
          var hls = /^(hls|hlsUrl|hlsManifestUrl|m3u8)$/i.test(key);
          var dash = /^(dashUrl|dashManifestUrl|mpd)$/i.test(key);
          var fields = /^(file|src|url|contentUrl|videoUrl|streamUrl|playbackUrl|manifestUrl|downloadUrl)$/i.test(key);
          if (!hls && !dash && !fields) return;
          var declared = hls || dash || /^(videoUrl|streamUrl|playbackUrl|manifestUrl)$/i.test(key) ||
            (/^VideoObject$/i.test(o['@type']) && /contentUrl/i.test(key)) || (typed && /^(src|file|url)$/i.test(key));
          emit(v, method, hls ? 'application/vnd.apple.mpegurl' : dash ? 'application/dash+xml' : mime, o, doc, declared);
        } else if (v && typeof v === 'object') {
          walk(v, depth + 1, /^(sources|playlist|streams|videos|renditions)$/i.test(key));
        }
      });
    }
    walk(value, 0, false);
  }

  function inspectText(raw, url, method, contentType) {
    if (typeof raw !== 'string' || raw.length > maxJson) return;
    var t = raw.trimStart();
    if (t.indexOf('#EXTM3U') === 0) {
      var k = t.match(/^#EXT-X-(?:SESSION-)?KEY:.*$/gm) || [];
      var drm = k.some(function (line) { return /SAMPLE-AES|skd:|KEYFORMAT="(?!identity")/i.test(line); }) ? 'ENCRYPTED_HLS' : '';
      emit(url, method, 'application/vnd.apple.mpegurl', { drm: drm }, document, true);
    } else if (/<(?:[\w-]+:)?MPD(?:\s|>)/.test(t.slice(0, 2048))) {
      emit(url, method, 'application/dash+xml', { drm: /ContentProtection/i.test(t) ? 'UNKNOWN_PROTECTED' : '' }, document, true);
    } else if (t[0] === '{' || t[0] === '[') {
      try { readObject(JSON.parse(t), method + '-json', document); } catch (_) {}
    }
  }

  function maybeRead(type, size) {
    return (size == null || (Number(size) >= 0 && Number(size) <= maxJson)) &&
      (/json|mpegurl|dash\+xml|^text\/plain/i.test(type) || (size != null && /^application\/octet-stream/i.test(type)));
  }

  async function inspectFetch(response) {
    if (!response.ok) return;
    var mime = response.headers.get('Content-Type') || '';
    emit(response.url, 'fetch-response', mime);
    if (!maybeRead(mime, response.headers.get('Content-Length'))) return;
    var stream = response.clone().body;
    if (!stream || !stream.getReader) return;
    var reader = stream.getReader();
    var decoder = new TextDecoder();
    var size = 0;
    var text = '';
    try {
      while (true) {
        var next = await reader.read();
        if (next.done) break;
        size += next.value.byteLength;
        if (size > maxJson) { reader.cancel().catch(function () {}); return; }
        text += decoder.decode(next.value, { stream: true });
      }
      inspectText(text + decoder.decode(), response.url, 'fetch-response', mime);
    } finally { reader.releaseLock(); }
  }

  var fetch = window.fetch;
  if (fetch) window.fetch = function (input, init) {
    var p = fetch.apply(this, arguments);
    try {
      if (String((init && init.method) || (input && input.method) || 'GET').toUpperCase() === 'GET') {
        emit(typeof input === 'string' ? input : (input && (input.url || input.href)), 'fetch');
        p.then(inspectFetch).catch(function () {});
      }
    } catch (_) {}
    return p;
  };

  var open = XMLHttpRequest.prototype.open;
  var xhrListeners = new WeakMap();
  XMLHttpRequest.prototype.open = function (method, url) {
    var r = open.apply(this, arguments);
    try {
      var prior = xhrListeners.get(this);
      if (prior) this.removeEventListener('load', prior);
      var get = String(method).toUpperCase() === 'GET';
      if (get) emit(String(url), 'xhr');
      var listener = function () {
        try {
          if (this.status < 200 || this.status >= 300) return;
          var mime = this.getResponseHeader('Content-Type') || '';
          if (get) emit(this.responseURL, 'xhr-response', mime);
          if (!maybeRead(mime, this.getResponseHeader('Content-Length'))) return;
          if (this.responseType === 'json') readObject(this.response, 'xhr-json', document);
          else if (!this.responseType || this.responseType === 'text') inspectText(this.responseText, get ? this.responseURL : '', 'xhr-response', mime);
        } catch (_) {}
      };
      xhrListeners.set(this, listener);
      this.addEventListener('load', listener, { once: true });
    } catch (_) {}
    return r;
  };

  function observe(root) {
    if (roots.has(root) || roots.size >= 40) return;
    roots.add(root);
    try { mutations.observe(root, { childList: true, subtree: true, attributes: true, attributeFilter: ['src','type','data-src','data-video','data-hls'] }); } catch (_) {}
    ['loadedmetadata','playing','play'].forEach(function (event) {
      root.addEventListener(event, function (e) { inspect(e.target); }, true);
    });
    root.addEventListener('encrypted', function (e) {
      var u = asUrl(e.target.currentSrc || e.target.src, e.target.ownerDocument);
      send(e.target.ownerDocument || document, { type: 'drm', url: u ? u.href : '' });
    }, true);
  }

  function scan() {
    queued = false;
    roots.forEach(function (root) {
      if (root.host && !root.host.isConnected) { roots.delete(root); return; }
      var doc = root.ownerDocument || root;
      Array.from(root.querySelectorAll(mediaSelector)).slice(0, 120).forEach(inspect);
      Array.from(root.querySelectorAll(metadataSelector)).slice(0, 12).forEach(function (el) {
        emit(el.getAttribute('content'), 'metadata', '', null, doc, false);
      });
      Array.from(root.querySelectorAll('a[download][href],a[href*=".m3u8"],a[href*=".mp4"],a[href*=".webm"]')).slice(0, 50).forEach(function (el) {
        emit(el.getAttribute('href'), 'media-link', '', null, doc, false);
      });
      Array.from(root.querySelectorAll('script[type="application/ld+json"]')).slice(0, 8).forEach(function (el) {
        var t = el.textContent;
        if (t && t.length < maxJson) try { readObject(JSON.parse(t), 'structured-data', doc); } catch (_) {}
      });
    });
    try { performance.getEntriesByType('resource').slice(-180).forEach(function (e) { emit(e.name, 'performance'); }); } catch (_) {}
    try {
      var j = typeof window.jwplayer === 'function' && window.jwplayer();
      if (j && typeof j.getPlaylist === 'function') readObject({ playlist: j.getPlaylist() }, 'jwplayer', document);
      if (window.videojs && typeof window.videojs.getPlayers === 'function') {
        Object.values(window.videojs.getPlayers() || {}).slice(0, 12).forEach(function (p) {
          if (p && p.currentSrc) emit(p.currentSrc(), 'videojs', p.currentType && p.currentType(), null, document, true);
        });
      }
      if (window.ytInitialPlayerResponse) readObject(window.ytInitialPlayerResponse, 'player-config', document);
    } catch (_) {}
  }

  function schedule() { if (!queued) { queued = true; setTimeout(scan, 100); } }
  var mutations = new MutationObserver(function () { schedule(); });
  observe(document);
  try {
    // Open Shadow DOM created by a player after page start, not a full-page shadow tree crawl every tick.
    var attach = Element.prototype.attachShadow;
    if (attach) Element.prototype.attachShadow = function () {
      var root = attach.apply(this, arguments);
      if (root && root.mode === 'open') { observe(root); schedule(); }
      return root;
    };
  } catch (_) {}
  window.__v2Scan = function () {
    // A manual rescan can repopulate Clear and works on WebViews that only support late injection.
    items.clear();
    try {
      Array.from(document.querySelectorAll('*')).slice(0, 1200).forEach(function (el) { if (el.shadowRoot) observe(el.shadowRoot); });
      Array.from(document.querySelectorAll('iframe')).slice(0, 20).forEach(function (frame) {
        try { if (frame.contentDocument) observe(frame.contentDocument); } catch (_) {}
      });
    } catch (_) {}
    scan();
  };
  try { new PerformanceObserver(function (list) {
    list.getEntries().forEach(function (e) { emit(e.name, 'performance'); });
  }).observe({ type: 'resource', buffered: true }); } catch (_) {}
  document.addEventListener('DOMContentLoaded', schedule, { once: true });
  schedule();
  setInterval(scan, 2800);
})();

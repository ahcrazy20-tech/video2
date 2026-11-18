// Passive, frame-local capture: observe responses that the player already requested.
// Never copy request bodies/credentials, make a second request, alter a signature, or click the page.
(function () {
  'use strict';
  if (!/^https?:$/.test(location.protocol)) return;
  if (window.__v2Scan) { window.__v2Scan(); return; }
  var items = new Map(), hints = new Map(), roots = new Set(), outbox = [];
  var route = '', routeStart = 0, queued = false, activeReads = 0, lastManual = -Infinity;
  var jsonLimit = 256 * 1024, playerLimit = 768 * 1024;
  var ext = /\.(m3u8|mpd|mp4|m4v|mov|webm|mkv|avi|3gp|3gpp|m4a|mp3|aac|wav|ogg|opus|flac)$/i;
  var fragment = /\.(ts|m4s|cmfa|cmfv|srt|vtt)$/i;
  var mediaSelector = 'video,audio,video source,audio source';
  var metadataSelector = 'meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"],meta[name="twitter:player:stream"]';

  function syncRoute() {
    var page = location.href.split('#')[0];
    if (page !== route) {
      if (route) routeStart = performance.now();
      route = page;
      items.clear(); hints.clear(); outbox.length = 0;
    }
    return page;
  }
  syncRoute();

  function asUrl(raw, doc) {
    if (typeof raw !== 'string' || !raw || raw.length > 16384) return null;
    try {
      var u = new URL(raw, (doc || document).baseURI || location.href);
      if (!/^https?:$/.test(u.protocol) || u.username || u.password) return null;
      u.hash = '';
      return u;
    } catch (_) { return null; }
  }
  function isYouTube(host) { return /(^|\.)youtube(?:-nocookie)?\.com$/i.test(host); }
  function isTrackDelivery(u) { return /(^|\.)googlevideo\.com$/i.test(u.hostname) && /\/videoplayback$/i.test(u.pathname); }
  function isFragment(u) {
    return fragment.test(u.pathname) || /^video\/mp2t$/i.test(u.searchParams.get('mime') || '') ||
      (/\/videoplayback$/i.test(u.pathname) && (u.searchParams.has('range') || u.searchParams.has('sq')));
  }
  function manifestType(u) {
    if (!/(^|\.)(googlevideo\.com|youtube\.com)$/i.test(u.hostname)) return '';
    if (/\/manifest\/hls_(?:playlist|variant)(?:\/|$)/i.test(u.pathname)) return 'application/vnd.apple.mpegurl';
    if (/\/manifest\/dash(?:\/|$)/i.test(u.pathname)) return 'application/dash+xml';
    return '';
  }
  function looksMedia(u, type) {
    type = String(type || u.searchParams.get('mime') || '').toLowerCase().split(';')[0].trim();
    return ext.test(u.pathname) || /^(video|audio)\//.test(type) && type !== 'video/mp2t' || /mpegurl|dash\+xml/.test(type);
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
  window.__v2Drain = function () { syncRoute(); return outbox.splice(0, outbox.length); };

  function hint(mode, doc) {
    syncRoute();
    doc = doc || document;
    var key = String(doc.URL || location.href) + '|' + mode;
    if (hints.has(key) || hints.size >= 50) return;
    var body = { type: 'playback', mode: mode };
    hints.set(key, { doc: doc, body: body });
    send(doc, body);
  }

  function emit(raw, method, type, info, doc, force) {
    syncRoute();
    doc = doc || document;
    var u = asUrl(raw, doc);
    if (!u) return;
    info = info || {};
    if (isFragment(u) || (isTrackDelivery(u) && !info.completeTrack)) {
      if (!info.ad && (isTrackDelivery(u) || /\.(?:m4s|cmfa|cmfv)$/i.test(u.pathname))) hint('segmented', doc);
      return;
    }
    type = String(type || manifestType(u)).slice(0, 150);
    if (!force && !looksMedia(u, type)) return;
    var key = u.href;
    var previous = items.get(key);
    var old = previous ? previous.item : {};
    var protection = typeof info.drm === 'string' && /^(NONE|FAIRPLAY|WIDEVINE|PLAYREADY|ENCRYPTED_HLS|UNKNOWN_PROTECTED)$/.test(info.drm) ? info.drm : '';
    var item = {
      url: key, method: method, mime: type || old.mime || '',
      duration: Number(info.duration) > 0 && Number.isFinite(Number(info.duration)) ? Number(info.duration) : old.duration || 0,
      height: Number(info.height) || old.height || 0, width: Number(info.width) || old.width || 0,
      label: String(info.qualityLabel || old.label || '').slice(0, 40), name: String(info.title || old.name || '').slice(0, 512),
      completeTrack: !!info.completeTrack || !!old.completeTrack,
      ad: !!info.ad || !!old.ad, drm: protection || old.drm || 'NONE'
    };
    if (/^html5/.test(old.method || '') && !/^html5/.test(method)) item.method = old.method;
    if (old.drm && old.drm !== 'NONE') item.drm = old.drm;
    if (old.method && Object.keys(item).every(function (name) { return old[name] === item[name] || name === 'method' && !/^html5/.test(item.method); })) return;
    if (items.size >= 160 && !items.has(key)) items.delete(items.keys().next().value);
    items.set(key, { doc: doc, item: item });
    send(doc, { type: 'media', item: item });
  }

  function isAd(el) {
    try {
      return !!el.closest('.ad-showing,.ad-interrupting,.video-ads,.ytp-ad-module,.videoAdUi,.ad-container,.ad-slot,.ad-banner,.ima-ad,[data-ad],[data-ad-slot],[data-is-ad="true"]') ||
        (el.videoWidth > 0 && el.videoWidth <= 320 && el.videoHeight > 0 && el.videoHeight <= 60);
    } catch (_) { return false; }
  }

  function inspect(el) {
    if (!el || !el.getAttribute) return;
    var doc = el.ownerDocument;
    var media = el.tagName === 'SOURCE' ? el.parentElement : el;
    if (!media || !/^(VIDEO|AUDIO)$/.test(media.tagName)) return;
    var info = { duration: media.duration, height: media.videoHeight, width: media.videoWidth, ad: isAd(media) };
    var src = el.currentSrc || el.getAttribute('src') || el.getAttribute('data-src') || el.getAttribute('data-hls') || el.getAttribute('data-video');
    if (!info.ad && (src || media.readyState > 0 || media.srcObject)) {
      if (/^(blob|data):/i.test(src || '') || media.srcObject) hint('in-page', doc);
      else if (!asUrl(src, doc)) hint('player', doc);
    }
    // A media element playing an HTTP URL directly isn't a generic MSE range request.
    info.completeTrack = !!el.currentSrc && !/^(blob|data):/i.test(el.currentSrc);
    var mime = el.getAttribute('type') || (el.getAttribute('data-hls') === src && src ? 'application/vnd.apple.mpegurl' : '');
    emit(src, media.tagName === 'AUDIO' ? 'html5-audio' : 'html5-video', mime, info, doc, true);
  }

  function watchId(doc) {
    var page = asUrl(doc.URL, doc);
    if (!page || !isYouTube(page.hostname)) return '';
    if (/^\/watch\/?$/.test(page.pathname)) return page.searchParams.get('v') || '';
    return (page.pathname.match(/^\/(?:embed|shorts|live|v)\/([^/]+)\/?$/) || [])[1] || '';
  }
  function readPlayer(o, method, doc) {
    var sd = o.streamingData;
    if (!sd || typeof sd !== 'object') return;
    var details = o.videoDetails || {}, current = watchId(doc);
    if (current && details.videoId && current !== details.videoId) return; // SPA prefetch for a different video.
    if (Array.isArray(sd.adaptiveFormats) && sd.adaptiveFormats.length || sd.serverAbrStreamingUrl) hint('segmented', doc);
    else hint('player', doc);
    // Only complete formats/manifest URLs explicitly returned by this player's response.
    // signatureCipher/cipher, track-only formats and SABR endpoints are not download links.
    if (Array.isArray(sd.formats)) sd.formats.slice(0, 30).forEach(function (f) {
      if (!f || !f.url || f.videoOnly || f.audioOnly || (f.drmFamilies && f.drmFamilies.length)) return;
      emit(f.url, /^player-/.test(method) ? method : 'player-payload', f.mimeType, { title: details.title, duration: details.lengthSeconds,
        height: f.height, width: f.width, qualityLabel: f.qualityLabel, completeTrack: true }, doc, Number(f.height) > 0 || /^video\//i.test(f.mimeType || ''));
    });
    var info = { title: details.title, duration: details.lengthSeconds };
    emit(sd.hlsManifestUrl, method, 'application/vnd.apple.mpegurl', info, doc, true);
    emit(sd.dashManifestUrl, method, 'application/dash+xml', info, doc, true);
  }

  function readObject(value, method, doc) {
    var budget = 300;
    function walk(o, depth, typed) {
      if (!o || typeof o !== 'object' || depth > 6 || budget-- <= 0) return;
      if (Array.isArray(o)) {
        for (var i = 0; i < Math.min(o.length, 32); i++) walk(o[i], depth + 1, typed);
        return;
      }
      readPlayer(o, method, doc);
      if (o.videoOnly === true || o.audioOnly === true || o.isSegment === true) return;
      var mime = typeof o.mimeType === 'string' ? o.mimeType : (typeof o.type === 'string' ? o.type : '');
      Object.keys(o).slice(0, 52).forEach(function (key) {
        if (/^(streamingData|adaptiveFormats|videoAds|adSlots|playerAds|related|recommendations)$/i.test(key)) return;
        var v = o[key];
        if (typeof v === 'string') {
          if (/^player_?response$/i.test(key) && v.length <= playerLimit) {
            try { walk(JSON.parse(v), depth + 1, false); } catch (_) {}
            return;
          }
          var hls = /^(hls|hlsUrl|hlsManifestUrl|m3u8)$/i.test(key);
          var dash = /^(dashUrl|dashManifestUrl|mpd)$/i.test(key);
          var fields = /^(file|src|url|contentUrl|videoUrl|streamUrl|playbackUrl|manifestUrl|downloadUrl)$/i.test(key);
          if (!hls && !dash && !fields) return;
          var declared = hls || dash || /^(videoUrl|streamUrl|playbackUrl|manifestUrl)$/i.test(key) ||
            (/^VideoObject$/i.test(o['@type']) && /^contentUrl$/i.test(key)) || (typed && /^(src|file|url)$/i.test(key));
          emit(v, method, hls ? 'application/vnd.apple.mpegurl' : dash ? 'application/dash+xml' : mime, o, doc, declared);
        } else if (v && typeof v === 'object') {
          walk(v, depth + 1, /^(sources|playlist|streams|videos|renditions)$/i.test(key));
        }
      });
    }
    walk(value, 0, false);
  }

  function playerEndpoint(u) {
    if (!u) return false;
    if (isYouTube(u.hostname) && /^\/(?:youtubei\/v1\/player|get_video_info)\/?$/i.test(u.pathname)) return true;
    return u.origin === location.origin && /\/(?:player(?:[-_]?config)?|playback[-_]?config)(?:\.json)?\/?$/i.test(u.pathname);
  }
  function inspectText(raw, url, method, limit) {
    if (typeof raw !== 'string' || raw.length > limit) return;
    var t = raw.replace(/^\s+/, '');
    if (t.indexOf(")]}'") === 0) t = t.replace(/^\)\]\}',?\s*/, '');
    if (url && t.indexOf('#EXTM3U') === 0) {
      var k = t.match(/^#EXT-X-(?:SESSION-)?KEY:.*$/gm) || [];
      var drm = k.some(function (line) { return /SAMPLE-AES|skd:|KEYFORMAT="(?!identity")/i.test(line); }) ? 'ENCRYPTED_HLS' : '';
      emit(url, method, 'application/vnd.apple.mpegurl', { drm: drm }, document, true);
    } else if (url && /<(?:[\w-]+:)?MPD(?:\s|>)/.test(t.slice(0, 2048))) {
      emit(url, method, 'application/dash+xml', { drm: /ContentProtection/i.test(t) ? 'UNKNOWN_PROTECTED' : '' }, document, true);
    } else if (t[0] === '{' || t[0] === '[') {
      try { readObject(JSON.parse(t), method + '-json', document); } catch (_) {}
    }
  }

  function maybeRead(type, size, player, limit) {
    return (size == null || (Number(size) >= 0 && Number(size) <= limit)) &&
      (/json|mpegurl|dash\+xml|^text\/plain/i.test(type) || (player && !type) ||
        ((player || size != null) && /^application\/octet-stream/i.test(type)));
  }
  async function inspectFetch(response, get, player, requestRoute) {
    if (!response.ok || requestRoute !== syncRoute()) return;
    var mime = response.headers.get('Content-Type') || '';
    var limit = player ? playerLimit : jsonLimit;
    if (get) emit(response.url, 'fetch-response', mime);
    if (activeReads >= (player ? 6 : 3) || !maybeRead(mime, response.headers.get('Content-Length'), player, limit)) return;
    var stream = response.clone().body;
    if (!stream || !stream.getReader) return;
    var reader = stream.getReader();
    var decoder = new TextDecoder();
    var size = 0, text = '';
    activeReads++;
    try {
      while (true) {
        var next = await reader.read();
        if (next.done) break;
        size += next.value.byteLength;
        if (size > limit || requestRoute !== syncRoute()) { reader.cancel().catch(function () {}); return; }
        text += decoder.decode(next.value, { stream: true });
      }
      if (requestRoute === syncRoute()) inspectText(text + decoder.decode(), get ? response.url : '', player ? 'player-fetch' : 'fetch-response', limit);
    } finally { activeReads--; reader.releaseLock(); }
  }

  var fetch = window.fetch;
  if (fetch) window.fetch = function (input, init) {
    var p = fetch.apply(this, arguments);
    try {
      var raw = typeof input === 'string' ? input : input && (input.url || input.href);
      var method = String((init && init.method) || (input && input.method) || 'GET').toUpperCase();
      var get = method === 'GET';
      var player = (get || method === 'POST') && playerEndpoint(asUrl(raw));
      var page = syncRoute();
      if (get) emit(raw, 'fetch');
      if (get || player) p.then(function (r) { return inspectFetch(r, get, player, page); }).catch(function () {});
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
      method = String(method).toUpperCase();
      var get = method === 'GET', player = (get || method === 'POST') && playerEndpoint(asUrl(String(url)));
      var page = syncRoute();
      if (!get && !player) return r;
      if (get) emit(String(url), 'xhr');
      var listener = function () {
        try {
          if (this.status < 200 || this.status >= 300 || page !== syncRoute()) return;
          var mime = this.getResponseHeader('Content-Type') || '';
          var limit = player ? playerLimit : jsonLimit;
          if (get) emit(this.responseURL, 'xhr-response', mime);
          if (!maybeRead(mime, this.getResponseHeader('Content-Length'), player, limit)) return;
          if (this.responseType === 'json') readObject(this.response, player ? 'player-xhr-json' : 'xhr-json', document);
          else if (!this.responseType || this.responseType === 'text') inspectText(this.responseText, get ? this.responseURL : '', player ? 'player-xhr' : 'xhr-response', limit);
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
      if (isAd(e.target)) return;
      syncRoute();
      var doc = e.target.ownerDocument || document;
      var u = asUrl(e.target.currentSrc || e.target.src, doc);
      var key = String(doc.URL) + '|drm|' + (u ? u.href : '');
      if (hints.has(key) || hints.size >= 50) return;
      var body = { type: 'drm', url: u ? u.href : '' };
      hints.set(key, { doc: doc, body: body });
      send(doc, body);
    }, true);
  }

  function resource(e) { if (!routeStart || e.startTime >= routeStart) emit(e.name, 'performance'); }
  function scan() {
    queued = false;
    syncRoute();
    roots.forEach(function (root) {
      if (root.host && !root.host.isConnected) { roots.delete(root); return; }
      var doc = root.ownerDocument || root;
      Array.from(root.querySelectorAll(mediaSelector)).slice(0, 120).forEach(inspect);
      Array.from(root.querySelectorAll(metadataSelector)).slice(0, 12).forEach(function (el) { emit(el.getAttribute('content'), 'metadata', '', null, doc, false); });
      Array.from(root.querySelectorAll('a[download][href],a[href*=".m3u8"],a[href*=".mp4"],a[href*=".webm"]')).slice(0, 50).forEach(function (el) {
        emit(el.getAttribute('href'), 'media-link', '', null, doc, false);
      });
      Array.from(root.querySelectorAll('script[type="application/ld+json"]')).slice(0, 8).forEach(function (el) {
        var t = el.textContent;
        if (t && t.length < jsonLimit) try { readObject(JSON.parse(t), 'structured-data', doc); } catch (_) {}
      });
    });
    try { performance.getEntriesByType('resource').slice(-180).forEach(resource); } catch (_) {}
    try {
      var j = typeof window.jwplayer === 'function' && window.jwplayer();
      if (j && typeof j.getPlaylist === 'function') readObject({ playlist: j.getPlaylist() }, 'jwplayer', document);
    } catch (_) {}
    try {
      if (window.videojs && typeof window.videojs.getPlayers === 'function') {
        Object.values(window.videojs.getPlayers() || {}).slice(0, 12).forEach(function (p) {
          if (p && p.currentSrc) emit(p.currentSrc(), 'videojs', p.currentType && p.currentType(), null, document, true);
        });
      }
    } catch (_) {}
    try {
      if (isYouTube(location.hostname)) {
        if (window.ytInitialPlayerResponse) readObject(window.ytInitialPlayerResponse, 'player-config', document);
        var args = window.ytplayer && window.ytplayer.config && window.ytplayer.config.args;
        if (args) readObject({ player_response: args.player_response }, 'player-config', document);
      }
    } catch (_) {}
  }

  function schedule() { if (!queued) { queued = true; setTimeout(scan, 125); } }
  var mutations = new MutationObserver(function () { schedule(); });
  observe(document);
  try {
    var attach = Element.prototype.attachShadow;
    if (attach) Element.prototype.attachShadow = function () {
      var root = attach.apply(this, arguments);
      if (root && root.mode === 'open') { observe(root); schedule(); }
      return root;
    };
  } catch (_) {}
  window.__v2Scan = function () {
    if (performance.now() - lastManual < 500) return;
    lastManual = performance.now();
    syncRoute();
    // Replay previously observed sources so a scan can recover events sent before a navigation committed.
    Array.from(items.values()).slice(-80).forEach(function (r) { send(r.doc, { type: 'media', item: r.item }); });
    hints.forEach(function (r) { send(r.doc, r.body); });
    try {
      Array.from(document.querySelectorAll('*')).slice(0, 1200).forEach(function (el) { if (el.shadowRoot) observe(el.shadowRoot); });
      Array.from(document.querySelectorAll('iframe')).slice(0, 20).forEach(function (frame) {
        try {
          if (frame.contentWindow) frame.contentWindow.postMessage('video2:rescan', '*');
          // Fallback on old WebViews that inject at page-finish; only same-origin DOM can be read there.
          if (frame.contentDocument && !frame.contentWindow.__v2Scan) observe(frame.contentDocument);
        } catch (_) {}
      });
    } catch (_) {}
    scan();
  };
  if (window.addEventListener) {
    window.addEventListener('message', function (e) {
      if (window.parent !== window && e.source === window.parent && e.data === 'video2:rescan') window.__v2Scan();
    });
    window.addEventListener('popstate', schedule);
  }
  try { new PerformanceObserver(function (list) { syncRoute(); list.getEntries().slice(-180).forEach(resource); })
    .observe({ type: 'resource', buffered: true }); } catch (_) {}
  document.addEventListener('DOMContentLoaded', schedule, { once: true });
  schedule();
  setInterval(scan, 2800);
})();

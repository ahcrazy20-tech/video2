// Video2 media extractor — Android port of ExtractorScript.swift
// Injected at document start; reports media to Kotlin via window.Video2Bridge.
(function () {
  if (window.__v2installed) return;
  window.__v2installed = true;
  var found = new Map();

  function isAdURL(url) {
    if (!url || typeof url !== 'string') return false;
    var u = url.toLowerCase();
    return /doubleclick|googlesyndication|googleadservices|pagead|vungle|teads|connatix|aniview|spotx|serving-sys|flashtalking|innovid|springserve|vidazoo|primis|exoclick|trafficjunky|adsterra|popads|popcash|clickadu|hilltopads|outbrain|taboola|revcontent|mgid|adnxs|smartadserver|imasdk/.test(u)
      || /[?&/](adformat|ad_type|ad_unit|adtag|preroll|midroll|postroll|vast|vpaid|videoads)/.test(u);
  }
  function kindFrom(url, mime) {
    var u = (url || '').split('?')[0].toLowerCase(), m = (mime || '').toLowerCase();
    if (u.indexOf('.m3u8') >= 0 || m.indexOf('mpegurl') >= 0) return 'HLS';
    if (u.indexOf('.mpd') >= 0 || m.indexOf('dash') >= 0) return 'DASH';
    if (u.endsWith('.webm')) return 'WEBM'; if (u.endsWith('.mkv')) return 'MKV';
    if (u.endsWith('.avi')) return 'AVI'; if (u.endsWith('.mov')) return 'MOV';
    if (u.endsWith('.m4v')) return 'M4V'; if (u.endsWith('.mp3')) return 'MP3';
    if (u.endsWith('.aac')) return 'AAC'; if (u.endsWith('.wav')) return 'WAV';
    if (u.endsWith('.ts')) return 'TS';
    if (u.endsWith('.mp4') || m.indexOf('mp4') >= 0 || m.indexOf('video') >= 0) return 'MP4';
    return 'OTHER';
  }
  function drmFrom(url, text) {
    var s = ((url || '') + ' ' + (text || '')).toLowerCase();
    if (s.indexOf('fairplay') >= 0) return 'FAIRPLAY';
    if (s.indexOf('widevine') >= 0) return 'WIDEVINE';
    if (s.indexOf('playready') >= 0) return 'PLAYREADY';
    if (/cbcs|drm\/|license/.test(s)) return 'ENCRYPTED_HLS';
    return 'NONE';
  }
  function emit(item) {
    if (!item || !item.url || isAdURL(item.url)) return;
    try { item.url = new URL(item.url, location.href).href; } catch (e) { return; }
    if (isAdURL(item.url)) return;
    if (item.url.indexOf('blob:') === 0 || item.url.indexOf('data:') === 0) {
      if (!item.drm || item.drm === 'NONE') item.drm = 'UNKNOWN_PROTECTED';
    }
    found.set(item.url, item);
    try {
      window.Video2Bridge.postMessage(JSON.stringify({
        type: 'media', item: item, page: location.href, title: document.title || ''
      }));
    } catch (e) {}
  }
  function scanVideos() {
    document.querySelectorAll('video, audio, source').forEach(function (el) {
      var url = el.currentSrc || el.src || (el.getAttribute && el.getAttribute('src'));
      if (!url) return;
      var dur = 0; try { if (el.duration && isFinite(el.duration)) dur = el.duration; } catch (e) {}
      emit({ url: url, kind: kindFrom(url, el.type || ''), drm: drmFrom(url, ''),
             duration: dur, width: el.videoWidth || 0, height: el.videoHeight || 0, method: 'html5' });
    });
  }
  // fetch hook
  var of = window.fetch;
  if (of) window.fetch = function () {
    var url = arguments[0] && (arguments[0].url || arguments[0]);
    var p = of.apply(this, arguments);
    try {
      if (typeof url === 'string' && /\.(m3u8|mp4|mpd|webm)(\?|$)/i.test(url))
        emit({ url: url, kind: kindFrom(url, ''), drm: drmFrom(url, ''), method: 'fetch' });
    } catch (e) {}
    return p;
  };
  // XHR hook (also catches youtubei/player responses)
  var oo = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (m, url) {
    try {
      if (typeof url === 'string' && /\.(m3u8|mp4|mpd|webm)(\?|$)/i.test(url))
        emit({ url: url, kind: kindFrom(url, ''), drm: drmFrom(url, ''), method: 'xhr' });
      if (typeof url === 'string' && url.indexOf('youtubei') >= 0) {
        this.addEventListener('load', function () {
          try {
            var j = JSON.parse(this.responseText);
            var fmts = ((j.streamingData || {}).formats || []).concat(((j.streamingData || {}).adaptiveFormats || []));
            fmts.forEach(function (f) {
              if (f.url) emit({ url: f.url, kind: 'MP4', drm: 'NONE', height: f.height || 0, method: 'youtubei' });
            });
            var hls = (j.streamingData || {}).hlsManifestUrl;
            if (hls) emit({ url: hls, kind: 'HLS', drm: drmFrom(hls, ''), method: 'youtubei' });
          } catch (e) {}
        });
      }
    } catch (e) {}
    return oo.apply(this, arguments);
  };
  // Performance entries
  try {
    new PerformanceObserver(function (list) {
      list.getEntries().forEach(function (e) {
        if (/\.(m3u8|mp4|mpd|webm|mov)(\?|$)/i.test(e.name))
          emit({ url: e.name, kind: kindFrom(e.name, ''), drm: drmFrom(e.name, ''), method: 'performance' });
      });
    }).observe({ entryTypes: ['resource'] });
  } catch (e) {}
  new MutationObserver(scanVideos).observe(document.documentElement, { childList: true, subtree: true });
  document.addEventListener('DOMContentLoaded', scanVideos);
  setInterval(scanVideos, 2500);
})();

const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync(new URL('../../main/assets/extractor.js', `file://${__filename}`), 'utf8');

function webFrame(base, responseFactory) {
  const messages = [], domEvents = {}, elements = [], resources = [];
  const location = new URL(base);
  const document = { title: 'Embedded player', URL: base, baseURI: base,
    querySelectorAll: query => query.startsWith('video,') ? elements : [],
    addEventListener: (name, callback) => { domEvents[name] = callback; } };
  class Xhr {
    constructor() { this.listeners = {}; }
    open() {}
    addEventListener(event, listener) { this.listeners[event] = listener; }
    removeEventListener(event) { delete this.listeners[event]; }
    getResponseHeader(name) { return name === 'Content-Type' ? this.mime : null; }
    finish() { this.listeners.load?.call(this); }
  }
  const sandbox = { URL, document, location, TextDecoder,
    setTimeout: () => {}, setInterval: () => {}, MutationObserver: class { observe() {} },
    XMLHttpRequest: Xhr, fetch: responseFactory, Video2Media: { postMessage: m => messages.push(JSON.parse(m)) },
    performance: { now: () => 100, getEntriesByType: () => resources } };
  sandbox.window = sandbox;
  vm.runInNewContext(script, sandbox);
  return { sandbox, document, elements, messages, domEvents, resources };
}

test('observes an extensionless, signed fetch URL using the response MIME without consuming it', async () => {
  const remote = 'https://cdn.example/get?sig=a%2Bb&token=xyz';
  const response = new Response(Buffer.from('unchanged'), { headers: { 'Content-Type': 'video/mp4' } });
  Object.defineProperty(response, 'url', { value: remote });
  const { sandbox, messages } = webFrame('https://embed.example/episode', () => Promise.resolve(response));
  const actual = await sandbox.fetch('/startup');
  assert.equal(await actual.text(), 'unchanged');
  assert.equal(messages[0]?.item.url, remote);
  assert.equal(messages[0]?.item.mime, 'video/mp4');
  assert.equal(messages[0]?.page, 'https://embed.example/episode');
});

test('learns HLS from a JSON XHR and drops fragment, data, and adaptive media entries', () => {
  const { sandbox, messages } = webFrame('https://embed.example/path/watch');
  const xhr = new sandbox.XMLHttpRequest();
  xhr.open('POST', '/player-config');
  xhr.status = 200; xhr.mime = 'application/json'; xhr.responseType = 'json';
  xhr.response = { streamingData: { hlsManifestUrl: '../playlist?sig=1', adaptiveFormats: [{ url: 'https://cdn.example/adaptive.mp4' }] },
    sources: [{ src: 'https://cdn.example/chunk.m4s' }, { src: 'data:video/mp4,' }] };
  xhr.finish();
  assert.deepEqual(messages.filter(m => m.type === 'media').map(m => [m.item.url, m.item.mime]), [['https://embed.example/playlist?sig=1', 'application/vnd.apple.mpegurl']]);
  assert.ok(messages.some(m => m.mode === 'segmented'));
});

test('updates metadata without repeated hits or marking blob as DRM; real encrypted event is reported', () => {
  const { sandbox, document, elements, messages, domEvents } = webFrame('https://embed.example/player');
  const video = { tagName: 'VIDEO', ownerDocument: document, currentSrc: '/asset', duration: 42,
    videoWidth: 1280, videoHeight: 720, closest: () => null, getAttribute: () => null };
  elements.push(video);
  sandbox.__v2Scan();
  domEvents.playing({ target: video });
  domEvents.play({ target: video });
  assert.equal(messages.length, 1);
  assert.equal(messages[0].item.height, 720);
  video.currentSrc = 'blob:https://embed.example/123';
  domEvents.play({ target: video });
  assert.equal(messages.length, 2);
  assert.equal(messages[1].mode, 'in-page');
  assert.equal(messages[1].url, undefined);
  domEvents.encrypted({ target: video });
  assert.deepEqual(messages[2], { type: 'drm', title: 'Embedded player', page: 'https://embed.example/player', url: '' });
});

const tick = () => new Promise(resolve => setImmediate(resolve));
function jsonResponse(body, url) {
  const response = new Response(body, { headers: { 'Content-Type': 'application/json' } });
  Object.defineProperty(response, 'url', { value: url });
  return response;
}

test('sees an already-requested player POST without another request, cipher decoding, or track promotion', async () => {
  const player = 'https://m.youtube.com/youtubei/v1/player?key=existing';
  const direct = 'https://edge.googlevideo.com/videoplayback?itag=18&sig=ab%2Bcd';
  const hls = 'https://manifest.googlevideo.com/api/manifest/hls_playlist/id/video/token/example';
  const payload = JSON.stringify({ videoDetails: { title: 'Episode', videoId: 'episode', lengthSeconds: '180' },
    streamingData: {
      formats: [{ url: direct, height: 360, mimeType: 'video/mp4', qualityLabel: '360p' },
        { signatureCipher: 'url=https%3A%2F%2Fedge.googlevideo.com%2Fciphered' }], hlsManifestUrl: hls,
      adaptiveFormats: [{ url: 'https://edge.googlevideo.com/videoplayback?itag=137&mime=video%2Fmp4' }],
    } });
  const raw = ")]}'\n" + payload;
  let calls = 0;
  const { sandbox, messages } = webFrame('https://m.youtube.com/watch?v=episode', () => {
    calls++;
    return Promise.resolve(jsonResponse(raw, player));
  });
  const reply = await sandbox.fetch(player, { method: 'POST', body: 'player input' });
  assert.equal(await reply.text(), raw);
  await tick();
  assert.equal(calls, 1);
  const rows = messages.filter(m => m.type === 'media').map(m => m.item);
  assert.deepEqual(rows.map(x => x.url), [direct, hls]);
  assert.equal(rows[0].label, '360p');
  assert.equal(rows[0].duration, 180);
  assert.equal(rows[0].name, 'Episode');
  assert.equal(rows[0].completeTrack, true);
  assert.ok(messages.some(m => m.mode === 'segmented'));
});

test('does not read unrelated POST responses, promote range traffic, or keep prior SPA results', async () => {
  const response = (url, type, body) => {
    const r = new Response(body, { headers: { 'Content-Type': type } });
    Object.defineProperty(r, 'url', { value: url });
    return r;
  };
  const start = 'https://m.youtube.com/watch?v=one';
  const rangeUrl = 'https://edge.googlevideo.com/videoplayback?range=0-999&mime=video%2Fmp4';
  let settle;
  const { sandbox, document, resources, messages } = webFrame(start, url => {
    if (url.endsWith('slow')) return new Promise(resolve => { settle = resolve; });
    if (url === rangeUrl) return Promise.resolve(response(url, 'video/mp4', 'fragment'));
    return Promise.resolve(jsonResponse(JSON.stringify({ sources: [{ url: '/noise.mp4' }] }), url));
  });
  await sandbox.fetch('https://m.youtube.com/private', { method: 'POST' });
  await sandbox.fetch(rangeUrl);
  await tick();
  assert.deepEqual(messages.map(m => m.mode), ['segmented']);
  const player = sandbox.fetch('https://m.youtube.com/api/player?slow', { method: 'POST' });
  resources.push({ name: 'https://cdn.example/stale.mp4', startTime: 1 });
  document.URL = document.baseURI = sandbox.location.href = 'https://m.youtube.com/watch?v=two';
  sandbox.ytInitialPlayerResponse = { videoDetails: { videoId: 'one' }, streamingData: { hlsManifestUrl: '/stale.m3u8' } };
  sandbox.__v2Scan();
  settle(jsonResponse(JSON.stringify({ videoDetails: { videoId: 'one' }, streamingData: { hlsManifestUrl: '/old.m3u8' } }), 'https://m.youtube.com/api/player?slow'));
  await player;
  await tick();
  assert.ok(!messages.some(m => m.type === 'media'));
});

test('recognizes extensionless manifest paths but does not mistake a lookalike host for a media CDN', async () => {
  const manifest = 'https://manifest.googlevideo.com/api/manifest/hls_variant/id/video';
  const fake = 'https://googlevideo.com.example/api/manifest/hls_variant/id/video';
  const { sandbox, messages } = webFrame('https://example.net/player', () => Promise.resolve(new Response('x')));
  await sandbox.fetch(manifest);
  await sandbox.fetch(fake);
  assert.deepEqual(messages.map(m => m.item?.url), [manifest]);
});

test('preserves the YouTube embed frame URL for watch-link recovery, never a different prefetched ID', () => {
  const id = 'AbCdEfG_123';
  const frame = `https://www.youtube-nocookie.com/embed/${id}?autoplay=1`;
  const { sandbox, messages } = webFrame(frame);
  const direct = 'https://edge.googlevideo.com/videoplayback?itag=18&sig=ephemeral';
  const player = (videoId) => ({ videoDetails: { videoId }, streamingData: { formats: [{ url: direct, mimeType: 'video/mp4', height: 360 }] } });
  sandbox.ytInitialPlayerResponse = player('ZyXwVu65432');
  sandbox.__v2Scan();
  assert.equal(messages.filter(m => m.type === 'media').length, 0);
  sandbox.ytInitialPlayerResponse = player(id);
  sandbox.performance.now = () => 700;
  sandbox.__v2Scan();
  const media = messages.find(m => m.type === 'media');
  assert.equal(media?.page, frame);
  assert.equal(media?.item.url, direct);
});

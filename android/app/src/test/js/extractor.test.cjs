const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync(new URL('../../main/assets/extractor.js', `file://${__filename}`), 'utf8');

function webFrame(base, responseFactory) {
  const messages = [], domEvents = {}, elements = [];
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
    performance: { getEntriesByType: () => [] } };
  sandbox.window = sandbox;
  vm.runInNewContext(script, sandbox);
  return { sandbox, document, elements, messages, domEvents };
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
  assert.deepEqual(messages.map(m => [m.item.url, m.item.mime]), [['https://embed.example/playlist?sig=1', 'application/vnd.apple.mpegurl']]);
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
  assert.equal(messages.length, 1);
  domEvents.encrypted({ target: video });
  assert.deepEqual(messages[1], { type: 'drm', title: 'Embedded player', page: 'https://embed.example/player', url: '' });
});

const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { runInNewContext } = require('node:vm');

const cosmetics = readFileSync(join(__dirname, '../../main/assets/adblock_strict.js'), 'utf8');

test('strict cosmetic CSS can run before the document exists and is idempotent', () => {
  const nodes = [];
  let ready;
  const document = {
    documentElement: null,
    head: null,
    getElementById: (id) => nodes.find((node) => node.id === id),
    createElement: (tag) => { assert.equal(tag, 'style'); return {}; },
    addEventListener: (name, callback, opts) => {
      assert.equal(name, 'DOMContentLoaded');
      assert.equal(opts.once, true);
      ready = callback;
    }
  };
  runInNewContext(cosmetics, { document });
  assert.equal(nodes.length, 0);
  document.documentElement = {};
  document.head = { appendChild: (style) => nodes.push(style) };
  ready();
  runInNewContext(cosmetics, { document });
  assert.equal(nodes.length, 1);
  assert.match(nodes[0].textContent, /ins\.adsbygoogle/);
  assert.match(nodes[0].textContent, /google_ads_iframe_/);
  assert.doesNotMatch(nodes[0].textContent, /\bvideo\b|\[data-ad\]/);
});

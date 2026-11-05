const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function harness(withObserver = true) {
  const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/com/zahah/tunnelview/ui/main/MainCoordinator.kt'), 'utf8');
  const match = source.match(/private fun injectDiagnostics\(\)[\s\S]*?val diagJs = """([\s\S]*?)"""\.trimIndent\(\)/);
  assert.ok(match, 'WebView diagnostics script not found');
  let reads = 0;
  let pendingResolve;
  const callbacks = {};
  const scheduled = new Map();
  const payloads = [];
  let id = 0;
  const document = {
    hidden: false, readyState: 'complete',
    documentElement: { get outerHTML() { reads++; return '<html>ok</html>'; } },
    addEventListener(name, fn) { callbacks[name] = fn; }
  };
  const window = {
    document, location: { href: 'https://example.test/' },
    AndroidOffline: { onSnapshotCandidate(payload) { payloads.push(JSON.parse(payload)); } },
    setTimeout(fn) { scheduled.set(++id, fn); return id; },
    clearTimeout(token) { scheduled.delete(token); },
    setInterval(fn) { callbacks.interval = fn; return 99; },
    clearInterval() { callbacks.interval = null; },
    addEventListener(name, fn) { callbacks[name] = fn; },
    fetch() { return new Promise(resolve => { pendingResolve = resolve; }); },
    MutationObserver: class {
      constructor(callback) { callbacks.mutation = callback; }
      observe() { callbacks.observations = (callbacks.observations || 0) + 1; }
      disconnect() { callbacks.disconnections = (callbacks.disconnections || 0) + 1; }
    }
  };
  if (!withObserver) delete window.MutationObserver;
  const context = { window, document, AndroidOffline: window.AndroidOffline };
  vm.runInNewContext(match[1], context);
  function flush() {
    for (const [key, fn] of [...scheduled]) { scheduled.delete(key); fn(); }
  }
  return { document, window, callbacks, payloads, flush, reads: () => reads,
    reinject: () => vm.runInNewContext(match[1], context),
    resolveFetch: () => pendingResolve({ ok: true }) };
}

test('hidden pages are not serialized, even if a snapshot was already queued', () => {
  const h = harness();
  h.flush();
  h.document.hidden = true;
  h.callbacks.visibilitychange();
  assert.equal(h.callbacks.disconnections, 1);
  assert.equal(h.callbacks.interval, null);
  h.flush();
  assert.equal(h.reads(), 0);
  assert.equal(h.payloads.length, 0);
  h.document.hidden = false;
  h.callbacks.visibilitychange();
  assert.equal(h.callbacks.observations, 2);
  h.flush();
  assert.equal(h.reads(), 1);
  assert.equal(h.payloads[0].html, '<html>ok</html>');
});

test('pending requests do not serialize HTML and completion still captures it', async () => {
  const h = harness();
  h.flush();
  const request = h.window.fetch('https://example.test/data');
  h.callbacks.interval();
  h.flush();
  assert.equal(h.reads(), 0);
  assert.equal(h.payloads.at(-1).pending, 1);
  assert.equal(h.payloads.at(-1).html, '');
  h.resolveFetch();
  await request;
  h.flush();
  assert.equal(h.reads(), 1);
  assert.equal(h.payloads.at(-1).html, '<html>ok</html>');
});

test('unchanged visible DOM is skipped while changes and URL navigation are captured', () => {
  const h = harness();
  h.flush();
  h.flush();
  assert.equal(h.reads(), 1);
  h.callbacks.interval();
  h.flush();
  assert.equal(h.reads(), 1);
  h.callbacks.mutation();
  assert.equal(h.callbacks.disconnections, 1);
  h.callbacks.interval();
  h.flush();
  assert.equal(h.reads(), 2);
  assert.equal(h.callbacks.observations, 3);
  h.window.location.href = 'https://example.test/another';
  h.callbacks.interval();
  h.flush();
  assert.equal(h.reads(), 3);
});

test('periodic snapshots remain available without MutationObserver', () => {
  const h = harness(false);
  h.flush(); h.flush();
  assert.equal(h.reads(), 1);
  h.callbacks.interval(); h.flush();
  assert.equal(h.reads(), 2);
});

test('an explicit refresh captures again even when the DOM is unchanged', () => {
  const h = harness();
  h.flush(); h.flush();
  assert.equal(h.reads(), 1);
  h.window.__tunnelviewRequestSnapshot();
  h.flush();
  assert.equal(h.reads(), 2);
});

test('reinjection on cache enable or page finish requests a fresh snapshot', () => {
  const h = harness();
  h.flush(); h.flush();
  assert.equal(h.reads(), 1);
  h.reinject();
  h.flush();
  assert.equal(h.reads(), 2);
});

test('disabling cache removes periodic work and reenabling captures again', () => {
  const h = harness();
  h.flush(); h.flush();
  assert.equal(h.reads(), 1);
  h.window.__tunnelviewSetSnapshotEnabled(false);
  assert.equal(h.callbacks.interval, null);
  h.callbacks.mutation();
  h.flush();
  assert.equal(h.reads(), 1);
  h.window.__tunnelviewSetSnapshotEnabled(true);
  assert.equal(typeof h.callbacks.interval, 'function');
  h.flush();
  assert.equal(h.reads(), 2);
});

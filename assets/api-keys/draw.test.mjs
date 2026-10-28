// The API keys page's drawing of its code (#590, `draw.js`), with sealing that is slow or fails:
// a clear code never stays on screen while a sealed one is made, or after sealing failed. Node 22
// or later: node --test assets/api-keys/draw.test.mjs

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { codeDrawer } from './draw.js';

const WORDS = ['aardvark', 'abandoned', 'abbreviate', 'abdomen', 'abhorrence'];

// A drawer whose sealing waits until the test settles it, and a log of what the screen shows.
function screen() {
  const pending = [];
  const log = [];
  const state = { shown: null, failures: 0 };
  const drawer = codeDrawer({
    seal: (text, words) =>
      new Promise((resolve, reject) => pending.push({ text, words, resolve, reject })),
    show: (content, sealed, describe) => {
      state.shown = { content, sealed };
      log.push(`show ${sealed ? 'sealed' : 'clear'} ${content}`);
      describe?.(sealed);
    },
    hide: () => {
      state.shown = null;
      log.push('hide');
    },
    failed: () => {
      state.failures++;
      log.push('failed');
    },
  });
  return { drawer, pending, log, state };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

test('a clear code goes before a sealed one is made, and the sealed one shows once sealed', async () => {
  const { drawer, pending, state } = screen();
  await drawer.draw('setup', null);
  assert.deepEqual(state.shown, { content: 'setup', sealed: false });

  const sealing = drawer.draw('setup', WORDS);
  assert.equal(state.shown, null, 'the clear code is gone while the key is derived');
  assert.equal(pending.length, 1);
  pending[0].resolve('sealed:setup');
  await sealing;
  assert.deepEqual(state.shown, { content: 'sealed:setup', sealed: true });
});

test('when sealing fails, no code shows, and it is said', async () => {
  const { drawer, pending, state } = screen();
  await drawer.draw('setup', null);
  const sealing = drawer.draw('setup', WORDS);
  pending[0].reject(new Error('no WebCrypto'));
  await sealing;
  await settle();
  assert.equal(state.shown, null);
  assert.equal(state.failures, 1);
  // Asked again, it is sealed again rather than taken as drawn.
  assert.equal(drawer.holds('setup', WORDS), false);
});

test('a code sealed after another was asked for is dropped', async () => {
  const { drawer, pending, state, log } = screen();
  const first = drawer.draw('one', WORDS);
  const second = drawer.draw('two', WORDS);
  pending[1].resolve('sealed:two');
  await second;
  pending[0].resolve('sealed:one');
  await first;
  assert.deepEqual(state.shown, { content: 'sealed:two', sealed: true });
  assert.ok(!log.includes('show sealed sealed:one'));
});

test('the same keys and words are not sealed again; clearing drops a code being sealed', async () => {
  const { drawer, pending, state } = screen();
  const sealing = drawer.draw('setup', WORDS);
  pending[0].resolve('sealed:setup');
  await sealing;
  await drawer.draw('setup', WORDS);
  assert.equal(pending.length, 1);
  assert.equal(drawer.holds('setup', WORDS), true);

  const again = drawer.draw('other', WORDS);
  drawer.clear();
  pending[1].resolve('sealed:other');
  await again;
  assert.equal(state.shown, null);
});

test('what is said of a code is the latest asked for, also when it changed while sealing', async () => {
  const { drawer, pending } = screen();
  const said = [];
  const sealing = drawer.draw('setup', WORDS, () => said.push('before'));
  // The same keys and words, another key left out meanwhile: not sealed again, but described anew.
  await drawer.draw('setup', WORDS, () => said.push('after'));
  assert.equal(pending.length, 1);
  pending[0].resolve('sealed:setup');
  await sealing;
  assert.deepEqual(said, ['after']);
});

// The Content Security Policy of the pages that handle keys (#1035): the API keys page
// (`api-keys.html`, #436) and the keyboard page (`keyboard.html`, #394). Each page sets its policy
// in a `<meta http-equiv>`, since the site (GitHub Pages) sends no headers of its own; the policy is
// what keeps the page from fetching or running anything but its own files. A change to it, a second
// policy, or one moved below the files it governs (a meta policy covers only what comes after it)
// fails here, so it is a change made on purpose, here and on the page together. `ci.yml` runs it in
// every CI run. Node 22 or later: node --test .github/scripts/page-csp.test.mjs

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const ROOT = new URL('../../', import.meta.url);

const POLICIES = {
  'api-keys.html':
    "default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self'; " +
    "connect-src 'none'; base-uri 'none'; form-action 'none'",
  'keyboard.html':
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'none'; " +
    "base-uri 'none'; form-action 'none'",
};

// The page's source without its HTML comments, where a tag is inert.
function source(page) {
  return readFileSync(new URL(page, ROOT), 'utf8').replace(/<!--[\s\S]*?-->/g, '');
}

// Every `<meta>` whose `http-equiv` is the policy, in any case or quoting, with its position.
function policies(text) {
  return [...text.matchAll(/<meta\b[^>]*>/gi)]
    .filter((m) => /\bhttp-equiv\s*=\s*["']?content-security-policy["'\s>/]/i.test(m[0]))
    .map((m) => ({ tag: m[0], at: m.index }));
}

function content(tag) {
  const m = tag.match(/\bcontent\s*=\s*(?:"([^"]*)"|'([^']*)')/i);
  return m ? (m[1] ?? m[2]) : null;
}

for (const [page, expected] of Object.entries(POLICIES)) {
  test(`${page} sets exactly its pinned policy, once`, () => {
    const found = policies(source(page));
    assert.equal(found.length, 1, `${page}: ${found.length} policies, not one`);
    assert.equal(content(found[0].tag), expected, `${page}: the policy changed`);
  });

  test(`${page} sets its policy before any script, style sheet or style it governs`, () => {
    const text = source(page);
    const [policy] = policies(text);
    assert.ok(policy, `${page}: no policy`);
    const first = text.search(/<(script|link|style|base|img|iframe|object|embed)\b/i);
    assert.ok(first === -1 || policy.at < first, `${page}: a resource comes before the policy`);
  });
}

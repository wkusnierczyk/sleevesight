// The Content Security Policy of the pages that handle keys (#1035): the API keys page
// (`api-keys.html`, #436) and the keyboard page (`keyboard.html`, #394). Each page sets its policy
// in a `<meta http-equiv>`, since the site (GitHub Pages) sends no headers of its own; the policy is
// what keeps the page from fetching or running anything but its own files. A policy that changes,
// is missing, set twice, inactive (not a child of the head: inside a `<template>` or `<noscript>`,
// or after the head has ended) or set after a resource it governs fails here, so a change to it is
// made on purpose, in the page and here together. `ci.yml` runs it in every CI run.
// Node 22 or later: node --test .github/scripts/page-csp.test.mjs
//
// Pages serves Jekyll's output, not the source read here. So that the two agree as far as the policy
// goes, a page must have front matter with `layout: null` (no layout wraps it) and no Liquid tag or
// output (`{%`, `{{`) from its start through the policy, which Liquid could otherwise use to drop or
// change it. Not covered: site-wide build settings (`_config.yml`, a `.nojekyll`), which
// `CODEOWNERS` covers instead, and the files the policy admits (`'self'`), likewise.
//
// The page is read by a small tokenizer, as an HTML parser reads it as far as the policy needs:
// comments, raw-text elements (`script`, `style`, `title`, `noscript` …), attributes by exact name
// (the first of a repeated one wins), quoted and unquoted values, and character references in them.

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

// Elements that may stay in the head; any other start tag (or text) ends it.
const HEAD = new Set([
  'base', 'basefont', 'bgsound', 'link', 'meta', 'title', 'noscript', 'noframes', 'style', 'script',
  'template',
]);
// Elements whose content is text, not tags (`noscript` as in a browser that runs scripts).
const RAW = new Set([
  'script', 'style', 'title', 'textarea', 'xmp', 'iframe', 'noembed', 'noframes', 'noscript', 'plaintext',
]);
// What may come before the policy: nothing it would govern.
const BEFORE_POLICY = new Set(['html', 'head', 'meta', 'title']);

const NAMED = { amp: '&', quot: '"', apos: "'", lt: '<', gt: '>' };

function decode(value) {
  return value.replace(/&(#x[0-9a-f]+|#[0-9]+|[a-z]+);/gi, (whole, ref) => {
    if (ref[0] === '#') {
      const code = ref[1] === 'x' || ref[1] === 'X' ? parseInt(ref.slice(2), 16) : parseInt(ref.slice(1), 10);
      return String.fromCodePoint(code);
    }
    return NAMED[ref.toLowerCase()] ?? whole;
  });
}

// A start tag at `at` (its `<`): its name, its attributes (the first of each name) and its end.
function startTag(text, at) {
  let i = at + 1;
  const name = text.slice(i).match(/^[^\s/>]+/)[0];
  i += name.length;
  const attributes = new Map();
  for (;;) {
    while (i < text.length && /[\s/]/.test(text[i])) i++;
    if (i >= text.length) break;
    if (text[i] === '>') {
      i++;
      break;
    }
    const attribute = text.slice(i).match(/^[^\s/>][^\s/>=]*/)[0];
    i += attribute.length;
    while (/\s/.test(text[i] ?? '')) i++;
    let value = '';
    if (text[i] === '=') {
      i++;
      while (/\s/.test(text[i] ?? '')) i++;
      const quote = text[i];
      if (quote === '"' || quote === "'") {
        const close = text.indexOf(quote, i + 1);
        const stop = close < 0 ? text.length : close;
        value = text.slice(i + 1, stop);
        i = stop + 1;
      } else {
        value = text.slice(i).match(/^[^\s>]*/)[0];
        i += value.length;
      }
    }
    const key = attribute.toLowerCase();
    if (!attributes.has(key)) attributes.set(key, decode(value));
  }
  return { name: name.toLowerCase(), attributes, end: i };
}

// The page's policies (every `<meta>` whose `http-equiv` is the policy's, active or not), each with
// the start tags before it, read from the page's HTML (its source after the front matter).
export function read(html) {
  const policies = [];
  const tags = [];
  let head = 'before'; // 'before' | 'in' | 'after'
  let templates = 0;
  let i = 0;
  while (i < html.length) {
    if (html.startsWith('<!--', i)) {
      const close = html.indexOf('-->', i + 4);
      i = close < 0 ? html.length : close + 3;
    } else if (html[i] === '<' && (html[i + 1] === '!' || html[i + 1] === '?')) {
      const close = html.indexOf('>', i);
      i = close < 0 ? html.length : close + 1;
    } else if (html[i] === '<' && html[i + 1] === '/' && /[a-z]/i.test(html[i + 2] ?? '')) {
      const name = html.slice(i + 2).match(/^[^\s/>]+/)[0].toLowerCase();
      if (name === 'template' && templates > 0) templates--;
      else if (name === 'head' && head === 'in' && templates === 0) head = 'after';
      const close = html.indexOf('>', i);
      i = close < 0 ? html.length : close + 1;
    } else if (html[i] === '<' && /[a-z]/i.test(html[i + 1] ?? '')) {
      const tag = startTag(html, i);
      if (tag.name === 'head' && head === 'before') head = 'in';
      else if (tag.name !== 'html' && head === 'before') head = HEAD.has(tag.name) ? 'in' : 'after';
      else if (tag.name !== 'html' && head === 'in' && templates === 0 && !HEAD.has(tag.name)) head = 'after';
      if (tag.name === 'meta' && tag.attributes.get('http-equiv')?.toLowerCase() === 'content-security-policy') {
        policies.push({
          content: tag.attributes.get('content') ?? null,
          active: head === 'in' && templates === 0,
          end: tag.end,
          before: [...tags],
        });
      }
      tags.push(tag.name);
      if (tag.name === 'template') templates++;
      i = tag.end;
      if (RAW.has(tag.name)) {
        const close = html.slice(i).search(new RegExp(`</${tag.name}[\\s/>]`, 'i'));
        i = close < 0 ? html.length : i + close;
      }
    } else {
      if (!/\s/.test(html[i]) && templates === 0 && head !== 'after') head = 'after';
      i++;
    }
  }
  return policies;
}

// What is wrong with a page's source against its pinned policy, as a list of reasons.
export function problems(source, expected) {
  const front = source.match(/^---\r?\n([\s\S]*?\r?\n)?---\r?\n/);
  if (!front) return ['no front matter: Jekyll would not build the page'];
  const found = [];
  if (!/^layout:[ \t]*null[ \t]*\r?$/m.test(front[1] ?? '')) found.push('front matter without `layout: null`');
  const html = source.slice(front[0].length);
  const policies = read(html);
  if (policies.length !== 1) found.push(`${policies.length} policies, not one`);
  if (policies.some((p) => !p.active)) found.push('a policy outside the head');
  const policy = policies.find((p) => p.active);
  if (!policy) return [...found, 'no active policy in the head'];
  if (policy.content !== expected) found.push(`the policy changed: ${JSON.stringify(policy.content)}`);
  const early = policy.before.filter((name) => !BEFORE_POLICY.has(name));
  if (early.length > 0) found.push(`before the policy: ${early.join(', ')}`);
  if (/\{[{%]/.test(html.slice(0, policy.end))) found.push('Liquid before or in the policy');
  return found;
}

for (const [page, expected] of Object.entries(POLICIES)) {
  test(`${page} sets exactly its pinned policy, once, in its head, before what it governs`, () => {
    assert.deepEqual(problems(readFileSync(new URL(page, ROOT), 'utf8'), expected), []);
  });
}

// Cases the checks must refuse, on a page built like the real ones.
const P = "default-src 'none'";
const page = (head, front = 'layout: null\n') =>
  `---\n${front}---\n<!DOCTYPE html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n${head}\n` +
  '<script src="a.js"></script>\n</head>\n<body><p>Text</p></body>\n</html>\n';
const csp = (content) => `<meta http-equiv="Content-Security-Policy" content="${content}">`;

test('the checks pass a page built as the real ones are', () => {
  assert.deepEqual(problems(page(csp(P)), P), []);
  // Case, quoting and character references, as a browser reads them.
  assert.deepEqual(problems(page(`<META HTTP-EQUIV=content-security-policy CONTENT="${P}">`), P), []);
  assert.deepEqual(problems(page(`<meta content='default-src "none"' http-equiv='CSP'>${csp(P)}`), P), []);
  assert.deepEqual(problems(page(csp('default-src &apos;none&#39;')), P), []);
});

// Each case with the reason it is refused for.
const refused = {
  'a changed policy': [page(csp("default-src 'none'; connect-src https:")), 'the policy changed'],
  'no policy': [page(''), 'no active policy'],
  'a second policy': [page(csp(P) + csp('script-src *')), '2 policies'],
  'the policy only in a comment': [page(`<!-- ${csp(P)} -->`), 'no active policy'],
  'the policy only inside a template': [page(`<template>${csp(P)}</template>`), 'outside the head'],
  'the policy only inside noscript': [page(`<noscript>${csp(P)}</noscript>`), 'no active policy'],
  'a decoy policy inside a template': [
    page(csp(P) + `<template>${csp('script-src *')}</template>`),
    'outside the head',
  ],
  'data-http-equiv for http-equiv': [
    page(`<meta data-http-equiv="Content-Security-Policy" content="${P}">`),
    'no active policy',
  ],
  'data-content beside a permissive content': [
    page(`<meta http-equiv="Content-Security-Policy" data-content="${P}" content="script-src *">`),
    'the policy changed',
  ],
  'a repeated content attribute, the first permissive': [
    page(`<meta http-equiv="Content-Security-Policy" content="script-src *" content="${P}">`),
    'the policy changed',
  ],
  'the policy after the head ended': [page(`</head><body>${csp(P)}`), 'outside the head'],
  'the policy after text ended the head': [page(`Text ${csp(P)}`), 'outside the head'],
  'the policy after a body element': [page(`<div></div>${csp(P)}`), 'outside the head'],
  'a script before the policy': [page(`<script src="x.js"></script>${csp(P)}`), 'before the policy: script'],
  'a style sheet before the policy': [
    page(`<link rel="stylesheet" href="x.css">${csp(P)}`),
    'before the policy: link',
  ],
  // Liquid that would drop the policy from the served page, where the HTML alone is sound.
  'Liquid that drops the policy': [
    page(`<meta name="a" content="{% if false %}">${csp(P)}<meta name="b" content="{% endif %}">`),
    'Liquid',
  ],
  'a Liquid comment around the policy': [
    page(`<!-- {% comment %} -->${csp(P)}<!-- {% endcomment %} -->`),
    'Liquid',
  ],
  'Liquid output in the policy': [page(csp('{{ page.policy }}')), 'Liquid'],
  'a layout': [page(csp(P), 'layout: default\n'), 'layout: null'],
  'no layout named': [page(csp(P), 'title: Keys\n'), 'layout: null'],
  'no front matter': [page(csp(P)).replace(/^---\n[\s\S]*?---\n/, ''), 'no front matter'],
};

for (const [name, [source, reason]] of Object.entries(refused)) {
  test(`the checks refuse ${name}`, () => {
    const found = problems(source, P);
    assert.ok(found.some((problem) => problem.includes(reason)), `${name}: ${JSON.stringify(found)}`);
  });
}

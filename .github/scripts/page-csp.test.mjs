// The Content Security Policy of the pages that handle keys (#1035): the API keys page
// (`api-keys.html`, #436) and the keyboard page (`keyboard.html`, #394). Each page sets its policy
// in a `<meta http-equiv>`, since the site (GitHub Pages) sends no headers of its own; the policy is
// what keeps the page from fetching or running anything but its own files. `ci.yml` runs this in
// every CI run. Node 22 or later: node --test .github/scripts/page-csp.test.mjs
//
// The test does not parse HTML. It pins each page's start, from the line after the front matter
// through the end of the policy's `<meta>` tag, byte for byte, so anything inserted, reordered,
// re-encoded or wrapped before or around the policy fails here, whatever a browser or Jekyll would
// make of it. Changing a page's head therefore means changing its pin here too, in `.github/`, for
// which `CODEOWNERS` names the owner (enforced only on pull requests into `main`, which gates
// bypass). A page must also name `Content-Security-Policy` only once, so a second policy later in the
// page fails; and its front matter must set `layout: null` in plain printable ASCII lines that a
// YAML reader cannot turn into anything else, so that no layout wraps the page when Jekyll builds it.
//
// No other file may be served in a page's place: no file beside it at the root under the same name
// (`api-keys.md` would build to `/api-keys.html`), and no front matter in the repository with a
// `permalink:` to it, or one this test cannot read plainly (quoted, escaped, in a flow mapping, split
// by a line break YAML knows and this test does not). `_posts/` documents and collections are among
// the files read. Not covered: site-wide build settings (`_config.yml`, a `.nojekyll`, the theme, a
// collection's permalink set there) and the files the policy admits (`'self'`).

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { closeSync, openSync, readFileSync, readSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const ROOT = new URL('../../', import.meta.url);

const policy = (content) => `<meta http-equiv="Content-Security-Policy" content="${content}">`;

// Each page's start, through its policy.
const STARTS = {
  'api-keys.html': [
    '<!DOCTYPE html>',
    '<html lang="en-US">',
    '<head>',
    '<meta charset="UTF-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    "<!-- API keys to a setup code (#436), for the app's Load keys, sealed with the five words the phone",
    "     shows (#590), in the documentation pages' type, header and footer (#764), without their intro.",
    '     The page connects to nothing: it fetches only its own scripts, style sheets and fonts, and the',
    "     policy below forbids any other request, so the browser's network panel shows that nothing is",
    "     sent. The policy admits no inline script or style, so the header's script and the pages' style",
    "     come as files (`assets/site/`), built from the same includes as the documentation pages'. The",
    "     keys and the words stay in the page's memory, never in storage, and go only into the QR code",
    '     drawn here. Light only, as the documentation pages. -->',
    policy(
      "default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self'; " +
        "connect-src 'none'; base-uri 'none'; form-action 'none'",
    ),
  ].join('\n'),
  'keyboard.html': [
    '<!doctype html>',
    '<html lang="en">',
    '<head>',
    '<meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    '<!-- Type on a computer (#394). The page connects to nothing: it fetches only its own script and',
    "     style sheet, and the policy below forbids any other request, so the browser's network panel",
    '     shows that nothing is sent. The text goes to the phone over Bluetooth alone, sealed with a key',
    "     made here and shown only in the QR code. Its front matter is there only for the site's footer",
    '     (`site-footer.html`, #1156), styled from `keyboard.css`, since the policy forbids inline styles. -->',
    policy(
      "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'none'; " +
        "base-uri 'none'; form-action 'none'",
    ),
  ].join('\n'),
};

// A front matter line: blank, a comment, or a plain key (lower case, unquoted), optionally as a list
// item, with a value or none. Anything else (a quoted or complex key, a merge key, a directive, a
// document marker, a block scalar's text, a tab) is refused rather than read.
const LINE = /^(?: *(?:#.*)?| *(?:- )?[a-z_][a-z0-9_]*:(?: .*)?)$/;

function frontMatter(yaml) {
  const found = [];
  // YAML also breaks lines at U+0085, U+2028 and U+2029, which a line here would run through.
  const odd = yaml.match(/[^\x20-\x7e\n]/);
  if (odd) {
    const code = odd[0].codePointAt(0).toString(16).padStart(4, '0');
    found.push(`front matter with U+${code}, outside printable ASCII`);
  }
  const lines = yaml.split('\n');
  for (const line of lines) {
    const shown = JSON.stringify(line);
    if (!LINE.test(line)) found.push(`front matter line not a plain key: ${shown}`);
    else if (line.includes('{')) found.push(`front matter with a flow mapping: ${shown}`);
    else if (/: *[&*!]/.test(line)) found.push(`front matter with an anchor, alias or tag: ${shown}`);
  }
  const layouts = lines.filter((line) => /^ *(?:- )?layout *:/.test(line));
  if (layouts.length !== 1 || layouts[0] !== 'layout: null') {
    found.push(`front matter's layout not exactly one \`layout: null\`: ${JSON.stringify(layouts)}`);
  }
  return found;
}

// What is wrong with a page's source against its pinned start, as a list of reasons.
export function problems(source, start) {
  const front = source.match(/^---\n([\s\S]*?\n)?---\n/);
  if (!front) return ['no front matter: Jekyll would not build the page'];
  const found = frontMatter((front[1] ?? '').replace(/\n$/, ''));
  const html = source.slice(front[0].length);
  const named = html.toLowerCase().split('content-security-policy').length - 1;
  if (named !== 1) found.push(`Content-Security-Policy named ${named} times, not once`);
  if (!html.startsWith(start)) found.push("the page's start, through its policy, is not the pinned one");
  return found;
}

const read = (page) => readFileSync(new URL(page, ROOT), 'utf8');

for (const [page, start] of Object.entries(STARTS)) {
  test(`${page} starts exactly as pinned, through its policy, and sets no other`, () => {
    assert.deepEqual(problems(read(page), start), []);
  });
}

// The paths the pages are served at, as Jekyll would write a permalink to them: without `.html`
// (`/api-keys` is written as `api-keys.html`), in lower case.
const SERVED = new Set(Object.keys(STARTS).map((page) => `/${page.replace(/\.html$/, '')}`));
const PERMALINK = /^permalink: (\/[A-Za-z0-9_.\/-]*)\r?$/;
const BREAKS = [0x85, 0x2028, 0x2029].map((code) => String.fromCodePoint(code));

// What is wrong with another file of the repository (its path, and the start of its text), as a
// list of reasons: a file Jekyll could serve in a page's place, or whose permalink cannot be read.
export function elsewhere(path, head) {
  const found = [];
  if (!path.includes('/') && SERVED.has(`/${path.toLowerCase().split('.')[0]}`) && !(path in STARTS)) {
    found.push(`${path}: beside a page under its name, which Jekyll could build in its place`);
  }
  const front = head.match(/^---[ \t]*\r?\n([\s\S]*?)^(?:---|\.\.\.)[ \t]*\r?$/m);
  if (!front || front.index !== 0) return found;
  const yaml = front[1];
  if (BREAKS.some((b) => yaml.includes(b))) found.push(`${path}: front matter with a line break YAML knows`);
  if (yaml.includes('\\')) found.push(`${path}: front matter with a backslash, which could escape a key`);
  for (const line of yaml.split('\n').filter((l) => /permalink/i.test(l))) {
    const plain = line.match(PERMALINK);
    if (!plain) {
      found.push(`${path}: a permalink not as \`permalink: /plain/path\`: ${JSON.stringify(line)}`);
    } else if (/\/\/|(^|\/)\.\.?(\/|$)/.test(plain[1])) {
      found.push(`${path}: a permalink with an empty, \`.\` or \`..\` segment: ${plain[1]}`);
    } else if (SERVED.has(plain[1].toLowerCase().replace(/\.html$/, ''))) {
      found.push(`${path}: a permalink to a page's path: ${plain[1]}`);
    }
  }
  return found;
}

// The start of a file: front matter is short, and fixtures are not.
function head(path) {
  const buffer = Buffer.alloc(65536);
  const fd = openSync(join(fileURLToPath(ROOT), path), 'r');
  try {
    return buffer.subarray(0, readSync(fd, buffer, 0, buffer.length, 0)).toString('utf8');
  } finally {
    closeSync(fd);
  }
}

test('no other file in the repository would be served in a page\'s place', () => {
  const files = execFileSync('git', ['ls-files', '-z'], { cwd: ROOT }).toString().split('\0').filter(Boolean);
  assert.ok(files.length > 100, `git ls-files listed ${files.length} files`);
  const found = files.filter((path) => !(path in STARTS)).flatMap((path) => elsewhere(path, head(path)));
  assert.deepEqual(found, []);
});

// Changes the checks must refuse, made to the real API keys page, each with the reason it is
// refused for. `before` puts text just before the policy, `around` wraps it.
const PAGE = read('api-keys.html');
const START = STARTS['api-keys.html'];
const POLICY = START.slice(START.lastIndexOf('\n') + 1);
const swap = (from, to) => {
  assert.equal(PAGE.split(from).length, 2, `once in the page: ${from}`);
  return PAGE.replace(from, to);
};
const before = (text) => swap(POLICY, text + POLICY);
const around = (open, close) => swap(POLICY, open + POLICY + close);
const layout = (lines) => swap('layout: null\n', lines);
const STARTED = 'is not the pinned one';
const LAYOUT = 'layout not exactly one';
const SCRIPT = '<script src="https://example.invalid/x.js"></script>';

const refused = {
  // The policy itself.
  'a changed policy': [swap("connect-src 'none'", 'connect-src https:'), STARTED],
  'no policy': [swap(POLICY, ''), STARTED],
  'a second policy later in the page': [swap('</head>', `${policy('script-src *')}\n</head>`), 'named 2 times'],
  'a pinned data-content beside a permissive content': [
    swap('Policy" content=', 'Policy" content="script-src *" data-content='),
    STARTED,
  ],
  'data-http-equiv for http-equiv': [swap('<meta http-equiv=', '<meta data-http-equiv='), STARTED],
  'a no-break space for a space in the tag': [
    swap('<meta http-equiv=', `<meta${String.fromCodePoint(0xa0)}http-equiv=`),
    STARTED,
  ],
  'a character reference in the policy': [swap("script-src 'self'", 'script-src &APOS;self&APOS;'), STARTED],
  // Where the policy is.
  'the policy only in a comment': [around('<!-- ', ' -->'), STARTED],
  'the policy inside a template': [around('<template>', '</template>'), STARTED],
  'the policy inside noscript': [around('<noscript>', '</noscript>'), STARTED],
  'the head ended before the policy': [before('</head><body>'), STARTED],
  'the body ended before the policy': [before('</body>'), STARTED],
  'text before the policy': [before('Text '), STARTED],
  'an element of the body before the policy': [before('<div></div>'), STARTED],
  'a script before the policy': [before(SCRIPT), STARTED],
  'a style sheet before the policy': [before('<link rel="stylesheet" href="x.css">'), STARTED],
  'a script after a comment closed by --!>': [before(`<!-- --!>${SCRIPT}<!-- -->`), STARTED],
  // Liquid, which Jekyll runs before Pages serves the page.
  'Liquid that drops the policy': [around('{% if false %}', '{% endif %}'), STARTED],
  'a Liquid comment around the policy': [around('<!-- {% comment %} -->', '<!-- {% endcomment %} -->'), STARTED],
  'Liquid output in the policy': [swap("default-src 'none'", '{{ page.policy }}'), STARTED],
  // The front matter.
  'another layout': [layout('layout: default\n'), LAYOUT],
  'no layout named': [layout(''), LAYOUT],
  '`layout: null` and then `layout: default`': [layout('layout: null\nlayout: default\n'), LAYOUT],
  'a quoted layout key': [layout('layout: null\n"layout": default\n'), 'not a plain key'],
  'an escaped layout key': [layout('layout: null\n"lay\\u006fut": default\n'), 'not a plain key'],
  'a merge key': [layout('layout: null\nbase: &base\n  layout: default\n<<: *base\n'), 'anchor'],
  'an alias': [layout('layout: null\nother: *base\n'), 'anchor'],
  'a flow mapping': [layout('layout: null\nmore: {layout: default}\n'), 'flow mapping'],
  'a document marker': [layout('layout: null\n...\n'), 'not a plain key'],
  'a tab before a key': [layout('layout: null\n\tlayout: default\n'), 'not a plain key'],
  // Line breaks YAML (Psych, which Jekyll uses) knows and a line here would run through.
  'a NEL before a second layout': [
    swap('docs_version: 0.6.5\n', `docs_version: 0.6.5${String.fromCodePoint(0x85)}layout: default\n`),
    'outside printable ASCII',
  ],
  'a line separator before a second layout': [
    swap('docs_version: 0.6.5\n', `docs_version: 0.6.5${String.fromCodePoint(0x2028)}layout: default\n`),
    'outside printable ASCII',
  ],
  'no front matter': [PAGE.replace(/^---\n(?:.*\n)*?---\n/, ''), 'no front matter'],
};

for (const [name, [source, reason]] of Object.entries(refused)) {
  test(`the checks refuse ${name}`, () => {
    const found = problems(source, START);
    assert.ok(found.some((problem) => problem.includes(reason)), `${name}: ${JSON.stringify(found)}`);
  });
}

// Other files that the checks must refuse, each with its path, its text and the reason.
const withFront = (...lines) => ['---', ...lines, '---', 'Text', ''].join('\n');
const refusedElsewhere = {
  'a Markdown page beside the API keys page': ['api-keys.md', 'Text\n', 'beside a page'],
  'a page beside the keyboard page, in another case': ['Keyboard.markdown', withFront('title: K'), 'beside a page'],
  'a permalink to the API keys page': ['docs/x/index.md', withFront('permalink: /api-keys.html'), "a page's path"],
  'a permalink to the keyboard page without `.html`': ['docs/x.md', withFront('permalink: /keyboard'), "a page's path"],
  'a permalink to the API keys page in another case': ['x.md', withFront('permalink: /API-Keys.html'), "a page's path"],
  'a post with a permalink to the API keys page': [
    '_posts/2026-01-01-x.md',
    withFront('title: X', 'permalink: /api-keys.html'),
    "a page's path",
  ],
  'a quoted permalink': ['x.md', withFront('permalink: "/api-keys.html"'), 'not as'],
  'a permalink in a flow mapping': ['x.md', withFront('defaults: {permalink: /api-keys.html}'), 'not as'],
  'a permalink under another key': ['x.md', withFront('base:', '  permalink: /api-keys.html'), 'not as'],
  'a permalink through `..`': ['x.md', withFront('permalink: /x/../api-keys.html'), '`..`'],
  'a permalink through an empty segment': ['x.md', withFront('permalink: //api-keys.html'), 'empty'],
  'a permalink after a NEL': [
    'x.md',
    withFront(`title: X${String.fromCodePoint(0x85)}permalink: /api-keys.html`),
    'line break',
  ],
  'a permalink after a paragraph separator': [
    'x.md',
    withFront(`title: X${String.fromCodePoint(0x2029)}permalink: /api-keys.html`),
    'line break',
  ],
  'an escaped permalink key': ['x.md', withFront('"perma' + '\\' + 'u006cink": /api-keys.html'), 'backslash'],
};

for (const [name, [path, text, reason]] of Object.entries(refusedElsewhere)) {
  test(`the checks refuse ${name}`, () => {
    const found = elsewhere(path, text);
    assert.ok(found.some((problem) => problem.includes(reason)), `${name}: ${JSON.stringify(found)}`);
  });
}

test('the checks pass other files as the site has them', () => {
  assert.deepEqual(elsewhere('privacy.md', withFront('title: Privacy', 'permalink: /privacy.html')), []);
  assert.deepEqual(elsewhere('docs/x/index.md', withFront('permalink: /docs/x/')), []);
  assert.deepEqual(elsewhere('assets/api-keys/api-keys.js', 'export {};\n'), []);
});

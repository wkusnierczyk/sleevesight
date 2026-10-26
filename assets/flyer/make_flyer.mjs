// Renders the tester flyer (#566) from its source, `tester-flyer.html`, beside this script: draws its
// two QR codes with the site's own encoder (`assets/keyboard/qr.js`), then prints the page with
// headless Chrome to `tester-flyer.pdf` (A4) and screenshots it to `tester-flyer.png` (A4 at 192 dpi,
// 1588 x 2246 px). Node 22 or later, and Google Chrome or Chromium; from the repository root:
//
//   node assets/flyer/make_flyer.mjs
//
// `CHROME` names the browser if it is not at macOS's usual place. The PDF carries its date of
// making, so a rerun changes its bytes even when the page has not changed; the PNG may differ a
// little with another version of Chrome.

import { execFileSync } from 'node:child_process';
import { readFileSync, rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { encode } from '../keyboard/qr.js';

const here = (name) => fileURLToPath(new URL(name, import.meta.url));

// The closed test's opt-in page on Google Play, and the tester brief.
const CODES = {
  optin: 'https://play.google.com/apps/testing/app.sleevesight',
  brief: 'https://sleevesight.app/docs/tester/alpha/',
};

const QUIET_ZONE = 4;

/** The QR code of [text] as SVG markup, dark on white with the standard quiet zone. */
function qrSvg(text, label) {
  const modules = encode(text);
  const side = modules.length + 2 * QUIET_ZONE;
  let d = '';
  modules.forEach((row, y) =>
    row.forEach((dark, x) => {
      if (dark) d += `M${x + QUIET_ZONE} ${y + QUIET_ZONE}h1v1h-1z`;
    }),
  );
  return `<svg viewBox="0 0 ${side} ${side}" role="img" aria-label="${label}" shape-rendering="crispEdges">` +
    `<rect width="${side}" height="${side}" fill="#fff"/><path d="${d}" fill="#000"/></svg>`;
}

let page = readFileSync(here('tester-flyer.html'), 'utf8');
for (const [name, url] of Object.entries(CODES)) {
  const mark = `<!--qr:${name}-->`;
  if (!page.includes(mark)) throw new Error(`tester-flyer.html has no ${mark}`);
  page = page.replace(mark, qrSvg(url, `QR code of ${url}`));
}

// Beside the source, so the fonts and the logo resolve as they do from it.
const built = here('.tester-flyer.build.html');
writeFileSync(built, page);
const chrome = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const common = ['--headless', '--disable-gpu', '--hide-scrollbars', '--virtual-time-budget=5000', '--no-first-run'];
try {
  execFileSync(chrome, [...common, '--no-pdf-header-footer', `--print-to-pdf=${here('tester-flyer.pdf')}`,
    pathToFileURL(built).href], { stdio: 'inherit' });
  execFileSync(chrome, [...common, '--window-size=794,1123', '--force-device-scale-factor=2',
    `--screenshot=${here('tester-flyer.png')}`, pathToFileURL(built).href], { stdio: 'inherit' });
} finally {
  rmSync(built, { force: true });
}

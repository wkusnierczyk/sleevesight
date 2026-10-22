// Writes the vectors the app's tests read (`KeyboardSessionTest`, `KeyboardQrTest`), made by the
// page's own code, so the two sides are checked against each other: a session from a fixed id and
// key, messages it sealed, and the QR code of its code. Node 22 or later:
//
//   node assets/keyboard/make_vectors.mjs           writes the file
//   node assets/keyboard/make_vectors.mjs --check   fails if the file differs (CI)

import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { hex, seal, sessionOf } from './protocol.js';
import { encode } from './qr.js';

const OUT = fileURLToPath(new URL('../../app/src/test/resources/keyboard-vectors.properties', import.meta.url));

const id = Uint8Array.from({ length: 8 }, (_, i) => 0xa0 + i);
const key = Uint8Array.from({ length: 16 }, (_, i) => i * 17);
const session = sessionOf(id, key);
const texts = ['', 'sk-ant-api03-example', 'Zoë, 日本語 and 🎵', 'x'.repeat(480)];

const lines = [
  '# Made by assets/keyboard/make_vectors.mjs from the page\'s own code (#394); do not edit.',
  `id=${hex(id)}`,
  `key=${hex(key)}`,
  `code=${session.code}`,
  `service=${session.service}`,
  `messages=${texts.length}`,
];
for (const [i, text] of texts.entries()) {
  lines.push(`message.${i}.counter=${i + 1}`);
  lines.push(`message.${i}.text=${hex(new TextEncoder().encode(text))}`);
  lines.push(`message.${i}.sealed=${hex(await seal(session, i + 1, text))}`);
}
lines.push(`qr=${encode(session.code).map((row) => row.map((d) => (d ? '1' : '0')).join('')).join(',')}`);
const content = `${lines.join('\n')}\n`;

if (process.argv.includes('--check')) {
  if (readFileSync(OUT, 'utf8') !== content) {
    console.error(`${OUT} is not what the page's code makes: run node assets/keyboard/make_vectors.mjs`);
    process.exit(1);
  }
} else {
  writeFileSync(OUT, content);
}

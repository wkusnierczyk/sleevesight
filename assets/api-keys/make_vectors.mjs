// Writes the vectors the app's tests read (`ApiKeysPageTest`), made by the page's own code, so the
// two sides are checked against each other: setup codes and their QR codes, which `KeySetup` and
// ZXing must read back, and the shape of keys, on which `KeyShape` must agree. Node 22 or later:
//
//   node assets/api-keys/make_vectors.mjs           writes the file
//   node assets/api-keys/make_vectors.mjs --check   fails if the file differs (CI)

import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { encode } from '../keyboard/qr.js';
import { check, write } from './keys.js';

const OUT = fileURLToPath(new URL('../../app/src/test/resources/api-keys-vectors.properties', import.meta.url));

const hex = (text) => Buffer.from(text, 'utf8').toString('hex');
// A key of [length] characters after [prefix], from a fixed alphabet, so the vectors do not change.
const made = (prefix, length, alphabet = 'AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-') =>
  prefix + Array.from({ length: length - prefix.length }, (_, i) => alphabet[(i * 7 + 3) % alphabet.length]).join('');

const anthropic = made('sk-ant-api03-', 108);
const openai = made('sk-proj-', 164);
const google = made('AIza', 39);
const exa = made('', 36, '0123456789abcdef-');
const lastfm = made('', 32, '0123456789abcdef');

// Setup codes from one key to every key at its longest form, so that the QR codes span small and
// large versions: the byte count's 16 bits from version 10, the version information from 7, and
// blocks of two lengths.
const setups = [
  new Map([['anthropic', anthropic]]),
  new Map([['anthropic', anthropic], ['exa', exa]]),
  new Map([['anthropic', anthropic], ['openai', openai], ['google', google], ['exa', exa], ['lastfm', lastfm]]),
  new Map([
    ['anthropic', made('sk-ant-', 400)],
    ['openai', made('sk-', 400)],
    ['google', made('AQ.', 400, 'AbCdEfGh.0123456789_-')],
    ['exa', made('', 200)],
    ['lastfm', lastfm],
  ]),
];

const shapes = [
  ['anthropic', anthropic], ['anthropic', ''], ['anthropic', 'sk-ant-short'], ['anthropic', made('sk-ant-', 401)],
  ['anthropic', made('sk-ant-', 40)], ['anthropic', `${anthropic.slice(0, 50)} ${anthropic.slice(51)}`],
  ['anthropic', openai], ['anthropic', `${anthropic}\n`],
  ['openai', openai], ['openai', anthropic], ['openai', made('sk-', 40)], ['openai', made('sk-', 39)],
  ['openai', made('sk-svcacct-', 120)], ['openai', made('pk-', 60)],
  ['google', google], ['google', made('AQ.', 60, 'Ab.0_-')], ['google', made('AIza', 29)], ['google', made('AIzb', 39)],
  ['google', `${google.slice(0, 20)}.${google.slice(21)}`], ['google', made('aiza', 39)],
  ['exa', exa], ['exa', made('', 15)], ['exa', made('', 16)], ['exa', made('', 200)], ['exa', made('', 201)],
  ['exa', 'abc.defghijklmnopq'],
  ['lastfm', lastfm], ['lastfm', lastfm.toUpperCase()], ['lastfm', lastfm.slice(1)], ['lastfm', `${lastfm.slice(1)}g`],
  ['lastfm', `${lastfm}0`],
];

const lines = ['# Made by assets/api-keys/make_vectors.mjs from the page\'s own code (#436); do not edit.'];
lines.push(`setups=${setups.length}`);
for (const [i, keys] of setups.entries()) {
  const text = write(keys);
  const modules = encode(text);
  lines.push(`setup.${i}.text=${hex(text)}`);
  lines.push(`setup.${i}.keys=${[...keys].map(([name, value]) => `${name}:${hex(value)}`).join(',')}`);
  lines.push(`setup.${i}.version=${(modules.length - 17) / 4}`);
  lines.push(`setup.${i}.qr=${modules.map((row) => row.map((d) => (d ? '1' : '0')).join('')).join(',')}`);
}
lines.push(`shapes=${shapes.length}`);
for (const [i, [name, value]] of shapes.entries()) {
  lines.push(`shape.${i}.name=${name}`);
  lines.push(`shape.${i}.value=${hex(value)}`);
  lines.push(`shape.${i}.problem=${check(name, value) ?? 'NONE'}`);
}
const content = `${lines.join('\n')}\n`;

if (process.argv.includes('--check')) {
  if (readFileSync(OUT, 'utf8') !== content) {
    console.error(`${OUT} is not what the page's code makes: run node assets/api-keys/make_vectors.mjs`);
    process.exit(1);
  }
} else {
  writeFileSync(OUT, content);
}

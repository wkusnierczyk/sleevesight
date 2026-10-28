// Writes the vectors the app's tests read (`ApiKeysPageTest`), made by the page's own code, so the
// two sides are checked against each other: setup codes and their QR codes, which `KeySetup` and
// ZXing must read back, and the shape of keys, on which `KeyShape` must agree. The keys file's names
// and what the page reads from a file are checked against the CLI's `Keys` (`KeysTest`). The sealed
// codes (#590) from fixed words, salts and nonces, which `SealedSetup` must make and open alike;
// words as typed, which `KeyWords` must complete alike; and the word list's SHA-256, which must be
// that of the app's copy. Node 22 or later:
//
//   node assets/api-keys/make_vectors.mjs           writes the file
//   node assets/api-keys/make_vectors.mjs --check   fails if the file differs (CI)

import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { encode } from '../keyboard/qr.js';
import { SERVICES, check, readFile, write } from './keys.js';
import { ITERATIONS, WORDS, readWords, seal } from './seal.js';
import { WORDLIST } from './words.js';

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
  // Each length bound, either side: n - 1 refused, n taken, the longest taken, one more refused.
  ...[
    ['anthropic', 'sk-ant-', 40, 400],
    ['openai', 'sk-', 40, 400],
    ['google', 'AIza', 30, 400],
    ['google', 'AQ.', 30, 400],
    ['exa', '', 16, 200],
  ].flatMap(([name, prefix, shortest, longest]) =>
    [shortest - 1, shortest, longest, longest + 1].map((length) => [name, made(prefix, length)]),
  ),
];

// Keys files, as `KeysTest` checks the CLI reads them too: every name of every service, the first
// name with a value winning whatever the order of the lines, and an empty value no key. No name is
// given twice here, and no code name (`anthropic=…`): on a name given twice, or a code name beside the
// `SLEEVESIGHT_` name, the page refuses the key and the CLI takes the last value of its own names.
const files = [
  SERVICES.map(({ code, variables }) => `${variables[0]}=${code}-prefixed`),
  SERVICES.map(({ code, variables }) => `${variables[1]}=${code}-usual`),
  ['GOOGLE_API_KEY=google-second', 'export LASTFM_API_KEY="lastfm-quoted"'],
  SERVICES.flatMap(({ code, variables }) => [...variables].reverse().map((name, i) => `${name}=${code}-${i}`)),
  ['GOOGLE_API_KEY=google-second', 'GEMINI_API_KEY=google-first'],
  ['SLEEVESIGHT_EXA_API_KEY=', 'EXA_API_KEY=exa-usual', "ANTHROPIC_API_KEY=''", 'OPENAI_API_KEY=openai-usual'],
].map((file) => `${file.join('\n')}\n`);

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
for (const { code, variables } of SERVICES) lines.push(`names.${code}=${variables.join(',')}`);
lines.push(`files=${files.length}`);
for (const [i, text] of files.entries()) {
  const { keys } = readFile(text);
  lines.push(`file.${i}.text=${hex(text)}`);
  lines.push(`file.${i}.keys=${[...keys].map(([name, value]) => `${name}:${hex(value)}`).join(',')}`);
}

// Sealed codes: each from its words, salt and nonce, the rounds the page uses, and a setup code from
// the ones above, the largest among them (version 36 or so), so its QR code is read back too.
const bytesOf = (length, start, step) => Uint8Array.from({ length }, (_, i) => (start + i * step) & 0xff);
const sealings = [
  { words: ['aardvark', 'abandoned', 'abbreviate', 'abdomen', 'abhorrence'], setup: write(setups[0]) },
  { words: WORDS.slice(-5), setup: write(setups[2]) },
  { words: [WORDS[100], 'yo-yo', WORDS[700], WORDS[3], WORDS[1295]], setup: write(setups[3]) },
];
lines.push(`words.count=${WORDS.length}`);
lines.push(`words.sha256=${createHash('sha256').update(WORDLIST, 'utf8').digest('hex')}`);
lines.push(`sealed=${sealings.length}`);
for (const [i, { words, setup }] of sealings.entries()) {
  const salt = bytesOf(16, 0x10 * (i + 1), 7);
  const nonce = bytesOf(12, 0xc0 + i, 13);
  const code = await seal(setup, words, salt, nonce, ITERATIONS);
  lines.push(`sealed.${i}.words=${words.join(' ')}`);
  lines.push(`sealed.${i}.salt=${Buffer.from(salt).toString('hex')}`);
  lines.push(`sealed.${i}.nonce=${Buffer.from(nonce).toString('hex')}`);
  lines.push(`sealed.${i}.iterations=${ITERATIONS}`);
  lines.push(`sealed.${i}.text=${hex(setup)}`);
  lines.push(`sealed.${i}.code=${code}`);
  if (i === sealings.length - 1) {
    const modules = encode(code);
    lines.push(`sealed.${i}.version=${(modules.length - 17) / 4}`);
    lines.push(`sealed.${i}.qr=${modules.map((row) => row.map((d) => (d ? '1' : '0')).join('')).join(',')}`);
  }
}

// Words as typed on the page: three letters or more of each, any case, spaces (Unicode's too), line
// breaks or commas between; a word typed wrong after its three letters, too short, or not in the
// list. The CLI splits and trims as the page does, which `KeyWords` is checked against.
const typed = [
  'aar aba abb abd abh',
  'AARDVARK, Abandoned,abbreviate\n  abdomen\tabhorrence ',
  'aardvarx aba',
  'aa ab',
  'yo- yo-yo yoy zuc zucchinis',
  'xyz qqq',
  'acid acidic acI',
  '',
  // Unicode spaces, as JavaScript's \s has them: no-break, ideographic, U+FEFF, line separator.
  '\u00a0aar\u00a0aba\u3000abb\ufeffabd\u2028abh\u00a0',
];
lines.push(`typed=${typed.length}`);
for (const [i, text] of typed.entries()) {
  lines.push(`typed.${i}.text=${hex(text)}`);
  lines.push(`typed.${i}.words=${readWords(text).map((w) => w ?? '-').join(',')}`);
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

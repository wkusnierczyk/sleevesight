// The sealed setup code (#590), as the app opens it (`SealedSetup` and `KeyWords` in `:core`): the
// setup code's text (`keys.js`) sealed with a key derived from the five words the phone's
// *Load keys* shows, so that a photo, a screenshot or a recording of the QR code gives nothing
// without them. WebCrypto only, so nothing is fetched; no DOM here, so Node runs it too, and the
// app's tests check the two sides agree, from vectors this code makes (`make_vectors.mjs`).
//
// The code: `sleevesight-keys:v2:` and base64url, without padding, of
// - the PBKDF2 rounds, 4 bytes, big-endian;
// - a random salt, 16 bytes;
// - a random nonce, 12 bytes;
// - the setup code's text as UTF-8, sealed with AES-256-GCM under the key, the tag 16 bytes, the
//   associated data the prefix.
// The key: PBKDF2-HMAC-SHA256 of the words, in order, in lower case, separated by single spaces, as
// UTF-8, with the salt, 32 bytes.

import { PREFIX as SETUP_PREFIX } from './keys.js';
import { WORDLIST } from './words.js';

export const PREFIX = `${SETUP_PREFIX}v2:`;
/** About a second on a low-end phone, which derives the key again to open the code. */
export const ITERATIONS = 600000;
export const SALT_BYTES = 16;
export const NONCE_BYTES = 12;
/** How many words the phone shows, and how many of a word's first letters are its own. */
export const COUNT = 5;
export const PREFIX_LETTERS = 3;

const encoder = new TextEncoder();

/** The list's words, in its order: the word after the tab on each line. */
export const WORDS = WORDLIST.split('\n').filter((line) => line.trim() !== '').map((line) => line.split('\t')[1].trim());

const byPrefix = new Map(WORDS.map((word) => [word.slice(0, PREFIX_LETTERS), word]));
if (WORDS.length !== 1296 || byPrefix.size !== WORDS.length) throw new Error('not the EFF short wordlist 2');

/**
 * The word of the list [typed] is, or null: [typed], trimmed and in lower case, holds at least the
 * word's first three letters and nothing that is not the word's.
 */
export function complete(typed) {
  const token = typed.trim().toLowerCase();
  if (token.length < PREFIX_LETTERS) return null;
  const word = byPrefix.get(token.slice(0, PREFIX_LETTERS));
  return word !== undefined && word.startsWith(token) ? word : null;
}

/** The words typed in [text], split at spaces, line breaks and commas: each word, or null where one is none. */
export function readWords(text) {
  return tokens(text).map(complete);
}

/** The pieces of [text] that [readWords] reads, as typed. */
export function tokens(text) {
  return text.split(/[\s,]+/).filter((t) => t !== '');
}

/** The password [words] give. */
export function password(words) {
  return encoder.encode(words.map((w) => w.toLowerCase()).join(' '));
}

/**
 * The sealed code of [setup], a setup code's text, under [words], with [salt] and [nonce] (random
 * ones, unless the vectors give them) and [iterations] rounds.
 */
export async function seal(setup, words, salt = random(SALT_BYTES), nonce = random(NONCE_BYTES), iterations = ITERATIONS) {
  if (salt.length !== SALT_BYTES || nonce.length !== NONCE_BYTES) throw new RangeError('salt or nonce');
  const base = await crypto.subtle.importKey('raw', password(words), 'PBKDF2', false, ['deriveKey']);
  const key = await crypto.subtle.deriveKey(
    { name: 'PBKDF2', hash: 'SHA-256', salt, iterations },
    base,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt'],
  );
  const sealed = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv: nonce, additionalData: encoder.encode(PREFIX), tagLength: 128 },
      key,
      encoder.encode(setup),
    ),
  );
  const bytes = new Uint8Array(4 + SALT_BYTES + NONCE_BYTES + sealed.length);
  new DataView(bytes.buffer).setUint32(0, iterations);
  bytes.set(salt, 4);
  bytes.set(nonce, 4 + SALT_BYTES);
  bytes.set(sealed, 4 + SALT_BYTES + NONCE_BYTES);
  return PREFIX + base64url(bytes);
}

function random(size) {
  return crypto.getRandomValues(new Uint8Array(size));
}

function base64url(bytes) {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// The keyboard's protocol (#394), shared by the page and the vector maker (`make_vectors.mjs`);
// the app reads it in `KeyboardSession.kt`. Nothing here touches the page.
//
// - The session code, shown as a QR code: `sleevesight-keyboard:v1:` and base64url, without
//   padding, of the session id (8 bytes) and the AES key (16 bytes).
// - The phone's Bluetooth service: `5d3e9a7c-1f42-4b8e-` and the id in hexadecimal, so the browser's
//   chooser lists that phone alone. One writable characteristic, the same in every session.
// - A message, the whole text at each change: a counter (4 bytes, big-endian, from 1), then the
//   text's UTF-8 sealed with AES-GCM under the key, the nonce the id and the counter, the associated
//   data the code's prefix and the id. The phone takes a message only if its counter is above the
//   last one it took.

export const PREFIX = 'sleevesight-keyboard:v1:';
export const ID_BYTES = 8;
export const KEY_BYTES = 16;
export const SERVICE_PREFIX = '5d3e9a7c-1f42-4b8e-';
export const CHARACTERISTIC = '5d3e9a7c-1f42-4b8e-a6d1-3c0b7e52f901';
// One write carries at most 512 bytes; the counter and the tag take 20 of them.
export const MAX_TEXT_BYTES = 480;
export const MAX_COUNTER = 0xffffffff;

const encoder = new TextEncoder();

/** A new session: a random id and key. */
export function newSession() {
  const bytes = crypto.getRandomValues(new Uint8Array(ID_BYTES + KEY_BYTES));
  return sessionOf(bytes.slice(0, ID_BYTES), bytes.slice(ID_BYTES));
}

/** The session with this id and key: its code, its service, and what seals its messages. */
export function sessionOf(id, key) {
  const both = new Uint8Array(ID_BYTES + KEY_BYTES);
  both.set(id);
  both.set(key, ID_BYTES);
  const aad = new Uint8Array(PREFIX.length + ID_BYTES);
  aad.set(encoder.encode(PREFIX));
  aad.set(id, PREFIX.length);
  return { id, key, code: PREFIX + base64url(both), service: serviceOf(id), aad };
}

/** The service UUID of the session [id]. */
export function serviceOf(id) {
  const h = hex(id);
  return `${SERVICE_PREFIX}${h.slice(0, 4)}-${h.slice(4)}`;
}

/** The UTF-8 length of [text], which a message may hold if it is at most [MAX_TEXT_BYTES]. */
export function textBytes(text) {
  return encoder.encode(text).length;
}

/** The message carrying [text] as number [counter] of the [session]. */
export async function seal(session, counter, text) {
  const plain = encoder.encode(text);
  if (plain.length > MAX_TEXT_BYTES) throw new RangeError('text too long');
  if (!Number.isInteger(counter) || counter < 1 || counter > MAX_COUNTER) throw new RangeError('counter');
  const head = new Uint8Array(4);
  new DataView(head.buffer).setUint32(0, counter);
  const nonce = new Uint8Array(12);
  nonce.set(session.id);
  nonce.set(head, ID_BYTES);
  const key = await crypto.subtle.importKey('raw', session.key, 'AES-GCM', false, ['encrypt']);
  const sealed = new Uint8Array(
    await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce, additionalData: session.aad, tagLength: 128 }, key, plain),
  );
  const message = new Uint8Array(head.length + sealed.length);
  message.set(head);
  message.set(sealed, head.length);
  return message;
}

export function hex(bytes) {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

function base64url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

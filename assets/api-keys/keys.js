// The setup code of API keys (#436) as the app reads it: its services, each key's shape, and the
// code's text, ported from `KeySetup` and `KeyShape` in `:core`, and the keys file as the CLI reads
// it (`Keys.parse`). The app's tests check the two sides agree, from vectors this code makes
// (`make_vectors.mjs`). No DOM here, so Node runs it too.

export const PREFIX = 'sleevesight-keys:';
export const VERSION = 'v1';

/** The services a setup code may carry, in `KeyName`'s order: its name in the code, and in the keys file. */
export const SERVICES = [
  { code: 'anthropic', label: 'Anthropic', variable: 'SLEEVESIGHT_ANTHROPIC_API_KEY' },
  { code: 'openai', label: 'OpenAI', variable: 'SLEEVESIGHT_OPENAI_API_KEY' },
  { code: 'google', label: 'Google', variable: 'SLEEVESIGHT_GOOGLE_API_KEY' },
  { code: 'exa', label: 'Exa', variable: 'SLEEVESIGHT_EXA_API_KEY' },
  { code: 'lastfm', label: 'Last.fm', variable: 'SLEEVESIGHT_LASTFM_API_KEY' },
];

/** Why a key is refused, by `KeyProblem`'s names. */
export const EMPTY = 'EMPTY';
export const BAD_CHARACTERS = 'BAD_CHARACTERS';
export const WRONG_SHAPE = 'WRONG_SHAPE';
export const DUPLICATE = 'DUPLICATE';

/** The longest unknown name shown as given; a longer one is cut, in case it is a key itself. */
export const SHOWN_NAME = 24;

const TOKEN = /^[A-Za-z0-9_-]+$/;
const GOOGLE_TOKEN = /^[A-Za-z0-9._-]+$/;
const HEX_32 = /^[0-9a-fA-F]{32}$/;
const within = (value, shortest, longest) => value.length >= shortest && value.length <= longest;

/** What is wrong with [value] as the key of the service named [code], or null when it has the key's shape. */
export function check(code, value) {
  if (value.length === 0) return EMPTY;
  if (!(code === 'google' ? GOOGLE_TOKEN : TOKEN).test(value)) return BAD_CHARACTERS;
  let shaped;
  switch (code) {
    case 'anthropic':
      shaped = value.startsWith('sk-ant-') && within(value, 40, 400);
      break;
    case 'openai':
      shaped = value.startsWith('sk-') && !value.startsWith('sk-ant-') && within(value, 40, 400);
      break;
    case 'google':
      shaped = (value.startsWith('AIza') || value.startsWith('AQ.')) && within(value, 30, 400);
      break;
    case 'exa':
      shaped = within(value, 16, 200);
      break;
    case 'lastfm':
      shaped = HEX_32.test(value);
      break;
    default:
      throw new RangeError(`no service ${code}`);
  }
  return shaped ? null : WRONG_SHAPE;
}

/** The setup code's text for [keys], a map from a service's code to its key, in the services' order. */
export function write(keys) {
  let text = PREFIX + VERSION;
  for (const { code } of SERVICES) if (keys.has(code)) text += `\n${code}=${keys.get(code)}`;
  return text;
}

/**
 * The keys in [text], a keys file: `NAME=value` lines, `export` and quotes allowed, comments and
 * blank lines skipped, as the CLI reads it. A key is named by its variable or by its name in the
 * code. Gives the [keys] found, by service code, the services named more than once ([repeated]),
 * which none of their values is taken from, the [unknown] names, each cut to [SHOWN_NAME], and how
 * many lines were not `NAME=value` ([unreadable]).
 */
export function readFile(text) {
  const keys = new Map();
  const counts = new Map();
  const unknown = [];
  let unreadable = 0;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim().replace(/^export /, '').trim();
    if (line === '' || line.startsWith('#')) continue;
    const at = line.indexOf('=');
    const name = at < 0 ? '' : line.slice(0, at).trim();
    if (name === '') {
      unreadable++;
      continue;
    }
    const value = unquoted(line.slice(at + 1).trim());
    const service = SERVICES.find((s) => s.variable === name || s.code === name);
    if (service === undefined) {
      unknown.push(name.slice(0, SHOWN_NAME));
    } else {
      keys.set(service.code, value);
      counts.set(service.code, (counts.get(service.code) ?? 0) + 1);
    }
  }
  const repeated = [...counts].filter(([, n]) => n > 1).map(([code]) => code);
  for (const code of repeated) keys.delete(code);
  return { keys, repeated, unknown, unreadable };
}

// Kotlin's `removeSurrounding` for double quotes, then single.
function unquoted(value) {
  const strip = (v, q) => (v.length >= 2 && v.startsWith(q) && v.endsWith(q) ? v.slice(1, -1) : v);
  return strip(strip(value, '"'), "'");
}

/** The last four characters of [key], by which the page and the app name it. */
export function ending(key) {
  return key.slice(-4);
}

// The setup code of API keys (#436) as the app reads it: its services, each key's shape, and the
// code's text, ported from `KeySetup` and `KeyShape` in `:core`, and the keys file as the CLI reads
// it (`Keys.parse`). The app's tests check the two sides agree, from vectors this code makes
// (`make_vectors.mjs`). No DOM here, so Node runs it too.

export const PREFIX = 'sleevesight-keys:';
export const VERSION = 'v1';

/**
 * The services a setup code may carry, in `KeyName`'s order: its name in the code, and its names in
 * the keys file, the `SLEEVESIGHT_` one first and then the provider's usual ones; the first one with a
 * value wins. The CLI reads the same names (`Keys.FILE_NAMES`), checked through the vectors.
 */
export const SERVICES = [
  { code: 'anthropic', label: 'Anthropic', variables: ['SLEEVESIGHT_ANTHROPIC_API_KEY', 'ANTHROPIC_API_KEY'] },
  { code: 'openai', label: 'OpenAI', variables: ['SLEEVESIGHT_OPENAI_API_KEY', 'OPENAI_API_KEY'] },
  { code: 'google', label: 'Google', variables: ['SLEEVESIGHT_GOOGLE_API_KEY', 'GEMINI_API_KEY', 'GOOGLE_API_KEY'] },
  { code: 'exa', label: 'Exa', variables: ['SLEEVESIGHT_EXA_API_KEY', 'EXA_API_KEY'] },
  { code: 'lastfm', label: 'Last.fm', variables: ['SLEEVESIGHT_LASTFM_API_KEY', 'LASTFM_API_KEY'] },
];

/** Why a key is refused, by `KeyProblem`'s names. */
export const EMPTY = 'EMPTY';
export const BAD_CHARACTERS = 'BAD_CHARACTERS';
export const WRONG_SHAPE = 'WRONG_SHAPE';
export const DUPLICATE = 'DUPLICATE';

/** The longest unknown name shown as given; a longer one is cut. */
export const SHOWN_NAME = 40;

// What a variable's name looks like; anything else before an `=` is a line not read.
const NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;

// Unknown names are shown only when they start `SLEEVESIGHT_` (a key's name mistyped) or end
// `_API_KEY` (a key named as another tool has it). Others are only counted: a line of base64 from a
// file chosen by mistake looks like a name, and may be a secret, but will hardly start or end so.
const SHOWN = /^SLEEVESIGHT_|_API_KEY$/;

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
 * blank lines skipped, as the CLI reads it. A key is named by one of its variables or by its name
 * in the code, which ranks with the `SLEEVESIGHT_` variable; of a service's names given a value,
 * the first in its list wins, and the others are left out without a word. An empty value is no
 * key, as the CLI has it. A key given more than once at its winning rank is refused, where the CLI,
 * which reads no code names, takes a name's last value: `anthropic=a` beside
 * `SLEEVESIGHT_ANTHROPIC_API_KEY=b` gives no Anthropic key here, and `b` in the CLI. Gives the
 * [keys] found, by service code, the services whose winning name is given more than once with a
 * value ([repeated]), which none of its values is taken from, those named with no value ([empty]),
 * the unknown names that start `SLEEVESIGHT_` or end `_API_KEY` ([unknown], each cut to
 * [SHOWN_NAME]), how many other names there were ([others]), and how many lines were not
 * `NAME=value` ([unreadable]).
 */
export function readFile(text) {
  const keys = new Map();
  // By service code: the rank of the best name given a value so far, and how often it was given.
  const ranks = new Map();
  const counts = new Map();
  const empty = new Set();
  const unknown = [];
  let others = 0;
  let unreadable = 0;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim().replace(/^export /, '').trim();
    if (line === '' || line.startsWith('#')) continue;
    const at = line.indexOf('=');
    const name = at < 0 ? '' : line.slice(0, at).trim();
    if (!NAME.test(name)) {
      unreadable++;
      continue;
    }
    const value = unquoted(line.slice(at + 1).trim()).trim();
    const service = SERVICES.find((s) => s.variables.includes(name) || s.code === name);
    if (service === undefined) {
      if (SHOWN.test(name)) unknown.push(name.slice(0, SHOWN_NAME));
      else others++;
    } else if (value === '') {
      empty.add(service.code);
    } else {
      const { code } = service;
      const rank = name === code ? 0 : service.variables.indexOf(name);
      const best = ranks.get(code) ?? Infinity;
      if (rank < best) {
        ranks.set(code, rank);
        keys.set(code, value);
        counts.set(code, 1);
      } else if (rank === best) {
        keys.set(code, value);
        counts.set(code, counts.get(code) + 1);
      }
    }
  }
  const repeated = [...counts].filter(([, n]) => n > 1).map(([code]) => code);
  for (const code of repeated) keys.delete(code);
  for (const code of keys.keys()) empty.delete(code);
  for (const code of repeated) empty.delete(code);
  return { keys, repeated, empty: [...empty], unknown, others, unreadable };
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

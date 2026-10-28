// The API keys page (#436): the keys from a keys file the user chooses or drops, or pasted in their
// fields, each checked against its service's form as the app checks it, and the ones that pass
// drawn as a setup code for the app's *Load keys*, sealed with the five words the phone shows
// (#590), unless sealing is turned off. The keys and the words are kept only in the fields; nothing
// is stored or sent.

import { qrSvg } from '../keyboard/qr-svg.js';
import { BAD_CHARACTERS, SERVICES, check, ending, readFile, write } from './keys.js';
import { COUNT, PREFIX_LETTERS, readWords, seal, tokens } from './seal.js';

const $ = (id) => document.getElementById(id);

/** A keys file is a few lines; anything much larger is not one. */
const LARGEST_FILE = 64 * 1024;

/** CSS pixels a module, so that a code of five keys can be scanned from a laptop's screen. */
const MODULE_PX = 5;

/** The unknown names a report lists; a file of other variables can name hundreds. */
const SHOWN_UNKNOWN = 10;

const FORMS = {
  anthropic: 'An Anthropic key starts sk-ant- and is 40 to 400 characters long.',
  openai: 'An OpenAI key starts sk- (not sk-ant-, which is Anthropic’s) and is 40 to 400 characters long.',
  google: 'A Google key starts AIza or AQ. and is 30 to 400 characters long.',
  exa: 'An Exa key is 16 to 200 characters long.',
  lastfm: 'A Last.fm key is 32 hexadecimal digits.',
};

// Masked without a password field, which a browser would offer to save; a password field only
// where the browser cannot mask a text field.
const MASKS = CSS.supports('-webkit-text-security', 'disc');

const inputs = new Map();
const messages = new Map();

// Counts the files read and the clears, so that a file still being read when another is chosen,
// or when *Clear* is chosen, is dropped once read rather than fill the fields.
let generation = 0;

// What `#holds` last said, so a live region is not rebuilt, and read out again, at each keystroke.
let held = null;

// Counts the codes asked to be drawn, so that a code still being sealed when the keys or the words
// change, or when *Clear* is chosen, is dropped once sealed; and what the code drawn holds, so that
// it is not sealed again, with a new salt, at a keystroke that changes nothing in it.
let drawing = 0;
let drawn = null;

const NUMBERS = ['no', 'one', 'two', 'three', 'four', 'five'];

/**
 * The words typed: the five words once they are all there, and what to say of them: how many so
 * far, or which one is not one of the list's, or that there are too many. The last word still being
 * typed, under three letters, is not yet wrong.
 */
function wordsTyped() {
  const text = $('words').value;
  const typed = tokens(text);
  const words = readWords(text);
  const typing = typed.length > 0 && !/[\s,]$/.test(text) && typed.at(-1).length < PREFIX_LETTERS;
  const wrong = words.findIndex((word, i) => word === null && !(typing && i === words.length - 1));
  if (wrong >= 0) return { words: null, said: `Word ${wrong + 1} is not one of the phone’s words: check it.`, error: true };
  if (words.length > COUNT) return { words: null, said: `More than ${NUMBERS[COUNT]} words: the phone shows ${NUMBERS[COUNT]}.`, error: true };
  const done = words.filter((word) => word !== null);
  if (done.length === COUNT) return { words: done, said: 'Five words, which seal the code.', error: false };
  return { words: null, said: typed.length === 0 ? '' : `${done.length} of ${COUNT} words.`, error: false };
}

// Says [said] of the words, in the error colour for an [error], only when it changes, so the live
// region is not read out again at each keystroke.
function reportWords(said, error) {
  const line = $('words-report');
  if (line.textContent !== said) line.textContent = said;
  line.className = error ? 'error' : 'ok';
}

function buildFields() {
  for (const { code, label } of SERVICES) {
    const field = document.createElement('div');
    field.className = 'field';
    const name = document.createElement('label');
    name.htmlFor = `key-${code}`;
    name.textContent = label;
    const input = document.createElement('input');
    input.id = `key-${code}`;
    input.type = MASKS ? 'text' : 'password';
    if (MASKS) input.className = 'masked';
    for (const [attribute, value] of [['autocomplete', 'off'], ['autocapitalize', 'off'], ['spellcheck', 'false']]) {
      input.setAttribute(attribute, value);
    }
    input.setAttribute('aria-describedby', `message-${code}`);
    input.addEventListener('input', update);
    const message = document.createElement('p');
    message.id = `message-${code}`;
    field.append(name, input, message);
    $('fields').append(field);
    inputs.set(code, input);
    messages.set(code, message);
  }
}

function labelOf(code) {
  return SERVICES.find((s) => s.code === code).label;
}

// Checks each field, says what is wrong with each key refused, and draws the code of the others,
// sealed with the words, unless sealing is off.
function update() {
  const keys = new Map();
  const refused = [];
  for (const { code } of SERVICES) {
    const value = inputs.get(code).value.trim();
    const message = messages.get(code);
    const problem = value === '' ? null : check(code, value);
    if (value === '') {
      message.textContent = '';
    } else if (problem === null) {
      keys.set(code, value);
      message.textContent = `In the code, ending ${ending(value)}.`;
      message.className = 'ok';
    } else {
      refused.push(code);
      message.textContent =
        `Not in the code: ${problem === BAD_CHARACTERS ? 'it holds a space or a character no such key has.' : FORMS[code]}`;
      message.className = 'error';
    }
    inputs.get(code).setAttribute('aria-invalid', problem === null ? 'false' : 'true');
  }
  const sealing = $('seal').checked;
  $('words').disabled = !sealing;
  $('show-words').disabled = !sealing;
  $('unsealed').hidden = sealing;
  const typed = sealing ? wordsTyped() : { words: null, said: '', error: false };
  reportWords(typed.said, typed.error);
  const waiting = sealing && typed.words === null;
  const said = JSON.stringify([[...keys].map(([name, value]) => [name, ending(value)]), refused, sealing, waiting]);
  const changed = said !== held;
  held = said;
  const holds = $('holds');
  if (keys.size === 0 || waiting) {
    hideCode();
    if (!changed) return;
    if (keys.size === 0) {
      holds.textContent = refused.length === 0 ? 'No key yet.' : 'No code: no key has its service’s form.';
    } else {
      holds.textContent = `No code yet: type the ${NUMBERS[COUNT]} words the phone shows, above.`;
    }
    return;
  }
  const text = write(keys);
  const words = sealing ? typed.words : null;
  draw(text, words);
  if (!changed) return;
  const list = document.createElement('ul');
  for (const [name, value] of keys) {
    const item = document.createElement('li');
    item.textContent = `${labelOf(name)}, ending ${ending(value)}`;
    list.append(item);
  }
  const lines = [document.createTextNode(sealing ? 'The code, sealed with the phone’s words, holds:' : 'The code holds:'), list];
  if (refused.length > 0) {
    const left = document.createElement('p');
    left.className = 'error';
    left.textContent = `Left out, not of its service’s form: ${refused.map(labelOf).join(', ')}.`;
    lines.push(left);
  }
  holds.replaceChildren(...lines);
}

// Hides the code, and drops one still being sealed.
function hideCode() {
  drawing++;
  drawn = null;
  const code = $('code');
  code.hidden = true;
  code.replaceChildren();
}

// Draws the setup code [text], sealed with [words] (none: as it is), unless the code drawn already
// holds the same; a code sealed after another was asked for is dropped.
async function draw(text, words) {
  const what = JSON.stringify([text, words]);
  if (what === drawn) return;
  drawn = what;
  const asked = ++drawing;
  let content = text;
  if (words !== null) {
    try {
      content = await seal(text, words);
    } catch {
      if (asked === drawing) {
        drawn = null;
        $('holds').textContent = 'The code could not be sealed: this browser lacks the cryptography it needs.';
      }
      return;
    }
    if (asked !== drawing) return;
  }
  const label = words === null ? 'Setup code of the keys listed below it' : 'Sealed setup code of the keys listed below it';
  const { svg, side } = qrSvg(content, label);
  const code = $('code');
  code.style.width = `${side * MODULE_PX}px`;
  code.replaceChildren(svg);
  code.hidden = false;
}

// Sets the file report at once. The same report again (the same file read twice) ends in a
// no-break space, or loses it, so that it is still a change, which the live region announces.
function report(text, error = false) {
  const line = $('file-report');
  line.className = error ? 'report error' : 'report';
  line.textContent = text !== '' && line.textContent === text ? `${text}\u00a0` : text;
}

// Fills the fields with the keys in [file], in place of what they held, and says what was left out.
async function load(file) {
  const reading = ++generation;
  if (file.size > LARGEST_FILE) {
    report('Not read: too large to be a keys file.', true);
    return;
  }
  let text;
  try {
    text = await file.text();
  } catch {
    if (reading === generation) report('The file could not be read.', true);
    return;
  }
  if (reading !== generation) return;
  const { keys, repeated, empty, unknown, others, unreadable } = readFile(text);
  for (const [code, input] of inputs) input.value = keys.get(code) ?? '';
  const said = [
    keys.size === 0 ? 'No key found in the file.' : `From the file: ${[...keys.keys()].map(labelOf).join(', ')}.`,
  ];
  if (repeated.length > 0) said.push(`Named more than once, so not taken: ${repeated.map(labelOf).join(', ')}.`);
  if (empty.length > 0) said.push(`Named with no key: ${empty.map(labelOf).join(', ')}.`);
  if (unknown.length > 0) {
    const more = unknown.length - SHOWN_UNKNOWN;
    const names = unknown.slice(0, SHOWN_UNKNOWN).join(', ') + (more > 0 ? ` and ${more} more` : '');
    said.push(`Not a key Sleevesight uses, left out: ${names}.`);
  }
  if (others > 0) said.push(`${others} other name${others > 1 ? 's' : ''} left out.`);
  if (keys.size + repeated.length + empty.length === 0 && unknown.length + others > 0) {
    const names = SERVICES.map((s) => s.variables.join(' or '));
    said.push(`Sleevesight reads ${names.slice(0, -1).join(', ')} and ${names.at(-1)}.`);
  }
  if (unreadable > 0) said.push(`${unreadable} line${unreadable > 1 ? 's' : ''} not of the form NAME=value, left out.`);
  report(said.join(' '));
  update();
}

function clear() {
  generation++;
  for (const input of inputs.values()) input.value = '';
  $('file').value = '';
  report('');
  $('show').checked = false;
  show(false);
  $('words').value = '';
  $('show-words').checked = false;
  showWords(false);
  $('seal').checked = true;
  hideCode();
  update();
}

function mask(input, shown) {
  if (MASKS) input.classList.toggle('masked', !shown);
  else input.type = shown ? 'text' : 'password';
}

function show(shown) {
  for (const input of inputs.values()) mask(input, shown);
}

function showWords(shown) {
  mask($('words'), shown);
}

function buildWords() {
  const input = $('words');
  for (const [attribute, value] of [['autocomplete', 'off'], ['autocapitalize', 'off'], ['spellcheck', 'false']]) {
    input.setAttribute(attribute, value);
  }
  showWords(false);
  input.addEventListener('input', update);
}

buildFields();
buildWords();
$('file').addEventListener('change', () => {
  const [file] = $('file').files;
  // Emptied, so the same file chosen again is read again.
  $('file').value = '';
  if (file) load(file);
});
const drop = $('drop');
drop.addEventListener('dragover', (event) => {
  event.preventDefault();
  drop.classList.add('over');
});
drop.addEventListener('dragleave', () => drop.classList.remove('over'));
drop.addEventListener('drop', (event) => {
  event.preventDefault();
  drop.classList.remove('over');
  const [file] = event.dataTransfer.files;
  if (file) load(file);
});
// A file dropped beside the box is not opened by the browser in place of the page; text dragged
// into a field still is dropped there.
const holdFile = (event) => {
  if (event.dataTransfer.types.includes('Files')) event.preventDefault();
};
window.addEventListener('dragover', holdFile);
window.addEventListener('drop', holdFile);
// Emptied as the page is left, so that no browser offers to save a key, where it masks the fields
// as passwords, and none is restored on coming back: before a navigation starts, and again as the
// page is hidden (also into the back-forward cache, which `beforeunload` does not see).
window.addEventListener('beforeunload', clear);
window.addEventListener('pagehide', clear);
$('show').addEventListener('change', () => show($('show').checked));
$('show-words').addEventListener('change', () => showWords($('show-words').checked));
$('seal').addEventListener('change', update);
$('clear').addEventListener('click', clear);
update();

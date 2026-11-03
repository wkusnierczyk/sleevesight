// The API keys page (#436, #764): the five words the phone's *Load keys* shows, typed in five
// boxes, three letters each; the keys from a keys file the user chooses or drops, or pasted in their
// fields, each checked against its service's form as the app checks it; and the ones that pass drawn
// as a setup code for *Load keys*, sealed with the words (#590), unless the code is made without
// them. The words and the keys are masked, each with an eye that shows its action (open while hidden,
// slashed while shown). They are kept only in the fields; nothing is stored or sent.

import { qrSvg } from '../keyboard/qr-svg.js';
import { BAD_CHARACTERS, SERVICES, check, ending, readFile, write } from './keys.js';
import { codeDrawer } from './draw.js';
import { COUNT, PREFIX_LETTERS, complete, seal } from './seal.js';

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

/** The word boxes, in order. */
const boxes = Array.from({ length: COUNT }, (_, i) => $(`w${i}`));

const inputs = new Map();
const messages = new Map();
const eyes = new Map();

// Whether the words are shown, and which keys are.
let wordsShown = false;
const shown = new Set();

// Counts the files read and the clears, so that a file still being read when another is chosen,
// or when *Clear* is chosen, is dropped once read rather than fill the fields.
let generation = 0;

// What the code's state last said, so a live region is not rebuilt, and read out again, at each
// keystroke.
let held = null;

const labelOf = (code) => SERVICES.find((s) => s.code === code).label;

/** What a word box keeps of [text]: lower-case letters, three at most. */
const keep = (text) => text.toLowerCase().replace(/[^a-z]/g, '').slice(0, PREFIX_LETTERS);

// Draws the code (`draw.js`): the one on screen goes as soon as what it holds changes, so a clear
// code never stays while a sealed one is made, nor after sealing fails.
const drawer = codeDrawer({
  seal,
  show: (content, sealed, describe) => {
    const label = sealed ? 'Sealed setup code of the keys listed below it' : 'Setup code of the keys listed below it';
    const { svg, side } = qrSvg(content, label);
    const code = $('code');
    code.style.width = `${side * MODULE_PX}px`;
    code.replaceChildren(svg);
    code.hidden = false;
    $('ph').hidden = true;
    $('warn').hidden = sealed;
    describe(sealed);
  },
  hide: () => {
    const code = $('code');
    code.hidden = true;
    code.replaceChildren();
    $('warn').hidden = true;
  },
  failed: () => {
    held = null;
    const ph = $('ph');
    ph.hidden = false;
    const line = document.createElement('span');
    line.className = 'error';
    line.textContent = 'No code: it could not be sealed, as this browser lacks the cryptography it needs.';
    ph.replaceChildren(line);
    $('holds').replaceChildren();
  },
});

/**
 * The words typed: the five words once they are all there, what the line under the boxes says of
 * them (how many so far, or which one is not one of the list's), and each box's state: empty, still
 * being typed (under three letters), a word, or none.
 */
function wordsTyped() {
  const states = boxes.map((box) => {
    const typed = box.value;
    if (typed === '') return 'empty';
    if (complete(typed) !== null) return 'ok';
    return typed.length < PREFIX_LETTERS ? 'typing' : 'bad';
  });
  const wrong = states.indexOf('bad');
  const done = states.filter((state) => state === 'ok').length;
  if (wrong >= 0) return { words: null, said: `Word ${wrong + 1} is not one of the phone’s words: check it.`, error: true, states };
  if (done === COUNT) return { words: boxes.map((box) => complete(box.value)), said: 'Five words, which seal the code.', error: false, states };
  return { words: null, said: states.every((state) => state === 'empty') ? '' : `${done} of ${COUNT} words.`, error: false, states };
}

// Under each box: the whole word once its letters match (only the tick while the words are hidden),
// *not a word*, or the box's name. The box is described by it only when it says more than its name.
function markBoxes(states) {
  states.forEach((state, i) => {
    $(`wb${i}`).className = `wbox${state === 'ok' ? ' okw' : state === 'bad' ? ' bad' : ''}`;
    const said = state === 'ok'
      ? (wordsShown ? `✓ ${complete(boxes[i].value)}` : '✓')
      : state === 'bad' ? '✗ not a word' : `Word ${i + 1}`;
    const mark = $(`wm${i}`);
    if (mark.textContent !== said) mark.textContent = said;
    if (state === 'ok' || state === 'bad') boxes[i].setAttribute('aria-describedby', `wm${i}`);
    else boxes[i].removeAttribute('aria-describedby');
  });
}

// Copies of the words' eye, one in each key field.
const EYE = $('showwords').innerHTML;

function buildFields() {
  for (const { code, label, variables } of SERVICES) {
    const field = document.createElement('div');
    field.className = 'field';
    const name = document.createElement('label');
    name.htmlFor = `k-${code}`;
    name.textContent = label;
    const wrap = document.createElement('div');
    wrap.className = 'kwrap';
    const input = document.createElement('input');
    input.id = `k-${code}`;
    input.type = 'text';
    for (const [attribute, value] of [['autocomplete', 'off'], ['autocapitalize', 'off'], ['spellcheck', 'false']]) {
      input.setAttribute(attribute, value);
    }
    input.setAttribute('aria-describedby', `m-${code}`);
    input.addEventListener('input', update);
    const eye = document.createElement('button');
    eye.type = 'button';
    eye.className = 'eye';
    eye.id = `e-${code}`;
    const named = `Show the ${label} key`;
    eye.setAttribute('aria-pressed', 'false');
    eye.setAttribute('aria-label', named);
    eye.title = named;
    eye.innerHTML = EYE;
    eye.addEventListener('click', () => {
      if (shown.has(code)) shown.delete(code);
      else shown.add(code);
      mask();
    });
    wrap.append(input, eye);
    const message = document.createElement('p');
    message.className = 'fmsg';
    message.id = `m-${code}`;
    field.append(name, wrap, message);
    $('fields').append(field);
    inputs.set(code, input);
    messages.set(code, message);
    eyes.set(code, eye);

    // The keys file's names for it, in the folded example.
    const row = document.createElement('tr');
    const service = document.createElement('td');
    service.textContent = label;
    const names = document.createElement('td');
    variables.forEach((variable, i) => {
      if (i > 0) names.append(document.createElement('br'));
      const text = document.createElement('code');
      text.textContent = variable;
      names.append(text);
    });
    row.append(service, names);
    $('names').append(row);
  }
}

function setMasked(input, visible) {
  if (MASKS) input.classList.toggle('masked', !visible);
  else input.type = visible ? 'text' : 'password';
}

// Masks or shows the words and each key as asked, and sets the eyes and the button under the keys
// to match.
function mask() {
  for (const box of boxes) setMasked(box, wordsShown);
  $('showwords').setAttribute('aria-pressed', String(wordsShown));
  for (const [code, input] of inputs) {
    setMasked(input, shown.has(code));
    eyes.get(code).setAttribute('aria-pressed', String(shown.has(code)));
  }
  $('showkeys').textContent = shown.size > 0 ? 'Hide all keys' : 'Show all keys';
  markBoxes(wordsTyped().states);
}

// The empty code box: what the code waits for, ticked as each comes.
function waitingFor(items) {
  const title = document.createElement('div');
  title.textContent = 'The code appears here once it has';
  const list = document.createElement('ul');
  for (const [got, text] of items) {
    const item = document.createElement('li');
    if (got) item.className = 'got';
    const tick = document.createElement('span');
    tick.className = 'tick';
    tick.textContent = got ? '✓' : '○';
    item.append(tick, text);
    list.append(item);
  }
  return [title, list];
}

// Checks each field, says what is wrong with each key refused, and draws the code of the others,
// sealed with the words, unless the code is made without them.
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
      message.textContent = `✓ In the code, ending ${ending(value)}.`;
      message.className = 'fmsg ok';
    } else {
      refused.push(code);
      message.textContent =
        `Not in the code: ${problem === BAD_CHARACTERS ? 'it holds a space or a character no such key has.' : FORMS[code]}`;
      message.className = 'fmsg error';
    }
    inputs.get(code).setAttribute('aria-invalid', problem === null ? 'false' : 'true');
  }

  const sealing = !$('nosealbox').checked;
  $('st1').classList.toggle('off', !sealing);
  for (const box of boxes) box.disabled = !sealing;
  $('showwords').disabled = !sealing;
  const typed = wordsTyped();
  markBoxes(typed.states);
  const said = sealing ? typed.said : 'Not needed: the code is made without the words.';
  const line = $('wr');
  if (line.textContent !== said) line.textContent = said;
  line.className = `line ${sealing && typed.error ? 'error' : sealing && typed.words !== null ? 'ok' : 'muted'}`;
  const wordsDone = sealing && typed.words !== null;
  $('st1').classList.toggle('done', wordsDone);
  $('n1').textContent = wordsDone ? '✓' : '1';
  $('st2').classList.toggle('done', keys.size > 0);
  $('n2').textContent = keys.size > 0 ? '✓' : '2';

  const waiting = sealing && typed.words === null;
  const state = JSON.stringify([[...keys].map(([name, value]) => [name, ending(value)]), refused, sealing, waiting]);
  const changed = state !== held;
  held = state;
  const holds = $('holds');
  const ph = $('ph');
  if (keys.size === 0 || waiting) {
    drawer.clear();
    if (!changed) return;
    holds.replaceChildren();
    ph.hidden = false;
    if (keys.size === 0 && refused.length > 0 && !waiting) {
      const none = document.createElement('span');
      none.className = 'error';
      none.textContent = 'No code: no key has its service’s form.';
      ph.replaceChildren(none);
      return;
    }
    const items = [];
    if (sealing) items.push([typed.words !== null, 'the phone’s five words']);
    items.push([keys.size > 0, 'at least one key']);
    ph.replaceChildren(...waitingFor(items));
    return;
  }

  const text = write(keys);
  const words = sealing ? typed.words : null;
  // What the code holds, said once it shows: as a sealed code only once a sealed one is drawn.
  const describe = (sealed) => {
    const list = document.createElement('ul');
    for (const [name, value] of keys) {
      const item = document.createElement('li');
      item.textContent = `${labelOf(name)}, ending ${ending(value)}`;
      list.append(item);
    }
    const lines = [document.createTextNode(sealed ? 'Sealed with the phone’s words. It holds:' : 'Not sealed. It holds:'), list];
    if (refused.length > 0) {
      const left = document.createElement('p');
      left.className = 'error';
      left.textContent = `Left out, not of its service’s form: ${refused.map(labelOf).join(', ')}.`;
      lines.push(left);
    }
    $('holds').replaceChildren(...lines);
  };
  if (drawer.holds(text, words)) {
    // The same code: the latest description kept for one still being sealed, and said again of
    // one shown only when it changed (a key left out, say).
    drawer.draw(text, words, describe);
    if (changed && !$('code').hidden) describe(words !== null);
    return;
  }
  if (words !== null) {
    ph.hidden = false;
    const sealingNow = document.createElement('div');
    const spinner = document.createElement('span');
    spinner.className = 'spin';
    spinner.setAttribute('aria-hidden', 'true');
    sealingNow.append(spinner, 'Sealing the code with the phone’s words…');
    ph.replaceChildren(sealingNow);
    holds.replaceChildren();
  }
  drawer.draw(text, words, describe);
}

// Sets the file report at once. The same report again (the same file read twice) ends in a
// no-break space, or loses it, so that it is still a change, which the live region announces.
function report(text, error = false) {
  const line = $('fr');
  line.className = error ? 'report error' : 'report';
  line.textContent = text !== '' && line.textContent === text ? `${text} ` : text;
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

// Empties the words, the keys, the file report and the code, hides everything again, and seals the
// code with the words again.
function clear() {
  generation++;
  for (const box of boxes) box.value = '';
  for (const input of inputs.values()) input.value = '';
  $('file').value = '';
  report('');
  wordsShown = false;
  shown.clear();
  $('nosealbox').checked = false;
  mask();
  drawer.clear();
  update();
}

// The word boxes: three letters each, lower case, nothing but letters. A space or a comma moves to
// the next box, as Tab does; Backspace in an empty box goes back. Several words pasted, or typed
// with a separator on a keyboard that sends no key events (Android's), fill this box and the ones
// after it.
function wireBoxes() {
  boxes.forEach((box, i) => {
    box.addEventListener('input', () => {
      const parts = box.value.split(/[\s,]+/);
      if (parts.length > 1) {
        const words = parts.map(keep);
        if (words.length === 2 && words[1] === '') {
          box.value = words[0];
          if (i < COUNT - 1) boxes[i + 1].focus();
        } else {
          let j = i;
          for (const word of words.filter((w) => w !== '')) {
            if (j >= COUNT) break;
            boxes[j].value = word;
            j++;
          }
          boxes[Math.min(j, COUNT - 1)].focus();
        }
      } else {
        const kept = keep(box.value);
        if (kept !== box.value) {
          const at = Math.min(box.selectionStart ?? kept.length, kept.length);
          box.value = kept;
          box.setSelectionRange(at, at);
        }
      }
      update();
    });
    box.addEventListener('keydown', (event) => {
      if (event.isComposing || event.ctrlKey || event.metaKey || event.altKey) return;
      if (event.key === 'Backspace' && box.value === '' && i > 0) {
        event.preventDefault();
        const previous = boxes[i - 1];
        previous.focus();
        previous.setSelectionRange(previous.value.length, previous.value.length);
      } else if (event.key === ' ' || event.key === ',') {
        event.preventDefault();
        if (i < COUNT - 1) boxes[i + 1].focus();
      }
    });
  });
}

buildFields();
wireBoxes();
$('showwords').addEventListener('click', () => {
  wordsShown = !wordsShown;
  mask();
});
$('showkeys').addEventListener('click', () => {
  const hide = shown.size > 0;
  shown.clear();
  if (!hide) for (const { code } of SERVICES) shown.add(code);
  mask();
});
$('nosealbox').addEventListener('change', update);
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
$('clear').addEventListener('click', clear);
mask();
update();

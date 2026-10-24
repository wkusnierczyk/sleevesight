// The API keys page (#436): the keys from a keys file the user chooses or drops, or pasted in their
// fields, each checked against its service's form as the app checks it, and the ones that pass
// drawn as a setup code for the app's *Scan keys*. The keys are kept only in the fields; nothing is
// stored or sent.

import { qrSvg } from '../keyboard/qr-svg.js';
import { BAD_CHARACTERS, SERVICES, check, ending, readFile, write } from './keys.js';

const $ = (id) => document.getElementById(id);

/** A keys file is a few lines; anything much larger is not one. */
const LARGEST_FILE = 64 * 1024;

/** CSS pixels a module, so that a code of five keys can be scanned from a laptop's screen. */
const MODULE_PX = 5;

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

// Checks each field, says what is wrong with each key refused, and draws the code of the others.
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
  const holds = $('holds');
  const code = $('code');
  if (keys.size === 0) {
    code.hidden = true;
    code.replaceChildren();
    holds.textContent = refused.length === 0 ? 'No key yet.' : 'No code: no key has its service’s form.';
    return;
  }
  const { svg, side } = qrSvg(write(keys), 'Setup code of the keys listed below it');
  code.style.width = `${side * MODULE_PX}px`;
  code.replaceChildren(svg);
  code.hidden = false;
  const list = document.createElement('ul');
  for (const [name, value] of keys) {
    const item = document.createElement('li');
    item.textContent = `${labelOf(name)}, ending ${ending(value)}`;
    list.append(item);
  }
  const lines = [document.createTextNode('The code holds:'), list];
  if (refused.length > 0) {
    const left = document.createElement('p');
    left.className = 'error';
    left.textContent = `Left out, not of its service’s form: ${refused.map(labelOf).join(', ')}.`;
    lines.push(left);
  }
  holds.replaceChildren(...lines);
}

// Fills the fields with the keys in [file], and says what was left out.
async function load(file) {
  const report = $('file-report');
  report.hidden = false;
  report.className = 'report';
  if (file.size > LARGEST_FILE) {
    report.textContent = 'Not read: too large to be a keys file.';
    report.className = 'report error';
    return;
  }
  let text;
  try {
    text = await file.text();
  } catch {
    report.textContent = 'The file could not be read.';
    report.className = 'report error';
    return;
  }
  const { keys, repeated, unknown, unreadable } = readFile(text);
  for (const [code, value] of keys) inputs.get(code).value = value;
  const said = [
    keys.size === 0 ? 'No key found in the file.' : `From the file: ${[...keys.keys()].map(labelOf).join(', ')}.`,
  ];
  if (repeated.length > 0) said.push(`Named more than once, so not taken: ${repeated.map(labelOf).join(', ')}.`);
  if (unknown.length > 0) said.push(`Not a key Sleevesight uses, left out: ${unknown.join(', ')}.`);
  if (unreadable > 0) said.push(`${unreadable} line${unreadable > 1 ? 's' : ''} not of the form NAME=value, left out.`);
  report.textContent = said.join(' ');
  update();
}

function clear() {
  for (const input of inputs.values()) input.value = '';
  $('file').value = '';
  $('file-report').hidden = true;
  $('file-report').textContent = '';
  $('show').checked = false;
  show(false);
  update();
}

function show(shown) {
  for (const input of inputs.values()) {
    if (MASKS) input.classList.toggle('masked', !shown);
    else input.type = shown ? 'text' : 'password';
  }
}

buildFields();
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
// A file dropped beside the box is not opened by the browser in place of the page.
window.addEventListener('dragover', (event) => event.preventDefault());
window.addEventListener('drop', (event) => event.preventDefault());
$('show').addEventListener('change', () => show($('show').checked));
$('clear').addEventListener('click', clear);
update();

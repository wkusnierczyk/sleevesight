// The keyboard page (#394): makes a session, shows its code, connects to the phone over Web
// Bluetooth, and writes the whole text, sealed, at each change. One write at a time; a change made
// meanwhile is sent once it is done, the latest text only.

import { CHARACTERISTIC, MAX_COUNTER, MAX_TEXT_BYTES, newSession, seal, textBytes } from './protocol.js';
import { encode } from './qr.js';

const QUIET_ZONE = 4;
const $ = (id) => document.getElementById(id);

const session = newSession();
let characteristic = null;
let counter = 0;
let sent = null;
let writing = false;

function status(message, kind = '') {
  const line = $('status');
  line.textContent = message;
  line.className = kind;
}

function drawCode(text) {
  const modules = encode(text);
  const side = modules.length + 2 * QUIET_ZONE;
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', `0 0 ${side} ${side}`);
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', 'QR code of this session');
  svg.setAttribute('shape-rendering', 'crispEdges');
  const back = document.createElementNS(svg.namespaceURI, 'rect');
  back.setAttribute('width', side);
  back.setAttribute('height', side);
  back.setAttribute('fill', '#fff');
  const path = document.createElementNS(svg.namespaceURI, 'path');
  let d = '';
  modules.forEach((row, y) =>
    row.forEach((dark, x) => {
      if (dark) d += `M${x + QUIET_ZONE} ${y + QUIET_ZONE}h1v1h-1z`;
    }),
  );
  path.setAttribute('d', d);
  path.setAttribute('fill', '#000');
  svg.append(back, path);
  $('code').replaceChildren(svg);
}

async function connect() {
  let device;
  try {
    device = await navigator.bluetooth.requestDevice({ filters: [{ services: [session.service] }] });
  } catch (e) {
    // The user closed the chooser, or no phone was found.
    if (e.name !== 'NotFoundError') status(`Could not look for the phone: ${e.message}`, 'error');
    return;
  }
  device.addEventListener('gattserverdisconnected', () => {
    characteristic = null;
    $('connect').hidden = false;
    status('Disconnected. If the phone still shows “Waiting for the computer”, connect again; otherwise reload this page for a new code.');
  });
  try {
    status('Connecting…');
    const server = await device.gatt.connect();
    const service = await server.getPrimaryService(session.service);
    characteristic = await service.getCharacteristic(CHARACTERISTIC);
  } catch (e) {
    status(`Could not connect to the phone: ${e.message}`, 'error');
    return;
  }
  $('connect').hidden = true;
  status('Connected. What you type here appears on the phone.', 'ok');
  sent = null;
  send();
}

// Writes the text as it is now, unless it was sent already; then again if it changed meanwhile.
async function send() {
  const text = $('text').value;
  if (writing || characteristic === null || text === sent) return;
  if (textBytes(text) > MAX_TEXT_BYTES) return;
  if (counter === MAX_COUNTER) {
    status('This session has sent all it can. Reload this page for a new code.', 'error');
    return;
  }
  writing = true;
  try {
    counter++;
    await characteristic.writeValueWithResponse(await seal(session, counter, text));
    sent = text;
  } catch (e) {
    // A disconnection says so itself; anything else is said here, and the next change tries again.
    if (characteristic !== null) status(`The phone did not take the text: ${e.message}`, 'error');
    return;
  } finally {
    writing = false;
  }
  if ($('status').className === 'error') status('Connected. What you type here appears on the phone.', 'ok');
  if (characteristic !== null && $('text').value !== sent) send();
}

function changed() {
  const over = textBytes($('text').value) > MAX_TEXT_BYTES;
  $('length').hidden = !over;
  if (!over) send();
}

function start() {
  drawCode(session.code);
  if (!navigator.bluetooth) {
    $('unsupported').hidden = false;
    $('connect').disabled = true;
    return;
  }
  $('connect').addEventListener('click', connect);
  $('text').addEventListener('input', changed);
  $('show').addEventListener('change', () => {
    $('text').classList.toggle('masked', !$('show').checked);
  });
}

start();

// A QR code encoder for the keyboard's session code (#394): byte mode, error correction level M,
// versions 1 to 6 (up to 106 bytes), which is all the code needs (56 bytes, version 4). Written
// here rather than loaded, so the page fetches nothing from elsewhere; the app's tests read a code
// it drew with ZXing (`make_vectors.mjs`). Follows ISO/IEC 18004 as Project Nayuki's encoder does.

// Per version: [total codewords, blocks, error correction codewords per block], level M.
const VERSIONS = [null, [26, 1, 10], [44, 1, 16], [70, 1, 26], [100, 2, 18], [134, 2, 24], [172, 4, 16]];
const LEVEL_M = 0; // Level M's two format bits.

/** The modules of a QR code of [text]: rows of booleans, true dark, without the quiet zone. */
export function encode(text) {
  const bytes = new TextEncoder().encode(text);
  let version = 1;
  while (version < VERSIONS.length && dataCapacity(version) < bytes.length + 2) version++;
  if (version === VERSIONS.length) throw new RangeError('text too long for a QR code here');
  const codewords = withCorrection(version, dataCodewords(version, bytes));
  const size = 17 + 4 * version;
  const base = functionPatterns(version, size);
  placeData(base, codewords);
  let best = null;
  for (let mask = 0; mask < 8; mask++) {
    const candidate = applyMask(base, mask);
    drawFormat(candidate, mask);
    const score = penalty(candidate.modules);
    if (best === null || score < best.score) best = { modules: candidate.modules, score };
  }
  return best.modules;
}

function dataCapacity(version) {
  const [total, blocks, ecc] = VERSIONS[version];
  return total - blocks * ecc;
}

// Mode, count, data, terminator and padding, as codewords. The count is 8 bits up to version 9.
function dataCodewords(version, bytes) {
  const capacity = dataCapacity(version);
  const bits = [];
  const put = (value, length) => {
    for (let i = length - 1; i >= 0; i--) bits.push((value >>> i) & 1);
  };
  put(0b0100, 4);
  put(bytes.length, 8);
  for (const b of bytes) put(b, 8);
  put(0, Math.min(4, capacity * 8 - bits.length));
  put(0, (8 - (bits.length % 8)) % 8);
  const out = [];
  for (let i = 0; i < bits.length; i += 8) out.push(bits.slice(i, i + 8).reduce((a, b) => (a << 1) | b, 0));
  for (let pad = 0xec; out.length < capacity; pad ^= 0xec ^ 0x11) out.push(pad);
  return out;
}

// The data split into equal blocks (true of level M up to version 6), each with its error
// correction, interleaved.
function withCorrection(version, data) {
  const [, blocks, ecc] = VERSIONS[version];
  const length = data.length / blocks;
  const divisor = rsDivisor(ecc);
  const split = [];
  for (let b = 0; b < blocks; b++) {
    const block = data.slice(b * length, (b + 1) * length);
    split.push([block, rsRemainder(block, divisor)]);
  }
  const out = [];
  for (let i = 0; i < length; i++) for (const [block] of split) out.push(block[i]);
  for (let i = 0; i < ecc; i++) for (const [, correction] of split) out.push(correction[i]);
  return out;
}

function gfMultiply(x, y) {
  let z = 0;
  for (let i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d);
    z ^= ((y >>> i) & 1) * x;
  }
  return z;
}

function rsDivisor(degree) {
  const result = new Array(degree).fill(0);
  result[degree - 1] = 1;
  let root = 1;
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < degree; j++) {
      result[j] = gfMultiply(result[j], root);
      if (j + 1 < degree) result[j] ^= result[j + 1];
    }
    root = gfMultiply(root, 0x02);
  }
  return result;
}

function rsRemainder(data, divisor) {
  const result = new Array(divisor.length).fill(0);
  for (const b of data) {
    const factor = b ^ result.shift();
    result.push(0);
    divisor.forEach((coefficient, i) => {
      result[i] ^= gfMultiply(coefficient, factor);
    });
  }
  return result;
}

// The finder, timing and alignment patterns, the dark module, and the format areas reserved.
function functionPatterns(version, size) {
  const modules = Array.from({ length: size }, () => new Array(size).fill(false));
  const reserved = Array.from({ length: size }, () => new Array(size).fill(false));
  const set = (x, y, dark) => {
    modules[y][x] = dark;
    reserved[y][x] = true;
  };
  for (let i = 0; i < size; i++) {
    set(6, i, i % 2 === 0);
    set(i, 6, i % 2 === 0);
  }
  for (const [cx, cy] of [[3, 3], [size - 4, 3], [3, size - 4]]) {
    for (let dy = -4; dy <= 4; dy++) {
      for (let dx = -4; dx <= 4; dx++) {
        const x = cx + dx;
        const y = cy + dy;
        const ring = Math.max(Math.abs(dx), Math.abs(dy));
        if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, ring !== 2 && ring !== 4);
      }
    }
  }
  if (version >= 2) {
    const c = size - 7;
    for (let dy = -2; dy <= 2; dy++) {
      for (let dx = -2; dx <= 2; dx++) set(c + dx, c + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
    }
  }
  for (let i = 0; i < 9; i++) {
    reserved[8][i] = true;
    reserved[i][8] = true;
  }
  for (let i = 0; i < 8; i++) {
    reserved[8][size - 1 - i] = true;
    reserved[size - 1 - i][8] = true;
  }
  set(8, size - 8, true);
  return { modules, reserved, size };
}

// The codewords' bits in the zigzag order, two columns at a time from the bottom right, skipping
// the vertical timing pattern. Bits left over (the remainder) stay light.
function placeData({ modules, reserved, size }, codewords) {
  let i = 0;
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    const upward = ((right + 1) & 2) === 0;
    for (let vertical = 0; vertical < size; vertical++) {
      const y = upward ? size - 1 - vertical : vertical;
      for (let j = 0; j < 2; j++) {
        const x = right - j;
        if (!reserved[y][x] && i < codewords.length * 8) {
          modules[y][x] = ((codewords[i >>> 3] >>> (7 - (i & 7))) & 1) === 1;
          i++;
        }
      }
    }
  }
}

const MASKS = [
  (x, y) => (x + y) % 2 === 0,
  (x, y) => y % 2 === 0,
  (x) => x % 3 === 0,
  (x, y) => (x + y) % 3 === 0,
  (x, y) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0,
  (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
  (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0,
  (x, y) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0,
];

function applyMask({ modules, reserved, size }, mask) {
  const masked = modules.map((row, y) => row.map((dark, x) => (reserved[y][x] ? dark : dark !== MASKS[mask](x, y))));
  return { modules: masked, size };
}

// The level and the mask, with their BCH error correction, in both copies.
function drawFormat({ modules, size }, mask) {
  const data = (LEVEL_M << 3) | mask;
  let remainder = data;
  for (let i = 0; i < 10; i++) remainder = (remainder << 1) ^ ((remainder >>> 9) * 0x537);
  const bits = ((data << 10) | remainder) ^ 0x5412;
  const bit = (i) => ((bits >>> i) & 1) === 1;
  const set = (x, y, dark) => {
    modules[y][x] = dark;
  };
  for (let i = 0; i <= 5; i++) set(8, i, bit(i));
  set(8, 7, bit(6));
  set(8, 8, bit(7));
  set(7, 8, bit(8));
  for (let i = 9; i < 15; i++) set(14 - i, 8, bit(i));
  for (let i = 0; i < 8; i++) set(size - 1 - i, 8, bit(i));
  for (let i = 8; i < 15; i++) set(8, size - 15 + i, bit(i));
}

// The standard's penalty: runs of five or more, 2 by 2 blocks, finder-like patterns, and the
// imbalance of dark and light. Any mask reads; the lowest penalty reads best.
function penalty(modules) {
  const size = modules.length;
  let score = 0;
  const lines = [];
  for (let i = 0; i < size; i++) {
    lines.push(modules[i]);
    lines.push(modules.map((row) => row[i]));
  }
  for (const line of lines) {
    let run = 1;
    for (let i = 1; i <= size; i++) {
      if (i < size && line[i] === line[i - 1]) {
        run++;
      } else {
        if (run >= 5) score += run - 2;
        run = 1;
      }
    }
    const padded = [...new Array(4).fill(false), ...line, ...new Array(4).fill(false)];
    for (let i = 0; i + 11 <= padded.length; i++) {
      const window = padded.slice(i, i + 11).map((d) => (d ? 1 : 0)).join('');
      if (window === '10111010000' || window === '00001011101') score += 40;
    }
  }
  let dark = 0;
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      if (modules[y][x]) dark++;
      if (x + 1 < size && y + 1 < size) {
        const c = modules[y][x];
        if (modules[y][x + 1] === c && modules[y + 1][x] === c && modules[y + 1][x + 1] === c) score += 3;
      }
    }
  }
  const total = size * size;
  score += Math.floor(Math.abs(dark * 20 - total * 10) / total) * 10;
  return score;
}

// A QR code encoder for the keyboard's session code (#394) and the setup code of API keys (#436):
// byte mode, error correction level M, versions 1 to 40 (up to 2331 bytes). The session code is
// 56 bytes (version 4); a setup code of five keys is about 400 (version 15 or so). Written here
// rather than loaded, so the pages fetch nothing from elsewhere; the app's tests read codes it drew
// with ZXing (both `make_vectors.mjs`). Follows ISO/IEC 18004 as Project Nayuki's encoder does.

// Per version, level M: the error correction codewords per block, and the blocks.
const ECC_PER_BLOCK = [null, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26,
  28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28];
const BLOCKS = [null, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25,
  26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49];
const LAST_VERSION = 40;
const LEVEL_M = 0; // Level M's two format bits.

/** The modules of a QR code of [text]: rows of booleans, true dark, without the quiet zone. */
export function encode(text) {
  const bytes = new TextEncoder().encode(text);
  let version = 1;
  while (version <= LAST_VERSION && dataCapacity(version) * 8 < 4 + countBits(version) + bytes.length * 8) version++;
  if (version > LAST_VERSION) throw new RangeError('text too long for a QR code');
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

// The codewords a version holds, data and error correction: its modules less the function
// patterns and the format and version areas, in whole bytes.
function totalCodewords(version) {
  let modules = (16 * version + 128) * version + 64;
  if (version >= 2) {
    const align = Math.floor(version / 7) + 2;
    modules -= (25 * align - 10) * align - 55;
    if (version >= 7) modules -= 36;
  }
  return Math.floor(modules / 8);
}

function dataCapacity(version) {
  return totalCodewords(version) - BLOCKS[version] * ECC_PER_BLOCK[version];
}

// The byte count's length in bits: 8 up to version 9, 16 from version 10.
function countBits(version) {
  return version <= 9 ? 8 : 16;
}

// Mode, count, data, terminator and padding, as codewords.
function dataCodewords(version, bytes) {
  const capacity = dataCapacity(version);
  const bits = [];
  const put = (value, length) => {
    for (let i = length - 1; i >= 0; i--) bits.push((value >>> i) & 1);
  };
  put(0b0100, 4);
  put(bytes.length, countBits(version));
  for (const b of bytes) put(b, 8);
  put(0, Math.min(4, capacity * 8 - bits.length));
  put(0, (8 - (bits.length % 8)) % 8);
  const out = [];
  for (let i = 0; i < bits.length; i += 8) out.push(bits.slice(i, i + 8).reduce((a, b) => (a << 1) | b, 0));
  for (let pad = 0xec; out.length < capacity; pad ^= 0xec ^ 0x11) out.push(pad);
  return out;
}

// The data split into blocks, each with its error correction, interleaved. Where the data does not
// divide evenly, the last blocks hold one codeword more.
function withCorrection(version, data) {
  const blocks = BLOCKS[version];
  const ecc = ECC_PER_BLOCK[version];
  const short = Math.floor(data.length / blocks);
  const shortBlocks = blocks - (data.length % blocks);
  const divisor = rsDivisor(ecc);
  const split = [];
  for (let b = 0, start = 0; b < blocks; b++) {
    const block = data.slice(start, start + short + (b < shortBlocks ? 0 : 1));
    start += block.length;
    split.push([block, rsRemainder(block, divisor)]);
  }
  const out = [];
  for (let i = 0; i <= short; i++) for (const [block] of split) if (i < block.length) out.push(block[i]);
  for (let i = 0; i < ecc; i++) for (const [, correction] of split) out.push(correction[i]);
  return out;
}

// The centres of the alignment patterns along each axis: none in version 1.
function alignmentPositions(version, size) {
  if (version === 1) return [];
  const count = Math.floor(version / 7) + 2;
  const step = Math.floor((version * 8 + count * 3 + 5) / (count * 4 - 4)) * 2;
  const positions = [6];
  for (let p = size - 7; positions.length < count; p -= step) positions.splice(1, 0, p);
  return positions;
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

// The finder, timing and alignment patterns, the dark module, the version information from
// version 7, and the format areas reserved.
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
  const centres = alignmentPositions(version, size);
  const last = centres.length - 1;
  centres.forEach((cy, j) =>
    centres.forEach((cx, i) => {
      // Not where a finder pattern is.
      if ((i === 0 && j === 0) || (i === 0 && j === last) || (i === last && j === 0)) return;
      for (let dy = -2; dy <= 2; dy++) {
        for (let dx = -2; dx <= 2; dx++) set(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
      }
    }),
  );
  if (version >= 7) {
    let remainder = version;
    for (let i = 0; i < 12; i++) remainder = (remainder << 1) ^ ((remainder >>> 11) * 0x1f25);
    const bits = (version << 12) | remainder;
    for (let i = 0; i < 18; i++) {
      const dark = ((bits >>> i) & 1) === 1;
      const a = size - 11 + (i % 3);
      const b = Math.floor(i / 3);
      set(a, b, dark);
      set(b, a, dark);
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

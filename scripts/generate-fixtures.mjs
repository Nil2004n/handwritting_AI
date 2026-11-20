/**
 * Phase 0 synthetic fixture-image generator.
 *
 * Generates original, clearly synthetic "notebook" PNGs with a hand-rolled
 * PNG encoder (Node builtins only: node:zlib, node:fs). No dependencies,
 * no network, deterministic output (seeded PRNG).
 *
 * The drawings are abstract ink-like strokes, NOT handwriting samples, and
 * are labeled synthetic everywhere. They exist only so block regions align
 * with visible marks on screen; they demonstrate nothing about OCR quality.
 *
 * Layout constants below MUST match the bounds in src/fixtures/documents/*.
 * Run: `node scripts/generate-fixtures.mjs`
 * Verify dims: the script prints IHDR dimensions it actually wrote.
 */

import { deflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const outDir = join(root, 'src', 'fixtures', 'images');
mkdirSync(outDir, { recursive: true });

// --- Seeded PRNG (mulberry32) for deterministic output ---
function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// --- Minimal canvas over an RGB buffer ---
function makeCanvas(w, h) {
  const buf = Buffer.alloc(w * h * 3);
  const px = (x, y, r, g, b) => {
    x = Math.round(x);
    y = Math.round(y);
    if (x < 0 || y < 0 || x >= w || y >= h) return;
    const i = (y * w + x) * 3;
    buf[i] = r;
    buf[i + 1] = g;
    buf[i + 2] = b;
  };
  const fill = (r, g, b) => {
    for (let i = 0; i < w * h; i++) {
      buf[i * 3] = r;
      buf[i * 3 + 1] = g;
      buf[i * 3 + 2] = b;
    }
  };
  const dot = (x, y, rad, r, g, b) => {
    for (let dy = -rad; dy <= rad; dy++) {
      for (let dx = -rad; dx <= rad; dx++) {
        if (dx * dx + dy * dy <= rad * rad) px(x + dx, y + dy, r, g, b);
      }
    }
  };
  const thickLine = (x0, y0, x1, y1, rad, r, g, b) => {
    const steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0), 1);
    for (let s = 0; s <= steps; s++) {
      const t = s / steps;
      dot(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, rad, r, g, b);
    }
  };
  return { w, h, buf, px, fill, dot, thickLine };
}

const PAPER = [247, 243, 232];
const RULE = [205, 198, 182];
const INK = [43, 58, 85];
const GRID = [120, 130, 150];

function paintPaper(c, rand) {
  c.fill(...PAPER);
  // Subtle deterministic grain.
  for (let i = 0; i < c.w * c.h; i += 7) {
    const v = Math.floor(rand() * 10) - 5;
    c.buf[i * 3] += v;
    c.buf[i * 3 + 1] += v;
    c.buf[i * 3 + 2] += v;
  }
}

/** One wavy handwriting-like stroke across [x0,x1] at base y. */
function scribbleLine(c, rand, x0, x1, y, amp, rad = 3) {
  const words = 3 + Math.floor(rand() * 3);
  const segLen = (x1 - x0) / words;
  for (let wi = 0; wi < words; wi++) {
    const sx = x0 + wi * segLen + 4;
    const ex = x0 + (wi + 1) * segLen - 6;
    const steps = Math.floor(ex - sx);
    const phase = rand() * Math.PI * 2;
    const freq = 2 + rand() * 2;
    for (let s = 0; s <= steps; s++) {
      const t = s / steps;
      const yy =
        y +
        Math.sin(t * Math.PI * freq + phase) * amp -
        Math.sin(t * Math.PI) * amp * 0.4;
      c.dot(sx + s, yy, rad, ...INK);
    }
    // Occasional ascender/descender tick.
    if (rand() < 0.7) {
      const tx = sx + rand() * (ex - sx);
      const up = rand() < 0.5;
      c.thickLine(tx, y, tx + (rand() * 8 - 4), y + (up ? -1 : 1) * (14 + rand() * 12), 2, ...INK);
    }
    // Occasional dot (i/j).
    if (rand() < 0.4) {
      c.dot(sx + rand() * (ex - sx), y - 16 - rand() * 6, 2, ...INK);
    }
    // Word gap.
    if (wi < words - 1) {
      const gx = ex + 2;
      c.thickLine(gx, y - 2, gx + 3, y - 2, 1, ...PAPER);
    }
  }
}

/** Ruled guide line (light, full width). */
function rule(c, y) {
  for (let x = 40; x < c.w - 40; x++) c.dot(x, y, 1, ...RULE);
}

/** "DEMO" watermark tag so screenshots are visibly synthetic. */
function demoTag(c, text = 'SYNTHETIC DEMO') {
  // Simple block-letter tag drawn as small rects along the top edge is
  // overkill; instead darken a strip-free corner marker: a small hollow box.
  const x0 = c.w - 150;
  const y0 = 24;
  for (let x = x0; x < x0 + 126; x++) {
    c.dot(x, y0, 1, ...GRID);
    c.dot(x, y0 + 30, 1, ...GRID);
  }
  for (let y = y0; y <= y0 + 30; y++) {
    c.dot(x0, y, 1, ...GRID);
    c.dot(x0 + 126, y, 1, ...GRID);
  }
  void text;
}

// --- PNG encoding (truecolor RGB, filter 0) ---
const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc(bytes) {
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const cs = Buffer.alloc(4);
  cs.writeUInt32BE(crc(td));
  return Buffer.concat([len, td, cs]);
}

function encodePng(c) {
  const raw = Buffer.alloc(c.h * (1 + c.w * 3));
  for (let y = 0; y < c.h; y++) {
    raw[y * (1 + c.w * 3)] = 0;
    c.buf.copy(raw, y * (1 + c.w * 3) + 1, y * c.w * 3, (y + 1) * c.w * 3);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(c.w, 0);
  ihdr.writeUInt32BE(c.h, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // truecolor
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

function save(name, c) {
  const p = join(outDir, name);
  writeFileSync(p, encodePng(c));
  console.log(`${name}: wrote ${c.w}x${c.h}`);
}

// --- Layouts (MUST match src/fixtures/documents/*.ts) ---

function prose(rand) {
  const c = makeCanvas(900, 1200);
  paintPaper(c, rand);
  for (let i = 0; i < 6; i++) {
    const y = 140 + i * 140;
    rule(c, y + 62);
    scribbleLine(c, rand, 90, 810, y + 30, 9);
    scribbleLine(c, rand, 90, 620 + rand() * 150, y + 78, 8);
  }
  demoTag(c);
  return c;
}

function numericals(rand) {
  const c = makeCanvas(900, 1200);
  paintPaper(c, rand);
  // Statement band.
  scribbleLine(c, rand, 90, 810, 150, 9);
  scribbleLine(c, rand, 90, 700, 198, 8);
  // Step rows.
  for (let i = 0; i < 4; i++) {
    const y = 300 + i * 180;
    rule(c, y + 92);
    scribbleLine(c, rand, 130, 760, y + 40, 8);
    scribbleLine(c, rand, 130, 640, y + 82, 7);
  }
  // Circle annotation flagging the ambiguous character (row index 2).
  const cy = 300 + 2 * 180 + 55;
  for (let a = 0; a < Math.PI * 2; a += 0.02) {
    c.dot(740 + Math.cos(a) * 46, cy + Math.sin(a) * 40, 2, 150, 60, 60);
  }
  // Final-result band.
  scribbleLine(c, rand, 130, 700, 1055, 9);
  demoTag(c);
  return c;
}

function table(rand) {
  const c = makeCanvas(1200, 900);
  paintPaper(c, rand);
  const gx = 150;
  const gy = 170;
  const cw = 300;
  const rh = 140;
  const rows = 4;
  const cols = 3;
  for (let r = 0; r <= rows; r++) {
    c.thickLine(gx, gy + r * rh, gx + cols * cw, gy + r * rh, 2, ...GRID);
  }
  for (let col = 0; col <= cols; col++) {
    c.thickLine(gx + col * cw, gy, gx + col * cw, gy + rows * rh, 2, ...GRID);
  }
  // Cell scribbles; cell (row 2, col 1) stays blank.
  for (let r = 0; r < rows; r++) {
    for (let col = 0; col < cols; col++) {
      if (r === 2 && col === 1) continue; // blank cell
      const short = r === 0 || rand() < 0.5;
      scribbleLine(c, rand, gx + col * cw + 24, gx + col * cw + (short ? 170 : 250), gy + r * rh + 62, 7, 2);
    }
  }
  demoTag(c);
  return c;
}

function equation(rand) {
  const c = makeCanvas(900, 700);
  paintPaper(c, rand);
  // Band 1: fraction-like figure.
  scribbleLine(c, rand, 150, 750, 150, 8);
  c.thickLine(140, 210, 760, 210, 3, ...INK); // vinculum
  scribbleLine(c, rand, 220, 680, 252, 8);
  // Band 2: sqrt-like figure + superscript blob.
  c.thickLine(150, 470, 190, 520, 4, ...INK);
  c.thickLine(190, 520, 230, 430, 4, ...INK);
  c.thickLine(230, 430, 720, 430, 3, ...INK); // overline
  scribbleLine(c, rand, 260, 660, 480, 7);
  c.dot(690, 408, 8, ...INK); // superscript-ish mark
  c.dot(716, 400, 5, ...INK);
  demoTag(c);
  return c;
}

function mixed(rand) {
  const c = makeCanvas(900, 1300);
  paintPaper(c, rand);
  // Text band.
  scribbleLine(c, rand, 90, 810, 140, 9);
  scribbleLine(c, rand, 90, 720, 190, 8);
  scribbleLine(c, rand, 90, 770, 240, 8);
  // Table grid: origin (150,420), 3x3 of 200x120.
  const gx = 150;
  const gy = 420;
  const cw = 200;
  const rh = 120;
  for (let r = 0; r <= 3; r++) c.thickLine(gx, gy + r * rh, gx + 3 * cw, gy + r * rh, 2, ...GRID);
  for (let col = 0; col <= 3; col++) c.thickLine(gx + col * cw, gy, gx + col * cw, gy + 3 * rh, 2, ...GRID);
  for (let r = 0; r < 3; r++) {
    for (let col = 0; col < 3; col++) {
      if (r === 1 && col === 2) continue; // blank cell
      scribbleLine(c, rand, gx + col * cw + 20, gx + col * cw + 150, gy + r * rh + 58, 6, 2);
    }
  }
  // Equation band.
  scribbleLine(c, rand, 150, 700, 950, 8);
  c.thickLine(140, 1005, 760, 1005, 3, ...INK);
  scribbleLine(c, rand, 230, 640, 1050, 8);
  demoTag(c);
  return c;
}

const jobs = { 'prose.png': prose, 'numericals.png': numericals, 'table.png': table, 'equation.png': equation, 'mixed.png': mixed };
for (const [name, fn] of Object.entries(jobs)) {
  save(name, fn(mulberry32(20261120)));
}
console.log('done ->', outDir);

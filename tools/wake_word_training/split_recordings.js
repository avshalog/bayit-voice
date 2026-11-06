#!/usr/bin/env node
// Splits long recordings of a repeated phrase into one clip per utterance, for wake-word training.
//
// Input: 16 kHz mono 16-bit PCM WAV files (convert first, e.g.
//   ffmpeg -i in.m4a -ac 1 -ar 16000 -c:a pcm_s16le in.wav).
// Output: <out_dir>/<input name>_<nnn>.wav, each utterance with a short pad of the recording's own
// background on both sides, plus segments.csv listing every clip.
//
// Usage: node split_recordings.js <out_dir> <in.wav> [more.wav ...]
// Tuning (environment): SPLIT_DB (dB above the noise floor that counts as speech, default 12),
//   SPLIT_GAP_MS (shorter pauses stay inside one utterance, default 350),
//   SPLIT_MIN_MS / SPLIT_MAX_MS (utterances outside this range are skipped, default 300 / 2500),
//   SPLIT_PAD_MS (padding, default 200).

const fs = require("fs");
const path = require("path");

const RATE = 16000;
const FRAME = 320; // 20 ms
const num = (name, def) => (process.env[name] ? Number(process.env[name]) : def);
const DB = num("SPLIT_DB", 12);
const GAP = num("SPLIT_GAP_MS", 350) / 20;
const MIN = num("SPLIT_MIN_MS", 300) / 20;
const MAX = num("SPLIT_MAX_MS", 2500) / 20;
const PAD = Math.round((num("SPLIT_PAD_MS", 200) / 1000) * RATE);

function readWav(file) {
  const buf = fs.readFileSync(file);
  if (buf.toString("ascii", 0, 4) !== "RIFF" || buf.toString("ascii", 8, 12) !== "WAVE") throw new Error(`${file}: not a WAV file`);
  let off = 12, fmt = null;
  while (off + 8 <= buf.length) {
    const id = buf.toString("ascii", off, off + 4);
    const size = buf.readUInt32LE(off + 4);
    if (id === "fmt ") fmt = { channels: buf.readUInt16LE(off + 10), rate: buf.readUInt32LE(off + 12), bits: buf.readUInt16LE(off + 22) };
    if (id === "data") {
      if (!fmt || fmt.channels !== 1 || fmt.rate !== RATE || fmt.bits !== 16) throw new Error(`${file}: needs 16 kHz mono 16-bit PCM`);
      const n = Math.floor(Math.min(size, buf.length - off - 8) / 2);
      const s = new Int16Array(n);
      for (let i = 0; i < n; i++) s[i] = buf.readInt16LE(off + 8 + i * 2);
      return s;
    }
    off += 8 + size + (size & 1);
  }
  throw new Error(`${file}: no data chunk`);
}

function writeWav(file, samples) {
  const buf = Buffer.alloc(44 + samples.length * 2);
  buf.write("RIFF", 0); buf.writeUInt32LE(36 + samples.length * 2, 4); buf.write("WAVE", 8);
  buf.write("fmt ", 12); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20); buf.writeUInt16LE(1, 22);
  buf.writeUInt32LE(RATE, 24); buf.writeUInt32LE(RATE * 2, 28); buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34);
  buf.write("data", 36); buf.writeUInt32LE(samples.length * 2, 40);
  for (let i = 0; i < samples.length; i++) buf.writeInt16LE(samples[i], 44 + i * 2);
  fs.writeFileSync(file, buf);
}

// Loudness per frame, measured on the speech band (about 200–3500 Hz) so a hum, fan or hiss
// does not hide the pauses between utterances. The clips themselves keep the full signal.
function frameDb(s) {
  const hpA = Math.exp((-2 * Math.PI * 200) / RATE);
  const lpA = Math.exp((-2 * Math.PI * 3500) / RATE);
  const band = new Float64Array(s.length);
  let hpPrevIn = 0, hpPrevOut = 0, lp = 0;
  for (let i = 0; i < s.length; i++) {
    const hp = hpA * (hpPrevOut + s[i] - hpPrevIn);
    hpPrevIn = s[i]; hpPrevOut = hp;
    lp = (1 - lpA) * hp + lpA * lp;
    band[i] = lp;
  }
  const out = new Float64Array(Math.floor(s.length / FRAME));
  for (let f = 0; f < out.length; f++) {
    let sum = 0;
    for (let i = f * FRAME; i < (f + 1) * FRAME; i++) sum += band[i] * band[i];
    out[f] = 10 * Math.log10(sum / FRAME / (32768 * 32768) + 1e-12);
  }
  return out;
}

function segments(db) {
  const sorted = Array.from(db).sort((a, b) => a - b);
  const floor = sorted[Math.floor(sorted.length * 0.1)];
  const thr = floor + DB;
  const raw = [];
  let start = -1;
  for (let f = 0; f <= db.length; f++) {
    const on = f < db.length && db[f] > thr;
    if (on && start < 0) start = f;
    if (!on && start >= 0) { raw.push([start, f]); start = -1; }
  }
  const merged = [];
  for (const seg of raw) {
    const last = merged[merged.length - 1];
    if (last && seg[0] - last[1] < GAP) last[1] = seg[1];
    else merged.push([...seg]);
  }
  return { floor, thr, segs: merged };
}

const [outDir, ...inputs] = process.argv.slice(2);
if (!outDir || inputs.length === 0) {
  console.error("Usage: node split_recordings.js <out_dir> <in.wav> [more.wav ...]");
  process.exit(1);
}
fs.mkdirSync(outDir, { recursive: true });
const rows = ["clip,source,start_s,end_s,duration_s,peak_db"];
for (const file of inputs) {
  const s = readWav(file);
  const db = frameDb(s);
  const { floor, thr, segs } = segments(db);
  const base = path.basename(file, path.extname(file));
  let kept = 0, short = 0, long = 0;
  for (const [a, b] of segs) {
    const len = b - a;
    if (len < MIN) { short++; continue; }
    if (len > MAX) { long++; continue; }
    const from = Math.max(0, a * FRAME - PAD);
    const to = Math.min(s.length, b * FRAME + PAD);
    const name = `${base}_${String(++kept).padStart(3, "0")}.wav`;
    writeWav(path.join(outDir, name), s.subarray(from, to));
    let peak = -Infinity;
    for (let f = a; f < b; f++) peak = Math.max(peak, db[f]);
    rows.push([name, path.basename(file), (from / RATE).toFixed(2), (to / RATE).toFixed(2), ((to - from) / RATE).toFixed(2), peak.toFixed(1)].join(","));
  }
  console.log(`${base}: ${kept} clips (noise floor ${floor.toFixed(1)} dB, threshold ${thr.toFixed(1)} dB; skipped ${short} too short, ${long} too long)`);
}
fs.writeFileSync(path.join(outDir, "segments.csv"), rows.join("\n") + "\n");

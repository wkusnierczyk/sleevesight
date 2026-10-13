// Runs the simulator's own `normalize` over a list of titles and artists and prints the results as
// JSON: the expected output of the `:core` normalizer's tests. The input is a playlist (an object
// with `tracks`) or an array of `{ "title", "artist" }`, numbered from 1. From the repository root:
//   node fixtures/below-soundtrack/normalized.js design/simulator.html \
//     fixtures/below-soundtrack/playlist.json > fixtures/below-soundtrack/normalized.json
//   node fixtures/below-soundtrack/normalized.js design/simulator.html \
//     core/src/test/resources/normalizer/edge-cases.json \
//     > core/src/test/resources/normalizer/edge-cases.normalized.json
const fs = require("fs");
const [html, input] = process.argv.slice(2).map(p => fs.readFileSync(p, "utf8"));
const start = html.indexOf("const SUFFIX_RE");
const end = html.indexOf("/* ================= Sources");
eval(html.slice(start, end) + "; globalThis.normalize = normalize;");
const parsed = JSON.parse(input);
const entries = Array.isArray(parsed) ? parsed.map((t, i) => ({ n: i + 1, ...t })) : parsed.tracks;
const out = entries.map(t => {
  const n = globalThis.normalize(t.title, t.artist);
  return { n: t.n, title: t.title, artist: t.artist, normalized: { title: n.title, artists: n.artists, primary: n.primary, from: n.from, key: n.key, notes: n.notes } };
});
console.log(JSON.stringify(out, null, 1));

// Runs the simulator's own `normalize` over every playlist entry and prints the results as JSON:
// the expected output of the `:core` normalizer's tests. From the repository root:
//   node fixtures/below-soundtrack/normalized.js design/simulator.html \
//     fixtures/below-soundtrack/playlist.json > fixtures/below-soundtrack/normalized.json
const fs = require("fs");
const [html, playlist] = process.argv.slice(2).map(p => fs.readFileSync(p, "utf8"));
const start = html.indexOf("const SUFFIX_RE");
const end = html.indexOf("/* ================= Sources");
eval(html.slice(start, end) + "; globalThis.normalize = normalize;");
const out = JSON.parse(playlist).tracks.map(t => {
  const n = globalThis.normalize(t.title, t.artist);
  return { n: t.n, title: t.title, artist: t.artist, normalized: { title: n.title, artists: n.artists, primary: n.primary, from: n.from, key: n.key, notes: n.notes } };
});
console.log(JSON.stringify(out, null, 1));

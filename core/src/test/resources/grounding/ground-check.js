// Runs the simulator's own `splitSentences` and `groundCheck`, and its `NO_EVIDENCE` test, over
// model replies and prints the results as JSON: the expected output of the `:core` grounding
// check's tests. From the repository root:
//   node core/src/test/resources/grounding/ground-check.js design/simulator.html \
//     core/src/test/resources/grounding/replies.json > core/src/test/resources/grounding/replies.checked.json
// The input is an array of `{ "name", "reply", "ids", "allowModel" }`.
const fs = require("fs");
const [html, input] = process.argv.slice(2).map(p => fs.readFileSync(p, "utf8"));
const start = html.indexOf("function splitSentences(text)");
const end = html.indexOf("const countChecks");
if (start < 0 || end < start) throw new Error("splitSentences and groundCheck not found in the simulator");
eval(html.slice(start, end) + "; globalThis.splitSentences = splitSentences; globalThis.groundCheck = groundCheck;");
// The `NO_EVIDENCE` test sits inside the simulator's `write`; it is taken from there, not copied.
const none = html.match(/none = (\/\^NO_EVIDENCE\\b\/)\.test\(t\)/);
if (!none) throw new Error("the NO_EVIDENCE test was not found in the simulator");
const noEvidence = eval(none[1]);
const out = JSON.parse(input).map(c => {
  const t = c.reply.trim(), isNone = noEvidence.test(t);
  const evidence = c.ids.map(id => ({ id }));
  return { name: c.name, none: isNone, paragraphs: isNone ? [] : globalThis.groundCheck(t, evidence, c.allowModel) };
});
console.log(JSON.stringify(out, null, 1));

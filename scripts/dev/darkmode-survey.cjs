#!/usr/bin/env node
/** Survey bare black/white alphas OUTSIDE dark-mode blocks (true remaining
 * migration work) vs inside dark blocks (legitimate patches). */
const fs = require('fs'), path = require('path');
function walk(d) {
  let out = [];
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) out = out.concat(walk(p));
    else if (p.endsWith('.less')) out.push(p);
  }
  return out;
}
const RE_ALL = /rgba\((0,\s*0,\s*0|255,\s*255,\s*255|26,\s*23,\s*22|43,\s*18,\s*0|234,\s*232,\s*231)/g;
const RE_VAR = /var\(--majo[^)]*rgba\((0,\s*0,\s*0|255,\s*255,\s*255|26,\s*23,\s*22|43,\s*18,\s*0|234,\s*232,\s*231)/g;
const rows = [];
let totalBase = 0;
for (const f of walk('src')) {
  const lines = fs.readFileSync(f, 'utf8').split('\n');
  const inDark = new Array(lines.length).fill(false);
  let depth = 0, darkDepth = -1;
  lines.forEach((line, i) => {
    if (darkDepth >= 0 && depth > darkDepth) inDark[i] = true;
    if (/dark-mode/.test(line) && darkDepth < 0) darkDepth = depth;
    for (const ch of line) {
      if (ch === '{') depth++;
      else if (ch === '}') { depth--; if (darkDepth >= 0 && depth <= darkDepth) darkDepth = -1; }
    }
    if (darkDepth >= 0) inDark[i] = true;
  });
  let baseBare = 0, darkCount = 0;
  for (let i = 0; i < lines.length; i++) {
    const all = (lines[i].match(RE_ALL) || []).length;
    if (!all) continue;
    const iv = (lines[i].match(RE_VAR) || []).length;
    const bare = all - iv;
    if (inDark[i]) darkCount += bare;
    else baseBare += bare;
  }
  if (baseBare > 0) rows.push({ f: f.split(path.sep).join('/'), baseBare, darkCount });
  totalBase += baseBare;
}
rows.sort((a, b) => b.baseBare - a.baseBare);
for (const r of rows.slice(0, 18)) {
  console.log(String(r.baseBare).padStart(4), '(dark-block:', String(r.darkCount) + ')', r.f);
}
console.log('--- true remaining (base-region bare):', totalBase, 'in', rows.length, 'files');

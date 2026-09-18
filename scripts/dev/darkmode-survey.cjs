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
for (const f of walk('src')) {
  const s = fs.readFileSync(f, 'utf8');
  const all = (s.match(RE_ALL) || []).length;
  const inVar = (s.match(RE_VAR) || []).length;
  const dark = (s.match(/dark-mode/g) || []).length;
  if (all > 0 || dark > 1) rows.push({ f: f.split(path.sep).join('/'), bare: all - inVar, fallback: inVar, dark });
}
rows.sort((a, b) => b.bare - a.bare);
for (const r of rows.slice(0, 22)) {
  console.log(String(r.bare).padStart(4), String(r.fallback).padStart(4), String(r.dark).padStart(3), r.f);
}
const tb = rows.reduce((s, r) => s + r.bare, 0);
console.log('--- total bare:', tb, ' files:', rows.length);

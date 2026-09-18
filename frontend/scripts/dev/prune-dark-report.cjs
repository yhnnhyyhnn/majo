#!/usr/bin/env node
/**
 * B1b helper: report which rules inside :global(.dark-mode) blocks are
 * redundant after B1a base tokenization, i.e. the base region styles the
 * SAME selector with a SUPERSET of the dark rule's properties.
 *
 * Usage: node scripts/dev/prune-dark-report.cjs <file.less>
 */
const fs = require('fs');

const file = process.argv[2];
const lines = fs.readFileSync(file, 'utf8').split('\n');

// ── dark regions (brace tracking identical to tokenize-alpha.cjs) ──
const inDark = new Array(lines.length).fill(false);
let depth = 0;
let darkDepth = -1;
lines.forEach((line, i) => {
  if (darkDepth >= 0 && depth > darkDepth) inDark[i] = true;
  if (/dark-mode/.test(line) && darkDepth < 0) darkDepth = depth;
  for (const ch of line) {
    if (ch === '{') depth++;
    else if (ch === '}') {
      depth--;
      if (darkDepth >= 0 && depth <= darkDepth) darkDepth = -1;
    }
  }
  if (darkDepth >= 0) inDark[i] = true;
});

// ── base rules: selector → property set (single-level class blocks) ──
const baseProps = new Map();
{
  let sel = '';
  let buf = [];
  let brace = 0;
  lines.forEach((line, i) => {
    if (inDark[i]) return;
    if (brace === 0) {
      const m = line.match(/^([^{}\s][^{}]*?)\s*\{\s*$/);
      if (m && /\./.test(m[1]) && !/[>~+:]$/.test(m[1].trim()) && !m[1].includes(':global')) {
        sel = m[1].trim();
        brace = 1;
        buf = [];
      }
      return;
    }
    const decl = line.match(/^\s*([a-z-]+)\s*:\s*([^;]+);/);
    if (decl) buf.push([decl[1], decl[2].trim()]);
    brace += (line.match(/\{/g) || []).length - (line.match(/\}/g) || []).length;
    if (brace <= 0) {
      if (sel && buf.length) {
        if (!baseProps.has(sel)) baseProps.set(sel, new Map());
        const m = baseProps.get(sel);
        buf.forEach(([pp, vv]) => m.set(pp, vv));
      }
      sel = '';
      buf = [];
      brace = 0;
    }
  });
}

// ── walk dark regions with relative depth ──
const report = [];
let rel = 0;
let curSel = '';
let curProps = null;
let curStart = -1;
for (let i = 0; i < lines.length; i++) {
  if (!inDark[i]) continue;
  const opens = (lines[i].match(/\{/g) || []).length;
  const closes = (lines[i].match(/\}/g) || []).length;

  if (rel === 1) {
    const m = lines[i].trim().match(/^(\.[^{},:]+)\s*\{$/);
    // Multi-selector rules may span lines (`.a,\n.b {`) — if the previous
    // non-empty line ends with a comma, this opener continues that list.
    let prevIsComma = false;
    for (let j = i - 1; j >= 0; j--) {
      const t = lines[j].trim();
      if (!t) continue;
      prevIsComma = t.endsWith(',');
      break;
    }
    if (m && opens === 1 && closes === 0 && !prevIsComma) {
      curSel = m[1].trim();
      curProps = new Set();
      curStart = i + 1; // 1-based line of the opener itself
      rel += opens;
      continue;
    }
  }
  if (rel >= 2 && curStart > 0) {
    const decl = lines[i].match(/^\s*([a-z-]+)\s*:/);
    if (decl) curProps.add(decl[1]);
  }
  rel += opens - closes;
  if (curStart > 0 && rel === 1) {
    const base = baseProps.get(curSel);
    const covered = base && [...curProps].every((p) =>
      base.has(p) && base.get(p).includes('var(--majo-color-'));
    report.push({ sel: curSel, props: [...curProps].join(','), start: curStart, end: i + 1, covered: !!covered });
    curSel = '';
    curProps = null;
    curStart = -1;
  }
}

let keep = 0, prune = 0;
for (const r of report) {
  if (r.covered) {
    prune++;
    console.log(`prune ${r.start}-${r.end}  ${r.sel}  (${r.props})`);
  } else {
    keep++;
    console.log(`KEEP  ${r.start}-${r.end}  ${r.sel}  (${r.props})`);
  }
}
console.error(`--- ${report.length} rules: ${prune} prunable, ${keep} keep`);

if (process.argv.includes('--apply')) {
  // Delete prunable ranges bottom-up so earlier line numbers stay valid.
  const ranges = report.filter((r) => r.covered).sort((a, b) => b.start - a.start);
  for (const r of ranges) {
    lines.splice(r.start - 1, r.end - r.start + 1);
  }
  // Collapse runs of blank lines left behind inside the dark block.
  let out = lines.join('\n').replace(/\n{3,}/g, '\n\n');
  // Drop dark wrappers that ended up empty (`:global(.dark-mode) { }`).
  out = out.replace(/(\n?):global\(\.dark-mode\)\s*\{\s*\}/g, '');
  fs.writeFileSync(file, out);
  console.error(`applied: removed ${ranges.length} rules from ${file}`);
}

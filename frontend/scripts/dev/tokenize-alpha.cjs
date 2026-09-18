#!/usr/bin/env node
/**
 * B1a base-region alpha/hex tokenizer for the dark-mode migration plan
 * (AI-Coding-Agent-Spec/05-Engineering/dark-mode-token-migration-plan.md).
 *
 * Transforms declarations in BASE regions only (outside :global(.dark-mode)
 * blocks): color/background/border properties get semantic antd tokens with
 * the original value kept as the var() fallback. Glass whites
 * (rgba(255,255,255,…) with backdrop-filter intent), constant-dark panels,
 * active tints rgba(43,18,0,0.08) and accent vars are deliberately left
 * alone. Dark-mode regions are untouched (pruned later in B1b once base is
 * tokenized).
 *
 * Usage: node scripts/dev/tokenize-alpha.cjs [--apply] <file…>
 * Without --apply prints a unified diff instead of writing.
 */
const fs = require('fs');

const TEXT = (fb) => `var(--majo-color-text, ${fb})`;
const TEXT2 = (fb) => `var(--majo-color-text-secondary, ${fb})`;
const TEXT3 = (fb) => `var(--majo-color-text-tertiary, ${fb})`;
const TEXT4 = (fb) => `var(--majo-color-text-quaternary, ${fb})`;
const FILL4 = (fb) => `var(--majo-color-fill-quaternary, ${fb})`;
const FILL3 = (fb) => `var(--majo-color-fill-tertiary, ${fb})`;
const FILL2 = (fb) => `var(--majo-color-fill-secondary, ${fb})`;
const FILL = (fb) => `var(--majo-color-fill, ${fb})`;
const BORD2 = (fb) => `var(--majo-color-border-secondary, ${fb})`;
const BORD = (fb) => `var(--majo-color-border, ${fb})`;

/** Text tier by black-alpha value. */
function textToken(a, fb) {
  if (a >= 0.8) return TEXT(fb);
  if (a >= 0.5) return TEXT2(fb);
  if (a >= 0.37) return TEXT3(fb);
  return TEXT4(fb);
}
/** Fill tier by black-alpha value (backgrounds). */
function fillToken(a, fb) {
  if (a <= 0.035) return FILL4(fb);
  if (a <= 0.05) return FILL3(fb);
  if (a <= 0.09) return FILL2(fb);
  return FILL(fb);
}

// Hex allowlist → token (property-disambiguated by the caller).
// 3-digit forms normalize to 6 before lookup (#999 → #999999).
const HEX_TEXT = new Map([
  ['#1f2937', TEXT], ['#111827', TEXT], ['#1a1716', TEXT], ['#374151', TEXT2],
  ['#4b5563', TEXT2], ['#6b7280', TEXT2], ['#7a7f8f', TEXT3], ['#9ca3af', TEXT3],
  ['#333333', TEXT], ['#666666', TEXT2], ['#999999', TEXT3], ['#cccccc', TEXT4],
]);
const HEX_BG = new Map([
  ['#fafafa', FILL4], ['#f9f8f4', FILL4], ['#f3f4f6', FILL3], ['#f5f5f5', FILL3],
  ['#f0f0f0', FILL3], ['#e5e7eb', FILL2], ['#eae9e7', BORD2], ['#eae8e7', BORD2],
  ['#eeeeee', FILL3], ['#ffffff', FILL4],
]);
const HEX_BORDER = new Map([
  ['#eae9e7', BORD2], ['#eae8e7', BORD2], ['#e5e7eb', BORD2], ['#d1d5db', BORD],
  ['#f0f0f0', BORD2], ['#d9d9d9', BORD], ['#dddddd', BORD2], ['#cccccc', BORD],
]);
const HEX_BGC = new Map([
  ['#ffffff', 'var(--majo-color-bg-container, #ffffff)'],
  ['#fff', 'var(--majo-color-bg-container, #fff)'],
]);

function normalizeHex(h) {
  if (h.length === 4) {
    return '#' + h[1] + h[1] + h[2] + h[2] + h[3] + h[3];
  }
  return h.toLowerCase();
}

function mapDeclaration(line) {
  // Skip anything already tokenized, accent-based, or in the keep-list.
  if (line.includes('var(--majo') || line.includes('rgba(var(')) return line;
  const m = line.match(/^(\s*)(color|background|background-color|border|border-bottom|border-top|border-left|border-right|border-color|border-bottom-color|border-top-color|border-left-color|border-right-color|outline)\s*:\s*([^;]+);(.*)$/);
  if (!m) return line;
  const [, indent, prop, valueRaw, tail] = m;
  const value = valueRaw.trim();
  const isBorder = prop.startsWith('border') || prop === 'outline';
  const isColor = prop === 'color';
  const isBg = prop.startsWith('background');

  // Active tint keeps its deliberate literal (light: neutral brown, dark: accent).
  if (/rgba\(43,\s*18,\s*0,\s*0\.08\)/.test(value)) return line;

  let token = null;
  let matchText = null;

  // 1) Black-alpha (and brand-brown variants) by tier and property.
  const black = value.match(/rgba\((0,\s*0,\s*0|26,\s*23,\s*22|43,\s*18,\s*0),\s*(0?\.\d+)\)/);
  if (black) {
    const a = parseFloat(black[2]);
    matchText = black[0];
    if (isColor) token = textToken(a, matchText);
    else if (isBorder) token = a <= 0.12 ? BORD2(matchText) : BORD(matchText);
    else if (isBg) token = fillToken(a, matchText);
  }

  // 2) Hex by property. Exact-value only (no gradients/multi-values).
  //    #1a1a1a backgrounds are constant-dark panels — never mapped.
  if (!token && /^[#][0-9a-fA-F]{3}([0-9a-fA-F]{3})?$/.test(value.trim())) {
    const h = normalizeHex(value.trim());
    if (isColor && HEX_TEXT.has(h)) token = HEX_TEXT.get(h)(h);
    else if (isBorder && HEX_BORDER.has(h)) token = HEX_BORDER.get(h)(h);
    else if (isBg && h !== '#1a1a1a') {
      if (HEX_BGC.has(value.trim().toLowerCase())) {
        token = HEX_BGC.get(value.trim().toLowerCase());
      } else if (HEX_BG.has(h)) {
        token = HEX_BG.get(h)(h);
      }
    }
    if (token) matchText = value.trim();
  }

  if (!token) return line;
  // Substring-level replace: preserve widths/styles around the color.
  return `${indent}${prop}: ${value.replace(matchText, token)};${tail}`;
}

/** True when line index i sits inside a :global(.dark-mode) region. */
function darkRegions(lines) {
  const inDark = new Array(lines.length).fill(false);
  let depth = 0;
  let darkDepth = -1;
  lines.forEach((line, i) => {
    if (darkDepth >= 0 && depth > darkDepth) inDark[i] = true;
    if (/dark-mode/.test(line) && darkDepth < 0) {
      darkDepth = depth;
    }
    for (const ch of line) {
      if (ch === '{') depth++;
      else if (ch === '}') {
        depth--;
        if (darkDepth >= 0 && depth <= darkDepth) darkDepth = -1;
      }
    }
    if (darkDepth >= 0) inDark[i] = true; // trailing lines of the block
  });
  return inDark;
}

const apply = process.argv.includes('--apply');
const files = process.argv.slice(2).filter((a) => !a.startsWith('--'));
let total = 0;
for (const file of files) {
  const src = fs.readFileSync(file, 'utf8');
  const lines = src.split('\n');
  const dark = darkRegions(lines);
  const out = lines.map((line, i) => {
    if (dark[i]) return line; // dark patches untouched in B1a
    // CRLF-safe: match against the \r-stripped line, re-append afterwards
    // (JS regex `.` does not match \r, so a raw CRLF line never matches).
    const cr = line.endsWith('\r');
    const clean = cr ? line.slice(0, -1) : line;
    const mapped = mapDeclaration(clean);
    if (mapped !== clean) total++;
    return mapped === clean ? line : mapped + (cr ? '\r' : '');
  });
  const result = out.join('\n');
  if (apply) {
    fs.writeFileSync(file, result);
    console.log(`${file}: applied`);
  } else {
    // naive line diff for review
    for (let i = 0; i < lines.length; i++) {
      if (lines[i] !== out[i]) {
        console.log(`${file}:${i + 1}\n- ${lines[i]}\n+ ${out[i]}`);
      }
    }
  }
}
console.error(`${apply ? 'applied' : 'would change'} ${total} declarations`);

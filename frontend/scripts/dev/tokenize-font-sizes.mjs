/**
 * One-shot codemod: replace hardcoded `font-size: NNpx` declarations in
 * less modules with the semantic typography tokens from styles/tokens.css
 * (qwenpaw #8005 counterpart). Only `font-size` lines are touched; widths,
 * heights, paddings etc. keep their px.
 *
 * Usage: node scripts/dev/tokenize-font-sizes.mjs [--dry]
 */
import { readFileSync, writeFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const ROOT = join(process.cwd(), "src");
const DRY = process.argv.includes("--dry");

/** px baseline → token (styles/tokens.css is the source of truth). */
const TOKENS = [
  [10, "--majo-font-xs"],
  [11, "--majo-font-sm"],
  [12, "--majo-font-caption"],
  [13, "--majo-font-secondary"],
  [14, "--majo-font-body"],
  [15, "--majo-font-md"],
  [16, "--majo-font-lg"],
  [18, "--majo-font-xl"],
  [20, "--majo-font-2xl"],
  [24, "--majo-font-title"],
];

function tokenFor(px) {
  let best = TOKENS[0];
  for (const entry of TOKENS) {
    if (Math.abs(entry[0] - px) < Math.abs(best[0] - px)) best = entry;
  }
  return best[1];
}

function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    const stat = statSync(full);
    if (stat.isDirectory()) {
      if (name === "node_modules" || name === "test") continue;
      yield* walk(full);
    } else if (name.endsWith(".less")) {
      yield full;
    }
  }
}

let files = 0;
let replacements = 0;
for (const file of walk(ROOT)) {
  const src = readFileSync(file, "utf8");
  let touched = 0;
  const out = src.replace(
    /font-size:\s*(\d+)px/g,
    (match, px, offset, whole) => {
      // Skip values already inside a var()/calc() (none expected, but safe).
      const before = whole.slice(Math.max(0, offset - 60), offset);
      if (before.includes("var(") && !before.includes(";")) return match;
      touched++;
      return `font-size: var(${tokenFor(Number(px))})`;
    },
  );
  if (touched > 0 && !DRY) {
    writeFileSync(file, out, "utf8");
  }
  if (touched > 0) {
    files++;
    replacements += touched;
  }
}
console.log(`${DRY ? "[dry] " : ""}${replacements} font-size declarations → tokens in ${files} files`);

// Bootstrap finalization for the desktop build.
//
// The bootstrap page is loaded by Electron via loadFile (file:// protocol),
// so asset references inside tauri.html must be RELATIVE. Vite emits
// absolute "/assets/..." paths even with base:"./" when the entry is a raw
// html input, so this hook rewrites them to "./assets/..." post-build.
import fs from "node:fs";
import path from "node:path";

const outDir = path.resolve(process.cwd(), "dist-desktop-bootstrap");
const htmlPath = path.join(outDir, "tauri.html");

if (!fs.existsSync(htmlPath)) {
  console.error(`[finalize_desktop_bootstrap] missing ${htmlPath}`);
  process.exit(1);
}

let html = fs.readFileSync(htmlPath, "utf8");
const before = html;

// Absolute -> relative (href/src), covering /assets/... and /online.svg.
html = html.replace(/(src|href)="\/([^/"])/g, '$1="./$2');

if (html !== before) {
  fs.writeFileSync(htmlPath, html);
  console.log(
    `[finalize_desktop_bootstrap] rewrote absolute asset paths to relative in dist-desktop-bootstrap/tauri.html`,
  );
} else {
  console.log(
    "[finalize_desktop_bootstrap] no absolute asset paths found — nothing to rewrite",
  );
}

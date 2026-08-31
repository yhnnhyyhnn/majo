// Bootstrap finalization step for the Tauri desktop build.
//
// vite emits the bootstrap page as `dist-tauri/tauri.html` (the source
// filename is preserved for raw html inputs). Tauri 2 release builds load
// `frontendDist/index.html` by convention, so this hook copies the built
// tauri.html to index.html after the vite build.
import fs from "node:fs";
import path from "node:path";

const outDir = path.resolve(process.cwd(), "dist-tauri");
const srcPath = path.join(outDir, "tauri.html");
const destPath = path.join(outDir, "index.html");

if (!fs.existsSync(srcPath)) {
  console.error(`[finalize_tauri_bootstrap] missing ${srcPath}`);
  process.exit(1);
}

fs.copyFileSync(srcPath, destPath);
console.log(`[finalize_tauri_bootstrap] copied tauri.html -> index.html`);

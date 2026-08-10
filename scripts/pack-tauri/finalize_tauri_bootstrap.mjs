// Bootstrap finalization step for the Tauri desktop build.
//
// The qwenpaw reference repo used this hook to post-process the vite
// bootstrap output. Majo's build produces `frontend/dist-tauri` directly at
// the path tauri.conf.json expects (`../dist-tauri`), so there is nothing to
// move or rewrite — the hook is kept as a no-op so `npm run build:tauri-bootstrap`
// keeps working.
console.log("[finalize_tauri_bootstrap] no-op: bootstrap output already at dist-tauri");

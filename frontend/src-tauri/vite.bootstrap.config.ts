import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");

  return {
    define: {
      VITE_API_BASE_URL: JSON.stringify(env.VITE_API_BASE_URL ?? ""),
      TOKEN: JSON.stringify(env.TOKEN || ""),
      MOBILE: false,
    },
    plugins: [react()],
    css: {
      modules: {
        localsConvention: "camelCase",
        generateScopedName: "[name]__[local]__[hash:base64:5]",
      },
      preprocessorOptions: {
        less: {
          javascriptEnabled: true,
        },
      },
    },
    resolve: {
      alias: {
        "@": path.resolve(__dirname, "../src"),
        // Route Tauri API imports to the Electron shims — the bootstrap page
        // runs in the Electron shell where window.majoDesktop is the bridge.
        "@tauri-apps/api/core": path.resolve(__dirname, "../src/shims/tauri-core.ts"),
        "@tauri-apps/api/event": path.resolve(__dirname, "../src/shims/tauri-event.ts"),
        "@tauri-apps/plugin-dialog": path.resolve(__dirname, "../src/shims/tauri-dialog.ts"),
      },
    },
    build: {
      outDir: "dist-desktop-bootstrap",
      emptyOutDir: true,
      sourcemap: false,
      cssCodeSplit: true,
      // Electron loads this page via loadFile (file:// protocol), so all
      // asset references must be relative ("./assets/...") — absolute
      // "/assets/..." resolves to the filesystem root and renders a blank
      // window.
      base: "./",
      rollupOptions: {
        input: {
          index: path.resolve(__dirname, "../tauri.html"),
        },
      },
    },
  };
});

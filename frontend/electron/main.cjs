/**
 * Majo Desktop — Electron main process.
 *
 * Replaces the former Tauri (Rust) shell. Responsibilities:
 *   • Spawn the Java Spring Boot backend sidecar and parse the
 *     `MAJO_BACKEND_READY {"port":N}` ready line from stdout.
 *   • Graceful shutdown: POST /api/desktop/shutdown with the
 *     X-Majo-Desktop-Shutdown-Token header, then wait for process exit.
 *   • Tray icon with the close-confirmation flow shared with
 *     CloseWindowPrompt.tsx (majo-close-requested / majo-shutdown-started).
 *   • External-link allowlist opening and devtools gating.
 *
 * The renderer has no Node access; native capabilities are exposed through
 * preload.cjs contextBridge (`window.majoDesktop`).
 */
const { app, BrowserWindow, ipcMain, shell, Tray, Menu, nativeImage } = require("electron");
const { spawn } = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

// ---------------------------------------------------------------------------
// Constants (protocol parity with the Java backend)
// ---------------------------------------------------------------------------

const BACKEND_READY_PREFIX = "MAJO_BACKEND_READY ";
const SHUTDOWN_TOKEN_ENV = "MAJO_DESKTOP_SHUTDOWN_TOKEN";
const SHUTDOWN_TOKEN_HEADER = "X-Majo-Desktop-Shutdown-Token";
const DESKTOP_SERVER_PORT = 1911;
const CLOSE_ACK_TIMEOUT_MS = 1500;
const SHUTDOWN_HTTP_TIMEOUT_MS = 5000;

const BUNDLED_JRE_DIR = "binaries/jre";
const BUNDLED_BACKEND_JAR = "binaries/majo-backend.jar";

const CLOSE_REQUESTED_EVENT = "majo-close-requested";
const SHUTDOWN_STARTED_EVENT = "majo-shutdown-started";

// ---------------------------------------------------------------------------
// Runtime state
// ---------------------------------------------------------------------------

/** @type {BrowserWindow | null} */
let mainWindow = null;
/** @type {Tray | null} */
let tray = null;
/** @type {import('child_process').ChildProcess | null} */
let backendProcess = null;
let backendPort = null; // number | null
let backendStartupError = null; // string | null
let shutdownToken = "";
let quitting = false;
let closeSeq = 0;
let closeAckedSeq = 0;

function isDev() {
  return !!process.env.VITE_DEV_SERVER_URL && !app.isPackaged;
}

/** Repo root during dev; resources dir when packaged. */
function baseDir() {
  return app.isPackaged ? process.resourcesPath : process.cwd();
}

function bootstrapPagePath() {
  if (app.isPackaged) {
    return path.join(process.resourcesPath, "dist-tauri", "tauri.html");
  }
  // Dev: built by `npm run build:tauri-bootstrap` into frontend/dist-tauri.
  return path.join(process.cwd(), "dist-tauri", "tauri.html");
}

function backendJarPath() {
  if (app.isPackaged) {
    return path.join(process.resourcesPath, BUNDLED_BACKEND_JAR);
  }
  // Dev expects a locally built jar under <repo>/backend/target.
  const repoRoot = path.join(process.cwd(), "..");
  return path.join(repoRoot, "backend", "target", "majo-backend.jar");
}

function bundledJavaPath() {
  if (!app.isPackaged) {
    // Dev: JAVA_HOME/bin/java if set, otherwise `java` on PATH.
    const javaHome = process.env.JAVA_HOME;
    if (javaHome) {
      const candidate = path.join(javaHome, process.platform === "win32" ? "bin/java.exe" : "bin/java");
      if (fs.existsSync(candidate)) return candidate;
    }
    return "java";
  }
  const relative = process.platform === "win32"
    ? path.join(BUNDLED_JRE_DIR, "bin", "java.exe")
    : path.join(BUNDLED_JRE_DIR, "bin", "java");
  return path.join(process.resourcesPath, relative);
}

function workingDataDir() {
  if (process.env.MAJO_WORKING_DIR) return process.env.MAJO_WORKING_DIR;
  // Match the Tauri shell: per-user app data directory.
  const userDataBase = process.platform === "win32"
    ? path.join(app.getPath("appData"), "majo")
    : path.join(app.getPath("home"), ".majo");
  return userDataBase;
}

// ---------------------------------------------------------------------------
// Backend sidecar lifecycle
// ---------------------------------------------------------------------------

function startBackend() {
  const jar = backendJarPath();
  if (!fs.existsSync(jar)) {
    backendStartupError =
      `backend jar not found at ${jar}\n` +
      "Run `mvn -f backend/pom.xml package -DskipTests` first.";
    logMain(backendStartupError);
    return;
  }

  shutdownToken = crypto.randomUUID();
  backendStartupError = null;
  backendPort = null;

  const java = bundledJavaPath();
  const cwd = app.isPackaged ? process.resourcesPath : path.join(baseDir());
  logMain(`[backend] starting java=${java} jar=${jar} cwd=${cwd}`);

  backendProcess = spawn(java, ["-jar", jar], {
    cwd,
    env: {
      ...process.env,
      MAJO_DESKTOP_APP: "1",
      [SHUTDOWN_TOKEN_ENV]: shutdownToken,
      SERVER_PORT: String(DESKTOP_SERVER_PORT),
      MAJO_WORKING_DIR: workingDataDir(),
    },
    stdio: ["ignore", "pipe", "pipe"],
  });

  let stdoutBuffer = "";
  backendProcess.stdout.on("data", (chunk) => {
    const text = chunk.toString();
    process.stdout.write(`[backend] ${text}`);
    stdoutBuffer += text;
    const lines = stdoutBuffer.split(/\r?\n/);
    stdoutBuffer = lines.pop() ?? "";
    for (const line of lines) {
      if (line.startsWith(BACKEND_READY_PREFIX)) {
        try {
          const payload = JSON.parse(line.slice(BACKEND_READY_PREFIX.length));
          if (typeof payload.port === "number") {
            backendPort = payload.port;
            logMain(`[backend] ready port=${backendPort}`);
            notifyFrontendBackendReady();
          }
        } catch {
          // Malformed line — ignore.
        }
      }
    }
  });

  backendProcess.stderr.on("data", (chunk) => {
    process.stderr.write(`[backend] ${chunk.toString()}`);
  });

  backendProcess.on("exit", (code, signal) => {
    logMain(`[backend] exited code=${code} signal=${signal}`);
    if (!quitting && backendPort === null && !backendStartupError) {
      backendStartupError = "The backend process exited before it became ready.";
    }
    backendProcess = null;
    backendPort = null;
  });
}

function notifyFrontendBackendReady() {
  mainWindow?.webContents.send("majo-backend-ready", { port: backendPort });
}

/**
 * Requests graceful shutdown over HTTP. Resolves true when the server
 * acknowledged, false on any failure (caller decides whether to kill).
 */
function requestGracefulShutdown(timeoutMs = SHUTDOWN_HTTP_TIMEOUT_MS) {
  return new Promise((resolve) => {
    if (!backendPort || !backendProcess) {
      resolve(false);
      return;
    }
    const options = {
      hostname: "127.0.0.1",
      port: backendPort,
      path: "/api/desktop/shutdown",
      method: "POST",
      headers: { [SHUTDOWN_TOKEN_HEADER]: shutdownToken },
      timeout: timeoutMs,
    };
    const req = http.request(options, (res) => {
      res.resume();
      resolve(res.statusCode !== undefined && res.statusCode < 500);
    });
    req.on("timeout", () => {
      req.destroy();
      resolve(false);
    });
    req.on("error", () => resolve(false));
    req.end();
  });
}

async function stopBackend(gracefulMs = SHUTDOWN_HTTP_TIMEOUT_MS) {
  const proc = backendProcess;
  if (!proc) return;
  await requestGracefulShutdown(gracefulMs);
  // Give the JVM some time to exit after the graceful request; force-kill as
  // a last resort so a wedged backend never blocks quit.
  const exited = await new Promise((resolve) => {
    if (proc.exitCode !== null || proc.signalCode) return resolve(true);
    const timer = setTimeout(() => resolve(false), 8000);
    proc.once("exit", () => {
      clearTimeout(timer);
      resolve(true);
    });
  });
  if (!exited) {
    logMain("[backend] force killing wedged sidecar");
    proc.kill();
  }
}

// ---------------------------------------------------------------------------
// Logging helper (Electron's console lands in the main-process stdout)
// ---------------------------------------------------------------------------

function logMain(message) {
  console.log(message);
}

// ---------------------------------------------------------------------------
// Close-confirmation flow (parity with tray.rs)
// ---------------------------------------------------------------------------

async function handleCloseRequested(mainWindow_) {
  // Frontend owns the prompt / remembered-choice flow once it acks.
  closeSeq += 1;
  const seq = closeSeq;
  mainWindow_.webContents.send(CLOSE_REQUESTED_EVENT, { seq });
  setTimeout(() => {
    if (closeAckedSeq < seq) {
      // No listener attached (bootstrap navigation / reload): fall back to
      // minimize-to-tray instead of losing window state.
      mainWindow_.hide();
    }
  }, CLOSE_ACK_TIMEOUT_MS);
}

async function quitAfterBackendShutdown() {
  quitting = true;
  mainWindow?.webContents.send(SHUTDOWN_STARTED_EVENT, {});
  await stopBackend();
  app.quit();
}

// ---------------------------------------------------------------------------
// Tray
// ---------------------------------------------------------------------------

/** @type {Map<string,string>} */
const trayLabels = new Map();

function trayTitle() {
  const show = trayLabels.get("show") ?? "Show";
  const quit = trayLabels.get("quit") ?? "Quit";
  return { show, quit };
}

function createTray() {
  // Use the app icon; falls back to an empty image if unavailable.
  const iconPath = app.isPackaged
    ? path.join(process.resourcesPath, "icon.png")
    : path.join(__dirname, "../scripts/pack/assets/icon.png");
  const icon = fs.existsSync(iconPath)
    ? nativeImage.createFromPath(iconPath).resize({ width: 16, height: 16 })
    : undefined;
  tray = new Tray(icon ?? nativeImage.createEmpty());
  const rebuild = () => {
    const labels = trayTitle();
    tray.setContextMenu(Menu.buildFromTemplate([
      { label: labels.show, click: () => showMainWindow() },
      { type: "separator" },
      { label: labels.quit, click: () => requestAppQuit() },
    ]));
    tray.setToolTip("Majo Desktop");
  };
  rebuild();
  trayRebuild = rebuild;
  tray.on("double-click", () => showMainWindow());
}

let trayRebuild = () => {};

function showMainWindow() {
  if (!mainWindow) return;
  if (mainWindow.isMinimized()) mainWindow.restore();
  mainWindow.show();
  mainWindow.focus();
}

async function requestAppQuit() {
  if (!quitting) {
    await quitAfterBackendShutdown();
    return;
  }
  app.quit();
}

// ---------------------------------------------------------------------------
// Single instance + IPC surface (mirrors the 13 Tauri commands)
// ---------------------------------------------------------------------------

function registerIpcHandlers() {
  ipcMain.handle("majo_backend_port", () => backendPort);
  ipcMain.handle("majo_backend_startup_error", () => backendStartupError);

  ipcMain.handle("majo_restart_backend", async () => {
    await stopBackend();
    startBackend();
    // The frontend polls until the new sidecar reports ready.
  });

  ipcMain.handle("majo_ack_close", () => {
    closeAckedSeq = closeSeq;
  });

  ipcMain.handle("majo_minimize_to_tray", () => {
    mainWindow?.hide();
  });

  ipcMain.handle("majo_quit_app", async () => {
    await quitAfterBackendShutdown();
  });

  ipcMain.handle("majo_set_tray_labels", (_event, labels) => {
    if (labels && typeof labels === "object") {
      for (const [k, v] of Object.entries(labels)) {
        trayLabels.set(k, String(v));
      }
      trayRebuild();
    }
  });

  ipcMain.handle("majo_open_devtools", () => {
    mainWindow?.webContents.openDevTools();
  });

  // Renderer-side emit compatibility (not used by the Majo frontend today).
  ipcMain.handle("majo___emit", (_event, { eventName, payload }) => {
    mainWindow?.webContents.send(eventName, payload);
  });

  ipcMain.handle("majo_open_external_link", async (_event, rawUrl) => {
    let parsed;
    try {
      parsed = new URL(String(rawUrl));
    } catch {
      return;
    }
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return;
    await shell.openExternal(parsed.toString());
  });

  ipcMain.handle("majo_show_save_dialog", async (_event, { defaultPath }) => {
    const win = mainWindow ?? undefined;
    const result = await dialog.showSaveDialog(win, {
      defaultPath: typeof defaultPath === "string" ? defaultPath : undefined,
    });
    return result.canceled ? null : result.filePath;
  });

  /**
   * Streams a local backend URL to a chosen file path, bypassing system
   * proxies (parity with backend_download.rs download_backend_file). Only
   * loopback URLs are accepted.
   */
  ipcMain.handle("majo_download_backend_file", async (_event, request) => {
    const url = new URL(String(request.url));
    if (url.protocol !== "http:" && url.protocol !== "https:") {
      throw new Error("rejected non-http download url");
    }
    if (!["127.0.0.1", "localhost", "[::1]"].includes(url.hostname)) {
      throw new Error("rejected remote download url");
    }
    const targetPath = String(request.filePath);
    const headers = (request.headers ?? {});
    const response = await fetch(url, {
      headers,
      // Node fetch ignores system proxies by default — parity with Rust reqwest.
    });
    if (!response.ok || !response.body) {
      throw new Error(`download failed: ${response.status}`);
    }
    const fsPromises = await import("node:fs/promises");
    const buffer = Buffer.from(await response.arrayBuffer());
    await fsPromises.writeFile(targetPath, buffer);
    return true;
  });

  /** Offline binary preview: read a workspace-relative file for the agent. */
  ipcMain.handle(
    "majo_read_workspace_binary_file",
    async (_event, { filePath: relativePath, agentId }) => {
      if (typeof relativePath !== "string" || !relativePath) {
        throw new Error("file_path is required");
      }
      const workingDir = process.env.MAJO_WORKING_DIR
        ? path.join(process.env.MAJO_WORKING_DIR)
        : path.join(app.getPath("appData"), "majo");
      const profile = JSON.parse(
        fs.readFileSync(path.join(workingDir, "agents.json"), "utf8"),
      );
      const profileEntry = profile?.profiles?.[agentId];
      const workspaceDir =
        profileEntry?.workspace_dir ?? path.join(workingDir, "workspaces", agentId);
      const resolved = path
        .join(workspaceDir, "coding_projects", path.join(String(relativePath)))
        .normalize();
      const rootDir = path.join(workspaceDir, "coding_projects");
      if (!resolved.startsWith(rootDir)) {
        throw new Error("path traversal");
      }
      return fs.readFileSync(resolved);
    },
  );
}

module.exports = { registerIpcHandlers };

// ---------------------------------------------------------------------------
// Window creation & app lifecycle
// ---------------------------------------------------------------------------

function createWindow() {
  mainWindow = new BrowserWindow({
    title: "Majo Desktop",
    width: 1280,
    height: 800,
    minWidth: 960,
    minHeight: 600,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      spellcheck: false,
    },
  });

  // Native drag-drop interception: OS drags arrive as HTML5 events.
  mainWindow.webContents.on("will-navigate", (event, url) => {
    // Only the bootstrap page may navigate the window itself; everything
    // else (external links) goes to the system browser via our handler.
    const devUrl = process.env.VITE_DEV_SERVER_URL;
    const allowedPrefixes = [
      "http://127.0.0.1:",
      "http://localhost:",
      devUrl ?? "",
    ].filter(Boolean);
    const currentOriginAllowed = allowedPrefixes.some(
      (prefix) => url.startsWith(prefix),
    );
    const isFileBootstrap = app.isPackaged
      ? url.startsWith("file://")
      : false;
    if (!currentOriginAllowed && !isFileBootstrap) {
      event.preventDefault();
    }
  });

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    // Prevent renderer-initiated windows; vetted links go through the bridge.
    void url;
    return { action: "deny" };
  });

  mainWindow.on("close", (event) => {
    if (quitting) return;
    event.preventDefault();
    void handleCloseRequested(mainWindow);
  });

  if (isDev()) {
    mainWindow.loadURL(`${process.env.VITE_DEV_SERVER_URL}/tauri.html`);
  } else {
    mainWindow.loadFile(bootstrapPagePath());
  }

  mainWindow.once("ready-to-show", () => mainWindow.show());
  mainWindow.on("closed", () => {
    mainWindow = null;
  });
}

const gotSingleInstanceLock = app.requestSingleInstanceLock();
if (!gotSingleInstanceLock) {
  app.quit();
} else {
  app.on("second-instance", () => showMainWindow());

  app.whenReady().then(() => {
    registerIpcHandlers();
    createWindow();
    createTray();

    // Development convenience: build the jar expectation clearly fails in the
    // loading page with instructions when missing.
    startBackend();

    app.on("activate", () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
      else showMainWindow();
    });
  });

  app.on("window-all-closed", () => {
    // Keep running when the window hides to tray; only quit here when truly
    // quitting (tray quit path sets `quitting`).
    if (quitting) {
      app.quit();
    }
  });

  app.on("before-quit", (event) => {
    if (!quitting) {
      event.preventDefault();
      void quitAfterBackendShutdown();
    }
  });
}

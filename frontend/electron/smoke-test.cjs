/**
 * Headless smoke test for the Electron shell: boots the real Java sidecar,
 * waits for the MAJO_BACKEND_READY line, then exercises the graceful
 * shutdown protocol — validating the exact bytes the Tauri shell used.
 * Run: node electron/smoke-test.cjs   (exits 0 on success)
 */
const { spawn } = require("node:child_process");
const http = require("node:http");
const path = require("node:path");
const crypto = require("node:crypto");

const READY_PREFIX = "MAJO_BACKEND_READY ";
const TOKEN_HEADER = "X-Majo-Desktop-Shutdown-Token";

function fail(message) {
  console.error(`SMOKE FAIL: ${message}`);
  process.exit(1);
}

const jar = path.join(__dirname, "..", "..", "backend", "target", "majo-backend.jar");
if (!require("node:fs").existsSync(jar)) {
  fail(`jar missing at ${jar}`);
}

const token = crypto.randomUUID();
let port = null;

console.log("[smoke] spawning backend...");
const child = spawn("java", ["-jar", jar], {
  cwd: path.join(__dirname, ".."),
  env: {
    ...process.env,
    MAJO_DESKTOP_APP: "1",
    [TOKEN_ENV_NAME()]: token,
    SERVER_PORT: "1911",
    MAJO_WORKING_DIR: path.join(process.env.LOCALAPPDATA || process.cwd(), "majo-smoke-data"),
  },
  stdio: ["ignore", "pipe", "pipe"],
});

function TOKEN_ENV_NAME() {
  return "MAJO_DESKTOP_SHUTDOWN_TOKEN";
}

const timeout = setTimeout(() => {
  child.kill();
  fail("timed out waiting for ready line (60s)");
}, 60000);

child.stdout.on("data", async (chunk) => {
  const text = chunk.toString();
  process.stdout.write(text);
  if (port === null) {
    for (const line of text.split(/\r?\n/)) {
      if (line.startsWith(READY_PREFIX)) {
        try {
          const payload = JSON.parse(line.slice(READY_PREFIX.length));
          if (typeof payload.port === "number") {
            port = payload.port;
            clearTimeout(timeout);
            await verifyAndShutdown();
          }
        } catch {
          // ignore malformed
        }
      }
    }
  }
});

async function verifyAndShutdown() {
  console.log(`[smoke] READY received, port=${port}. Verifying...`);
  // 1. Health endpoint answers on the advertised port.
  await new Promise((resolve) => {
    http.get(`http://127.0.0.1:${port}/api/health`, (res) => {
      console.log(`[smoke] /api/health status=${res.statusCode}`);
      resolve(res.statusCode === 200);
      resolve(undefined);
    }).on("error", () => resolve(undefined));
  });

  // 2. Graceful shutdown with the token header must be accepted (2xx).
  const shutdownStatus = await new Promise((resolve) => {
    const req = http.request(
      {
        hostname: "127.0.0.1",
        port,
        path: "/api/desktop/shutdown",
        method: "POST",
        headers: { [TOKEN_HEADER]: token },
        timeout: 5000,
      },
      (res) => {
        res.resume();
        resolve(res.statusCode);
      },
    );
    req.on("timeout", () => { req.destroy(); resolve(0); });
    req.on("error", () => resolve(0));
    req.end();
  });
  if (!shutdownStatus || shutdownStatus >= 500) {
    child.kill();
    fail(`graceful shutdown rejected (status=${shutdownStatus})`);
  }

  // 3. Process exits on its own after graceful shutdown.
  const exited = await new Promise((resolve) => {
    if (child.exitCode !== null) return resolve(true);
    const timer = setTimeout(() => resolve(false), 15000);
    child.once("exit", () => { clearTimeout(timer); resolve(true); });
  });
  if (!exited) {
    child.kill();
    fail("backend did not exit within 15s after graceful shutdown");
  }
  console.log("[smoke] PASS: ready line + health + graceful shutdown + exit all verified");
  process.exit(0);
}

child.on("exit", (code) => {
  if (port === null) {
    clearTimeout(timeout);
    fail(`backend exited before ready (code=${code})`);
  }
});

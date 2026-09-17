#!/usr/bin/env node
/**
 * API smoke test for Majo — exercises every surface added/changed in the
 * 0.2.x line (memory commands, coding mode, theme, heartbeat config,
 * token usage, multi-folder workspaces) against a RUNNING backend.
 *
 * Usage:  node scripts/dev/api-smoke.mjs [baseUrl]
 *         (default http://127.0.0.1:18789; backend must already be running)
 *
 * Exit 0 = all checks passed; 1 = at least one failure.
 */
const BASE = (process.argv[2] || "http://127.0.0.1:18789").replace(/\/$/, "");

let passed = 0;
let failed = 0;
const failures = [];

function check(name, cond, detail) {
  if (cond) {
    passed++;
    console.log(`  ✓ ${name}`);
  } else {
    failed++;
    failures.push(name);
    console.log(`  ✗ ${name}${detail ? " — " + JSON.stringify(detail).slice(0, 300) : ""}`);
  }
}

async function req(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: body ? { "Content-Type": "application/json" } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  let json = null;
  try {
    json = await res.json();
  } catch {
    // non-JSON body — leave null
  }
  return { status: res.status, json };
}

// ── sections ──────────────────────────────────────────────────────────────

async function smokeCore() {
  console.log("\n[core]");
  const h = await req("GET", "/api/health");
  check("health ok", h.status === 200 && h.json?.status === "ok", h.json);
  const v = await req("GET", "/api/version");
  check("version present", v.status === 200 && !!v.json?.version, v.json);
}

async function smokeMemoryCommands() {
  console.log("\n[memory commands]");
  const marker = "smoke-" + Date.now();

  const status = await req("POST", "/api/commands/run", { text: "/memory status", agent_id: "default" });
  check("status lists backend", status.json?.reply?.includes("Memory 状态"), status.json);

  const write = await req("POST", "/api/commands/run", {
    text: `/memory write marker ${marker}`,
    agent_id: "default",
  });
  check("write succeeds", write.json?.reply?.includes("已写入"), write.json);

  const search = await req("POST", "/api/commands/run", {
    text: `/memory search ${marker}`,
    agent_id: "default",
  });
  check("search finds marker", search.json?.reply?.includes("找到"), search.json);

  const checkCtl = await req("POST", "/api/commands/check", { text: "/memory status" });
  check("control command detected", checkCtl.json?.is_control_command === true, checkCtl.json);
  const checkPlain = await req("POST", "/api/commands/check", { text: "hello world" });
  check("plain text not a command", checkPlain.json?.is_control_command === false, checkPlain.json);

  // cleanup: find today's note and forget it
  const list = await req("POST", "/api/commands/run", { text: "/memory list 3", agent_id: "default" });
  const line = (list.json?.reply || "").split("\n").find((l) => l.includes("daily/"));
  if (line) {
    const p = line.match(/`([^`]+)`/)?.[1];
    if (p) {
      const forget = await req("POST", "/api/commands/run", { text: `/memory forget ${p}`, agent_id: "default" });
      check("forget removes note", forget.json?.reply?.includes("已删除"), forget.json);
    }
  }
}

async function smokeCodingMode() {
  console.log("\n[coding mode]");
  const get1 = await req("GET", "/api/coding-mode");
  check("GET returns enabled flag", typeof get1.json?.enabled === "boolean", get1.json);
  const original = get1.json.enabled;

  const post = await req("POST", "/api/coding-mode", { enabled: !original });
  check("POST toggles", post.json?.enabled === !original, post.json);
  const get2 = await req("GET", "/api/coding-mode");
  check("toggle persisted", get2.json?.enabled === !original, get2.json);

  // restore
  await req("POST", "/api/coding-mode", { enabled: original });
}

async function smokeTheme() {
  console.log("\n[theme]");
  const put = await req("PUT", "/api/config/theme", { accent: "#123456", accent_dark: "#654321" });
  check("PUT accepts #rrggbb", put.status === 200 && put.json?.accent === "#123456", put.json);

  const bad = await req("PUT", "/api/config/theme", { accent: "red" });
  check("invalid color rejected", bad.status === 400, bad);

  const get = await req("GET", "/api/config/theme");
  check("GET returns persisted", get.json?.accent === "#123456" && get.json?.accent_dark === "#654321", get.json);

  const del = await req("DELETE", "/api/config/theme");
  check("DELETE resets", del.json?.accent === "", del.json);
}

async function smokeHeartbeat() {
  console.log("\n[heartbeat]");
  const get1 = await req("GET", "/api/config/heartbeat");
  check("config shape", get1.json && ["enabled", "every", "target", "timeoutSeconds", "last_run"].every(
    (k) => k in get1.json), get1.json);

  const original = get1.json;
  const put = await req("PUT", "/api/config/heartbeat", {
    enabled: false,
    every: "30m",
    target: "inbox",
    timeoutSeconds: 90,
    active_hours: { start: "08:00", end: "22:00" },
  });
  check("PUT persists + active_hours", put.json?.every === "30m" && put.json?.target === "inbox"
    && put.json?.active_hours?.start === "08:00", put.json);

  // restore (keep original active_hours semantics by writing it back empty)
  await req("PUT", "/api/config/heartbeat", {
    enabled: original.enabled,
    every: original.every,
    target: original.target,
    timeoutSeconds: original.timeoutSeconds,
    active_hours: original.active_hours || { start: "", end: "" },
  });
}

async function smokeTokenUsage() {
  console.log("\n[token usage]");
  const daily = await req("GET", "/api/token-usage");
  check("daily summary shape", daily.json && "total_prompt_tokens" in daily.json, daily.json);
  const agents = await req("GET", "/api/token-usage/agents");
  check("agents array", Array.isArray(agents.json?.agents), agents.json);
}

async function smokeProjectDirs() {
  console.log("\n[project dirs]");
  const winTemp = "C:\\Windows\\Temp";
  const unixTemp = "/tmp";
  const tmp = process.platform === "win32" ? winTemp : unixTemp;

  const put = await req("PUT", "/api/workspace/coding-project/dirs", {
    project_dirs: [{ path: tmp, label: "smoke" }],
  });
  check("PUT stores primary", Array.isArray(put.json) && put.json[0]?.path === tmp, put.json);

  const get = await req("GET", "/api/workspace/coding-project/dirs");
  check("GET lists", get.json?.length === 1, get.json);

  const del = await req("DELETE", "/api/workspace/coding-project/dirs");
  check("DELETE clears", Array.isArray(del.json) && del.json.length === 0, del.json);
}

async function smokeInbox() {
  console.log("\n[inbox]");
  const events = await req("GET", "/api/console/inbox/events?limit=5");
  check("inbox events endpoint", events.status === 200 && Array.isArray(events.json?.events), events.json);
}

// ── main ──────────────────────────────────────────────────────────────────

(async () => {
  const ping = await fetch(BASE + "/api/health").then((r) => r.json()).catch(() => null);
  if (!ping) {
    console.error(`Backend not reachable at ${BASE} — start it first (mvn spring-boot:run).`);
    process.exit(1);
  }
  await smokeCore();
  await smokeMemoryCommands();
  await smokeCodingMode();
  await smokeTheme();
  await smokeHeartbeat();
  await smokeTokenUsage();
  await smokeProjectDirs();
  await smokeInbox();

  console.log(`\n${passed} passed, ${failed} failed`);
  if (failures.length) {
    console.log("failed:", failures.join(" | "));
    process.exit(1);
  }
})();

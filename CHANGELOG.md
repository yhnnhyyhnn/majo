# Changelog

All notable changes to Majo are documented here. Format follows Keep a Changelog; versions are semver-ish (MAJOR.MINOR.PATCH).

## [0.3.0] — 2026-09-18

Reliability and polish release: doom-loop protection, self-refreshing memory index, background external-agent delegation with live progress, per-agent token statistics, sidebar session-list grouping modes, and a large design-token migration. Ported from the QwenPaw September 17–18 batch (references `#NNNN` are QwenPaw PRs).

### Reliability

- **Doom-loop detection** (#7808 semantics): identical repeated tool calls (name + args hash, sliding window per session) are denied in stages — a change-of-approach warning from the 3rd call, a hard stop from the 6th. State resets on a differing call or a new turn; wired into the tool-guard hook so the model sees the denial instead of wasting executions
- **Memory index auto-refresh**: searches compare a `path:size:mtime` signature of the indexed files and rebuild lazily on drift — externally edited `memory/` files are picked up without a manual rebuild; legacy persisted indexes self-heal on first search
- **Background external-agent delegation** (ACP phase 3): `delegate_external_agent(background=true)` registers a task and returns a `task_id` immediately; every `session/update` publishes a progress snapshot pollable via `check_agent_task`. Sync path unchanged

### Console

- **Per-agent token usage table** in Agent Statistics (backed by `GET /token-usage/agents`, ADR-0011): input/output tokens, turns, and average per-turn duration per agent; agent ids resolve to display names; localized in all 7 languages
- **Sidebar session-list grouping modes** (#7788): by date / by channel / flat, persisted with window-event sync across mounted lists; collapse state now survives reloads; duplicate simple-mode stylesheet block (171 lines) removed
- **Design tokens fully adopted**: all black/white alpha colors in the main layout stylesheet migrated to semantic antd tokens — ~60 hand-written dark-mode overrides retired, fixed invisible scrollbar thumb and dividers in dark mode
- **pt-BR language selection fixed** (#7752 port): `nonExplicitSupportedLngs` reduced `pt-BR` to `pt` and silently fell back to English; regression-tested

### Quality

- `HarnessAgentFactory` consolidates five divergent hand-rolled `HarnessAgent.builder()` sites (fixed dropped `agentId`s, ignored display names, a guaranteed-to-fail model fallback)
- `StreamTextCollector` replaces duplicated reflection-based stream-delta accumulation with typed event handling
- Auth interceptor caches `allow_no_auth_hosts` by file mtime instead of re-parsing per request
- New upstream issue drafted: ast-grep cannot match Java field declarations at all, even with exact literals (see `AI-Coding-Agent-Spec/07-Operation/`)

## [0.2.0] — 2026-09-17

Major capability release: completes the long-term memory loop, Coding Mode with real code intelligence, heartbeat scheduling, theming, and a series of correctness fixes traced through end-to-end testing. Aligned with QwenPaw 2.2.x September batch (references `#NNNN` are QwenPaw PRs).

### Memory (ADR-0008 / 0009 / 0014)

- **Memory backend SPI**: pluggable backends selected via `memory_manager_backend`, with ordered fallback when the selected backend is unavailable (#7544/#7663 semantics)
- **Write pipeline**: accumulated conversation turns are flushed to `MemoryBackend.remember()` on a configured interval; pending turns persist across restarts; degradation is user-visible via inbox events
- **LLM extraction backend (`summary`)**: before persisting, a single small model call distills the batch into durable facts (QwenPaw ReMe auto_memory semantics); extraction failures fall back to raw excerpts — an unavailable LLM degrades quality, never loses turns
- **`/memory` slash command**: `status / list / search / read / forget / write` with path-traversal protection; available in console chat, channel messages, and `POST /api/commands/run`
- **Auto recall**: relevant memories are injected into model requests as a system reminder; `memory_search` tool for explicit recall
- **AGENTS.md / SOUL.md / PROFILE.md** are hot-loaded into the system prompt every call (previously copied but never read)

### Coding Mode & code intelligence (ADR-0010)

- **Coding Mode runtime wiring**: the sidebar toggle now actually changes agent behavior — injects the coding discipline prompt (TODO-checklist tracking, `path:line` references, tool preferences)
- **`lsp` tool**: real language-server client (JSON-RPC over stdio, pooled per workspace+language) with goToDefinition / findReferences / hover / goToImplementation / documentSymbol / workspaceSymbol; supports TypeScript/JavaScript (typescript-language-server) and Python (pyright/pylsp); 1-based positions, readable fallback hints
- **`ast_search` tool**: structural code search via the ast-grep CLI (`$NAME` / `$$$NAME` patterns), read-only, grep-style exit semantics, install hint when the binary is missing

### Heartbeat (ADR-0013)

- **HEARTBEAT.md-driven scheduling**: the agent periodically runs the workspace `HEARTBEAT.md` content as a user turn (interval string or cron expression; default off, 6h)
- **Targets**: `main` (run without delivery; inbox on failure), `inbox` (result preview into inbox), `last` (proactively send to the most recently contacted channel — Telegram/Slack/Discord supported)
- **Active hours window** with cross-midnight ranges and agent timezone support
- `POST /api/config/heartbeat/run` triggers a run for real; `GET` exposes `last_run` status

### Console UX

- **Customizable accent colors** (#7741): light/dark accent pickers in the sidebar settings panel; applied live to the antd token and `--majo-accent` CSS variable; persisted per deployment
- **Semantic design tokens** (#7682): antd `cssVar` enabled — 130+ previously-dead `var(--color*)` references in stylesheets now resolve in both light and dark modes
- **Grouped session pagination** (#7665/#7688): sessions render 10 per group with a "Load more · N remaining" row; the active session's page auto-expands
- **Multi-folder default workspaces** (#7789): ordered default project folders (up to 10) with promote-on-select; `GET/PUT/DELETE /workspace/coding-project/dirs`; Defaults section in the project picker
- **Sent-files drawer** (#7750/#7704): files delivered via `send_file_to_user` in the current session are listed in a right-side drawer
- Sidebar collapse state persists across reloads (#7681); skill drawer channel options follow the backend (#7782)
- Chat SDK upgraded to AgentScopeRuntimeWebUI 1.2 (#7382)

### Backend correctness

- **PDF in tool results stripped unconditionally** (#7636): OpenAI-compatible chat-completions servers reject `file` parts even on multimodal models
- **execute_command timeout actually enforced** (ADR-0012 phase 1): stdout is consumed off-thread; timeouts kill the whole process tree (Windows `cmd /c` grandchildren previously survived)
- **Heartbeat session id**: dashes instead of colons (illegal in Windows file names — caught in E2E)
- **LSP executable discovery on Windows**: resolves `.cmd`/`.exe` variants (npm's bare shims are unspawnable); JSON-RPC envelope unwrapping; tree-kill on close
- **agents.json startup migration no longer overwrites live config** (silent data-loss bug: every restart reverted running configs if a legacy `backend/agents.json` existed)
- **Memory index cache keyed by workspace path** (CI-only stale-index bug after `/memory forget`)
- **`/memory` read/forget accept workspace-relative paths** (contract mismatch between list and read)
- Tests use an isolated working directory and in-memory database — no more dev-data pollution or order-dependent CI failures

### Platform

- Docker image build fixed: `backend/local-repo` placeholder committed; CI test gate green (347 backend + 1350+ frontend tests)
- Test JVMs run against an isolated `MAJO_WORKING_DIR` and in-memory H2

### Known limitations

- `view_video` frame extraction requires ffmpeg; without it only metadata is returned
- `lsp` requires a language server on PATH; `ast_search` requires ast-grep — both degrade to readable hints
- Heartbeat `target=last` supports token-based channels (Telegram/Slack/Discord); webhook-only channels fall back to inbox delivery

## [0.1.0] — initial public snapshot

Multi-agent console with chat, tool system (28 built-in tools), skills, MCP bridge, cron jobs, 12 messaging channels, backups, desktop (Tauri) packaging.

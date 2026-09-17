# Changelog

All notable changes to Majo are documented here. Format follows Keep a Changelog; versions are semver-ish (MAJOR.MINOR.PATCH).

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

# Majo — AI Coding Agent

[English](README.en.md) | [中文](README.md)

Spring Boot + AgentScope + React + H2 + Flyway + Tauri (optional desktop)

## Architecture

```
                    ┌─────────────────────────────────────┐
                    │          Spring Boot Backend         │
                    │  port 18789 (browser) / 1911 (desktop)│
                    │                                      │
  Browser ──────────┤  /                ← static (frontend SPA)│
  Tauri  ──────────┤  /console         ← SPA (desktop entry)│
                    │  /api/chat        ← Chat SSE streaming│
                    │  /api/console/chat← Console Chat      │
                    │  /api/agents      ← Multi-agent mgmt  │
                    │  /api/skills      ← Skill pool + market│
                    │  /api/chats       ← Session mgmt      │
                    │  /api/backups     ← Backup/restore/import│
                    │  /api/settings    ← LLM config        │
                    │  /api/providers   ← Model providers   │
                    │  /api/desktop/shutdown ← desktop graceful shutdown│
                    │                                      │
                    │  ┌──────────────────────────────┐    │
                    │  │ AgentScope 2.0 (Harness)      │    │
                    │  │ read_file / write_file / ...  │    │
                    │  └──────────────────────────────┘    │
                    │                                      │
                    │  ┌──────────┐  ┌─────────────────┐   │
                    │  │ H2 (file)│  │ data/majo/      │   │
                    │  │          │  │ ├ agents.json   │   │
                    │  │ Flyway   │  │ ├ workspaces/{id}│  │
                    │  │          │  │ ├ skill_pool/   │   │
                    │  │          │  │ └ .backups/     │   │
                    │  └──────────┘  └─────────────────┘   │
                    └─────────────────────────────────────┘
```

## Desktop App (Tauri, optional)

Majo can be packaged as a native desktop app: `frontend/src-tauri/` is a Tauri 2 shell (Rust) that spawns the Spring Boot backend as a **sidecar child process** and loads the backend-hosted SPA in a WebView. The shell and backend share only two language-agnostic protocol conventions:

1. **Ready protocol**: once started, the backend prints a line `MAJO_BACKEND_READY {"port":N}` to stdout; the Rust shell parses the port and redirects the frontend to it.
2. **Graceful shutdown protocol**: on exit the shell POSTs `http://127.0.0.1:{port}/api/desktop/shutdown` with the `X-Majo-Desktop-Shutdown-Token` header (the token is injected via the `MAJO_DESKTOP_SHUTDOWN_TOKEN` env var). The backend validates it and runs Spring's normal shutdown flow instead of being force-killed.

Key desktop behaviors:

- The backend starts on **`SERVER_PORT=1911`** (avoids collisions with local services); the data directory is fixed to `MAJO_WORKING_DIR` (default `{data_dir}/majo`, e.g. `%APPDATA%/majo`)
- The SPA is served under `/console` (`SpaFallbackController` forwards to index.html); `App.tsx` auto-detects the `/console` basename
- The bundle embeds a **jlink-trimmed JRE** (~76MB), so end users don't need Java installed
- Auto-update (updater) is currently disabled as a placeholder — before release, fill in your minisign public key and update server in `tauri.conf.json`

### Desktop build

```powershell
# Requires: Node.js, JDK 21, Rust toolchain (cargo + rustc), platform WebView deps
.\scripts\pack-tauri\build-desktop.ps1
```

The script does everything in one pass: frontend build → copy dist into backend `static/` → `mvn package` → jlink-trim JRE → **AppCDS training run producing `app.jsa`** → copy jar to `src-tauri/binaries/` → `tauri build`.

Useful flags:

```powershell
# Only stage artifacts (jre + jar + static + app.jsa), skip tauri build
.\scripts\pack-tauri\build-desktop.ps1 -SkipTauriBuild
```

Output lands in `frontend/src-tauri/target/release/bundle/` (NSIS installer + portable dir on Windows); the double-clickable portable binary is at `frontend/src-tauri/target/release/majo-desktop.exe`.

> ⚠️ Note: `majo-desktop.exe` depends on the sibling `binaries/` directory (embedded jre + jar + app.jsa). Distribute via the installer, or keep the directory intact — don't copy the exe alone.

### Desktop development (dev mode)

```powershell
cd frontend
npm run desktop:dev   # tauri dev; requires `mvn package` first to produce backend/target/majo-backend.jar
```

In dev mode the shell launches the backend with `java -jar backend/target/majo-backend.jar` (if the jar is missing, the loading page shows a clear error).

### Startup optimizations

Three optimizations cut the desktop backend ready time from ~12s to ~6s:

1. **AppCDS archive**: the build script trains a backend boot with the jlink JRE and ships the resulting `app.jsa`. At runtime the Rust shell passes `-XX:SharedArchiveFile=app.jsa` to skip application class loading.
2. **JVM flags**: `-XX:TieredStopAtLevel=1` (C1-only JIT for faster boot) + `-Xmx1g` (heap cap).
3. **Desktop profile lazy-init**: the backend activates `application-desktop.yml` via `SPRING_PROFILES_ACTIVE=desktop`, enabling `spring.main.lazy-initialization=true` and disabling springdoc, so non-critical beans initialize on first use.

> Browser/Docker deployments are unaffected (the desktop profile is not activated).

### Desktop loading page

While the backend warms up, a branded loading page is shown (`frontend/src/tauri/BackendLoadingPage.tsx`):

- **Flowing gradient progress bar**: a full-width bar with an orange → light-orange → pink → gold gradient flowing in a loop (indeterminate — not percentage-based) until the backend is ready and the app loads
- **AI fun-fact carousel**: a random AI-related fun fact every 5 seconds (25 items each in Chinese and English; copy lives in `src/locales/*.json` under `startup.tips`)
- Ambient light blobs, floating logo, frosted-glass card entrance, and other animations for a polished branded startup
- On failure/timeout an error message and retry button are shown (no status text during normal loading)

## Data directory (WORKING_DIR)

All runtime data (`agents.json`, workspaces, skill_pool, plugins, backups, database, logs) is centralized in a dedicated data directory, separated from the source tree (see qwenpaw):

- Resolution order: **`MAJO_WORKING_DIR` env var** → default `{user.dir}/data/majo`
- On first start, legacy data at the old location (repo root) — `agents.json` / `skill_pool` / `workspaces` / `plugins` — is auto-migrated to the new directory (rewriting each agent's `workspace_dir`)
- Each agent (including `default`) gets its own workspace subdirectory: `data/majo/workspaces/{agent_id}`

```text
data/majo/
├── agents.json            # multi-agent registry
├── workspaces/{agent_id}/ # per-agent workspaces
├── skill_pool/            # skill pool
├── plugins/               # plugins
├── inbox_traces/          # inbox traces
├── db/majo.mv.db          # H2 database
├── majo.log               # backend log
└── .backups/              # backup archives
```

## Quick start

### Docker (recommended)

```bash
docker compose up -d
```

Open http://localhost:18789 in a browser. The frontend is embedded in the backend JAR, no extra startup needed.

Data persists on the host under `./data/majo` (docker-compose mounts `./data` to `/app/data` in the container, with `WORKING_DIR=/app/data/majo`) — including the H2 database, agents.json, workspaces, skill_pool, backups and logs.

### Local development

#### 1. Start the backend

```powershell
cd backend
mvn spring-boot:run
```

On first start Flyway runs the DB migrations automatically; `data/majo/` is created automatically; legacy data is migrated automatically.

To use a custom data directory, set `$env:MAJO_WORKING_DIR = "D:\majo-data"` first.

#### 2. Start the frontend

```powershell
cd frontend
npm install
npm run dev
```

The frontend dev server runs at http://localhost:5173 and proxies API requests to `localhost:18789`.

#### 3. Configure the LLM

Open the page in a browser → click **Settings** → fill in:

| Field | Example |
|---|---|
| API URL | `https://www.dmxapi.cn` |
| API Key | `sk-xxxxxxxx` |
| Model | `deepseek-v4-pro-guan` |

Click **Save** — takes effect immediately, no backend restart needed.

## Docker deployment

### docker compose

```bash
# build and start
docker compose up -d

# view logs
docker compose logs -f

# stop
docker compose down
```

### Build the image

```bash
docker build -t majo .
```

The image uses a three-stage build: Node builds the frontend → Maven packages the backend (frontend embedded in `static/`) → JRE runtime.

### CI/CD

Pushing to `main`/`master` or pushing a `v*` tag triggers GitHub Actions to build and push the image to `ghcr.io`.

## Project structure

```
majo/
├── backend/
│   ├── src/main/java/com/agent/coding/
│   │   ├── AgentApplication.java            # entry point
│   │   ├── ChatService.java                 # session management
│   │   ├── SettingsService.java             # LLM configuration
│   │   ├── WorkspaceContext.java            # workspace context
│   │   ├── agent/
│   │   │   └── AgentStore.java              # multi-agent registry (agents.json)
│   │   ├── backup/                          # backup services (zip + signature + restore)
│   │   │   ├── BackupStore.java             # storage/list/signature
│   │   │   ├── BackupCreator.java           # create + SSE progress
│   │   │   ├── BackupRestorer.java          # restore
│   │   │   ├── BackupImporter.java          # import + conflict handling
│   │   │   └── BackupMeta.java              # metadata model
│   │   ├── controller/
│   │   │   ├── ChatController.java          # POST /api/chat (SSE)
│   │   │   ├── ConsoleController.java       # POST /api/console/chat
│   │   │   ├── ChatsController.java         # /api/chats CRUD
│   │   │   ├── AgentsController.java        # /api/agents multi-agent
│   │   │   ├── SkillsController.java        # /api/skills skill pool
│   │   │   ├── ToolsController.java         # /api/tools tool management
│   │   │   ├── McpController.java           # /api/mcp client
│   │   │   ├── ACPConfigController.java     # /api/config/acp ACP config
│   │   │   ├── BackupController.java        # /api/backups backups
│   │   │   ├── PluginsController.java       # /api/plugins plugins
│   │   │   ├── DesktopController.java       # /api/desktop/shutdown desktop shutdown
│   │   │   ├── DesktopReadyPrinter.java     # prints MAJO_BACKEND_READY ready line
│   │   │   ├── SpaFallbackController.java   # /console → index.html SPA fallback
│   │   │   └── SettingsController.java      # GET/POST /api/settings
│   │   ├── skill/                           # skill system (pool + workspace)
│   │   ├── entity/                          # JPA entities
│   │   └── repository/
│   ├── src/main/resources/
│   │   ├── application.yml                  # server port + DB config
│   │   ├── application-desktop.yml          # desktop profile (lazy-init + no swagger)
│   │   ├── builtin-skills/                  # 17 built-in skills (classpath)
│   │   └── db/migration/                    # Flyway migrations (V1-V24)
│   └── local-repo/                          # AgentScope jars (local Maven repo)
├── frontend/
│   ├── src/
│   │   ├── pages/Chat/                      # chat UI
│   │   ├── pages/Settings/                  # settings UI
│   │   ├── stores/agentStore.ts             # multi-agent frontend state
│   │   ├── api/modules/                     # API clients
│   │   └── tauri/                           # desktop bootstrap/loading/update (desktop only)
│   │       ├── BackendLoadingPage.tsx       # loading page (gradient bar + AI fun facts)
│   │       ├── BackendReadyGate.tsx         # backend ready gate
│   │       └── backendRuntime.ts            # Tauri invoke/URL resolution
│   ├── src-tauri/                          # Tauri 2 desktop shell (Rust)
│   │   ├── src/                            # sidecar lifecycle/tray/update/graceful shutdown
│   │   ├── icons/                          # app icons (generated by tauri icon)
│   │   ├── tauri.conf.json                 # window/bundle/security config
│   │   └── capabilities/ permissions/      # Tauri 2 permission declarations
├── scripts/pack-tauri/
│   ├── build-desktop.ps1                   # one-shot desktop packaging (frontend+jlink+AppCDS+tauri build)
│   └── finalize_tauri_bootstrap.mjs        # copies tauri.html → index.html after build
├── data/majo/                               # runtime data directory (auto-created)
├── Dockerfile
├── docker-compose.yml
├── .github/workflows/docker-build.yml       # CI/CD
└── pom.xml                                  # parent POM
```

## API

| Endpoint | Method | Description |
|---|---|---|
| `/api/health` | GET | Health check |
| `/api/version` | GET | Version info (used by the desktop loading page readiness polling) |
| `/api/chat` | POST | Chat message (SSE streaming) |
| `/api/console/chat` | POST | Console chat (SSE, supports `X-Agent-Id`) |
| `/api/chats` | GET | Session list (supports `X-Agent-Id` filtering) |
| `/api/chats/{id}` | GET | Session detail + message history |
| `/api/chats/{id}` | DELETE | Delete session |
| `/api/agents` | GET | Multi-agent list |
| `/api/agents` | POST | Create agent |
| `/api/agents/{id}` | PUT/PATCH/DELETE | Agent CRUD |
| `/api/skills/pool` | GET | Skill pool list |
| `/api/skills/workspaces` | GET | Per-agent workspace skills |
| `/api/tools` | GET | Built-in tool list (supports `X-Agent-Id` enabled state) |
| `/api/mcp` | GET | MCP client list |
| `/api/config/acp` | GET/PUT | ACP config |
| `/api/backups` | GET | Backup list |
| `/api/backups/stream` | POST | Create backup (SSE progress stream) |
| `/api/backups/{id}` | GET | Backup detail |
| `/api/backups/{id}/restore` | POST | Restore backup |
| `/api/backups/{id}/export` | GET | Export backup zip |
| `/api/backups/import` | POST | Import backup (conflict 409 + trust_mode) |
| `/api/backups/delete` | POST | Delete backup |
| `/api/settings` | GET/POST | LLM configuration |
| `/api/providers` | GET | Model provider management |
| `/api/desktop/shutdown` | POST | Desktop graceful shutdown (requires token header, desktop only) |

## Backups

Backups are zip archives stored in `data/majo/.backups/`, containing:

- `data/workspaces/{agent_id}/` — per-agent workspaces (no project source/build artifacts)
- `data/config.json` — global config (agents.json)
- `data/skill_pool/` — skill pool
- `data/secrets/` — secrets (scoped)

Each backup carries a machine-local HMAC signature (`hmac-sha256-v1`) distinguishing "created here", "exported from another machine (foreign)" and "legacy unsigned". Importing foreign/legacy backups requires frontend confirmation of trust_mode. The frontend streams progress via SSE while creating backups.

## Configuration

`backend/src/main/resources/application.yml`:

```yaml
server:
  port: 18789

spring:
  datasource:
    url: jdbc:h2:file:./data/majo/db/majo;DB_CLOSE_DELAY=-1;MODE=PostgreSQL
  flyway:
    enabled: true

logging:
  file:
    name: ./data/majo/majo.log
```

No LLM keys are required in the config file.

## Switching to PostgreSQL

1. Replace the H2 dependency in `pom.xml` with PostgreSQL:

   ```xml
   <dependency>
       <groupId>org.postgresql</groupId>
       <artifactId>postgresql</artifactId>
       <scope>runtime</scope>
   </dependency>
   <dependency>
       <groupId>org.flywaydb</groupId>
       <artifactId>flyway-database-postgresql</artifactId>
   </dependency>
   ```

2. Update the datasource in `application.yml`:

   ```yaml
   spring:
     datasource:
       url: jdbc:postgresql://localhost:5432/majo
       driver-class-name: org.postgresql.Driver
       username: majo
       password: your-password
     jpa:
       database-platform: org.hibernate.dialect.PostgreSQLDialect
   ```

Flyway runs the existing migrations on PostgreSQL automatically.

## Tech stack

| Layer | Technology |
|---|---|
| Backend framework | Spring Boot 3.4 |
| AI framework | AgentScope 2.0 |
| Database | H2 (file) → switchable to PostgreSQL |
| Migrations | Flyway 10 |
| ORM | Spring Data JPA + Hibernate 6 |
| Frontend | React + Vite + @agentscope-ai/chat |
| Desktop shell (optional) | Tauri 2 (Rust, sidecar mode, embedded jlink JRE + AppCDS archive) |
| Desktop packaging | scripts/pack-tauri/build-desktop.ps1 (jlink + AppCDS + NSIS) |
| Deployment | Docker + Docker Compose + GitHub Actions |
| Java | 21 |
| Rust | 1.77+ (rust-version = 1.77.2) |

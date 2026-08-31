# Majo — AI Coding Agent

Spring Boot + AgentScope + React + H2 + Flyway + Tauri（桌面可选）

## 架构

```
                    ┌─────────────────────────────────────┐
                    │          Spring Boot Backend         │
                    │  port 18789 (浏览器) / 1911 (桌面)    │
                    │                                      │
  Browser ──────────┤  /                ← static (前端 SPA) │
  Tauri  ──────────┤  /console         ← SPA (桌面入口)    │
                    │  /api/chat        ← Chat SSE 流式    │
                    │  /api/console/chat← Console Chat     │
                    │  /api/agents      ← 多Agent 管理      │
                    │  /api/skills      ← 技能池 + 市场     │
                    │  /api/chats       ← 会话管理          │
                    │  /api/backups     ← 备份/恢复/导入     │
                    │  /api/settings    ← 大模型配置        │
                    │  /api/providers   ← 模型供应商        │
                    │  /api/desktop/shutdown ← 桌面优雅停机  │
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

## 桌面应用（Tauri，可选）

Majo 可以打包成原生桌面应用：`frontend/src-tauri/` 是一套 Tauri 2 壳（Rust），把 Spring Boot 后端作为 **sidecar 子进程** 拉起，WebView 加载后端托管的 SPA。壳与后端之间只有两个语言无关的协议约定：

1. **就绪协议**：后端启动后向 stdout 打印一行 `MAJO_BACKEND_READY {"port":N}`，Rust 壳解析出端口后引导前端跳转
2. **优雅停机协议**：退出时壳 POST `http://127.0.0.1:{port}/api/desktop/shutdown`，携带 `X-Majo-Desktop-Shutdown-Token` header（token 由壳通过 `MAJO_DESKTOP_SHUTDOWN_TOKEN` env 注入），后端校验后走 Spring 正常关闭流程，避免被强杀

桌面模式关键行为：

- 后端以 **`SERVER_PORT=1911`** 启动（避免与用户本地服务冲突），数据目录固定为 `MAJO_WORKING_DIR`（默认 `{data_dir}/majo`，如 `%APPDATA%/majo`）
- 前端 SPA 通过 `/console` 路径访问（`SpaFallbackController` 转发到 index.html），`App.tsx` 自动识别 `/console` basename
- 打包时内置一个 **jlink 裁剪的 JRE**（约 76MB），最终用户无需安装 Java
- 自动更新（updater）当前为占位禁用状态——发布前需在 `tauri.conf.json` 填入自己的 minisign 公钥和更新服务器

### 桌面构建

```powershell
# 需要：Node.js、JDK 21、Rust 工具链 (cargo + rustc)、各平台 WebView 依赖
.\scripts\pack-tauri\build-desktop.ps1
```

脚本一条龙完成：前端构建 → dist 拷入后端 `static/` → `mvn package` → jlink 裁剪 JRE → 拷贝 jar 到 `src-tauri/binaries/` → `tauri build`。

常用参数：

```powershell
# 只准备产物（jre + jar + static），不跑 tauri build
.\scripts\pack-tauri\build-desktop.ps1 -SkipTauriBuild
```

产物输出到 `frontend/src-tauri/target/release/bundle/`（Windows 下为 NSIS 安装器 + 免安装目录）。

### 桌面开发（dev 模式）

```powershell
cd frontend
npm run desktop:dev   # tauri dev，需先 mvn package 生成 backend/target/majo-backend.jar
```

dev 模式壳直接 `java -jar backend/target/majo-backend.jar` 启动后端（jar 不存在时启动页会给出明确报错提示）。

## 数据目录（WORKING_DIR）

所有运行时数据（`agents.json`、workspaces、skill_pool、plugins、备份、数据库、日志）集中存放在独立数据目录，与项目源码分离（参考 qwenpaw）：

- 解析优先级：**`MAJO_WORKING_DIR` 环境变量** → 默认 `{user.dir}/data/majo`
- 首次启动时若检测到旧位置（项目根）的 `agents.json` / `skill_pool` / `workspaces` / `plugins` 等，自动迁移到新目录（并重写 agent 的 `workspace_dir`）
- 每个 agent（含 default）的 workspace 是独立子目录：`data/majo/workspaces/{agent_id}`

```text
data/majo/
├── agents.json            # 多Agent 注册表
├── workspaces/{agent_id}/ # 各 agent 工作区
├── skill_pool/            # 技能池
├── plugins/               # 插件
├── inbox_traces/          # 收件箱追踪
├── db/majo.mv.db          # H2 数据库
├── majo.log               # 后端日志
└── .backups/              # 备份压缩包
```

## 快速开始

### Docker（推荐）

```bash
docker compose up -d
```

浏览器打开 http://localhost:18789 。前端已嵌入后端 JAR，无需额外启动。

数据持久化在宿主机 `./data/majo`（docker-compose 将 `./data` 挂载到容器 `/app/data`，容器内 `WORKING_DIR=/app/data/majo`）——包含 H2 数据库、agents.json、workspaces、skill_pool、备份、日志。

### 本地开发

#### 1. 启动后端

```powershell
cd backend
mvn spring-boot:run
```

首次启动 Flyway 会自动执行数据库迁移；数据目录 `data/majo/` 自动创建；旧数据自动迁移。

如需指定数据目录：`$env:MAJO_WORKING_DIR = "D:\majo-data"` 后再启动。

#### 2. 启动前端

```powershell
cd frontend
npm install
npm run dev
```

前端 dev server 运行在 http://localhost:5173 ，API 请求代理到 `localhost:18789`。

#### 3. 配置大模型

浏览器打开页面 → 点击 **Settings** → 填入：

| 字段 | 示例 |
|---|---|
| API URL | `https://www.dmxapi.cn` |
| API Key | `sk-xxxxxxxx` |
| Model | `deepseek-v4-pro-guan` |

点击 **Save** 即可生效，无需重启后端。

## Docker 部署

### docker compose

```bash
# 构建并启动
docker compose up -d

# 查看日志
docker compose logs -f

# 停止
docker compose down
```

### 镜像构建

```bash
docker build -t majo .
```

镜像为三阶段构建：Node 构建前端 → Maven 打包后端（前端嵌入 `static/`）→ JRE 运行。

### CI/CD

push 到 `main`/`master` 分支或推送 `v*` tag 时，GitHub Actions 自动构建并推送镜像到 `ghcr.io`。

## 项目结构

```
majo/
├── backend/
│   ├── src/main/java/com/agent/coding/
│   │   ├── AgentApplication.java            # 入口
│   │   ├── ChatService.java                 # 会话管理
│   │   ├── SettingsService.java             # 大模型配置
│   │   ├── WorkspaceContext.java            # 工作区上下文
│   │   ├── agent/
│   │   │   └── AgentStore.java              # 多Agent 注册表 (agents.json)
│   │   ├── backup/                          # 备份服务 (zip + 签名 + 恢复)
│   │   │   ├── BackupStore.java             # 存储/列表/签名
│   │   │   ├── BackupCreator.java           # 创建 + SSE 进度
│   │   │   ├── BackupRestorer.java          # 恢复
│   │   │   ├── BackupImporter.java          # 导入 + 冲突处理
│   │   │   └── BackupMeta.java              # 元数据模型
│   │   ├── controller/
│   │   │   ├── ChatController.java          # POST /api/chat (SSE)
│   │   │   ├── ConsoleController.java       # POST /api/console/chat
│   │   │   ├── ChatsController.java         # /api/chats CRUD
│   │   │   ├── AgentsController.java        # /api/agents 多Agent
│   │   │   ├── SkillsController.java        # /api/skills 技能池
│   │   │   ├── ToolsController.java         # /api/tools 工具管理
│   │   │   ├── McpController.java           # /api/mcp 客户端
│   │   │   ├── ACPConfigController.java     # /api/config/acp ACP 配置
│   │   │   ├── BackupController.java        # /api/backups 备份
│   │   │   ├── PluginsController.java       # /api/plugins 插件
│   │   │   ├── DesktopController.java       # /api/desktop/shutdown 桌面停机
│   │   │   ├── DesktopReadyPrinter.java     # 打印 MAJO_BACKEND_READY 就绪行
│   │   │   ├── SpaFallbackController.java   # /console → index.html SPA fallback
│   │   │   └── SettingsController.java      # GET/POST /api/settings
│   │   ├── skill/                           # 技能系统 (pool + workspace)
│   │   ├── entity/                          # JPA Entity
│   │   └── repository/
│   ├── src/main/resources/
│   │   ├── application.yml                  # 服务端口 + DB 配置
│   │   ├── builtin-skills/                  # 17 个内置技能 (classpath)
│   │   └── db/migration/                    # Flyway 迁移脚本 (V1-V24)
│   └── local-repo/                          # AgentScope jar (本地 Maven 仓库)
├── frontend/
│   ├── src/
│   │   ├── pages/Chat/                      # 聊天界面
│   │   ├── pages/Settings/                  # 设置页面
│   │   ├── stores/agentStore.ts             # 多Agent 前端状态
│   │   ├── api/modules/                     # API 客户端
│   │   └── tauri/                           # 桌面引导/轮询/更新 (仅桌面构建)
│   ├── src-tauri/                          # Tauri 2 桌面壳 (Rust)
│   │   ├── src/                            # sidecar 生命周期/托盘/更新/优雅停机
│   │   ├── tauri.conf.json                 # 窗口/打包/安全配置
│   │   └── capabilities/ permissions/      # Tauri 2 权限声明
├── scripts/pack-tauri/
│   ├── build-desktop.ps1                   # 桌面打包一条龙 (前端+jlink+tauri build)
│   └── finalize_tauri_bootstrap.mjs        # bootstrap 产物收尾 (no-op)
├── data/majo/                               # 运行时数据目录 (自动创建)
├── Dockerfile
├── docker-compose.yml
├── .github/workflows/docker-build.yml       # CI/CD
└── pom.xml                                  # 父 POM
```

## API

| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/health` | GET | 健康检查 |
| `/api/version` | GET | 版本信息（桌面引导页就绪轮询） |
| `/api/chat` | POST | 聊天消息 (SSE 流式) |
| `/api/console/chat` | POST | Console Chat (SSE，支持 `X-Agent-Id`) |
| `/api/chats` | GET | 会话列表（支持 `X-Agent-Id` 按 Agent 过滤） |
| `/api/chats/{id}` | GET | 会话详情 + 消息历史 |
| `/api/chats/{id}` | DELETE | 删除会话 |
| `/api/agents` | GET | 多Agent 列表 |
| `/api/agents` | POST | 创建 Agent |
| `/api/agents/{id}` | PUT/PATCH/DELETE | Agent CRUD |
| `/api/skills/pool` | GET | 技能池列表 |
| `/api/skills/workspaces` | GET | 各 Agent workspace 技能 |
| `/api/tools` | GET | 内置工具列表（支持 `X-Agent-Id` 按 Agent 启用状态） |
| `/api/mcp` | GET | MCP 客户端列表 |
| `/api/config/acp` | GET/PUT | ACP 配置 |
| `/api/backups` | GET | 备份列表 |
| `/api/backups/stream` | POST | 创建备份 (SSE 进度流) |
| `/api/backups/{id}` | GET | 备份详情 |
| `/api/backups/{id}/restore` | POST | 恢复备份 |
| `/api/backups/{id}/export` | GET | 导出备份 zip |
| `/api/backups/import` | POST | 导入备份（支持冲突 409 + trust_mode） |
| `/api/backups/delete` | POST | 删除备份 |
| `/api/settings` | GET/POST | 大模型配置 |
| `/api/providers` | GET | 模型供应商管理 |
| `/api/desktop/shutdown` | POST | 桌面优雅停机（需 token header，仅桌面模式） |

## 备份

备份为 zip 归档，存储在 `data/majo/.backups/`，包含：

- `data/workspaces/{agent_id}/` — 各 agent 工作区（不包含项目源码/构建产物）
- `data/config.json` — 全局配置（agents.json）
- `data/skill_pool/` — 技能池
- `data/secrets/` — 密钥（按 scope）

每个备份带本机 HMAC 签名（`hmac-sha256-v1`），用于区分"本机创建"、"他机导出（foreign）"与"旧版无签名（legacy）"备份。导入他机/旧版备份时需要前端确认 trust_mode。前端创建备份时通过 SSE 实时展示进度。

## 配置文件

`backend/src/main/resources/application.yml`：

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

无需在配置文件中填写大模型密钥。

## 切换到 PostgreSQL

1. `pom.xml` 替换 H2 依赖为 PostgreSQL：

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

2. `application.yml` 修改数据源：

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

Flyway 会自动在 PostgreSQL 上执行已有迁移脚本。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端框架 | Spring Boot 3.4 |
| AI 框架 | AgentScope 2.0 |
| 数据库 | H2 (file) → 可切换 PostgreSQL |
| 迁移工具 | Flyway 10 |
| ORM | Spring Data JPA + Hibernate 6 |
| 前端 | React + Vite + @agentscope-ai/chat |
| 桌面壳（可选） | Tauri 2（Rust，sidecar 模式，内置 jlink JRE） |
| 部署 | Docker + Docker Compose + GitHub Actions |
| Java | 21 |

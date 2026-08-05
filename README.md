# Majo — AI Coding Agent

Spring Boot + AgentScope + React + H2 + Flyway

## 架构

```
                    ┌─────────────────────────────────────┐
                    │          Spring Boot Backend         │
                    │  port 18789                          │
                    │                                      │
  Browser ──────────┤  /                ← static (前端 SPA) │
                    │  /api/chat        ← Chat SSE 流式    │
                    │  /api/console/chat← Console Chat     │
                    │  /api/agents      ← 多Agent 管理      │
                    │  /api/skills      ← 技能池 + 市场     │
                    │  /api/chats       ← 会话管理          │
                    │  /api/settings    ← 大模型配置        │
                    │  /api/providers   ← 模型供应商        │
                    │                                      │
                    │  ┌──────────────────────────────┐    │
                    │  │ AgentScope 2.0 (Harness)      │    │
                    │  │ read_file / write_file / ...  │    │
                    │  └──────────────────────────────┘    │
                    │                                      │
                    │  ┌──────────┐  ┌─────────────────┐   │
                    │  │ H2 (file)│  │ skill_pool/      │   │
                    │  │          │  │ workspaces/{id}/ │   │
                    │  │ Flyway   │  │ agents.json      │   │
                    │  └──────────┘  └─────────────────┘   │
                    └─────────────────────────────────────┘
```

## 快速开始

### Docker（推荐）

```bash
docker compose up -d
```

浏览器打开 http://localhost:18789 。前端已嵌入后端 JAR，无需额外启动。

数据持久化在 Docker volume `majo_data` 中（H2、agents.json、workspaces）。

### 本地开发

#### 1. 启动后端

```powershell
cd backend
mvn spring-boot:run
```

首次启动 Flyway 会自动执行数据库迁移，`skill_pool/`、`agents.json`、`workspaces/` 自动创建。

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
│   │   ├── controller/
│   │   │   ├── ChatController.java          # POST /api/chat (SSE)
│   │   │   ├── ConsoleController.java       # POST /api/console/chat
│   │   │   ├── ChatsController.java         # /api/chats CRUD
│   │   │   ├── AgentsController.java        # /api/agents 多Agent
│   │   │   ├── SkillsController.java        # /api/skills 技能池
│   │   │   └── SettingsController.java      # GET/POST /api/settings
│   │   ├── skill/                           # 技能系统 (pool + workspace)
│   │   ├── entity/                          # JPA Entity
│   │   └── repository/
│   ├── src/main/resources/
│   │   ├── application.yml                  # 服务端口 + DB 配置
│   │   ├── builtin-skills/                  # 17 个内置技能 (classpath)
│   │   └── db/migration/                    # Flyway 迁移脚本 (V1-V21)
│   └── local-repo/                          # AgentScope jar (本地 Maven 仓库)
├── frontend/
│   └── src/
│       ├── pages/Chat/                      # 聊天界面
│       ├── pages/Settings/                  # 设置页面
│       ├── stores/agentStore.ts             # 多Agent 前端状态
│       └── api/modules/                     # API 客户端
├── Dockerfile
├── docker-compose.yml
├── .github/workflows/docker-build.yml       # CI/CD
└── pom.xml                                  # 父 POM
```

## API

| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/health` | GET | 健康检查 |
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
| `/api/settings` | GET/POST | 大模型配置 |
| `/api/providers` | GET | 模型供应商管理 |

## 配置文件

`backend/src/main/resources/application.yml`：

```yaml
server:
  port: 18789

spring:
  datasource:
    url: jdbc:h2:file:./data/majo;DB_CLOSE_DELAY=-1;MODE=PostgreSQL
  flyway:
    enabled: true
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
| 部署 | Docker + Docker Compose + GitHub Actions |
| Java | 21 |

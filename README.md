# Majo — AI Coding Agent

Spring Boot + AgentScope + React + H2 + Flyway

## 架构

```
frontend (React)  ──POST /api/chat──▶  backend (Spring Boot)
     │                                      │
     │  Settings UI                          │  ChatController
     │  POST /api/settings                  │  SettingsService
     │                                      │  AgentConfig (Toolkit)
     └──────────────────────────────────────┘
                                                      │
                                                      ▼
                                              H2 Database (file)
                                              └── settings 表 (api_key, base_url, model_name)
                                              └── Flyway 迁移管理
```

## 快速开始

### 1. 启动后端

```powershell
cd backend
mvn spring-boot:run
```

首次启动 Flyway 会自动创建 `data/majo.mv.db` 并建表。

### 2. 启动前端

```powershell
cd frontend
npm install
npm run dev
```

### 3. 配置大模型

浏览器打开前端页面 → 点击 **Settings** → 填入：

| 字段 | 示例 |
|---|---|
| API URL | `https://www.dmxapi.cn` |
| API Key | `sk-xxxxxxxx` |
| Model | `deepseek-v4-pro-guan` |

点击 **Save** 即可生效，无需重启后端。配置保存在 H2 数据库中，重启后仍然保留。

## 项目结构

```
majo/
├── backend/
│   ├── src/main/java/com/agent/coding/
│   │   ├── AgentApplication.java        # 入口
│   │   ├── AgentConfig.java             # Toolkit Bean
│   │   ├── SettingsService.java         # 大模型配置 CRUD
│   │   ├── WorkspaceContext.java        # 工作区上下文
│   │   ├── entity/SettingsEntity.java   # JPA Entity
│   │   ├── repository/SettingsRepository.java
│   │   ├── controller/
│   │   │   ├── ChatController.java      # POST /api/chat (SSE)
│   │   │   └── SettingsController.java  # GET/POST /api/settings
│   │   └── tool/                        # AgentScope 工具
│   └── src/main/resources/
│       ├── application.yml              # 服务端口 + DB 配置
│       └── db/migration/
│           └── V1__create_settings_table.sql
├── frontend/
│   └── src/App.jsx                      # React 聊天界面 + Settings
└── pom.xml                              # 父 POM
```

## API

| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/health` | GET | 健康检查 |
| `/api/settings` | GET | 获取当前大模型配置 |
| `/api/settings` | POST | 保存大模型配置 `{apiKey, baseUrl, modelName}` |
| `/api/chat` | POST | 发送聊天消息 (SSE 流式) `{prompt, sessionId?, workspace?}` |

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

1. `pom.xml` 替换依赖：
   ```xml
   <!-- 移除 h2 -->
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
| 前端 | React + Vite |
| Java | 21 |

# Database DDL — SQLite 完整建表脚本

> **状态：✅ 完整** — 基于 [Domain Model](../02-Architecture/Domain%20Model.md) 实体定义和 [Database](../02-Architecture/Database.md) 设计，给出可直接执行的 SQLite DDL。

## 1. 建表脚本

```sql
-- =====================================================
-- AI Coding Agent — SQLite Schema v1.0
-- 执行环境：SQLite 3.40+ (WAL mode)
-- =====================================================

PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

-- =====================================================
-- 1. session
-- =====================================================
CREATE TABLE IF NOT EXISTS session (
    id              TEXT PRIMARY KEY NOT NULL,       -- UUID v4
    workspace_path  TEXT NOT NULL,                    -- 项目根目录绝对路径
    autonomy_level  TEXT NOT NULL DEFAULT 'L2'       -- 'L1'|'L2'|'L3'|'L4'
                        CHECK(autonomy_level IN ('L1','L2','L3','L4')),
    model_id        TEXT NOT NULL,                    -- 'qwen:qwen-plus' 等AgentScope格式
    status          TEXT NOT NULL DEFAULT 'Created'   -- 'Created'|'Active'|'Paused'|'Interrupted'|'Recovering'|'Completed'
                        CHECK(status IN ('Created','Active','Paused','Interrupted','Recovering','Completed')),
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    last_active_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_session_status ON session(status);
CREATE INDEX idx_session_workspace ON session(workspace_path);
-- 保证同一 Workspace 同时只有一个 Active Session
CREATE UNIQUE INDEX idx_session_active_workspace
    ON session(workspace_path) WHERE status = 'Active';

-- =====================================================
-- 2. task
-- =====================================================
CREATE TABLE IF NOT EXISTS task (
    id              TEXT PRIMARY KEY NOT NULL,       -- UUID v4
    session_id      TEXT NOT NULL REFERENCES session(id) ON DELETE CASCADE,
    user_prompt     TEXT NOT NULL,                    -- 用户原始输入文本
    status          TEXT NOT NULL DEFAULT 'TaskCreated'
                        CHECK(status IN ('TaskCreated','Running','WaitingUser','TaskCompleted','TaskFailed')),
    retry_count     INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    completed_at    TEXT
);

CREATE INDEX idx_task_session ON task(session_id);
CREATE INDEX idx_task_status ON task(status);
CREATE INDEX idx_task_created ON task(created_at DESC);

-- =====================================================
-- 3. turn
-- 主键为 (task_id, idx) 复合键
-- =====================================================
CREATE TABLE IF NOT EXISTS turn (
    idx             INTEGER NOT NULL CHECK(idx >= 0),
    task_id         TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    plan_state      TEXT,                             -- Plan 状态 JSON 快照
    started_at      TEXT NOT NULL DEFAULT (datetime('now')),
    completed_at    TEXT,
    PRIMARY KEY (task_id, idx)
);

-- =====================================================
-- 4. tool_call
-- =====================================================
CREATE TABLE IF NOT EXISTS tool_call (
    call_id         TEXT PRIMARY KEY NOT NULL,       -- UUID v4
    task_id         TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    turn_idx        INTEGER NOT NULL,
    tool_name       TEXT NOT NULL,
    parameters      TEXT NOT NULL,                    -- JSON 字符串
    status          TEXT NOT NULL DEFAULT 'pending'
                        CHECK(status IN ('pending','running','success','error','cancelled')),
    duration_ms     INTEGER,
    started_at      TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (task_id, turn_idx) REFERENCES turn(task_id, idx)
);

CREATE INDEX idx_tool_call_task ON tool_call(task_id);
CREATE INDEX idx_tool_call_tool_name ON tool_call(tool_name);
CREATE INDEX idx_tool_call_turn ON tool_call(task_id, turn_idx);

-- =====================================================
-- 5. observation
-- =====================================================
CREATE TABLE IF NOT EXISTS observation (
    call_id                 TEXT PRIMARY KEY NOT NULL REFERENCES tool_call(call_id) ON DELETE CASCADE,
    status                  TEXT NOT NULL CHECK(status IN ('success','error','partial')),
    content                 TEXT NOT NULL,
    error_code              TEXT,                     -- VALIDATION_ERROR|TIMEOUT|PERMISSION_DENIED|...
    exit_code               INTEGER,
    truncated               INTEGER NOT NULL DEFAULT 0 CHECK(truncated IN (0,1)),
    truncated_original_len  INTEGER
);

-- =====================================================
-- 6. snapshot
-- 保留策略：每个 Task 最多保留 3 个（应用层控制）
-- =====================================================
CREATE TABLE IF NOT EXISTS snapshot (
    id              TEXT PRIMARY KEY NOT NULL,       -- UUID v4
    task_id         TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    turn_idx        INTEGER NOT NULL,                 -- 快照对应的 Turn 序号
    plan_state      TEXT NOT NULL,                    -- Plan 状态 JSON 序列化
    modified_files  TEXT NOT NULL DEFAULT '[]',       -- JSON array [{path, mtime, sha256}]
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (task_id, turn_idx) REFERENCES turn(task_id, idx)
);

CREATE INDEX idx_snapshot_task ON snapshot(task_id);
-- 按 Turn 倒序取最近快照
CREATE INDEX idx_snapshot_task_turn ON snapshot(task_id, turn_idx DESC);

-- =====================================================
-- 7. permission_decision
-- =====================================================
CREATE TABLE IF NOT EXISTS permission_decision (
    id              TEXT PRIMARY KEY NOT NULL,       -- UUID v4
    task_id         TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    turn_idx        INTEGER NOT NULL,
    tool_name       TEXT NOT NULL,
    risk_tier       TEXT NOT NULL CHECK(risk_tier IN ('low','medium','high','critical')),
    decision        TEXT NOT NULL CHECK(decision IN ('allowed','needs_confirmation','denied')),
    reason          TEXT NOT NULL,
    was_escalated   INTEGER NOT NULL DEFAULT 0 CHECK(was_escalated IN (0,1)),
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (task_id, turn_idx) REFERENCES turn(task_id, idx)
);

CREATE INDEX idx_permission_task ON permission_decision(task_id);
CREATE INDEX idx_permission_decision ON permission_decision(decision);
CREATE INDEX idx_permission_created ON permission_decision(created_at);
```

## 2. 向量索引表（sqlite-vec）

```sql
-- 需要先加载 sqlite-vec 扩展
-- .load ./vec0

-- 向量索引：384 维（all-MiniLM-L6-v2）
CREATE VIRTUAL TABLE IF NOT EXISTS embedding_index USING vec0(
    chunk_id        INTEGER PRIMARY KEY,
    file_path       TEXT NOT NULL,
    symbol_name     TEXT,                             -- 关联的符号名（函数/类）
    chunk_text      TEXT NOT NULL,                    -- 代码片段原文（用于 Reranking）
    start_line      INTEGER NOT NULL,
    end_line        INTEGER NOT NULL,
    embedding       FLOAT[384]                        -- 384维向量
);

-- 辅助索引：按文件路径查询
CREATE INDEX IF NOT EXISTS idx_embedding_file
    ON embedding_index(file_path);
```

## 3. Schema Migration 策略

```sql
-- =====================================================
-- Migration 版本表
-- =====================================================
CREATE TABLE IF NOT EXISTS schema_version (
    version     INTEGER PRIMARY KEY NOT NULL,
    applied_at  TEXT NOT NULL DEFAULT (datetime('now')),
    description TEXT
);

-- 初始 Migration（v1.0.0）
INSERT INTO schema_version (version, description)
VALUES (1, 'Initial schema: session, task, turn, tool_call, observation, snapshot, permission_decision');
```

**Migration 执行方式**：Spring Boot 启动时检查 `schema_version` 表，顺序执行未应用的 Migration SQL 文件（存放在 `classpath:db/migration/`）。

## 4. 常用操作示例 SQL

### 4.1 创建 Session + 提交 Task

```sql
-- 创建 Session
INSERT INTO session (id, workspace_path, autonomy_level, model_id)
VALUES ('sess-001', '/home/user/projects/myapp', 'L2', 'qwen:qwen-plus');

-- 提交 Task
INSERT INTO task (id, session_id, user_prompt)
VALUES ('task-001', 'sess-001', '给用户模块加邮箱验证功能');
UPDATE session SET status = 'Active', last_active_at = datetime('now')
WHERE id = 'sess-001';
```

### 4.2 记录 Turn 和 ToolCall

```sql
-- 开始 Turn
INSERT INTO turn (idx, task_id) VALUES (0, 'task-001');

-- 记录工具调用
INSERT INTO tool_call (call_id, task_id, turn_idx, tool_name, parameters, status)
VALUES ('call-001', 'task-001', 0, 'read_file', '{"path":"src/auth/login.ts","offset":0,"limit":50}', 'success');

-- 记录 Observation
INSERT INTO observation (call_id, status, content, error_code)
VALUES ('call-001', 'success', 'import { AuthService } ... (file content)', NULL);

-- 完成 Turn
UPDATE turn SET completed_at = datetime('now') WHERE task_id = 'task-001' AND idx = 0;
```

### 4.3 记录 Permission Decision

```sql
INSERT INTO permission_decision (id, task_id, turn_idx, tool_name, risk_tier, decision, reason)
VALUES ('perm-001', 'task-001', 0, 'write_file', 'medium', 'needs_confirmation',
        'L2模式：write操作需用户确认。目标文件：src/auth/login.ts (+15 -3)');
```

### 4.4 Session 恢复查询

```sql
-- 1. 加载 Session 信息
SELECT * FROM session WHERE id = 'sess-001';

-- 2. 加载最近 Task
SELECT * FROM task WHERE session_id = 'sess-001' ORDER BY created_at DESC LIMIT 1;

-- 3. 加载最近 Snapshot（恢复用）
SELECT * FROM snapshot WHERE task_id = 'task-001' ORDER BY turn_idx DESC LIMIT 1;

-- 4. 加载上次 Turn 之后未完成的 Turn
SELECT * FROM turn
WHERE task_id = 'task-001' AND idx > (SELECT MAX(turn_idx) FROM snapshot WHERE task_id = 'task-001')
ORDER BY idx;
```

### 4.5 数据清理

```sql
-- 清理 30 天前的 turn/tool_call/observation 详情
DELETE FROM turn WHERE task_id IN (
    SELECT id FROM task WHERE created_at < datetime('now', '-30 days')
);
DELETE FROM permission_decision WHERE created_at < datetime('now', '-30 days');

-- 每个 Task 只保留最近 3 个 Snapshot（应用层维护，此处仅为示例查询）
-- SELECT id FROM snapshot WHERE task_id = ? ORDER BY turn_idx DESC LIMIT -1 OFFSET 3
```

## 5. JDBC 连接配置

```yaml
# Spring Boot application.yml
spring:
  datasource:
    url: jdbc:sqlite:${user.home}/.agent/data.db
    driver-class-name: org.sqlite.JDBC
  jpa:
    database-platform: org.sqlite.hibernate.dialect.SQLiteDialect  # 如使用Hibernate
    hibernate:
      ddl-auto: none      # 手动管理 Schema，不依赖 Hibernate 自动建表
```

**为什么不用 Hibernate 自动建表**：Migration 版本表 (`schema_version`) 需要显式管理版本演进。自动建表无法处理"已有数据的有序升级"场景。建议使用 Flyway 或 Liquibase，或直接执行 SQL 脚本。

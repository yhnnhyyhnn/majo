# Technical Architecture — 技术架构（Java + AgentScope）

> **状态：✅ 完整** — 基于 [ADR-0006](../08-ADR/ADR-0006-java-agentscope.md)（推翻 ADR-0001，选 Java + AgentScope Java 2.0）。本文档覆盖：技术栈、AgentScope 覆盖度映射、分层设计、Maven 多模块结构、自研模块实现路径。

## 1. 技术栈

| 领域 | 选型 | 说明 |
|---|---|---|
| **语言** | Java 21 LTS | Records、Sealed Classes、Virtual Threads、Pattern Matching |
| **Agent 框架** | AgentScope Java 2.0 (`io.agentscope:agentscope-harness`) | ReActAgent + HarnessAgent + PermissionEngine + ModelRegistry + Sandbox + MCP |
| **应用框架** | Spring Boot 3.x | DI 容器、配置管理、CLI 命令（通过 Spring Shell） |
| **响应式编程** | Project Reactor (Mono/Flux) | AgentScope 原生响应式——全链路异步非阻塞 |
| **构建工具** | Maven 3.9+（多模块） | AgentScope 原生 Maven 模块——保持一致 |
| **JDK 版本** | JDK 21（最低 JDK 17） | AgentScope 要求 |
| **CLI/TUI** | Spring Shell + JLine | 交互式终端命令 + Diff 渲染 + Review 面板 |
| **桌面应用** | JetBrains Compose for Desktop 或 JavaFX | 跨平台桌面 GUI |
| **LLM Provider** | AgentScope ModelRegistry（Qwen / DeepSeek / OpenAI / Anthropic / Ollama） | 不使用裸官方 SDK——通过 AgentScope 的 `agentscope-extensions-model-*` 模块接入 |
| **向量索引** | sqlite-vec（via JNI 或 standalone）或 AgentScope 内置 RAG | Context Engine 语义检索 |
| **本地 Embedding** | ONNX Runtime Java bindings | all-MiniLM-L6-v2 384 维 |
| **数据库** | SQLite（via HikariCP + SQLite JDBC） | Session 元数据 + 审计日志 |
| **Git 操作** | JGit（纯 Java Git 实现）或 org.eclipse.jgit | 分支管理、diff 生成、commit |
| **AST 解析** | Tree-sitter via JNI 或 javaparser（仅Java） | Context Engine 符号索引 |
| **构建/打包** | GraalVM native-image（via Spring Boot AOT） | 原生二进制分发，冷启动 < 200ms |
| **分发** | Maven Central + SDKMAN + 原生二进制下载 | 开发者渠道 |
| **测试框架** | JUnit 5 + Mockito + Testcontainers | 单元测试 + 集成测试 |
| **日志** | SLF4J + Logback（JSON encoder） | 结构化日志 |
| **CI/CD** | GitHub Actions（Maven 多平台矩阵） | macOS/Linux/Windows |

## 2. AgentScope 覆盖度映射

AgentScope Java 2.0 的核心能力与本产品 P0 RFC 的对齐关系：

```mermaid
graph TB
    subgraph AS["AgentScope Java 2.0 原生提供"]
        ReAct[ReActAgent<br/>Reason→Tool→Reply 循环]
        Harness[HarnessAgent<br/>Workspace+Memory+Sandbox+Sub-agent]
        Perm[PermissionEngine<br/>allow/approve/deny 三层]
        Model[ModelRegistry<br/>6家Provider+降级]
        Tool[Tookit+@Tool注解+MCP]
        State[AgentStateStore<br/>Session持久化+恢复]
        SB[Sandbox<br/>local/Docker/K8s/E2B]
        TE[OpenTelemetry原生<br/>Studio可视化]
        Stream[streamEvents()<br/>28种AgentEvent]
    end

    subgraph Our["我们基于AgentScope定制的部分"]
        CE[Context Engine<br/>三路检索+Reranking]
        GIT[JGit Git集成<br/>分支+commit+回退]
        CLI[Spring Shell TUI<br/>Diff渲染+Review面板]
        Desktop[JetBrains Compose桌面版]
        DeepPerm[L1-L4自主性<br/>HardConfirmList]
        Bench[Benchmark Task Set<br/>自动化运行]
    end

    ReAct -->|映射| RFC0001[RFC-0001 Agent Runtime ✅]
    Harness -->|映射| RFC0004[RFC-0004 Task Engine ✅]
    Harness -->|映射| RFC0006[RFC-0006 Workspace ✅]
    Perm -->|映射| RFC0015[RFC-0015 Permission ✅ (基础)]
    Model -->|映射| RFC0012[RFC-0012 Model Router ✅]
    Tool -->|映射| RFC0003[RFC-0003 Tool Runtime ✅]
    State -->|映射| RFC0004_2[RFC-0004 Session恢复 ✅]
    SB -->|映射| RFC0014[RFC-0014 Sandbox ✅]
    TE -->|映射| RFC0016[RFC-0016 Telemetry ✅]
    Stream -->|映射| RFC0007[RFC-0007 Review 事件流 ✅]

    CE -->|定制| RFC0002[RFC-0002 Context Engine]
    GIT -->|定制| RFC0008[RFC-0008 Git]
    CLI -->|定制| TUI[CLI/TUI界面]
    DeepPerm -->|定制| RFC0015_2[RFC-0015 L1-L4 + HardConfirmList]
    Bench -->|定制| Eval[验收评估]

    style AS fill:#e0ffe0
    style Our fill:#e8f4fd

    ReAct -.-> Our
    Harness -.-> Our
    Perm -.-> Our
```

**关键结论**：10 篇 P0 RFC 中，7 篇由 AgentScope 原生覆盖（我们做配置+策略定制），3 篇需要自研（Context Engine / Git / Review UI）。工程焦点从"造 Runtime"变成"造策略"。

## 3. 分层架构

```mermaid
graph TB
    subgraph UI["表现层（自研）"]
        TUI[Spring Shell CLI<br/>JLine Terminal UI]
        Desktop[JetBrains Compose Desktop]
    end

    subgraph App["应用层（自研）"]
        AgentService[Agent Service<br/>Spring Boot Bean]
        ContextService[Context Engine Service<br/>三路检索+Reranking]
        GitService[Git Service<br/>JGit 分支+commit+回退]
        ReviewService[Review Service<br/>Diff生成+Hunk交互]
    end

    subgraph AS["AgentScope Framework（依赖）"]
        HarnessAgent[HarnessAgent<br/>ReAct+Workspace+Memory+Sandbox]
        PermissionEngine[PermissionEngine + 自定义L1-L4策略]
        ModelRegistry[ModelRegistry + Provider Extensions]
        Toolkit[@Tool注解工具集]
        AgentStateStore[StateStore (SQLite/Redis)]
        EventStream[streamEvents() + 28种AgentEvent]
    end

    subgraph Infra["基础设施（部分自研）"]
        SQLite[(SQLite 数据库)]
        VectorStore[(sqlite-vec 向量索引)]
        ONNX[ONNX Runtime<br/>本地Embedding推理]
        TreeSitter[Tree-sitter<br/>AST解析]
        JGit[JGit<br/>Git操作]
        Sandbox[AgentScope Sandbox<br/>进程级隔离]
    end

    TUI --> App
    Desktop --> App
    App --> AS
    AS --> Infra

    style UI fill:#ffe0e0
    style App fill:#fff0e0
    style AS fill:#e0ffe0
    style Infra fill:#f0f0f0
```

## 4. Maven 多模块结构

```
agent-coding/
├── pom.xml                          # 根 POM：dependencyManagement + modules
│
├── agent-core/                      # 核心业务逻辑（自研）
│   ├── pom.xml
│   └── src/main/java/com/agent/coding/
│       ├── AgentCodingApplication.java       # Spring Boot 启动类
│       │
│       ├── service/                          # 应用服务层
│       │   ├── AgentService.java             # HarnessAgent 封装：配置+调用
│       │   ├── ContextEngineService.java     # 三路检索+Reranking 定制实现
│       │   ├── GitService.java               # JGit 分支+commit+回退
│       │   └── ReviewService.java            # Diff生成+Hunk交互状态机
│       │
│       ├── context/                          # Context Engine 定制
│       │   ├── SymbolIndexer.java            # Tree-sitter AST → 符号索引
│       │   ├── SemanticRetriever.java        # ONNX Embedding → sqlite-vec检索
│       │   ├── DepGraphBuilder.java          # 依赖图构建
│       │   ├── Reranker.java                 # 多维度Reranking
│       │   └── BudgetAllocator.java          # Context Window预算分配
│       │
│       ├── permission/                       # Permission 定制
│       │   ├── AutonomyConfig.java           # L1-L4自主性级别
│       │   ├── HardConfirmList.java          # 硬性确认清单
│       │   ├── CommandRiskAnalyzer.java      # execute_command动态分析
│       │   └── SessionTrustManager.java      # Session临时信任
│       │
│       ├── tool/                             # 内置工具（@Tool注解）
│       │   ├── ReadFileTool.java             # 文件读取
│       │   ├── WriteFileTool.java            # 文件写入
│       │   ├── EditFileTool.java             # oldString/newString替换
│       │   ├── ListDirectoryTool.java        # 目录浏览
│       │   ├── SearchCodeTool.java           # grep搜索
│       │   ├── SearchSymbolTool.java         # AST符号搜索
│       │   └── ExecuteCommandTool.java       # 命令执行（经Sandbox）
│       │
│       ├── git/                              # Git 集成
│       │   ├── BranchManager.java            # 智能分支策略
│       │   ├── CommitService.java            # Commit+suggest/auto
│       │   ├── RollbackService.java          # 一键回退
│       │   └── ConflictDetector.java         # 外部Git操作检测
│       │
│       ├── event/                            # 事件监听
│       │   ├── AgentEventListener.java       # AgentScope Event订阅
│       │   └── UIEventPublisher.java         # 向TUI发布状态的EventPublisher
│       │
│       └── config/                           # Spring配置
│           ├── AgentScopeConfig.java         # HarnessAgent Bean配置
│           ├── ModelConfig.java              # ModelRegistry 配置
│           ├── SandboxConfig.java            # Sandbox 策略配置
│           └── PermissionConfig.java         # PermissionEngine + 自定义规则
│
├── agent-tui/                       # CLI/TUI（自研）
│   ├── pom.xml
│   └── src/main/java/com/agent/coding/tui/
│       ├── AgentShell.java                   # Spring Shell 命令定义
│       │                                     # > agent start, resume, history, config, clean
│       ├── ui/
│       │   ├── TaskView.java                 # 任务进度展示
│       │   ├── DiffView.java                 # Diff渲染
│       │   ├── ReviewPanel.java              # 逐Hunk审查交互
│       │   ├── ContextInspector.java         # Context可审查面板
│       │   ├── StatusLine.java               # 状态栏
│       │   └── StreamRenderer.java           # AgentScope Event → TUI渲染
│       └── service/
│           └── AgentClient.java             # 调用 AgentService 的客户端
│
├── agent-desktop/                   # 桌面版（自研）
│   ├── pom.xml
│   └── src/main/java/com/agent/coding/desktop/
│       └── MainWindow.kt                    # JetBrains Compose 主窗口
│
├── agent-benchmark/                 # Benchmark Task Set 自动化（自研）
│   ├── pom.xml
│   └── src/main/java/com/agent/coding/benchmark/
│       ├── BenchmarkRunner.java             # 任务定义加载+执行+判定
│       └── scorer/                           # 各类型任务的评分器
│
└── agent-distribution/              # 分发打包
    ├── pom.xml
    └── src/main/assembly/
        └── native.xml                        # GraalVM native-image 配置
```

### 4.1 Maven 依赖管理

```xml
<!-- agent-core/pom.xml 核心依赖 -->
<dependencies>
    <!-- AgentScope 核心（ReActAgent + HarnessAgent + Permission + Sandbox + MCP） -->
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope-harness</artifactId>
        <version>2.0.0</version>
    </dependency>

    <!-- Model Providers（不使用官方SDK） -->
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope-extensions-model-qwen</artifactId>
        <version>2.0.0</version>
    </dependency>
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope-extensions-model-deepseek</artifactId>
        <version>2.0.0</version>
    </dependency>
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope-extensions-model-ollama</artifactId>
        <version>2.0.0</version>
    </dependency>

    <!-- 基础设施 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.eclipse.jgit</groupId>
        <artifactId>org.eclipse.jgit</artifactId>
    </dependency>
    <dependency>
        <groupId>org.xerial</groupId>
        <artifactId>sqlite-jdbc</artifactId>
    </dependency>
</dependencies>
```

## 5. 核心自研模块实现路径

### 5.1 AgentService —— HarnessAgent 封装

```java
@Service
public class AgentService {
    private final HarnessAgent agent;

    public AgentService(AgentScopeConfig config) {
        this.agent = HarnessAgent.builder()
            .name("coding-agent")
            .sysPrompt(config.getSystemPrompt())        // 来自 RFC-0013 Prompt Engine
            .model(config.getDefaultModel())             // "qwen:qwen-plus" / "deepseek:deepseek-chat"
            .workspace(config.getWorkspacePath())
            .toolkit(customToolkit())                    // 注册内置工具
            .permission(customPermissionEngine())        // L1-L4 + HardConfirmList
            .build();
    }

    public Flux<AgentEvent> executeTask(String userId, String sessionId, String prompt) {
        return agent.streamEvents(
            Msg.builder().role(Role.USER).content(prompt).build(),
            RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build()
        );
    }
}
```

**关键**：AgentScope 的 `streamEvents()` 返回 28 种 `AgentEvent`——我们的 TUI 层的 `StreamRenderer` 订阅这些事件驱动 UI 更新（RFC-0007 Review 的 Diff 展示、状态栏等）。不需要自己写 EventBus。

### 5.2 ContextEngineService —— 唯一的重型自研模块

AgentScope 提供 Workspace + Memory + RAG 基础能力，但**不提供代码库专用三路检索**。这是我们产品差异化的核心——需要在 AgentScope 的基础上定制实现。

```java
@Service
public class ContextEngineService {
    // AgentScope 的 Workspace 提供文件访问
    private final WorkspaceBase workspace;

    // 自研部分
    private final SymbolIndexer symbolIndexer;     // Tree-sitter
    private final SemanticRetriever retriever;     // ONNX + sqlite-vec
    private final DepGraphBuilder depGraph;        // M2交付
    private final Reranker reranker;               // 多维度Reranking
    private final BudgetAllocator budgetAllocator; // 预算分配

    public List<ContextCard> retrieve(String taskDescription, int budget) {
        List<ContextCard> symbolResults = symbolIndexer.search(taskDescription);
        List<ContextCard> semanticResults = retriever.search(taskDescription);
        List<ContextCard> depResults = depGraph.query(taskDescription); // M2

        List<ContextCard> merged = deduplicate(symbolResults, semanticResults, depResults);
        List<ContextCard> ranked = reranker.rank(merged, taskDescription);
        return budgetAllocator.allocate(ranked, budget);
    }
}
```

### 5.3 Permission 定制 —— PermissionEngine 扩展

AgentScope 提供 `PermissionEngine`（allow/approve/deny 三层），我们需要实现 RFC-0015 的 L1-L4 自主性级别和 HardConfirmList：

```java
@Component
public class CustomPermissionConfig {
    public PermissionEngine createEngine(AutonomyLevel level, HardConfirmList hardList) {
        return PermissionEngine.builder()
            .staticRule(tool -> {
                // 静态规则：工具风险级别 → 判定
                if (hardList.isHardConfirm(tool)) {
                    return PermissionDecision.REQUIRE_APPROVAL;  // 强确认
                }
                return level.evaluate(tool.getRiskTier());       // L1-L4判定
            })
            .inputAnalyzer(new CommandRiskAnalyzer())             // execute_command动态分析
            .build();
    }
}
```

### 5.4 Git 集成 —— JGit

AgentScope 不提供 Git 工作流能力。RFC-0008 的所有逻辑（智能分支策略、SafeBase、一键回退、外部操作冲突检测）都需要基于 JGit 自研。

## 6. 构建与分发

```bash
# 开发模式
mvn spring-boot:run -pl agent-tui

# 运行 Benchmark
mvn exec:java -pl agent-benchmark

# 构建原生镜像（GraalVM native-image）
mvn -Pnative native:compile -pl agent-distribution

# 产物
# agent-distribution/target/agent-cli        # 原生二进制 (~30MB)
# agent-distribution/target/agent-desktop    # 桌面版原生二进制
```

## 7. 从 TS 到 Java 的关键差异

| 维度 | TS 方案（旧） | Java 方案（新） |
|---|---|---|
| Agent Runtime 实现 | 自研 `runAgenticLoop()` (~500行核心代码) | `HarnessAgent.streamEvents()` （框架提供） |
| Tool Runtime   | 自研 ToolRegistry + Dispatcher | `@Tool` 注解 + `Toolkit` |
| Permission     | 自研 Evaluator + CommandAnalyzer | `PermissionEngine` + 自定义规则 |
| Model Router   | 自研 ProviderAdapter（OpenAI+Anthropic） | `ModelRegistry` + model extensions |
| Session 持久化 | 自研 SQLite Snapshot 机制 | `AgentStateStore` (JsonFile/Redis) |
| Event Stream   | 自研 EventBus + async iterator | `streamEvents()` 28种AgentEvent |
| Sandbox        | 自研三平台 Sandbox | AgentScope Sandbox |
| Telemetry      | 自研 pino 日志 | OpenTelemetry 原生 |
| MCP            | 需自研客户端 | AgentScope 内置 |
| **预计代码量** | **~15,000 行核心代码** | **~5,000 行核心 + 框架配置** |

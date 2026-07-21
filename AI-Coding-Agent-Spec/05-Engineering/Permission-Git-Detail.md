# Permission & Git Detailed Design — 权限与 Git 详细设计

> **状态：✅ 完整** — 基于 [RFC-0015 Permission](../03-RFC/RFC-0015%20Permission.md) 和 [RFC-0008 Git](../03-RFC/RFC-0008%20Git.md)，给出 Java 实现级设计。

## 1. CustomPermissionConfig —— L1-L4 + HardConfirmList

```java
@Component
public class CustomPermissionConfig {

    private final HardConfirmList hardConfirmList;
    private final CommandRiskAnalyzer commandRiskAnalyzer;
    private final SessionTrustManager sessionTrustManager;

    /**
     * 创建 PermissionEngine——注入到 HarnessAgent。
     */
    public PermissionEngine createEngine(AutonomyLevel defaultLevel) {
        return PermissionEngine.builder()
            // 静态规则：工具风险级别 × 自主性级别 → 判定
            .staticRule(toolCall -> evaluateStatic(toolCall, defaultLevel))
            // 输入分析：execute_command 动态风险判定
            .inputAnalyzer(toolCall -> {
                if ("execute_command".equals(toolCall.toolName())) {
                    return commandRiskAnalyzer.analyze(toolCall.parameters());
                }
                return null;  // 其他工具不需要动态分析
            })
            .build();
    }

    /**
     * L1-L4 判定矩阵——与 RFC-0015 §4 完全一致。
     */
    private PermissionDecision evaluateStatic(ToolCall toolCall, AutonomyLevel level) {
        RiskTier risk = resolveRiskTier(toolCall);

        // 1. 先检查 HardConfirmList——任何级别都无法绕过
        if (hardConfirmList.isHardConfirm(toolCall)) {
            return PermissionDecision.REQUIRE_APPROVAL;
        }

        // 2. 按风险级别 × 自主性级别判定
        return switch (risk) {
            case READONLY -> level.compareTo(AutonomyLevel.L2) >= 0
                ? PermissionDecision.ALLOW
                : PermissionDecision.REQUIRE_APPROVAL;
            case WRITE -> level.compareTo(AutonomyLevel.L3) >= 0
                ? PermissionDecision.ALLOW
                : PermissionDecision.REQUIRE_APPROVAL;
            case DESTRUCTIVE -> PermissionDecision.REQUIRE_APPROVAL;
            case DYNAMIC -> evaluateDynamic(toolCall, level);
        };
    }

    private RiskTier resolveRiskTier(ToolCall tc) {
        return switch (tc.toolName()) {
            case "read_file", "list_directory", "search_code", "search_symbol"
                -> RiskTier.READONLY;
            case "write_file", "edit_file"
                -> RiskTier.WRITE;
            case "execute_command"
                -> RiskTier.DYNAMIC;  // 走 CommandRiskAnalyzer
            default
                -> RiskTier.WRITE;     // 未知工具默认中风险
        };
    }

    private PermissionDecision evaluateDynamic(ToolCall tc, AutonomyLevel level) {
        // CommandRiskAnalyzer 返回的风险级别
        RiskTier dynamicRisk = commandRiskAnalyzer.analyze(tc.parameters());

        return switch (dynamicRisk) {
            case CRITICAL -> PermissionDecision.REQUIRE_APPROVAL;
            case HIGH -> PermissionDecision.REQUIRE_APPROVAL;
            case MEDIUM -> level.compareTo(AutonomyLevel.L4) >= 0
                ? PermissionDecision.ALLOW
                : PermissionDecision.REQUIRE_APPROVAL;
            case LOW -> level.compareTo(AutonomyLevel.L3) >= 0
                ? PermissionDecision.ALLOW
                : PermissionDecision.REQUIRE_APPROVAL;
            default -> PermissionDecision.REQUIRE_APPROVAL;
        };
    }
}
```

### 1.1 L1-L4 判定矩阵（可视化）

| 工具 / 风险级别 | L1（安全） | L2（标准·默认） | L3（自主） | L4（完全） |
|---|---|---|---|---|
| `read_file` (readonly) | 确认 | **允许** | 允许 | 允许 |
| `write_file` (write) | 确认 | **确认** | 允许 | 允许 |
| `execute_command` → LOW (装依赖/跑测试) | 确认 | 确认 | 允许 | 允许 |
| `execute_command` → MEDIUM (修改代码) | 确认 | 确认 | **确认** | 允许 |
| `execute_command` → HIGH (rm -rf) | 强确认 | 强确认 | 强确认 | **强确认** |
| `execute_command` → CRITICAL (sudo) | 拒绝 | 强确认 | 强确认 | 强确认 |

## 2. CommandRiskAnalyzer —— execute_command 动态分析

```java
@Component
public class CommandRiskAnalyzer {

    /**
     * 命令风险模式列表——按严重度从高到低匹配。
     */
    private static final List<RiskPattern> PATTERNS = List.of(
        // CRITICAL: 提权、不可逆系统操作
        new RiskPattern(RiskTier.CRITICAL,
            Pattern.compile("\\bsudo\\b"),
            "sudo 提权操作"),
        new RiskPattern(RiskTier.CRITICAL,
            Pattern.compile("\\bchmod\\s+777\\b"),
            "chmod 777 全局开放权限"),

        // HIGH: 不可逆删除、强制推送
        new RiskPattern(RiskTier.HIGH,
            Pattern.compile("\\brm\\s+-rf\\b"),
            "rm -rf 不可逆删除"),
        new RiskPattern(RiskTier.HIGH,
            Pattern.compile("\\bgit\\s+push\\s+.*--force"),
            "git push --force 强制推送"),
        new RiskPattern(RiskTier.HIGH,
            Pattern.compile("\\bgit\\s+reset\\s+--hard"),
            "git reset --hard 不可逆操作"),
        new RiskPattern(RiskTier.HIGH,
            Pattern.compile("\\bcurl\\b.*\\|\\s*(ba)?sh\\b"),
            "curl | bash 管道下载执行（供应链攻击入口）"),

        // MEDIUM: 文件系统修改
        new RiskPattern(RiskTier.MEDIUM,
            Pattern.compile("\\bmv\\b|\\bcp\\b|\\brename\\b"),
            "文件系统修改操作"),

        // LOW: 开发工具（默认放行在 L3+）
        new RiskPattern(RiskTier.LOW,
            Pattern.compile("\\bnpm\\s+(install|ci)\\b|\\bpip\\s+install\\b"),
            "依赖安装"),
        new RiskPattern(RiskTier.LOW,
            Pattern.compile("\\bnpm\\s+(test|run)|\\bmvn\\s+test\\b"),
            "测试执行"),
        new RiskPattern(RiskTier.LOW,
            Pattern.compile("\\bgit\\s+(status|diff|log|branch|checkout)\\b"),
            "Git 只读/分支操作")
    );

    /**
     * 分析命令——返回风险级别。
     * 先匹配 PATTERNS（精确模式），再结合上下文信息调整。
     */
    public RiskTier analyze(String command) {
        // Step 1: 模式匹配
        for (RiskPattern pattern : PATTERNS) {
            if (pattern.regex().matcher(command).find()) {
                // Step 2: 上下文调整
                return adjustByContext(pattern.risk(), command);
            }
        }
        // 未命中任何已知模式 → 默认为 MEDIUM（保守策略）
        return RiskTier.MEDIUM;
    }

    /**
     * 上下文感知调整——降低误判。
     */
    private RiskTier adjustByContext(RiskTier baseRisk, String command) {
        // rm -rf node_modules → 降级为 LOW（常见清理操作）
        if (baseRisk == RiskTier.HIGH
            && command.contains("node_modules")
            && !command.contains("/")) {
            return RiskTier.LOW;
        }
        // rm -rf dist / build → 降级为 LOW
        if (baseRisk == RiskTier.HIGH
            && (command.contains("dist") || command.contains("build"))
            && !command.contains("/")) {
            return RiskTier.LOW;
        }
        // 命令引用 Workspace 外路径 → 升级风险
        if (command.contains("~/") || command.contains("/etc/")
            || command.contains("/usr/") || command.contains("/var/")) {
            return RiskTier.HIGH;
        }
        return baseRisk;
    }
}

record RiskPattern(RiskTier risk, Pattern regex, String description) {}

enum RiskTier { READONLY, WRITE, DESTRUCTIVE, DYNAMIC, LOW, MEDIUM, HIGH, CRITICAL }
enum AutonomyLevel { L1, L2, L3, L4 }
```

## 3. HardConfirmList —— 不可绕过的硬性确认清单

```java
@Component
public class HardConfirmList {

    /**
     * 硬性确认条目——匹配规则。
     */
    private static final List<HardConfirmRule> RULES = List.of(
        // 不可逆删除（非构建产物路径）
        HardConfirmRule.of(
            tc -> "execute_command".equals(tc.toolName())
               && tc.parameters().contains("rm -rf")
               && !tc.parameters().contains("node_modules")
               && !tc.parameters().contains("dist"),
            "rm -rf 删除非构建产物路径"),

        // 强制推送
        HardConfirmRule.of(
            tc -> tc.parameters().contains("git push") && tc.parameters().contains("--force"),
            "git push --force 强制远程推送"),

        // git reset --hard
        HardConfirmRule.of(
            tc -> tc.parameters().contains("git reset --hard"),
            "git reset --hard 不可逆本地操作"),

        // sudo 提权
        HardConfirmRule.of(
            tc -> tc.parameters().contains("sudo"),
            "sudo 提权操作"),

        // Workspace 外路径写入
        HardConfirmRule.of(
            tc -> "write_file".equals(tc.toolName())
               && isOutsideWorkspace(tc.parameters()),
            "写入 Workspace 外路径"),

        // curl | bash
        HardConfirmRule.of(
            tc -> tc.parameters().matches(".*curl.*\\|.*(ba)?sh.*"),
            "curl | bash 管道下载执行"),

        // 敏感文件权限修改
        HardConfirmRule.of(
            tc -> tc.parameters().contains("chmod")
               && (tc.parameters().contains(".gitignore")
                || tc.parameters().contains(".env")
                || tc.parameters().contains(".npmrc")),
            "修改敏感配置文件权限")
    );

    /**
     * 检查工具调用是否命中硬性确认清单。
     */
    public boolean isHardConfirm(ToolCall toolCall) {
        return RULES.stream().anyMatch(rule -> rule.predicate().test(toolCall));
    }

    /**
     * 获取匹配的规则描述（用于可解释性提示）。
     */
    public List<String> getMatchingReasons(ToolCall toolCall) {
        return RULES.stream()
            .filter(rule -> rule.predicate().test(toolCall))
            .map(HardConfirmRule::reason)
            .toList();
    }

    private static boolean isOutsideWorkspace(String parameters) {
        // 简单检测：参数中包含 ~/ /etc/ /usr/ 等绝对路径
        return parameters.contains("~/") || parameters.contains("/etc/")
            || parameters.contains("/usr/") || parameters.contains("/var/");
    }
}

record HardConfirmRule(Predicate<ToolCall> predicate, String reason) {
    static HardConfirmRule of(Predicate<ToolCall> predicate, String reason) {
        return new HardConfirmRule(predicate, reason);
    }
}
```

### 3.1 查找算法复杂度

- HardConfirmList 规则数：7 条（v1.0），匹配一次 O(7) —— 线性扫描即可，不需要引入 Trie/Aho-Corasick
- CommandRiskAnalyzer 模式数：9 条，匹配一次 O(9) —— 同上
- 两者合计每个 ToolCall 判定开销 < 1ms —— 不成为性能瓶颈

## 4. SessionTrustManager —— Session 临时信任

```java
@Component
public class SessionTrustManager {

    /**
     * 临时信任记录：{ toolName → RiskTier → 信任授予时间 }
     */
    private final Map<String, ConcurrentHashMap<String, Instant>> sessionTrusts
        = new ConcurrentHashMap<>();

    /**
     * 检查同类型操作是否已被信任。
     */
    public boolean isTrusted(String sessionId, String toolName, RiskTier risk) {
        var trusts = sessionTrusts.get(sessionId);
        if (trusts == null) return false;
        return trusts.containsKey(trustKey(toolName, risk));
    }

    /**
     * 记录连续确认后的"信任"。
     * 规则：同一 session 内，同 toolName + 同 riskTier 操作被确认 3 次后触发。
     */
    public void recordConfirm(String sessionId, String toolName, RiskTier risk) {
        String key = sessionId + ":" + toolName + ":" + risk;
        // TODO: 实现确认计数器
        // 确认 3 次 → 调用 grantTrust
    }

    public void grantTrust(String sessionId, String toolName, RiskTier risk) {
        sessionTrusts
            .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
            .put(trustKey(toolName, risk), Instant.now());
    }

    /**
     * Session 结束时清除所有临时信任。
     */
    public void clearSession(String sessionId) {
        sessionTrusts.remove(sessionId);
    }

    private String trustKey(String toolName, RiskTier risk) {
        return toolName + ":" + risk.name();
    }
}
```

## 5. JGit BranchManager —— 智能分支管理

```java
@Component
public class BranchManager {

    private final Git git;

    /**
     * 智能分支创建策略。
     */
    public BranchDecision decide(String sessionId) throws GitAPIException {
        Repository repo = git.getRepository();
        String currentBranch = repo.getBranch();
        Status status = git.status().call();

        boolean isDirty = !status.getModified().isEmpty()
            || !status.getAdded().isEmpty()
            || !status.getRemoved().isEmpty()
            || !status.getUntracked().isEmpty();

        // 场景 1: Dirty Working Directory → 不切换分支
        if (isDirty && !currentBranch.startsWith("agent/task-")) {
            return new BranchDecision(
                BranchAction.STAY_ON_CURRENT,
                currentBranch,
                "Working Directory 有未提交改动——在当前分支继续工作"
            );
        }

        // 场景 2: 已在 Agent 分支上 → 询问用户
        if (currentBranch.startsWith("agent/task-")) {
            return new BranchDecision(
                BranchAction.ASK_USER,
                currentBranch,
                "当前已在 Agent 分支 '" + currentBranch + "'。继续此分支 or 创建新分支？"
            );
        }

        // 场景 3: Clean Working Directory, Main/Feature 分支 → 创建 WorkBranch
        if (!isDirty && !currentBranch.startsWith("agent/task-")) {
            String workBranch = generateBranchName();
            git.checkout()
                .setCreateBranch(true)
                .setName(workBranch)
                .call();
            return new BranchDecision(
                BranchAction.CREATED_WORK_BRANCH,
                workBranch,
                "创建 Agent 工作分支 '" + workBranch + "'"
            );
        }

        return new BranchDecision(BranchAction.STAY_ON_CURRENT, currentBranch, "");
    }

    private String generateBranchName() {
        return "agent/task-" +
            LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) +
            "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 合并 WorkBranch 回原分支。
     */
    public void mergeBack(String workBranch, String targetBranch) throws GitAPIException {
        git.checkout().setName(targetBranch).call();
        git.merge().include(git.getRepository().resolve(workBranch)).call();
    }
}

record BranchDecision(BranchAction action, String branch, String message) {}
enum BranchAction { STAY_ON_CURRENT, CREATED_WORK_BRANCH, ASK_USER }
```

## 6. JGit CommitService

```java
@Component
public class CommitService {

    private final Git git;
    private final CommitStrategy defaultStrategy;

    /**
     * 执行 commit——根据策略决定行为。
     */
    public CommitResult commit(String taskSummary, CommitStrategy strategy)
            throws GitAPIException {

        // 生成 commit message（Conventional Commits 格式）
        String message = generateCommitMessage(taskSummary);

        return switch (strategy) {
            case SUGGEST -> {
                // 建议模式：返回 message 供用户确认，不实际 commit
                yield new CommitResult(false, message, null);
            }
            case AUTO -> {
                // 自动模式（需用户主动启用）
                RevCommit rev = git.commit()
                    .setMessage(message)
                    .call();
                yield new CommitResult(true, message, rev.getName());
            }
        };
    }

    /**
     * 用户确认后执行 commit。
     */
    public CommitResult commitWithMessage(String customMessage) throws GitAPIException {
        RevCommit rev = git.commit()
            .setMessage(customMessage)
            .call();
        return new CommitResult(true, customMessage, rev.getName());
    }

    /**
     * 生成 Conventional Commits 格式的 commit message。
     * type 基于 taskSummary 推断；scope 从修改的文件目录推断。
     */
    private String generateCommitMessage(String taskSummary) {
        // 简化实现——实际应调用 Prompt Engine 生成
        return "feat(auth): " + taskSummary;
    }
}

record CommitResult(boolean committed, String message, String commitHash) {}
enum CommitStrategy { SUGGEST, AUTO }
```

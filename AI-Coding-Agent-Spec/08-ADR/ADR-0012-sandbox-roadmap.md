# ADR-0012：沙箱执行隔离路线图——进程级加固先行，OS 原生隔离按平台分期

## Status

Accepted（2026-09-16）

## Context

QwenPaw 有完整的 OS 级沙箱层（`sandbox/`：Linux bubblewrap / macOS sandbox-exec / Windows AppContainer，提权与非提权双路径）。majo 的隔离目前停留在**策略层**：`ToolGuardHook` → `ToolGuardService`（命令黑名单）+ `FileGuardService`（工作区包含）+ `ApprovalHook`（人工审批），`ExecuteCommandTool` 直接以用户身份跑 shell。

ADR-0002 早已裁定路线："v1.0 进程级，容器级 P1+"。当前的实际状态落后于该裁定——进程级本身也有缺口：

1. **超时失效缺陷**：`ExecuteCommandTool` 的输出读取循环阻塞在 `readLine()`，进程不关 stdout 时 `waitFor(timeout)` 永远执行不到——超时参数形同虚设，agent 可被无限挂起。
2. **进程树残留**：超时/输出超限时 `destroyForcibly()` 只杀直接子进程（Windows 上 `cmd.exe /c` 的孙进程存活），孤儿进程继续占资源。

同时，直接跳到 AppContainer/bubblewrap 不现实：Win32 interop（JNA + CreateProcess 属性列表/受限令牌）工作量大且无法在此环境可靠验证，先行落地会产生不可信的安全声明——比没有沙箱更糟。

## Decision

**分三期，本 ADR 落地第一期：**

### 第一期（本 ADR，立即）：进程级加固（纯 Java，无原生依赖）

- **超时真实生效**：后台线程消费 stdout，主线程 `waitFor(timeout)` 强制限时；超时后 kill 进程树（`process.descendants()` + `destroyForcibly`，Windows 上先 `taskkill /T /F` 兜底由 JVM `descendants` 覆盖）。
- **进程树清理**：正常退出与异常路径同样执行 descendants 清理，消灭孤儿。
- **输出上限保持**（50k 字符，截断标注），达到上限即杀树。
- 定位：**资源治理与可用性**，不是安全边界——明确不声称"防恶意命令"。防恶意仍由 ToolGuard 黑名单 + 审批承担。

### 第二期（后续，需要专门验证环境）：OS 原生隔离

- Windows：AppContainer（JNA，受限令牌 + 能力 SID），优先于其他平台（majo 主要开发/部署环境）
- Linux：bubblewrap 包装（`bwrap --ro-bind --dev-bind --tmpfs` 白名单式文件系统视图）
- macOS：sandbox-exec profile（已废弃但仍可用的 `sandbox-init`，或 Endpoint Security——后者明确不做）
- 引入任一平台前必须有该平台上的自动化验证（受限进程内尝试越界写并断言失败）

### 第三期（远期）：容器级

- Docker/gVisor 每会话容器（ADR-0002 的 P1+ 项），服务化部署形态启用

### 明确不做

- 不做自研 sandbox-init 语言/策略引擎——用平台原生机制
- 不在第一期引入 JNA/原生依赖

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

**第二期 Windows 部分已落地（2026-09-30，本机 Win10 19045 真实验证）：**

- `sandbox/` 包：`AppContainerSandbox`（JNA 桥接：CreateAppContainerProfile 幂等建档 /
  SECURITY_CAPABILITIES 属性列表启动 / 管道输出收集 / 进程树终止）+ `SandboxService`（配置路由，
  `sandbox.mode="appcontainer"` 时 `execute_command` 改走容器，默认 `off`，失败 fail-closed 不回退明执行）
- 本机自动化边界验证（`AppContainerBoundaryVerificationTest`，`@EnabledOnOs(WINDOWS)`，CI Linux 跳过）：
  子进程令牌特权被削减（无 SeDebug/SeTakeOwnership）、越界读拒绝、越界写文件未产生、工作区内读写正常、
  profile 幂等复用、父建文件对容器可见
- **平台发现（血泪教训，后续平台接入前先验证同类问题）**：
  1. JNA 的 `char[]` 命令行不带 null 终止符，CreateProcessW 会把堆垃圾读进最后一个参数——必须显式补 `\0`；
  2. 子进程控制台输出为 OEM 代码页（中文系统 GBK），按 JVM 默认 UTF-8 解码全为乱码，需按 GetOEMCP() 选字符集；
  3. **deny ACE 对 lowbox 子进程不可靠**：首位置 deny-full（目录继承或文件直落）被忽略，同 SID 的 allow 照常授权
     （Get-Acl 确认 ACE 存在且顺序正确）。因此沙箱策略是**构造即正确的白名单**：grant 与 deny 路径重叠时整个
     grant 被剔除（fail-closed，`SandboxServicePolicyTest`），deny ACE 仅作纵深防御保留；
  4. ACL 写入用 inbox `icacls` 子进程而非 SetEntriesInAclW interop——后者在本机产生掩码异常的 deny ACE。
- 与上游差异：QwenPaw 另有提权服务路径与 bubblewrap/macOS 实现；majo 二期仅覆盖非提权 AppContainer，
  Linux/macOS 维持一期进程加固，待有对应验证环境再接

### 第三期（远期）：容器级

- Docker/gVisor 每会话容器（ADR-0002 的 P1+ 项），服务化部署形态启用

### 明确不做

- 不做自研 sandbox-init 语言/策略引擎——用平台原生机制
- 不在第一期引入 JNA/原生依赖

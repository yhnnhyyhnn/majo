# ADR-0002：Sandbox 隔离技术路线

## Status

Accepted

## Context

[RFC-0014 Sandbox](../03-RFC/RFC-0014%20Sandbox.md) §3 给出了两层可选方案：**Tier 1 进程级隔离**（资源限制 + 文件系统视图）和 **Tier 2 容器级隔离**（Docker/gVisor + 完整命名空间隔离）。[Design Principles](../00-Vision/Design%20Principles.md) 原则 6 要求"沙箱是缺省"，但未指定隔离强度的技术路线。

影响本决策的关键因素：

1. **启动延迟**：Docker 容器冷启动 ~500ms-2s，进程级隔离启动延迟为 0。CLI 交互场景中用户期望即时响应，每轮工具调用都等容器启动会严重伤害体验。
2. **安装门槛**：[Product Philosophy](../00-Vision/Product%20Philosophy.md) 定位"本地优先"，用户不应被强制安装 Docker 等外部依赖才能使用产品。
3. **威胁模型匹配**：v1.0 防御的是 Agent 误操作（如不小心 rm 了非预期文件、运行了有 bug 的脚本），不是主动高级逃逸攻击。进程级隔离对此威胁模型足够。
4. **跨平台一致性**：进程级资源限制在 macOS（rlimit）、Linux（cgroups）、Windows（Job Objects）均有成熟 OS 级支持，而 Docker Desktop 在非 Linux 平台体验不够原生。
5. **RFC-0014 的设计方向**：RFC-0014 在设计上已经偏向进程级隔离为默认（文件系统视图、资源限制、网络策略均已设计），容器方案仅作为 P1+ 可选增强预留。

## Decision

**v1.0 采用 Tier 1 进程级隔离；Tier 2 容器级隔离作为 P1+ 架构预留，接口可替换。**

理由：

1. CLI 交互响应速度是用户感知质量的关键因素——等 2s 容器启动再跑 `npm test` 不可接受
2. 零外部依赖安装（不需要 Docker）降低首次使用门槛，符合"本地优先"哲学
3. 进程级隔离的文件系统视图（chroot/bubblewrap/NTFS 权限）+ 资源限制（rlimit/cgroups/Job Objects）已覆盖 Agent 误操作的主要风险面
4. [RFC-0014 §3](../03-RFC/RFC-0014%20Sandbox.md) 的 SandboxInstance 接口设计已保证两套实现可互换——如果用户反馈或安全审计发现进程级隔离不足，切换到容器方案不需要重写上层代码

**备选策略（未采用的理由）**：
- **v1.0 直接上 Docker**：启动延迟和安装门槛对于 CLI-first 产品体验伤害过大，且 v1.0 威胁模型下进程级隔离已足够，容器级隔离的边际安全收益不足以补偿体验成本

## Consequences

### 正面影响

- 零外部依赖安装（除 Node.js 外），用户 `npm install -g` 或下载二进制即可使用
- 工具调用响应速度不受沙箱启动延迟影响
- 跨平台一致性更容易保证（均使用 OS 级进程管理 API，而非依赖 Docker Desktop 的易变性）

### 负面权衡

- 进程级隔离的文件系统边界弱于 Docker namespace——需要通过 OS 权限控制（macOS sandbox-exec / Linux bubblewrap / Windows NTFS）弥补，每种平台的实现方案不同，需要维护三套隔离实现
- 不能防御主动逃逸攻击——如果 Agent 执行的代码（如安装的恶意 npm 包）试图主动突破沙箱，进程级隔离的防线比容器弱。这是一个明确接受的风险
- 网络隔离能力弱于容器方案——进程级只能通过限制工具调用参数做策略控制（如禁止 `curl` 外部 URL），而容器可以用 iptables/nftables 做网络层强制阻断

### 对已有 RFC 的影响

- [RFC-0014 Sandbox](../03-RFC/RFC-0014%20Sandbox.md) 已在撰写时采用本决策的方向（Tier 1 进程级隔离为默认），无需修订
- [Deployment.md](../02-Architecture/Deployment.md) 的跨平台差异章节需要反映三种 OS 的不同隔离实现
- 如果后续 Benchmark Task Set 或真实用户反馈表明进程级隔离存在安全盲区，可通过此 ADR 的 Superseded 版本升级到 Tier 2

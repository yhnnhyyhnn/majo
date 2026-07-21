# 06-Enterprise — 目录说明

> **状态：🚧 待展开** — 本目录当前仅有此说明文件，尚未产出具体内容。**本目录对应的能力已在 [PRD.md 第3节](../01-Product/PRD.md#3-产品范围本阶段-p0p1p2) 中明确列为 P2，排除在 v1.0 交付范围之外。**

## 本目录的定位

06-Enterprise 覆盖企业级能力——RBAC 权限模型、审计日志、私有化部署、合规认证（如 SOC2）。呼应 [Mission.md](../00-Vision/Mission.md) 的策略："先把核心能力对标做扎实，再谈差异化和企业化"，本目录当前仅做架构影响面的预判讨论（见 [RFC-0019 Enterprise](../03-RFC/RFC-0019%20Enterprise.md)），不投入详细设计精力。

## 计划包含的文件

- `RBAC.md` — 基于角色的权限模型设计
- `Audit Log.md` — 企业级审计日志要求（不可篡改、合规留存周期）
- `Private Deployment.md` — 私有化部署方案（完全离线、客户自有基础设施内运行）
- `Compliance.md` — 合规认证路径（SOC2、ISO27001 等）
- `Team Collaboration.md` — 团队协作能力（多人共享配置、团队级 Memory/Knowledge）

## 与已完成章节的关联

- 直接承接 [RFC-0019 Enterprise](../03-RFC/RFC-0019%20Enterprise.md) 的架构预留讨论
- RBAC 设计需要对比 v1.0 的 [RFC-0015 Permission](../03-RFC/RFC-0015%20Permission.md)（面向个人自主性配置），理清两套权限模型的本质差异
- 审计日志需要对比 v1.0 的 [RFC-0016 Telemetry](../03-RFC/RFC-0016%20Telemetry.md)（面向个人调试），确认企业级要求带来的额外设计约束
- 私有化部署直接影响 [RFC-0012 Model Router](../03-RFC/RFC-0012%20Model%20Router.md) 对本地模型的支持完整度要求

## 启动这个目录写作的前置条件

- 需要真实的企业客户需求信号（付费意向、具体合规要求），而非团队自我假设的需求，才应该启动详细设计
- 需要 v1.0 核心能力（P0/P1）先交付验证，团队有余力时再评估企业化的投入优先级，避免在核心竞争力尚未建立时分散资源

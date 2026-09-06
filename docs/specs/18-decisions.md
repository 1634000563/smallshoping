# ADR / 设计决策记录

## ADR-001 AI-first

决定：AI 是主要交互入口，传统 UI 是兜底。
原因：降低小店老板学习成本。

## ADR-002 Offline-first

决定：核心业务不依赖网络。
原因：门店网络不稳定，收银不能因网络停摆。

## ADR-003 AI 不直写 DB

决定：AI 仅调用 Tool/UseCase。
原因：确保账务、库存、会员资金的确定性与可审计性。

## ADR-004 单账本事实源

决定：库存/会员/客户余额必须有 Ledger。
原因：修复、审计、重建和数据迁移。

## ADR-005 单 App module 起步

决定：V1 先单 Gradle app module + 强 package boundary。
原因：降低早期构建与 Codex 协作复杂度。

## ADR-006 不把“所有行业”编码成行业分支

决定：通用 Product + Attribute + Unit 模型。
原因：菜店、五金、便利店共享核心。

## ADR-007 出货与销售分离

决定：Fulfillment 独立实体。
原因：销售完成不一定等于已经交付。

## ADR-008 官方支付延后

决定：V1 使用店铺已有微信/支付宝收款 + 手工确认。
原因：降低商户接入、联网、结算复杂度；保留 PaymentProvider 抽象。

## ADR-009 Cloud AI gateway

决定：生产客户端不保存共享 AI provider secret；使用服务端 Gateway。
原因：防止 API Key 泄露，并便于免费额度、限流、模型路由和成本控制。

## ADR-010 Store-scoped records

决定：核心业务记录预留 store_id。
原因：未来多店/云同步/恢复时避免重构所有数据模型。

## ADR-011 Import/Export as V1 P1

决定：至少支持 CSV 导入导出。
原因：降低从旧系统迁移的阻力。

## ADR-012 AI eval is release gate

决定：Prompt、Model、Tool schema 任一变化都跑黄金集。
危险操作误执行率必须为0。

## ADR-013 V1.1-codex-ready 基线冻结（Task 001）

决定：五部宪法（产品/数据/AI/离线/安全）与 `ARCHITECTURE.md` 冻结为 V1 开发基线（版本 V1.1-codex-ready，2026-09-06，Task 001）。
原因：后续 60 个 Task 依赖稳定不变的顶层原则；冻结前已对照 AGENTS.md、specs 索引、25-route-redesign、99-gap-review 完成冲突检查，未发现原则级冲突。
影响：`docs/constitution/*` 与 `ARCHITECTURE.md` 为受保护设计资产，此后任何修改必须走 `docs/guards/CHANGE_CONTROL.md` 流程并新增 ADR，不得在 Task 内静默修改。

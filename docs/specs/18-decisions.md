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

## ADR-014 应用标识与最低 Android 版本（Task 002）

决定：applicationId/namespace 定为 `com.smallshoping.app`；minSdk 24（Android 7.0）、targetSdk 35、compileSdk 35。
原因：规格未定义包名与最低版本，由工程落地时确定。包名跟随仓库名 smallshoping；minSdk 24 在旧设备覆盖（小店老板可能用旧手机）与依赖/维护成本间取平衡。
影响：包名变更会影响数据库/存储路径与后续发布签名；minSdk 可在 Task 052 真机兼容验证时依据实测重审。

## ADR-015 退款（refund_sale）V1 不发布，诚实失败（Task 057）

决定：V1 发布版本不包含退款流程。`refund_sale` 保留在 Tool 目录契约中（spec 07 §7 退款链）但无 Domain UseCase 与 Handler 实现；不在 `allowedTools` 白名单中，AI 选择时编排层回复「这个操作暂时没有开放」，本地解析器无退款句式（返回「没听懂」）——两条路径均诚实失败，绝不伪造退款成功。
原因：spec 04 §3 给出了退款原则（反向业务事实、不删除原销售），但退款交互（退哪一单、退现金/微信/原路、部分退款、退款后库存口径）与账务细节无规格定义；Task 057 非 AI 兜底完整性验收发现此缺口时，其余 V1 必须清单（销售/采购/称重/损耗/储值/欠款/支付确认/报表/离线）均已具备无网可用路径，退款为唯一无实现的必须项。按「规格未定义的产品决策不得自行发明」原则，不在验收任务内发明退款流程。
影响：V1 发布说明须标注「退款 V1.1 提供」；V1.1 实现前需先补充退款规格（交互/账务/幂等/审计）并新增 ADR，实现为反向业务事实（refund record + 库存返还 + 支付冲销），不得删除原销售。老板实际退换货场景 V1 暂以「损耗 + 调整」手工记事实处理，不得伪装成退款流水。

## ADR-016 V1 RC 签名策略（Task 059）

决定：V1 Release Candidate 使用 AGP 自动生成的 debug keystore 签名（~/.android/debug.keystore，证书 CN=Android Debug）；版本基线 versionCode=1 / versionName=0.1.0；APK 通过 adb 安装（内部安装），不上任何商店。
原因：V1 目标是小店老板内部装机，无商店审核需求；debug 签名可复现、无密钥管理负担。正式签名（独立 keystore + 密码离线保存）在出现商店发布/正式分发需求时再建立。
影响：debug keystore 是设备安装凭据，换机器构建的 APK 无法覆盖安装（签名不同）——跨机器协作时需共享或重建 keystore；releaseCandidate 任务每次发布前自动验证签名与密钥安全。

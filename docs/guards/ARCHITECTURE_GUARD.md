# Architecture Guard

## 目标
在每个 Task 后检查“代码虽然能跑，但已经偏离 AI 原生小店操作系统”的情况。

## 绝对禁止
- AI/LLM/Prompt 层直接依赖 DAO/Room Database。
- UI 层直接修改余额、库存或支付状态。
- 任何核心金额计算使用 Float/Double。
- 通过覆盖余额字段代替 Ledger。
- 通过覆盖库存字段代替库存流水。
- 网络调用成为销售结账的硬前置。
- 使用 AI 输出直接当作业务事实而不经过 Schema/Domain 校验。
- 为了方便而增加传统 ERP 菜单链路并让 AI 失去意义。

## 必须存在
- Domain UseCase/业务引擎。
- Ledger/审计流水。
- Tool Executor 与风险控制。
- 幂等与事务边界。
- 离线降级路径。
- AI 黄金测试。

## 守门结果
PASS：无违规且测试通过。
FAIL：必须修复后才能进入下一个 Gate；不得用“以后再改”跳过。

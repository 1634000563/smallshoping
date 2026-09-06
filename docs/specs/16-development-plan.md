# 开发计划与 Codex 协作

## Phase 0：规格冻结

完成：
- 架构
- DB schema
- Ledger rules
- Tool schema
- AI risk
- tests fixtures

出口条件：无关键未决架构问题。

## Phase 1：本地业务底座

Product / Unit / Inventory / Purchase / Sale / Member / Customer / Payment / Audit。

无 AI 也能营业。

## Phase 2：设备输入

Barcode / Manual Weight / basic voice STT integration。

## Phase 3：极简 UI

Home / Sale / Quick Product / Member / Customer / Report。

## Phase 4：AI Tool layer

Tool contracts / Command / Entity resolution / Context / Risk。

## Phase 5：AI Agent

Voice/Text → Tool → Domain → Result。

## Phase 6：Memory

Store memory / aliases / context / customer memory。

## Phase 7：Reliability

Offline / crash recovery / migration / backup / eval regression。

## Phase 8：Pilot

真实菜店 + 五金店各至少一个验证点，记录语句和错误。

## Codex 单任务模板

1. 说明目标
2. 指定必读文档
3. 指定文件范围
4. 先写/更新测试
5. 实现
6. 运行测试
7. 输出变更摘要
8. 输出风险与未覆盖项
9. 不跨阶段偷偷扩展范围

## Git 规则

每个可验证阶段使用独立提交/PR；避免一个超大提交同时包含 DB、UI、AI 和支付。

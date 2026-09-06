# Data 模块局部规则
- Ledger 是库存/会员余额/欠款的审计事实。
- Repository 负责持久化，不定义业务决策。
- 任何 schema 变化必须有 migration test。
- 不得静默覆盖历史业务事实。

# 运维、可观测性与发布

## 1. 本地诊断

允许用户在设置中生成“诊断包”，包括：
- App version
- DB schema version
- device category
- 最近错误码
- AI provider 状态

不得默认包含完整销售/会员隐私数据。

## 2. AI 成本统计

如果使用云 AI Gateway，应按 store/device 统计：
- 请求次数
- 输入 tokens
- 输出 tokens
- 成本估算
- 失败率
- 平均延迟

这些是运营数据，不影响小店账本。

## 3. Feature Flags

至少预留：
- AI_ENABLED
- CLOUD_BACKUP_ENABLED
- BLUETOOTH_SCALE_ENABLED
- OFFICIAL_PAYMENT_ENABLED
- CUSTOMER_CREDIT_ENABLED

V1 默认按产品策略关闭未来能力。

## 4. 发布策略

Android：
- debug
- internal
- beta
- production

数据库 migration 每个版本必须有升级路径，不允许依赖“卸载重装”。

## 5. 回滚

应用版本回滚不能把 DB schema 降级到不兼容版本。
需要先定义兼容窗口；破坏性迁移必须通过导出/升级路径处理。

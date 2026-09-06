# Domain 模块局部规则
- Domain 不依赖 Android UI、LLM、网络客户端。
- 金额用 Money，禁止 Float/Double。
- 库存/余额/欠款写操作必须通过 UseCase。
- 写操作事务化并可幂等。
- 每个 UseCase 必须有测试。

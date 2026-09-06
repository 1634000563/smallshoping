# 非功能需求

## Reliability
- 核心销售流程本地可用
- 数据事务原子性
- Ledger 可重建
- 崩溃恢复

## Performance
- 常用本地查询目标 < 200ms
- 本地结账目标 < 500ms
- AI 不能阻塞 DB transaction

## Usability
- 大按钮
- 高对比度
- 简短反馈
- 语音/扫码优先
- 键盘兜底

## Maintainability
- 领域边界清晰
- Tool contract 稳定
- Prompt versioned
- Schema migration 可测试

## Security
- 最小权限
- 客户端不保存共享云密钥
- AI 最小数据原则
- 敏感日志脱敏

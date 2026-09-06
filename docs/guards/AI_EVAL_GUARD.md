# AI Evaluation Guard

任何 AI 行为修改至少检查：
- Intent 正确率
- Entity Resolution 正确率
- Tool 选择正确率
- 参数正确率
- 消歧正确率
- 高风险误执行率
- Tool 失败后的诚实反馈
- 离线降级是否生效

发布底线：
- 高风险误执行率必须为 0。
- 金额/数量/单位 Schema 错误不得直接写入业务。
- Tool 返回失败时 AI 不得回复“已完成”。
- 关键黄金案例不得因 Prompt/模型更新而回退。

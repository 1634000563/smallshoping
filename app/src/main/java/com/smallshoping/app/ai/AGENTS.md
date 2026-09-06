# AI 模块局部规则
- AI 只能产生 Intent / Command / Tool Call。
- 禁止直接依赖 DAO/Room。
- 所有 Tool 参数必须 Schema Validate。
- 不确定实体必须消歧。
- 高风险写操作必须确认。
- 不得声称 Tool 失败的操作已完成。
- Prompt/Tool/Schema 改动必须跑 AI Eval。

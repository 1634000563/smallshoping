# AI 宪法

## AI 的职责
AI 负责：自然语言理解、实体解析、上下文处理、Tool 选择、参数生成、结果解释。

AI 不负责：直接写数据库、直接计算最终金额、直接决定库存余额、伪造支付成功、绕过风险控制。

## AI 执行链
用户输入 → ASR/文本 → Intent Schema → Entity Resolution → Risk Engine → Tool → Domain UseCase → Ledger/DB → 结果 → AI/界面反馈。

## 必须遵守
1. AI 输出必须 Schema Validate。
2. Tool 必须有稳定名称、版本、参数 Schema、风险等级、幂等策略、离线策略。
3. AI 不得绕过 Tool Executor 调用 Domain。
4. 高风险操作必须确认；无法确认实体不得猜测。
5. Tool 失败时不得声称成功。
6. AI 只能获得执行当前任务所需的最小上下文。
7. Prompt、Tool、Schema 都必须版本化。
8. 修改 AI 行为必须更新黄金测试集或说明为何无需更新。
9. AI 服务不可用时，核心业务继续走非 AI 入口或降级流程。
10. 任何学习到的“习惯”不能静默改变账务事实；它最多改变默认建议，并应可追溯、可撤销。

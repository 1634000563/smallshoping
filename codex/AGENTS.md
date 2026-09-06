# Codex Task Rules

- 按 `plans/V1_CODEX_TASK_MANIFEST.md` 顺序执行；不得跳过依赖。
- 先读根 `AGENTS.md`，再读当前目录规则和当前 Task。
- V1 使用“难点优先 + AI 原生 Vertical Slice”，不是传统进销存模块堆叠。
- 当前 Task 必须能解释“老板会怎么说/怎么做”和“最终写入什么事实”。
- AI 不直接访问 DAO/Room/SQLite；必须 Intent → Tool → Risk → Domain → Ledger。
- 新行业不得复制一套业务模块；扩展通用实体、属性、单位和规则。
- 每个 Task 先补测试，再实现最小变更。
- 未经批准不得修改产品宪法、数据宪法、AI 宪法、离线宪法或架构核心决策。
- 完成前必须运行规定测试并检查 `git diff`/`git status`。

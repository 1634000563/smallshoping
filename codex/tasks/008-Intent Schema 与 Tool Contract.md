# Task 008 — Intent Schema 与 Tool Contract

## 任务定位
- 阶段：A
- 难度：★★★★★
- 依赖：007
- 里程碑：M1

## 目标
完成本任务的最小完整能力，并让后续 Task 可以直接复用。

## 为什么现在做
本路线采用“最难优先”：优先验证会影响整体架构的假设，避免先做大量传统 POS 页面后再返工。

## 必读文档
- `AGENTS.md`
- `plans/V1_IMPLEMENTATION_PLAN.md`
- `plans/V1_CODEX_TASK_MANIFEST.md`
- `ARCHITECTURE.md`
- 与本任务直接相关的 `docs/specs/*`、`docs/guards/*`、`docs/schemas/*`。

## 实现范围
- 只实现：Intent Schema 与 Tool Contract
- 复用已有契约，不复制业务逻辑。
- 如果发现规格冲突：停止、记录问题/ADR、等待架构决定；不得静默修改顶层原则。

## 明确不做
- 不提前实现后续 Task。
- 不建立“传统菜单优先”的新主流程。
- 不为了开发方便绕过 Domain、Ledger、Tool 或 Risk 边界。
- 不把临时业务规则写进 Prompt 代替程序规则。

## AI / Domain / Data 边界
- **AI**：理解、分类、实体解析、选择 Tool、上下文管理；不拥有事实。
- **Tool**：机器可验证的能力契约；负责参数约束、权限与幂等入口。
- **Domain**：唯一业务执行层，负责规则、状态、事务。
- **Data/Ledger**：持久化事实、审计和可重建状态。
- **UI/Device**：输入与反馈，不得绕过 Domain 修改事实。



## 测试要求
1. 正常路径。
2. 边界输入。
3. 异常/失败路径。
4. 幂等/重复执行（适用时）。
5. 断网/AI 不可用降级（适用时）。
6. 回归现有测试。

## 验收标准
- 本任务的目标行为可以重复验证。
- 自动化测试通过。
- 构建通过（如本任务涉及代码）。
- Architecture Guard / Route Guard / Task Guard 通过。
- Git diff 只包含本任务及其必要测试。
- 没有偷偷新增 V1 范围外功能。

## Codex 完成报告
必须输出：
- 修改文件
- 关键设计决定
- 测试命令与结果
- 未解决问题
- 是否需要新增/修改 ADR
- 是否满足本 Task 的 Gate/里程碑。

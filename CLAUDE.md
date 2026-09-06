# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目是什么

V1「AI 原生小店操作系统」（Android，生鲜/五金/便利店通用底座）：老板用语音/扫码/称重说一句话（如「卖两斤土豆」），系统经 AI 理解后通过受控 Tool 调用确定性 Domain，写入 SQLite/Ledger。核心理念：**老板不学软件，软件学老板**。

当前仓库是**规格与任务驱动的知识库，尚无代码**——Android 工程由 `codex/tasks/002` 创建。所有开发按 `plans/V1_CODEX_TASK_MANIFEST.md` 的 60 个任务顺序执行（A–E 阶段、M0–M10 里程碑、Gate A–E 门禁）。

## 标准工作循环（每个 Task 一次）

1. 读 `AGENTS.md`（硬约束）→ `ARCHITECTURE.md` → `docs/constitution/` → `docs/specs/00-index.md` → 当前 Task 指定的 spec/schema/eval。
2. 只执行 `codex/tasks/` 中的当前任务：不跳依赖、不提前做后续任务、不扩大范围。
3. 先补测试 → 最小实现 → 运行测试 → 过 Guard（Architecture/Route/Task，AI 任务另加 AI Eval Guard，见 `docs/guards/`）→ 检查 git diff/status → 按 `codex/CODEX_WORKFLOW.md` 的模板输出完成报告（含修改文件、测试命令与结果、Gate PASS/FAIL、剩余风险、下一任务）。
4. 规则冲突优先级：Constitution > Architecture > Specs > Task > 实现便利性。规格未定义的产品决策不得自行发明，应新增 ADR 到 `docs/specs/18-decisions.md`。

## 不可违反的架构规则

- 唯一链路：`用户输入 → Intent → Entity Resolution → Tool → Risk → Domain UseCase → Ledger/DB`。**AI 严禁直接访问 DAO/Room/SQLite**；UI 同理（UI → ViewModel → UseCase → Repository → Room）。
- Domain 是唯一业务执行入口，不依赖 UI/LLM/Android Context。AI 路径与人工路径必须写入完全相同的 Domain。
- 金额禁止 Float/Double（用 Money 类型）；库存/会员余额/客户欠款必须可由 Ledger 重建，禁止直接覆盖余额字段。
- 写操作事务化、可幂等、可审计；AI/网络失败不得阻塞销售，核心业务离线可运行。
- 云端 AI 密钥不得进 APK；Prompt/Tool/Schema 必须版本化；Tool 需 Schema Validate 且有风险级别（低风险自动、中风险确认、高风险强制确认）。
- 防跑偏（Route Guard）：禁止演变成「传统 POS + AI 聊天框」，禁止为方便新增大量传统菜单页面；新行业复用通用商品/单位/交易底座，禁止复制业务模块。

## 知识库地图

- `AGENTS.md` — 硬规则与导航（工作规则的权威来源）；`ARCHITECTURE.md` — 系统地图与推荐包结构（单 App module + 包边界，V1 不拆多 module）
- `docs/constitution/` — 产品/数据/AI/离线/安全五大宪法（最高优先级）
- `docs/specs/` — 专题规格，索引与阅读策略在 `00-index.md`
- `docs/guards/` — 自动检查清单（Architecture/Route/Task/AI Eval/Change Control）
- `docs/schemas/tool-catalog.json`、`docs/prompts/system.md`、`docs/evals/golden_cases.jsonl` — Tool 契约、Prompt 基线、AI 黄金集
- `templates/AGENTS/` — 各层（domain/data/ai/ui/device）局部规则模板，创建对应目录时放入
- `codex/tasks/` — 单任务文件，固定格式：目标/为什么现在做/前置依赖/必读文档/实现范围/不做什么/AI·Domain·Data 边界/契约/测试要求/验收标准
- `git.txt` — 仓库地址 https://github.com/1634000563/smallshoping.git（Task 001 已完成工作区初始化并推送 main；仓库原「小小商城静态站点」备份于 `archive/static-site` 分支）

## 构建与测试

当前无代码，暂无构建命令。Android 工程（Gradle 单 module）由 Task 002 创建，此后按各 Task 的「测试要求」用 Gradle 运行测试。V1 成功标准 = 六句黄金语句可可靠完成并追溯到确定性账务事实（`plans/V1_IMPLEMENTATION_PLAN.md` §8）。

全库文档与完成报告均为中文，产出文档/注释保持中文。

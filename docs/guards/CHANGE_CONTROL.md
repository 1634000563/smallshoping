# 变更控制

以下文件属于“受保护设计资产”：
- `docs/constitution/*`
- `ARCHITECTURE.md`
- `docs/specs/03-data-model.md`
- `docs/specs/04-ledgers.md`
- `docs/specs/05-ai-orchestrator.md`
- `docs/specs/07-tool-contracts.md`
- `docs/specs/08-risk-state-machines.md`

修改这些文件必须：
1. 写明为什么需要变化；
2. 更新 `docs/specs/18-decisions.md`；
3. 检查受影响 Task；
4. 更新测试/评测；
5. 不得借修改规格绕过已有 Gate。

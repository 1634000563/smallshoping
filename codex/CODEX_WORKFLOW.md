# Codex 工作流程

## 每个 Task 的循环

```text
读取 AGENTS.md
→ 读取相关 constitution
→ 读取当前 Task
→ 读取相关 specs / schemas / evals
→ 检查依赖 Task / Gate
→ 先写或补测试
→ 最小实现
→ 运行测试
→ Architecture Guard
→ AI Eval Guard（AI Task）
→ git diff 审查
→ 生成完成报告
```

## 完成报告模板

- Task：
- 已完成：
- 修改文件：
- 测试命令：
- 测试结果：
- Gate：PASS / FAIL
- 未完成：
- 风险：
- 下一任务：

## 发现需求冲突时
优先级从高到低：
1. 产品宪法
2. 数据宪法 / AI 宪法 / 离线宪法 / 安全宪法
3. ARCHITECTURE.md
4. 专题 specs
5. 当前 Task
6. 实现便利性

实现便利性永远不能推翻上层约束。

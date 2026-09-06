# Task Gate Guard

每个 Codex Task 必须完成：

1. 读取根 AGENTS.md。
2. 读取相关目录 AGENTS.md。
3. 读取 Task 文件列出的 specs。
4. 确认前置 Task 已完成。
5. 明确本 Task 的“不做什么”。
6. 先补测试，再实现。
7. 运行相关测试、静态检查和构建。
8. 检查 schema migration、事务、幂等和离线行为（适用时）。
9. 检查 git diff，不允许无关重构。
10. 输出：修改文件、测试结果、剩余风险、是否建议进入下一 Task。

没有通过验收标准的 Task 不得标记 DONE。

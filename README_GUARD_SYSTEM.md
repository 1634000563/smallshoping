# 防跑偏体系

本项目采用四层约束：

1. **Constitution**：产品、数据、AI、离线、安全的最高级规则。
2. **AGENTS.md**：Codex 的导航与硬约束。
3. **Task / Schema / Eval**：当前任务的可执行契约。
4. **Guard + Tests**：用自动检查验证，而不是依赖“记住规则”。

## 规则来源优先级
Constitution > Architecture > Specs > Task > 实现便利性。

## 最重要的一句话
**任何代码选择，都不能把 AI 原生小店系统重新变成传统 POS + AI。**

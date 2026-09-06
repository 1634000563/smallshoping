# Route Guard — 防止传统进销存开发路线

## 目标

防止开发过程重新退化成：商品模块 → 采购模块 → 销售模块 → UI → 最后 AI。

## 违规模式

出现以下任一情况，应暂停当前 Task 并重新检查路线：

1. 为了方便先做大量传统菜单页面，再考虑 AI 入口。
2. AI 功能只能“填表”，无法调用 Domain Tool。
3. 一个业务动作需要创建重复的 AI 专用业务逻辑。
4. AI 直接读写数据库。
5. 新行业通过复制一套 Product/Sale/Inventory 代码实现。
6. 新增 UI 是主要交付物，而没有对应老板真实任务或 Vertical Slice。

## 正确模式

```text
真实老板表达
→ Intent
→ Entity/Context
→ Tool
→ Risk
→ Domain
→ Ledger
→ DB
→ UI/语音反馈
```

传统页面只能作为：
- 精细查看
- 复杂修正
- 历史查询
- 数据导入导出
- AI 无法使用时的人工兜底

## Gate

任何新功能必须回答：

- 老板会怎么说？
- 是否可以自然语言完成？
- AI 调用了哪个 Tool？
- Tool 调用了哪个 Domain UseCase？
- 最终事实写到了哪个 Ledger？
- 无 AI 时如何完成同一动作？

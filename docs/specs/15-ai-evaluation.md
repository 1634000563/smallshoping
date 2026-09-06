# AI 评测与回归

## 1. 为什么单元测试不够

AI 的核心风险是：同一句自然语言在不同上下文/模型版本下解析变化。因此必须维护“黄金语句集”。

## 2. 每条 Case 至少包含

```json
{
  "utterance": "两斤半土豆",
  "context": {...},
  "expected_intent": "ADD_SALE_ITEM",
  "expected_entities": {...},
  "expected_tool": "add_sale_item",
  "expected_risk": "LOW",
  "expected_clarification": false
}
```

## 3. 覆盖行业

- 菜店
- 水果店
- 便利店
- 五金店
- 水电材料

## 4. 核心语句

- “卖两斤土豆。”
- “土豆两斤六。”
- “刚才那个不要了。”
- “还是昨天的价格。”
- “给张姐充200。”
- “张姐还有多少钱？”
- “进100斤白菜，1块9。”
- “给老张还是上次那些。”
- “来十个M8的304螺丝。”
- “老张先记账。”

## 5. 关键评测指标

- Intent accuracy
- Entity extraction accuracy
- Entity resolution accuracy
- Tool selection accuracy
- Argument schema validity
- Wrong-action rate
- Unnecessary-clarification rate
- Dangerous auto-execution rate（目标为 0）

## 6. 回归原则

任何模型、Prompt、Tool schema 变更都必须跑黄金集。
如果危险操作误执行率增加，禁止上线。

# 风险策略与状态机

## 1. 风险等级

LOW：查询、加购物车、普通销售。

MEDIUM：价格修改、会员充值/扣款、普通退款、库存调整。

HIGH：大额退款、批量价格变更、历史事实删除、批量库存修正、删除会员/客户。

## 2. 自动执行规则

自动执行需要：
- 意图明确
- 实体唯一
- 参数完整
- 业务规则通过
- 金额合理
- 无高风险

否则澄清/确认。

## 3. Sale Order

```text
DRAFT
READY_TO_PAY
PAYMENT_PENDING
PAID
COMPLETED
CANCELLED
PARTIAL_REFUNDED
REFUNDED
```

## 4. Payment

```text
PENDING
CONFIRMED
FAILED
REVERSED
```

V1 手工微信/支付宝：老板确认到账后进入 CONFIRMED。

## 5. Fulfillment

```text
NOT_REQUIRED
READY
WAITING_PICKUP
OUT_FOR_DELIVERY
DELIVERED
CANCELLED
```

## 6. Purchase

```text
DRAFT
RECEIVING
COMPLETED
CANCELLED
```

## 7. Member ledger

只能通过明确业务事件产生：RECHARGE / PURCHASE / REFUND / ADJUSTMENT / BONUS。

## 8. 风险异常例

“土豆从4块改成40块”应触发异常变更提示。
“给张姐充200”找到两个张姐时必须消歧。
“删除昨天销售”应禁止或走受控管理员修正流程；V1 默认不提供物理删除。

## 9. 操作确认状态机

```text
PROPOSED
  ├─ AUTO_EXECUTE → EXECUTING → SUCCEEDED / FAILED
  └─ NEED_CONFIRM → WAITING_CONFIRM → CONFIRMED / REJECTED / EXPIRED
```

用户说“是/确定/执行”时，只能确认最近一个处于 WAITING_CONFIRM 的请求，并校验其 request_id，防止确认错对象。

## 10. AI 失败状态

AI 请求失败不能把业务状态设置为成功。

```text
AI_PENDING → AI_SUCCESS
          └→ AI_FAILED
```

AI_FAILED 与业务失败完全分离；本地业务可继续走非 AI UI。

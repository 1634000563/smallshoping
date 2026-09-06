# AI Tool Contracts

## 1. Tool 分类

### Read-only
- find_product
- find_product_by_barcode
- get_stock
- get_today_sales
- get_month_sales
- get_top_products
- get_low_stock
- find_member
- get_member_balance
- find_customer
- get_customer_debt
- get_context

### Mutating
- create_product
- purchase_in
- create_sale
- add_sale_item
- checkout_sale
- refund_sale
- change_price
- recharge_member
- charge_member
- record_customer_credit
- settle_customer_debt
- record_loss
- adjust_stock

## 2. Tool Contract 统一字段

每个 tool 必须定义：
- name
- description
- input_schema
- risk_level
- required_confirmation
- idempotency_required
- allowed_offline
- audit_action
- success_result_schema
- error_codes

## 3. 例：create_sale

```json
{
  "name": "create_sale",
  "risk_level": "LOW",
  "required_confirmation": false,
  "allowed_offline": true,
  "input_schema": {
    "type": "object",
    "required": ["items"],
    "properties": {
      "customer_id": {"type": ["string", "null"]},
      "member_id": {"type": ["string", "null"]},
      "items": {
        "type": "array",
        "minItems": 1,
        "items": {"$ref": "#/definitions/SaleLine"}
      }
    }
  }
}
```

## 4. 业务工具必须返回事实

不要返回：
```text
“应该成功了”
```

要返回：
```text
status=SUCCESS
sale_id=...
total_minor=...
stock_changes=[...]
```

## 5. 不允许“万能 execute_sql”

禁止向 AI 暴露 SQL 工具、任意 HTTP 工具或任意文件写入工具。

## 6. Schema 文件

完整机器可读契约放在：
`docs/schemas/tool-catalog.json`

## 7. V1 Tool 完整性

除了基础工具外，销售链必须包含：
- add_sale_item
- remove_sale_item
- get_current_sale
- cancel_sale

退款链包含：
- refund_sale

出货链包含：
- create_fulfillment
- update_fulfillment_status

## 8. Tool 版本化

每个 Tool 使用 `tool_name + major_version`，例如 `add_sale_item.v1`。
破坏性参数变更不得悄悄替换旧契约。

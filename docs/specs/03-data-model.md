# 数据模型与数据库表

## 1. 原则

- UUID/稳定 ID，不用业务名称作为主键
- created_at / updated_at 必须统一时区策略
- 删除优先软删除；历史事实禁止物理删除
- 金额用 Money；数量用 Quantity
- 所有外键有明确语义
- 所有外部请求可带 idempotency_key

## 2. 核心表

### store
`id, name, currency, timezone, default_weight_unit, default_low_stock_threshold, created_at, updated_at`

### product
`id, name, normalized_name, product_type, category_id, sale_unit_id, purchase_unit_id, current_sale_price, current_cost_price, stock_warning_threshold, active, created_at, updated_at`

### product_alias
`id, product_id, alias, normalized_alias, source, confidence, created_at`

### product_barcode
`id, product_id, barcode, barcode_type, is_primary`

### product_attribute
`id, product_id, name, normalized_name, value, normalized_value`

### unit
`id, code, name, dimension, scale`

dimension：COUNT / MASS / LENGTH / VOLUME / OTHER

### unit_conversion
`id, product_id, from_unit_id, to_unit_id, ratio_numerator, ratio_denominator`

避免使用浮点比例。

### purchase_order
`id, supplier_id, status, total_amount_minor, source, idempotency_key, created_at, completed_at`

### purchase_item
`id, purchase_order_id, product_id, quantity_scaled, unit_id, unit_cost_minor, total_cost_minor`

### sale_order
`id, customer_id, member_id, status, subtotal_minor, discount_minor, total_minor, paid_minor, payment_status, source, idempotency_key, created_at, completed_at`

### sale_item
`id, sale_order_id, product_id, quantity_scaled, unit_id, unit_price_minor, cost_price_minor_snapshot, subtotal_minor`

### payment
`id, sale_order_id, method, status, amount_minor, external_reference, idempotency_key, created_at, confirmed_at`

### member
`id, name, phone, alias, status, created_at, updated_at`

### member_ledger
`id, member_id, type, amount_minor, reference_type, reference_id, balance_after_minor, idempotency_key, created_at, note`

### customer
`id, name, phone, alias, status, credit_enabled, created_at, updated_at`

### customer_ledger
`id, customer_id, type, amount_minor, reference_type, reference_id, balance_after_minor, idempotency_key, created_at, note`

### stock_ledger
`id, product_id, change_quantity_scaled, base_unit_id, movement_type, reference_type, reference_id, unit_cost_minor, idempotency_key, created_at, note`

### inventory_snapshot
`product_id, quantity_scaled, base_unit_id, calculated_at`

### price_history
`id, product_id, price_type, old_price_minor, new_price_minor, unit_id, source, created_at`

### loss_record
`id, product_id, quantity_scaled, unit_id, reason, cost_amount_minor, stock_ledger_id, created_at`

### fulfillment
`id, sale_order_id, mode, status, recipient_name, phone, address_note, scheduled_at, completed_at, created_at`

### day_close
`id, business_date, opened_at, closed_at, cash_expected_minor, cash_actual_minor, variance_minor, status, note`

### context_session
`id, device_session_id, active_sale_order_id, last_product_id, last_customer_id, last_member_id, last_intent, context_json, expires_at, updated_at`

### memory_fact
`id, scope_type, scope_id, fact_type, key, value_json, confidence, source, last_confirmed_at, created_at, updated_at, active`

### audit_log
`id, actor_type, actor_id, action, source, command_id, before_json, after_json, result, created_at`

## 3. 数据类型规则

金额：Long minor units。
数量：Long scaled units，例如克用整数；若业务需要更高精度使用 Decimal 库封装，不允许 Double 传播进 Domain。
时间：Instant 存储 + Store timezone 展示。

## 4. 多租户/多店提前预留

即使 V1 单店单设备，所有业务数据表建议保留 `store_id`；设备相关操作保留 `installation_id`/`device_id`。

原因：未来云同步、多店和数据恢复不能再大规模重构主键和数据隔离。

## 5. Contact 统一身份建议

会员与赊账客户可能是同一个人。为减少 AI 消歧歧义，设计上预留 `contact`/角色扩展概念：

- Contact：人/组织的基础身份
- MemberProfile：会员余额角色
- CustomerAccount：客户欠款角色

V1 可以物理上保持 member/customer 独立表，但接口层应允许未来统一映射。

## 6. 业务日期

所有日报/月报必须根据 `Store.timezone` 计算 business_date，不能直接用设备 UTC 日期。
可预留“营业日切换时间”配置，例如凌晨2点仍属于前一天经营日。

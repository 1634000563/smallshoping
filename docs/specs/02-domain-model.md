# Domain Model

## 1. 核心实体

- Store
- Product
- ProductAlias
- Barcode
- ProductAttribute
- Unit
- UnitConversion
- PriceBookEntry / PriceHistory
- Supplier
- PurchaseOrder / PurchaseItem
- SaleOrder / SaleItem
- Fulfillment / Shipment
- Payment
- Member
- MemberLedger
- Customer
- CustomerLedger
- StockLedger
- InventorySnapshot
- LossRecord
- DayClose / Shift
- ContextSession
- MemoryFact
- AuditLog
- OutboxEvent（未来同步预留）

## 2. 产品模型

Product 不能假定“一个商品=一个条码=一个单位”。支持：
- 多条码
- 多属性
- 采购单位和销售单位不同
- 单位换算
- 多价格历史
- 称重/件数模式

## 3. 菜店

`土豆`：销售单位=斤；采购单位=斤；损耗可用。

## 4. 五金

`304 M8x30 外六角螺栓`：属性化表达；支持个/盒/包和包装换算。

## 5. 销售与出货解耦

SaleOrder = 钱与商品交易事实。
Fulfillment = 交付状态事实。

V1 可支持：
- 即取即走
- 待自提
- 待配送
- 已出货/已交付

不要让“出货”直接等于“销售”。销售可能已经完成但商品尚未交付。

## 6. Supplier

V1 保存基础供应商信息即可，不做完整供应链协同。

## 7. DayClose

用于一天结束时核对：
- 现金
- 微信手工确认
- 支付宝手工确认
- 会员消费
- 客户赊账
- 退款

DayClose 不是财务报表的替代，只是经营核对。

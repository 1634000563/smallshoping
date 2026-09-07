# 老板语言 → 软件操作映射图谱（Task 059 核心资产）

> 本图谱由 78 条黄金语句（docs/evals/golden_cases.jsonl）与全量单测守护。
> 规则：**每句话只能落到一条链路**：意图 → Tool → Risk → Domain UseCase → 账务事实。
> 未开放/不认识的必须诚实失败（不猜测、不伪造）。

## 1. 售卖场景（加购）

| 老板说（形态示例） | 意图 | Tool | Domain | 账务效果 | 风险 |
|---|---|---|---|---|---|
| 卖两斤土豆 / 卖了2斤 / 加两斤 / 称两斤 / 再来两斤 / 来两斤 | 加购 | add_sale_item | AddSaleItemUseCase | 草稿单加行（**不扣库存**，结账才扣） | LOW 自动 |
| 土豆两斤六 / 土豆要两斤 / 两斤半土豆 | 加购 | add_sale_item | 同上 | 同上（小计=目录价×数量，AI 不算钱） | LOW 自动 |
| 卖5个304螺栓（任意商品：虾/铁丝/螺母/桔子…） | 加购 | add_sale_item | 同上 | 同上 | LOW 自动 |
| 老张上次那些螺丝再来十盒 | 补单 | reorder_last_item | 客户记忆+加购 | 同上 | LOW 自动 |

## 2. 结账场景

| 老板说 | 意图 | Tool | Domain | 账务效果 | 风险 |
|---|---|---|---|---|---|
| 结账 / 结一下账 / 买单 | 现金结账 | checkout_sale | CheckoutSaleUseCase | 完成单 + 库存流水 SALE_OUT + 现金支付记录（CONFIRMED） | MEDIUM 确认 |
| 微信 / 微信结账 | 微信结账 | checkout_sale | 同上 | 支付记录 PENDING，老板确认到账后 CONFIRMED | MEDIUM 确认 |
| 支付宝 / 现金 / 会员结账 | 对应方式 | checkout_sale | 同上 | 同上 | MEDIUM 确认 |
| 张姐买单 | 会员余额结账 | checkout_sale | 同上 | 完成单 + SALE_OUT + member_ledger MEMBER_CONSUME | MEDIUM 确认 |

## 3. 入库场景

| 老板说 | 意图 | Tool | Domain | 账务效果 | 风险 |
|---|---|---|---|---|---|
| 进100斤土豆，成本2块8 / 进100斤土豆，2块8 / 成本两块八 | 采购入库 | purchase_in | PurchaseInUseCase | 采购单 + 库存流水 PURCHASE_IN + 加权平均成本更新 | LOW 自动 |
| 进货/采购/买100斤土豆 / 进了100斤 / 近100斤（同音） | 采购入库 | purchase_in | 同上 | 同上 | LOW 自动 |
| 土豆进了100斤（倒装） | 采购入库 | purchase_in | 同上 | 同上 | LOW 自动 |
| 商品不存在时 | 顺手建档 | （handler 内） | 建档（数量单位、售价0待改价）+ 入库 | 同上 | LOW 自动 |
| 重复同一句 | 幂等 | purchase_in | 同上 | **不重复入库**（幂等键） | — |

## 4. 损耗/修正

| 老板说 | 意图 | Tool | Domain | 账务效果 | 风险 |
|---|---|---|---|---|---|
| 损耗半斤土豆 / 坏了半斤土豆 / 半斤土豆坏了 / 土豆坏了2斤 | 报损 | record_loss | RecordLossUseCase | 损耗单 + 库存流水 LOSS（成本按加权价） | MEDIUM 确认 |
| 调整库存xxx | 库存调整 | adjust_stock | — | **未开放**：不在白名单，诚实提示（HIGH 风险强制确认能力保留给维护） | — |
| 删除/删掉/抹掉xxx | 删除 | — | — | 明确拒绝：账务事实只追加不删除 | — |
| 退货/退款 | 退款 | refund_sale | — | **V1 不发布**（ADR-015）：诚实失败，不伪造退款 | — |

## 5. 会员场景

| 老板说 | 意图 | Tool | Domain | 账务效果 | 风险 |
|---|---|---|---|---|---|
| 给张姐充200 / 充两百 / 充两百块钱 / 冲200（错字） | 充值 | recharge_member | RechargeMemberUseCase | member_ledger MEMBER_RECHARGE | MEDIUM 确认 |
| 张姐还有多少钱 / 张姐余额多少 | 查余额 | get_member_balance | MemberFundsQuery | 只读（余额=流水重建值） | LOW |
| 张姐买单 | 会员消费 | checkout_sale(member) | CheckoutSaleUseCase | MEMBER_CONSUME（余额不足明确拒绝） | MEDIUM 确认 |

## 6. 客户欠款场景

| 老板说 | 意图 | Tool | Domain | 账务效果 | 风险 |
|---|---|---|---|---|---|
| 老张先记账 | 赊账（草稿单金额） | record_customer_credit | RecordCustomerCreditUseCase | 完成单 + customer_ledger CUSTOMER_CREDIT | MEDIUM 确认 |
| 老张赊200 / 欠200 / 舍账200（错字） | 赊账（指定金额） | record_customer_credit | 同上 | 同上 | MEDIUM 确认 |
| 老张还1块 / 还一百 / 还钱100 / 还两毛五 | 还款 | settle_customer_debt | ReceiveCustomerPaymentUseCase | CUSTOMER_PAYMENT（追加式，不删历史） | MEDIUM 确认 |
| 老张欠多少钱 | 查欠款 | get_customer_debt | CustomerDebtQuery | 只读 | LOW |

## 7. 价格场景

| 老板说 | 意图 | Tool | Domain | 账务效果 | 风险 |
|---|---|---|---|---|---|
| 土豆改成四块 / 改为 / 改到 / 改价 / 把土豆改成四块 | 改价 | change_price | ChangeProductPriceUseCase | 商品当前价 + price_history（只追加） | MEDIUM 确认 |
| 土豆的销售价格是四块 / 土豆价格是四块 | 改价 | change_price | 同上 | 同上 | MEDIUM 确认 |
| 土豆卖四块 | 改价 | change_price | 同上 | 同上 | MEDIUM 确认 |
| 还是昨天那个价格 | 昨价复用 | apply_yesterday_price | YesterdayPriceQuery | 取昨日价格改价（无记录明确提示） | MEDIUM 确认 |
| 土豆多少钱 / 多少钱一斤 / 好多钱 / 几多钱 / 多钱 / 多少钱一米 | 询价 | find_product | ProductResolver | 只读（报当前售价） | LOW |

## 8. 查询场景（全部只读、离线）

| 老板说 | Tool | 回答什么 |
|---|---|---|
| 今天卖了多少钱 | get_today_sales | 今日笔数+总额（本地计算） |
| 本月卖了多少钱 | get_month_sales | 月笔数+总额 |
| 今天赚了多少 | get_profit_summary | 经营毛利（收入-成本-退款，不含房租水电） |
| 土豆还有多少货 / 库存多少 | get_stock | 当前库存（流水重建值） |
| 现在有什么 | get_current_sale | 草稿单明细+合计 |
| 哪些货快没了 | get_low_stock | 缺货/低库存清单 |
| 6901234567890（条码数字） | find_product_by_barcode | 条码对应商品 |

## 9. 交互场景

| 老板说 | 操作 |
|---|---|
| 确认 / 是 / 对 / 确定 / 好 / 行 / 可以 / 嗯 / **要得 / 中 / 好嘞 / 成 / 欧了**（方言） | 确认挂起操作 |
| 不 / 不用 / 不要 / 取消 / 算了 / 别 / 不行 / 不要了 | 拒绝挂起操作 |
| 不是土豆，是红薯 | 纠错：移除刚才加的（不创建错误交易），再重说新的 |
| 刚才那个不要了 / 去掉 / 退了 | 移除最近商品 |
| 建商品螺丝，卖5块 | 建档（MEDIUM 确认；不猜价格，没给价按 0 元再改） |
| 没听懂的话 | 明确引导：换种说法或告诉我商品、数量、要做什么（绝不猜） |

## 10. 链路铁律（每句话都必须走完）

```
老板的话 → 预处理纠错 → 意图识别 → Tool 选择 → Schema 校验
        → 风险门（LOW 自动 / MEDIUM 确认 / HIGH 强制确认）
        → Domain UseCase（唯一业务入口）
        → Ledger/DB（流水可重建、幂等、审计）
        → 回复（做了什么/金额数量/是否完成/卡在哪里）
```

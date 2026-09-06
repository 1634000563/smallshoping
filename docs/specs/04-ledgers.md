# Ledger、金额、库存与一致性

## 1. 三本账

### 库存账
`stock_ledger`

### 会员资金账
`member_ledger`

### 客户应收账
`customer_ledger`

任何余额都是可由流水重建的派生结果。

## 2. 销售事务

销售完成必须原子完成：

```text
BEGIN
sale_order
sale_items
payment
stock_ledger(-)
inventory_snapshot update
member_ledger(-) if needed
customer_ledger(+) if credit
fulfillment if applicable
audit_log
COMMIT
```

失败全部回滚。

## 3. 退款

退款不得删除原销售。

创建反向业务事实：
- refund record
- stock return if goods returned
- payment reversal / manual refund status
- member credit reversal if applicable

## 4. 幂等

所有可能被重复触发的命令都要有 idempotency_key。
同一业务操作重复提交必须返回同一业务结果，而不是重复扣库存/扣会员余额。

## 5. 库存不变量

`inventory_snapshot` 必须可通过 `stock_ledger` 重建。

禁止：
```text
product.stock = product.stock - 2
```

允许：
```text
append stock_ledger SALE_OUT
recalculate/update snapshot
```

## 6. 会员不变量

充值与消费必须通过 `member_ledger`。
余额不足默认拒绝消费；透支必须成为显式配置，不默认开启。

## 7. 客户欠款

赊账销售：应收增加。
收款：应收减少。
退款：按业务关系冲销。

## 8. 毛利口径

V1 默认：
`销售收入 - sale_item 成本快照 - 退款影响`

损耗单独统计，不伪装成销售成本。

## 9. 成本

V1 默认使用加权平均成本。
未来可扩展批次/FIFO，但接口不要绑定具体算法。

## 10. 日结

日结不修改历史销售，只生成核对快照。
差异写入 `variance_minor` 和 audit。

## 11. 库存负数策略

默认禁止销售导致负库存；若门店启用“允许负库存”，必须产生 warning + audit。

## 12. 经营毛利定义

V1 页面不得把“毛利”写成“净利润”。默认显示：

`经营毛利 = 销售收入 - 商品成本 - 退款影响`

损耗、房租、工资、水电等非商品成本不在 V1 毛利中处理。

## 13. 账本重建命令

必须有内部维护能力：
- rebuildInventorySnapshot(store_id)
- rebuildMemberBalance(member_id)
- rebuildCustomerDebt(customer_id)

重建后结果必须与缓存一致，否则标记数据一致性错误，不自动静默覆盖。

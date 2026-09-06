# 支付与收款

## 1. V1 支付策略

App 不做资金清算平台。

支持：
- CASH
- WECHAT_MANUAL
- ALIPAY_MANUAL
- MEMBER_BALANCE
- CUSTOMER_CREDIT
- OTHER

## 2. 手工微信/支付宝

订单金额确定 → 显示/调用店铺自己的收款方式 → 老板确认到账 → 记录 payment=CONFIRMED → 完成销售。

V1 不依赖第三方在线回调。

## 3. 官方支付预留

```text
PaymentProvider
├─ CashProvider
├─ ManualWechatProvider
├─ ManualAlipayProvider
├─ MemberBalanceProvider
├─ OfficialWechatProvider (future)
└─ OfficialAlipayProvider (future)
```

## 4. 重要边界

“老板确认微信到账”与“微信官方 API 已验证到账”必须是两个不同状态来源，不能混淆。

## 5. 客户赊账

客户赊账不是支付成功；Sale 可以完成但 payment 为 CUSTOMER_CREDIT，并在 customer_ledger 形成应收。

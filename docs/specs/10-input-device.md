# 输入设备：扫码、称重、语音、打印

## 1. Barcode

V1 支持主流：EAN-13、EAN-8、UPC-A、UPC-E、Code 128、Code 39。

流程：
扫描 → 本地 barcode lookup → 命中加购 → 未命中新商品向导。

## 2. Weight

抽象接口：
```text
WeightProvider
├─ ManualWeightProvider
└─ BluetoothScaleProvider (future)
```

V1 默认手动输入重量。

## 3. 语音

```text
Mic → STT → text → AI/Local parser → Tool
```

V1 不要求自训练模型。

## 4. 打印

V1 可预留 PrinterProvider，不将打印作为销售完成前提。

## 5. 输入的统一原则

所有输入最后都归一成 Domain Command，而不是各自写数据库逻辑。

例：
- 扫码发现商品 → AddSaleItemCommand
- 语音“卖两斤土豆” → AddSaleItemCommand
- 点击“土豆” + 输入2斤 → AddSaleItemCommand

三种入口共享同一业务逻辑。

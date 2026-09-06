# AI Gateway Contract

## 1. 目标

生产 Android 客户端不保存共享模型 API Key。客户端只持有短期会话凭证/设备身份，AI Gateway 负责模型供应商访问。

## 2. 请求

```json
{
  "store_id": "...",
  "device_id": "...",
  "app_version": "...",
  "model_policy": "default",
  "prompt_version": "v1",
  "tool_schema_version": "v1",
  "input": {
    "type": "text",
    "text": "两斤土豆"
  },
  "context": {
    "current_sale_id": "...",
    "recent_entities": []
  },
  "allowed_tools": ["find_product", "add_sale_item"]
}
```

## 3. 响应

必须能区分：
- FINAL_TEXT
- TOOL_CALL
- CLARIFICATION
- CONFIRMATION_REQUIRED
- MODEL_ERROR
- RATE_LIMITED

## 4. Gateway 责任

- 鉴权
- 限流
- 成本计量
- 模型路由
- Prompt 版本
- Tool schema 版本
- Provider secret
- 防滥用

## 5. 不允许

- 客户端内置长期共享 API Key
- Gateway 把完整数据库转发给模型
- Gateway 直接执行业务数据库写操作

## 6. 生产成本控制

按 store/device/request 做配额和异常流量监控。
默认简单查询优先本地，不进入 Gateway。
复杂请求才调用模型。

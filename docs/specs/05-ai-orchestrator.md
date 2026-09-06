# AI Orchestrator

## 1. AI 的职责

- 语句理解
- 意图识别
- 实体抽取
- 上下文引用解析
- 选择工具
- 组织澄清问题
- 选择自动执行/确认/拒绝

AI 不负责：
- 账务事实
- 最终金额计算
- 库存计算
- 会员余额计算
- 支付到账事实

## 2. Agent Loop

```text
input
 ↓
normalize
 ↓
intent + entities
 ↓
entity resolution
 ↓
context augmentation
 ↓
tool planning
 ↓
schema validation
 ↓
risk policy
 ↓
execute tool
 ↓
receive factual result
 ↓
respond / continue tool loop
```

## 3. Tool loop 限制

V1 单次用户请求默认最多 5 个 Tool calls；超过必须进入澄清或分步执行，防止失控循环。

## 4. Provider 抽象

```text
AiProvider
├─ CloudProvider
└─ LocalProvider / RuleParser
```

AI Provider 不接触 DB，只接收最小任务上下文。

## 5. Simple/Complex 路由

简单命令优先：本地 parser / 直接映射。
复杂自然语言：云 AI。

## 6. 失败降级

AI 超时/解析失败：
- 不改变任何事实
- 返回可执行的人工选择路径
- 不伪造成功

## 7. “AI 店员”定义

不是聊天机器人，而是“可调用小店业务能力的自然语言操作层”。

## 8. AI Provider 密钥安全

生产 Android APK 中不得内置共享云模型 API Key。
推荐：
`Android → AI Gateway → Model Provider`

Gateway 负责：
- API Key 保存
- 用户/设备限流
- Token/成本统计
- 模型路由
- Prompt 版本
- Tool schema 版本
- 防滥用

V1 开发阶段可使用开发密钥，但正式发布必须移出客户端。

## 9. Prompt / Model / Tool 版本

每次 AI 写操作记录：
- model_id
- prompt_version
- tool_schema_version
- app_version

这样模型升级后出现行为变化时可以定位。

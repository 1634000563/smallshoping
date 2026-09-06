# V1 架构总览

## 1. 核心架构

```text
老板
 │
 ├─ 语音
 ├─ 扫码
 ├─ 称重
 └─ 点击
 │
 ▼
Interaction Layer
 │
 ▼
AI Orchestrator / Local Command Parser
 │
 ├─ Context Manager
 ├─ Memory Manager
 ├─ Entity Resolver
 ├─ Tool Router
 └─ Risk Engine
 │
 ▼
Tool / Command Layer
 │
 ▼
Domain Engine
 │
 ├─ Catalog
 ├─ Sales
 ├─ Purchase
 ├─ Inventory
 ├─ Member
 ├─ Customer Account
 ├─ Payment
 ├─ Reports
 └─ Audit
 │
 ▼
Repository / Transaction Boundary
 │
 ▼
Room / SQLite + Ledgers
 │
 ├─ 本地事实
 ├─ 本地可重建
 └─ 离线继续营业

网络可用时：
AI Provider / Backup / Sync / Update
```

## 2. 核心依赖规则

```text
UI → ViewModel → UseCase → Repository → Room
                         ↑
                AI Tool Adapter
```

禁止：

```text
UI → DAO
AI → DAO
AI → Entity mutation
```

## 3. 为什么不做“AI 直连数据库”

AI 可能存在理解错误、幻觉、上下文歧义和模型版本变化。账务和库存必须确定性执行。因此：

```text
自然语言
 → 结构化意图
 → Tool 参数 Schema
 → 风险检查
 → Domain 规则校验
 → DB Transaction
```

## 4. Android V1 推荐目录

```text
app/src/main/java/<package>/
├─ core/
│  ├─ common/
│  ├─ money/
│  ├─ quantity/
│  ├─ time/
│  ├─ result/
│  └─ logging/
├─ data/
│  ├─ db/
│  ├─ dao/
│  ├─ entity/
│  ├─ mapper/
│  ├─ repository/
│  ├─ ledger/
│  └─ migration/
├─ domain/
│  ├─ catalog/
│  ├─ sales/
│  ├─ purchase/
│  ├─ inventory/
│  ├─ member/
│  ├─ customer/
│  ├─ payment/
│  ├─ report/
│  └─ audit/
├─ ai/
│  ├─ orchestrator/
│  ├─ context/
│  ├─ memory/
│  ├─ entityresolution/
│  ├─ risk/
│  ├─ tools/
│  ├─ providers/
│  └─ prompts/
├─ device/
│  ├─ scanner/
│  ├─ weight/
│  └─ printer/
├─ feature/
│  ├─ home/
│  ├─ sale/
│  ├─ purchase/
│  ├─ product/
│  ├─ inventory/
│  ├─ member/
│  ├─ customer/
│  ├─ report/
│  └─ settings/
└─ app/
   ├─ navigation/
   ├─ di/
   └─ lifecycle/
```

## 5. 不建议 V1 过早拆多个 Gradle module

V1 先保持单 App module + 清晰 package boundaries。等代码规模和构建时间证明需要时再拆模块。这样降低 Codex 早期协调成本。

## 6. 事实、缓存、记忆三分法

- Fact：商品、订单、支付、库存、会员流水等，来自数据库/账本。
- Cache：库存快照、统计缓存等，可以重建。
- Memory：老板习惯、别名、默认单位等，可以撤销和重新学习。

任何 Memory 与 Fact 冲突时，Fact 优先。

## 7. 生产 AI 网络拓扑

```text
Android App
   │
   ├─ 本地业务 / SQLite  ← 不依赖网络
   │
   └─ HTTPS
       ↓
    AI Gateway
       ├─ auth / rate limit
       ├─ prompt version
       ├─ tool schema version
       ├─ model router
       └─ provider secret
       ↓
    Model Provider
```

不要把第三方模型的长期共享密钥写进 APK。

## 8. 推荐的知识库结构

```text
AGENTS.md                  # 约100行的硬规则和地图
ARCHITECTURE.md            # 系统地图
README.md                  # 人类入口
docs/specs/                # 业务与技术事实
docs/schemas/              # 机器可读契约
docs/prompts/              # Prompt 基线
docs/evals/                # AI 黄金集
plans/                     # 执行顺序
codex/tasks/               # 单任务执行模板
```

这符合 AI agent 开发中“短 AGENTS + 结构化知识库 + 小任务”的思路，而不是用一个超大文档填满每次任务上下文。

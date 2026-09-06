package com.smallshoping.app.ai.risk

/**
 * 风险等级（spec 08 §1）：
 * LOW=查询/加购物车/普通销售；MEDIUM=改价/充值扣款/普通退款/库存调整；
 * HIGH=大额退款/批量变更/删除类操作。
 */
enum class RiskLevel { LOW, MEDIUM, HIGH }

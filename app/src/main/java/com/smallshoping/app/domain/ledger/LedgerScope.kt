package com.smallshoping.app.domain.ledger

/** 账本类别（spec 04 §1：库存账/会员资金账/客户应收账）。 */
enum class LedgerScopeType { STOCK, MEMBER, CUSTOMER }

/**
 * 账本范围：某类账本下某个主体（如某个商品 / 某个会员 / 某个客户）。
 * 余额 = 该范围内全部流水的 delta 之和。
 */
data class LedgerScope(val type: LedgerScopeType, val scopeId: String)

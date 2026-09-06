package com.smallshoping.app.domain.ledger

/**
 * 幂等键：同一业务命令每次提交携带同一键，重复提交不得重复记账（spec 04 §4）。
 */
@JvmInline
value class IdempotencyKey(val value: String) {

    init {
        require(value.isNotBlank()) { "幂等键不能为空" }
    }

    override fun toString(): String = value
}

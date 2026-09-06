package com.smallshoping.app.domain.customer

import com.smallshoping.app.core.money.Money
import com.smallshoping.app.domain.ledger.AppendResult
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.Ledger
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType

/** 欠款变动请求（spec 04 §7：赊账销售应收增加，收款应收减少）。 */
data class CustomerDebtRequest(
    val storeId: String,
    val customerId: String,
    /** 变动金额（正数，分）。 */
    val amount: Money,
    val idempotencyKey: String,
    val note: String
)

sealed interface CustomerDebtResult {
    data class Success(val balanceAfterMinor: Long) : CustomerDebtResult
    data object CustomerNotFound : CustomerDebtResult
    /** 同一幂等键重复提交：返回原结果，不重复入账（spec 04 §4）。 */
    data class AlreadyCompleted(val balanceAfterMinor: Long) : CustomerDebtResult
}

/**
 * 赊账（应收增加，CUSTOMER_CREDIT +）。
 *
 * 事实只有一条 customer_ledger 流水；销售结账（Task 047）将把它
 * 作为整批事务的一部分写入，本 UseCase 是唯一合法入口。
 */
class RecordCustomerCreditUseCase(
    private val customers: CustomerRepository,
    private val ledger: Ledger
) {

    operator fun invoke(request: CustomerDebtRequest): CustomerDebtResult =
        appendDebt(customers, ledger, request, MovementType.CUSTOMER_CREDIT, request.amount.minor)
}

/**
 * 收款（应收减少，CUSTOMER_PAYMENT -）。
 *
 * 还款冲减欠款，不删除历史赊账流水（spec 04 §3 追加式原则）。
 */
class ReceiveCustomerPaymentUseCase(
    private val customers: CustomerRepository,
    private val ledger: Ledger
) {

    operator fun invoke(request: CustomerDebtRequest): CustomerDebtResult =
        appendDebt(customers, ledger, request, MovementType.CUSTOMER_PAYMENT, -request.amount.minor)
}

/** 两类欠款变动的共同实现：正金额校验 + 客户存在 + 单条流水 + 幂等。 */
private fun appendDebt(
    customers: CustomerRepository,
    ledger: Ledger,
    request: CustomerDebtRequest,
    movementType: MovementType,
    delta: Long
): CustomerDebtResult {
    require(!request.amount.isNegative && !request.amount.isZero) { "变动金额必须为正：${request.amount}" }
    customers.findCustomerById(request.customerId) ?: return CustomerDebtResult.CustomerNotFound

    val scope = LedgerScope(LedgerScopeType.CUSTOMER, request.customerId)
    val entry = LedgerEntry(
        scope = scope,
        movementType = movementType,
        delta = delta,
        referenceType = movementType.name.lowercase(),
        referenceId = request.idempotencyKey,
        idempotencyKey = IdempotencyKey(request.idempotencyKey),
        note = request.note
    )
    return when (ledger.append(entry)) {
        is AppendResult.Appended -> CustomerDebtResult.Success(ledger.balance(scope))
        is AppendResult.Duplicate -> CustomerDebtResult.AlreadyCompleted(ledger.balance(scope))
    }
}

/**
 * 客户欠款查询与维护（spec 04 §13）。
 *
 * 欠款一律由 customer_ledger 流水派生；[rebuildDebt] 重算后与
 * 缓存不一致时抛 [com.smallshoping.app.domain.ledger.DataIntegrityException]，
 * 绝不静默覆盖。
 */
class CustomerDebtQuery(
    private val customers: CustomerRepository,
    private val ledger: Ledger
) {

    /** 客户当前欠款（分，正=欠）；客户不存在返回 null。 */
    fun debtOf(customerId: String): Long? {
        customers.findCustomerById(customerId) ?: return null
        return ledger.balance(LedgerScope(LedgerScopeType.CUSTOMER, customerId))
    }

    /** 从流水重建欠款并核对缓存（spec 04 §13 rebuildCustomerDebt）；客户不存在返回 null。 */
    fun rebuildDebt(customerId: String): Long? {
        customers.findCustomerById(customerId) ?: return null
        return ledger.rebuildBalance(LedgerScope(LedgerScopeType.CUSTOMER, customerId))
    }
}

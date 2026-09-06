package com.smallshoping.app.domain.member

import com.smallshoping.app.core.money.Money
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.Ledger
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType

/** 会员充值请求（spec 04 §6：充值必须通过 member_ledger）。 */
data class RechargeMemberRequest(
    val storeId: String,
    val memberId: String,
    /** 充值金额，必须为正（分）。 */
    val amount: Money,
    val idempotencyKey: String,
    val note: String = "会员充值"
)

sealed interface RechargeMemberResult {
    /** 充值成功；余额由流水派生，随结果一并返回方便展示。 */
    data class Success(val balanceAfterMinor: Long) : RechargeMemberResult
    data object MemberNotFound : RechargeMemberResult
    /** 同一幂等键重复提交：返回原结果，不重复入账（spec 04 §4）。 */
    data class AlreadyCompleted(val balanceAfterMinor: Long) : RechargeMemberResult
}

/**
 * 会员充值（资金入账）。充值事实只有一条 member_ledger 流水，
 * 不单独建充值单表（spec 03 §4）；幂等由账本幂等键保证。
 */
class RechargeMemberUseCase(
    private val members: MemberRepository,
    private val ledger: Ledger
) {

    operator fun invoke(request: RechargeMemberRequest): RechargeMemberResult {
        require(!request.amount.isNegative && !request.amount.isZero) { "充值金额必须为正：${request.amount}" }
        members.findMemberById(request.memberId) ?: return RechargeMemberResult.MemberNotFound

        val scope = LedgerScope(LedgerScopeType.MEMBER, request.memberId)
        val entry = LedgerEntry(
            scope = scope,
            movementType = MovementType.MEMBER_RECHARGE,
            delta = request.amount.minor,
            referenceType = "member_recharge",
            referenceId = request.idempotencyKey,
            idempotencyKey = IdempotencyKey(request.idempotencyKey),
            note = request.note
        )
        return when (val result = ledger.append(entry)) {
            is com.smallshoping.app.domain.ledger.AppendResult.Appended ->
                RechargeMemberResult.Success(ledger.balance(scope))
            is com.smallshoping.app.domain.ledger.AppendResult.Duplicate ->
                RechargeMemberResult.AlreadyCompleted(ledger.balance(scope))
        }
    }
}

/**
 * 会员资金查询与维护（spec 04 §13）。
 *
 * 余额一律由 member_ledger 流水派生；[rebuildBalance] 重算后与
 * 缓存不一致时抛 [com.smallshoping.app.domain.ledger.DataIntegrityException]，
 * 绝不静默覆盖。
 */
class MemberFundsQuery(
    private val members: MemberRepository,
    private val ledger: Ledger
) {

    /** 会员余额（分）；会员不存在返回 null。 */
    fun balanceOf(memberId: String): Long? {
        members.findMemberById(memberId) ?: return null
        return ledger.balance(LedgerScope(LedgerScopeType.MEMBER, memberId))
    }

    /** 从流水重建余额并核对缓存（spec 04 §13 rebuildMemberBalance）；会员不存在返回 null。 */
    fun rebuildBalance(memberId: String): Long? {
        members.findMemberById(memberId) ?: return null
        return ledger.rebuildBalance(LedgerScope(LedgerScopeType.MEMBER, memberId))
    }
}

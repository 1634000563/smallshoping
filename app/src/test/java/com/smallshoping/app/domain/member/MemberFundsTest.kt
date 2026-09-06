package com.smallshoping.app.domain.member

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.data.ledger.InMemoryLedger
import com.smallshoping.app.data.repository.InMemoryMemberRepository
import com.smallshoping.app.domain.ledger.DataIntegrityException
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 020 验收：会员资金必须全部走 member_ledger，余额由流水派生。 */
class MemberFundsTest {

    private val members = InMemoryMemberRepository()
    private val ledger = InMemoryLedger()
    private val recharge = RechargeMemberUseCase(members, ledger)
    private val funds = MemberFundsQuery(members, ledger)

    private fun seedMember(id: String = "M-1", name: String = "张姐"): Member {
        val member = Member(
            id = id, storeId = "STORE-1", name = name,
            normalizedName = normalize(name), alias = "张女士"
        )
        members.saveMember(member)
        return member
    }

    @Test
    fun `正常路径：充值 200 元 → 余额由流水派生`() {
        seedMember()
        val result = recharge(
            RechargeMemberRequest(
                storeId = "STORE-1", memberId = "M-1",
                amount = Money.fromYuan(200), idempotencyKey = "recharge:M-1:20000"
            )
        )
        assertTrue(result is RechargeMemberResult.Success)
        assertEquals(20000L, (result as RechargeMemberResult.Success).balanceAfterMinor)
        assertEquals(20000L, funds.balanceOf("M-1"))

        // 账务事实可追溯：一条 MEMBER_RECHARGE 流水，delta 为正
        val entries = ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1"))
        assertEquals(1, entries.size)
        assertEquals(MovementType.MEMBER_RECHARGE, entries[0].movementType)
        assertEquals(20000L, entries[0].delta)
    }

    @Test
    fun `多次充值累计：余额等于流水之和`() {
        seedMember()
        recharge(
            RechargeMemberRequest("STORE-1", "M-1", Money.fromYuan(200), "recharge:M-1:a")
        )
        recharge(
            RechargeMemberRequest("STORE-1", "M-1", Money.fromYuan(35), "recharge:M-1:b")
        )
        assertEquals(23500L, funds.balanceOf("M-1"))
        assertEquals(2, ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1")).size)
    }

    @Test
    fun `幂等：同键重复充值不重复入账，返回原结果`() {
        seedMember()
        val request = RechargeMemberRequest(
            "STORE-1", "M-1", Money.fromYuan(200), "recharge:M-1:20000"
        )
        val first = recharge(request)
        val second = recharge(request)
        assertTrue(first is RechargeMemberResult.Success)
        assertTrue(second is RechargeMemberResult.AlreadyCompleted)
        assertEquals(20000L, (second as RechargeMemberResult.AlreadyCompleted).balanceAfterMinor)
        assertEquals(20000L, funds.balanceOf("M-1"))
        assertEquals(1, ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1")).size)
    }

    @Test
    fun `会员不存在：拒绝充值且不产生流水`() {
        val result = recharge(
            RechargeMemberRequest("STORE-1", "M-404", Money.fromYuan(200), "recharge:M-404:x")
        )
        assertTrue(result is RechargeMemberResult.MemberNotFound)
        assertNull(funds.balanceOf("M-404"))
        assertEquals(0, ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-404")).size)
    }

    @Test
    fun `边界输入：零或负金额直接拒绝`() {
        seedMember()
        assertThrows(IllegalArgumentException::class.java) {
            recharge(RechargeMemberRequest("STORE-1", "M-1", Money.ZERO, "recharge:M-1:z"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            recharge(RechargeMemberRequest("STORE-1", "M-1", Money(-1), "recharge:M-1:n"))
        }
        assertEquals(0L, funds.balanceOf("M-1"))
        assertEquals(0, ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1")).size)
    }

    @Test
    fun `余额重建：与缓存一致返回派生值`() {
        seedMember()
        recharge(
            RechargeMemberRequest("STORE-1", "M-1", Money.fromYuan(200), "recharge:M-1:r")
        )
        assertEquals(20000L, funds.rebuildBalance("M-1"))
        assertNull(funds.rebuildBalance("M-404"))
    }

    @Test
    fun `余额重建：缓存被破坏时抛一致性异常，不静默覆盖`() {
        seedMember()
        recharge(
            RechargeMemberRequest("STORE-1", "M-1", Money.fromYuan(200), "recharge:M-1:c")
        )
        ledger.debugCorruptCachedBalance(LedgerScope(LedgerScopeType.MEMBER, "M-1"), 1L)
        assertThrows(DataIntegrityException::class.java) { funds.rebuildBalance("M-1") }
    }
}

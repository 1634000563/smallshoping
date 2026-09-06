package com.smallshoping.app.domain.payment

import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import com.smallshoping.app.domain.member.Member
import com.smallshoping.app.domain.member.RechargeMemberRequest
import com.smallshoping.app.domain.sales.AddSaleItemRequest
import com.smallshoping.app.domain.sales.CheckoutSaleRequest
import com.smallshoping.app.domain.sales.CheckoutSaleResult
import com.smallshoping.app.domain.sales.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 047 验收：支付记录模型（现金即刻确认/手工支付待确认/会员余额消费）。 */
class PaymentDomainTest {

    private val root = CompositionRoot()

    private fun seedPotatoAndMember() {
        root.products.saveProduct(
            Product(
                id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
                saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
                currentSalePrice = Money(380), currentCostPrice = Money(280)
            )
        )
        root.ledger.append(
            LedgerEntry(
                scope = LedgerScope(LedgerScopeType.STOCK, "P-1"),
                movementType = MovementType.PURCHASE_IN,
                delta = 5000,
                idempotencyKey = IdempotencyKey("IN-047"),
                note = "测试入库"
            )
        )
        root.members.saveMember(
            Member(id = "M-1", storeId = "STORE-1", name = "张姐", normalizedName = normalize("张姐"))
        )
    }

    private fun draftOneJin(): String {
        val added = root.addSaleItemUseCase(
            AddSaleItemRequest("STORE-1", null, "P-1", Quantity(500, Unit.GRAM))
        ) as com.smallshoping.app.domain.sales.AddSaleItemResult.Success
        return added.sale.id
    }

    @Test
    fun `现金结账：支付记录即刻 CONFIRMED`() {
        seedPotatoAndMember()
        val saleId = draftOneJin()
        val result = root.checkoutSaleUseCase(
            CheckoutSaleRequest(saleId, PaymentMethod.CASH, "ck-cash-1")
        )
        assertTrue(result is CheckoutSaleResult.Success)
        val payments = root.payments.bySale(saleId)
        assertEquals(1, payments.size)
        assertEquals(PaymentStatus.CONFIRMED, payments[0].status)
        assertEquals(380L, payments[0].amountMinor)
    }

    @Test
    fun `微信结账：PENDING 待老板确认到账 → 确认后 CONFIRMED`() {
        seedPotatoAndMember()
        val saleId = draftOneJin()
        root.checkoutSaleUseCase(CheckoutSaleRequest(saleId, PaymentMethod.WECHAT, "ck-wx-1"))
        val pending = root.payments.bySale(saleId).single()
        assertEquals(PaymentStatus.PENDING, pending.status)

        // 老板确认到账（spec 11 §2）
        val result = root.confirmPaymentUseCase.confirmBySale(saleId)
        assertEquals(1, result.confirmed.size)
        assertEquals(PaymentStatus.CONFIRMED, result.confirmed[0].status)
        // 重复确认幂等：已确认不再改动
        val again = root.confirmPaymentUseCase.confirmBySale(saleId)
        assertEquals(0, again.confirmed.size)
        assertEquals(1, again.skipped)
    }

    @Test
    fun `会员余额结账：消费走 member_ledger，余额足时同批落账`() {
        seedPotatoAndMember()
        root.rechargeMemberUseCase(
            RechargeMemberRequest("STORE-1", "M-1", Money.fromYuan(200), "recharge-047")
        )
        val saleId = draftOneJin()
        val result = root.checkoutSaleUseCase(
            CheckoutSaleRequest(saleId, PaymentMethod.MEMBER, "ck-member-1", memberId = "M-1")
        )
        assertTrue(result is CheckoutSaleResult.Success)
        // 余额 20000 - 380 = 19620；member_ledger 两条流水（充值+消费）
        assertEquals(19620L, root.memberFundsQuery.balanceOf("M-1"))
        val entries = root.ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1"))
        assertEquals(2, entries.size)
        assertEquals(MovementType.MEMBER_CONSUME, entries[1].movementType)
        assertEquals(-380L, entries[1].delta)
        // 支付记录即刻 CONFIRMED
        assertEquals(PaymentStatus.CONFIRMED, root.payments.bySale(saleId).single().status)
    }

    @Test
    fun `会员余额不足：拒绝消费不落账（spec 04 §6）`() {
        seedPotatoAndMember()
        root.rechargeMemberUseCase(
            RechargeMemberRequest("STORE-1", "M-1", Money(100), "recharge-047b") // 100 分 < 380 分
        )
        val saleId = draftOneJin()
        val result = root.checkoutSaleUseCase(
            CheckoutSaleRequest(saleId, PaymentMethod.MEMBER, "ck-member-2", memberId = "M-1")
        )
        assertTrue(result is CheckoutSaleResult.InsufficientBalance)
        assertEquals(100L, root.memberFundsQuery.balanceOf("M-1"))
        assertEquals(1, root.ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1")).size)
        // 销售单仍为 DRAFT，可换支付方式
        assertEquals(
            com.smallshoping.app.domain.sales.SaleStatus.DRAFT,
            root.sales.findById(saleId)!!.status
        )
    }

    @Test
    fun `支付记录幂等：重复结账不重复记录`() {
        seedPotatoAndMember()
        val saleId = draftOneJin()
        root.checkoutSaleUseCase(CheckoutSaleRequest(saleId, PaymentMethod.CASH, "ck-cash-1"))
        // AlreadyCompleted 路径不再写支付记录
        val again = root.checkoutSaleUseCase(
            CheckoutSaleRequest(saleId, PaymentMethod.CASH, "ck-cash-1")
        )
        assertTrue(again is CheckoutSaleResult.AlreadyCompleted)
        assertEquals(1, root.payments.bySale(saleId).size)
    }
}

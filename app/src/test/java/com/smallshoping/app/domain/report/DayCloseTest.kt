package com.smallshoping.app.domain.report

import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.customer.Customer
import com.smallshoping.app.domain.customer.CustomerDebtRequest
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import com.smallshoping.app.domain.payment.PaymentStatus
import com.smallshoping.app.domain.sales.AddSaleItemRequest
import com.smallshoping.app.domain.sales.CheckoutSaleRequest
import com.smallshoping.app.domain.sales.CheckoutSaleResult
import com.smallshoping.app.domain.sales.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 048 验收：日结快照不修改历史销售，收款差异如实记录。 */
class DayCloseTest {

    private val root = CompositionRoot()

    private fun seed() {
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
                delta = 50000,
                idempotencyKey = IdempotencyKey("IN-048"),
                note = "测试入库"
            )
        )
        root.customers.saveCustomer(
            Customer(id = "C-1", storeId = "STORE-1", name = "老张", normalizedName = normalize("老张"))
        )
    }

    private fun sellOneJin(method: PaymentMethod): String {
        val added = root.addSaleItemUseCase(
            AddSaleItemRequest("STORE-1", null, "P-1", Quantity(500, Unit.GRAM))
        ) as com.smallshoping.app.domain.sales.AddSaleItemResult.Success
        val result = root.checkoutSaleUseCase(
            CheckoutSaleRequest(added.sale.id, method, "ck-${method.name}-${added.sale.id}")
        )
        assertTrue(result is CheckoutSaleResult.Success)
        return added.sale.id
    }

    @Test
    fun `经营核对：现金应收只含已确认现金支付，其余分列`() {
        seed()
        sellOneJin(PaymentMethod.CASH) // 380
        val wxSale = sellOneJin(PaymentMethod.WECHAT) // 380，PENDING 不计
        sellOneJin(PaymentMethod.ALIPAY) // 380，PENDING 不计
        // 微信到账后计入
        root.confirmPaymentUseCase.confirmBySale(wxSale)

        val summary = root.dayCloseService.checkSummary()
        assertEquals(380L, summary.cashMinor)
        assertEquals(380L, summary.wechatMinor)
        assertEquals(0L, summary.alipayMinor)
        assertEquals(0L, summary.memberMinor)
    }

    @Test
    fun `客户赊账进入当日核对（流水事实）`() {
        seed()
        root.recordCustomerCreditUseCase(
            CustomerDebtRequest("STORE-1", "C-1", Money(500), "credit-048", "赊账")
        )
        val summary = root.dayCloseService.checkSummary()
        assertEquals(500L, summary.customerCreditMinor)
    }

    @Test
    fun `日结：实收与应收差异如实记录，不修改历史销售`() {
        seed()
        sellOneJin(PaymentMethod.CASH) // 应收 380
        val historyBefore = root.sales.allSales().size

        // 老板实际点到 400 分：差异 +20
        val outcome = root.dayCloseService.closeDay(cashActualMinor = 400, note = "多出 20 分")
        assertTrue(outcome is CloseOutcome.Closed)
        val dayClose = (outcome as CloseOutcome.Closed).dayClose
        assertEquals(380L, dayClose.cashExpectedMinor)
        assertEquals(400L, dayClose.cashActualMinor)
        assertEquals(20L, dayClose.varianceMinor)

        // 历史销售未被修改（spec 04 §10）
        assertEquals(historyBefore, root.sales.allSales().size)
        // 同日重复日结：幂等返回原快照
        val again = root.dayCloseService.closeDay(cashActualMinor = 999)
        assertTrue(again is CloseOutcome.AlreadyClosed)
        assertEquals(400L, (again as CloseOutcome.AlreadyClosed).dayClose.cashActualMinor)
    }

    @Test
    fun `微信支付未确认不计入应收`() {
        seed()
        val wxSale = sellOneJin(PaymentMethod.WECHAT)
        assertEquals(PaymentStatus.PENDING, root.payments.bySale(wxSale).single().status)
        val summary = root.dayCloseService.checkSummary()
        assertEquals(0L, summary.cashMinor)
        assertEquals(0L, summary.wechatMinor)
        // 确认到账后计入
        root.confirmPaymentUseCase.confirmBySale(wxSale)
        assertEquals(380L, root.dayCloseService.checkSummary().wechatMinor)
    }
}

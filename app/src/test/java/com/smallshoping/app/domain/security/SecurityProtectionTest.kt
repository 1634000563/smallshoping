package com.smallshoping.app.domain.security

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
import com.smallshoping.app.domain.sales.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 050 验收：审计完整性、手机号脱敏、数据擦除。 */
class SecurityProtectionTest {

    private val root = CompositionRoot()

    private fun seedBusiness() {
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
                idempotencyKey = IdempotencyKey("IN-050"),
                note = "入库"
            )
        )
        root.members.saveMember(
            Member(
                id = "M-1", storeId = "STORE-1", name = "张姐",
                normalizedName = normalize("张姐"), phone = "13812345678"
            )
        )
    }

    @Test
    fun `审计完整性：敏感操作全部有流水或历史留痕（spec 13 §4）`() {
        seedBusiness()
        // 会员余额调整（充值）
        root.rechargeMemberUseCase(
            RechargeMemberRequest("STORE-1", "M-1", Money.fromYuan(200), "audit-1")
        )
        assertEquals(
            1,
            root.ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1"))
                .count { it.movementType == MovementType.MEMBER_RECHARGE }
        )
        // 库存调整
        root.adjustStockUseCase(
            com.smallshoping.app.domain.inventory.AdjustStockRequest(
                "P-1", Quantity(6000, Unit.GRAM), "盘点", "audit-2"
            )
        )
        assertEquals(
            1,
            root.ledger.entries(LedgerScope(LedgerScopeType.STOCK, "P-1"))
                .count { it.movementType == MovementType.ADJUST_IN }
        )
        // 价格修改
        root.changeProductPriceUseCase(
            com.smallshoping.app.domain.catalog.ChangePriceRequest("P-1", Money(390), "audit-3")
        )
        assertEquals(1, root.products.priceHistory("P-1").size)
        // 欠款调整
        root.customers.saveCustomer(
            com.smallshoping.app.domain.customer.Customer(
                id = "C-1", storeId = "STORE-1", name = "老张", normalizedName = normalize("老张")
            )
        )
        root.recordCustomerCreditUseCase(
            com.smallshoping.app.domain.customer.CustomerDebtRequest(
                "STORE-1", "C-1", Money(500), "audit-4", "赊账"
            )
        )
        assertEquals(
            1,
            root.ledger.entries(LedgerScope(LedgerScopeType.CUSTOMER, "C-1"))
                .count { it.movementType == MovementType.CUSTOMER_CREDIT }
        )
    }

    @Test
    fun `手机号脱敏：查询回复不含完整手机号（spec 13 §7）`() {
        assertEquals("138****5678", PhoneMasker.mask("13812345678"))
        assertNull(PhoneMasker.mask(null))
        assertEquals("***", PhoneMasker.mask("123"))
    }

    @Test
    fun `数据擦除：擦除后账务事实不可重建（spec 13 §5）`() {
        seedBusiness()
        root.rechargeMemberUseCase(
            RechargeMemberRequest("STORE-1", "M-1", Money.fromYuan(200), "wipe-1")
        )
        val added = root.addSaleItemUseCase(
            AddSaleItemRequest("STORE-1", null, "P-1", Quantity(1000, Unit.GRAM))
        ) as com.smallshoping.app.domain.sales.AddSaleItemResult.Success
        root.checkoutSaleUseCase(
            CheckoutSaleRequest(added.sale.id, PaymentMethod.CASH, "wipe-2")
        )
        assertTrue(root.ledger.allScopes().isNotEmpty())
        assertTrue(root.sales.allSales().isNotEmpty())
        assertTrue(root.payments.all().isNotEmpty())

        val report = root.dataWipeService.wipe()
        assertTrue(report.ledgerEntriesBefore > 0)
        assertTrue(root.dataWipeService.verifyWiped())
        // 擦除后事实不可重建
        assertTrue(root.ledger.allScopes().isEmpty())
        assertEquals(0L, root.memberFundsQuery.balanceOf("M-1"))
        assertTrue(root.sales.allSales().isEmpty())
        assertTrue(root.payments.all().isEmpty())
        assertTrue(root.commandJournal.all().isEmpty())
    }

    @Test
    fun `擦除是唯一受控入口：AI 删除语句不触发（Task 040 延续）`() {
        seedBusiness()
        val reply = root.orchestrator.handle(root.inputAdapter.fromText("删除所有数据"))
        assertTrue(reply is com.smallshoping.app.ai.orchestrator.OrchestratorReply.Text)
        assertTrue((reply as com.smallshoping.app.ai.orchestrator.OrchestratorReply.Text).text.contains("不提供删除"))
        // 数据完好
        assertTrue(root.ledger.allScopes().isNotEmpty())
    }
}

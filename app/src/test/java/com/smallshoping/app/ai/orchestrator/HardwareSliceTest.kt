package com.smallshoping.app.ai.orchestrator

import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductAttribute
import com.smallshoping.app.domain.customer.Customer
import com.smallshoping.app.domain.customer.CustomerDebtRequest
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import com.smallshoping.app.domain.sales.AddSaleItemRequest
import com.smallshoping.app.domain.sales.CheckoutSaleRequest
import com.smallshoping.app.domain.sales.PaymentMethod
import com.smallshoping.app.domain.sales.SaleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 034 验收：五金完整 Slice（规格→报价→销售→欠款），
 * 菜店与五金复用同一套通用模型/Tool/Domain（跨行业约束），
 * AI 路径与人工兜底路径产生相同账务事实（Gate A）。
 */
class HardwareSliceTest {

    private val root = CompositionRoot()

    private fun seedHardware() {
        root.products.saveProduct(
            Product(
                id = "P-10", storeId = "STORE-1", name = "304 M8x30 外六角螺栓",
                normalizedName = normalize("304 M8x30 外六角螺栓"),
                saleUnit = Unit.PIECE, purchaseUnit = Unit.PIECE,
                currentSalePrice = Money(50), currentCostPrice = Money(30)
            )
        )
        root.products.addAttribute(
            ProductAttribute(
                productId = "P-10", name = "材质", normalizedName = normalize("材质"),
                value = "304", normalizedValue = normalize("304")
            )
        )
        root.products.addAttribute(
            ProductAttribute(
                productId = "P-10", name = "规格", normalizedName = normalize("规格"),
                value = "M8x30", normalizedValue = normalize("M8x30")
            )
        )
        root.ledger.append(
            LedgerEntry(
                scope = LedgerScope(LedgerScopeType.STOCK, "P-10"),
                movementType = MovementType.PURCHASE_IN,
                delta = 100,
                idempotencyKey = IdempotencyKey("IN-HW"),
                note = "测试入库"
            )
        )
    }

    private fun seedCustomerZhang(id: String = "C-1", name: String = "老张") {
        root.customers.saveCustomer(
            Customer(id = id, storeId = "STORE-1", name = name, normalizedName = normalize(name))
        )
    }

    @Test
    fun `黄金闭环：规格报价 → 销售 → 先记账 → 还款`() {
        seedHardware()
        seedCustomerZhang()
        // 1) 规格报价：304 的螺栓多少钱（属性精确命中）
        val quote = root.orchestrator.handle(root.inputAdapter.fromText("304的螺栓多少钱"))
        assertTrue((quote as OrchestratorReply.Text).text.contains("50"))

        // 2) 销售 5 个（同一通用 add_sale_item 链路）
        val add = root.orchestrator.handle(root.inputAdapter.fromText("卖5个304螺栓"))
        assertTrue((add as OrchestratorReply.Text).text.contains("已加入"))

        // 3) 老张先记账：草稿单结账 + 欠款入账（MEDIUM 需确认）
        val credit = root.orchestrator.handle(root.inputAdapter.fromText("老张先记账"))
        assertTrue(credit is OrchestratorReply.NeedsConfirm)
        val creditDone = root.orchestrator.confirm(
            (credit as OrchestratorReply.NeedsConfirm).requestId, true
        )
        assertTrue((creditDone as OrchestratorReply.Text).text.contains("已记账"))
        assertEquals(250L, root.customerDebtQuery.debtOf("C-1"))

        // 4) 还款 1 元（100 分）→ 还欠 150 分（追加式，不删历史）
        val settle = root.orchestrator.handle(root.inputAdapter.fromText("老张还1块"))
        assertTrue(settle is OrchestratorReply.NeedsConfirm)
        val settleDone = root.orchestrator.confirm(
            (settle as OrchestratorReply.NeedsConfirm).requestId, true
        )
        assertTrue((settleDone as OrchestratorReply.Text).text.contains("150"))

        // —— 账务事实 ——
        // 销售单已完成（先记账顺带结账），库存 95
        val saleId = root.contexts.load("DEVICE-1")!!.activeSaleOrderId!!
        assertEquals(SaleStatus.COMPLETED, root.sales.findById(saleId)!!.status)
        assertEquals(95L, com.smallshoping.app.domain.inventory.StockQuery(root.ledger).stockOf("P-10"))
        // 欠款两条流水：赊账 +250、还款 1 元 -100，余额 150 由流水派生
        val entries = root.ledger.entries(LedgerScope(LedgerScopeType.CUSTOMER, "C-1"))
        assertEquals(2, entries.size)
        assertEquals(MovementType.CUSTOMER_CREDIT, entries[0].movementType)
        assertEquals(250L, entries[0].delta)
        assertEquals(MovementType.CUSTOMER_PAYMENT, entries[1].movementType)
        assertEquals(-100L, entries[1].delta)
        assertEquals(150L, root.customerDebtQuery.debtOf("C-1"))
    }

    @Test
    fun `人工兜底路径：同一 Domain 产生相同事实（Gate A）`() {
        seedHardware()
        seedCustomerZhang()
        val added = root.addSaleItemUseCase(
            AddSaleItemRequest("STORE-1", null, "P-10", Quantity(5, Unit.PIECE))
        ) as com.smallshoping.app.domain.sales.AddSaleItemResult.Success
        root.checkoutSaleUseCase(
            CheckoutSaleRequest(added.sale.id, PaymentMethod.CASH, "manual-hw-ck")
        )
        root.recordCustomerCreditUseCase(
            CustomerDebtRequest("STORE-1", "C-1", Money(250), "manual-hw-credit", "赊账")
        )
        root.receiveCustomerPaymentUseCase(
            CustomerDebtRequest("STORE-1", "C-1", Money(150), "manual-hw-settle", "收款")
        )
        assertEquals(250L - 150L, root.customerDebtQuery.debtOf("C-1"))
        assertEquals(95L, com.smallshoping.app.domain.inventory.StockQuery(root.ledger).stockOf("P-10"))
    }

    @Test
    fun `赊账歧义：追问后报名字重跑`() {
        seedHardware()
        // 「老张」前缀同时命中两人，无精确同名 → 必须追问
        seedCustomerZhang(id = "C-2", name = "老张哥")
        seedCustomerZhang(id = "C-3", name = "老张叔")
        val first = root.orchestrator.handle(root.inputAdapter.fromText("老张赊200"))
        assertTrue(first is OrchestratorReply.NeedsConfirm)
        val ambiguous = root.orchestrator.confirm(
            (first as OrchestratorReply.NeedsConfirm).requestId, true
        )
        assertTrue((ambiguous as OrchestratorReply.Text).text.contains("是哪一个"))

        val resolved = root.orchestrator.handle(root.inputAdapter.fromText("老张哥"))
        assertTrue(resolved is OrchestratorReply.NeedsConfirm)
        val done = root.orchestrator.confirm(
            (resolved as OrchestratorReply.NeedsConfirm).requestId, true
        )
        assertTrue((done as OrchestratorReply.Text).text.contains("已记账"))
        assertEquals(20000L, root.customerDebtQuery.debtOf("C-2"))
        assertEquals(0L, root.customerDebtQuery.debtOf("C-3"))
    }
}

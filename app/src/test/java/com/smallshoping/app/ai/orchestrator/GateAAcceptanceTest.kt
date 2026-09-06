package com.smallshoping.app.ai.orchestrator

import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.inventory.StockQuery
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
 * Gate A 验收（Task 016 / M2）：
 * 1. 六句黄金语句之一「卖两斤土豆」经 AI 路径产生真实可追溯账务；
 * 2. AI 路径与人工路径写入完全相同的 Domain，账务结果一致（V1_IMPLEMENTATION_PLAN §3）；
 * 3. AI 不可用时人工路径不受影响。
 */
class GateAAcceptanceTest {

    private fun newRoot(): CompositionRoot {
        val root = CompositionRoot()
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
                delta = 10,
                idempotencyKey = IdempotencyKey("IN-1"),
                note = "测试入库"
            )
        )
        return root
    }

    @Test
    fun `Gate A：AI 路径与人工路径账务结果完全一致`() {
        val aiRoot = newRoot()
        // —— AI 路径：语音原话 → 意图 → Tool → Risk → Domain ——
        aiRoot.orchestrator.handle(aiRoot.inputAdapter.fromText("卖两斤土豆"))
        val confirm = aiRoot.orchestrator.handle(aiRoot.inputAdapter.fromText("结账"))
        assertTrue(confirm is OrchestratorReply.NeedsConfirm)
        aiRoot.orchestrator.confirm((confirm as OrchestratorReply.NeedsConfirm).requestId, true)

        // —— 人工路径：不经任何 AI 组件，直接调同一 Domain UseCase ——
        val manualRoot = newRoot()
        val added = manualRoot.addSaleItemUseCase(
            AddSaleItemRequest("STORE-1", null, "P-1", Quantity(2, Unit.JIN))
        ) as com.smallshoping.app.domain.sales.AddSaleItemResult.Success
        val checked = manualRoot.checkoutSaleUseCase(
            CheckoutSaleRequest(added.sale.id, PaymentMethod.CASH, "manual-ck-1")
        )
        assertTrue(checked is com.smallshoping.app.domain.sales.CheckoutSaleResult.Success)

        // —— 两条路径的账务事实必须逐项一致 ——
        assertEquals(8L, StockQuery(aiRoot.ledger).stockOf("P-1"))
        assertEquals(8L, StockQuery(manualRoot.ledger).stockOf("P-1"))

        val aiSale = aiRoot.sales.findById(aiRoot.contexts.load("DEVICE-1")!!.activeSaleOrderId!!)!!
        val manualSale = (checked as com.smallshoping.app.domain.sales.CheckoutSaleResult.Success).sale
        assertEquals(SaleStatus.COMPLETED, aiSale.status)
        assertEquals(aiSale.status, manualSale.status)
        assertEquals(aiSale.total, manualSale.total)
        assertEquals(Money(760), aiSale.total)

        val aiStockEntries = aiRoot.ledger.entries(LedgerScope(LedgerScopeType.STOCK, "P-1"))
            .filter { it.movementType == MovementType.SALE_OUT }
        val manualStockEntries = manualRoot.ledger.entries(LedgerScope(LedgerScopeType.STOCK, "P-1"))
            .filter { it.movementType == MovementType.SALE_OUT }
        assertEquals(1, aiStockEntries.size)
        assertEquals(1, manualStockEntries.size)
        assertEquals(aiStockEntries[0].delta, manualStockEntries[0].delta)
        assertEquals(aiStockEntries[0].movementType, manualStockEntries[0].movementType)
    }

    @Test
    fun `Gate A：AI 不可用时人工路径不受影响`() {
        val root = newRoot()
        // 完全绕开编排器与 Provider：Domain 直接可营业
        val added = root.addSaleItemUseCase(
            AddSaleItemRequest("STORE-1", null, "P-1", Quantity(2, Unit.JIN))
        ) as com.smallshoping.app.domain.sales.AddSaleItemResult.Success
        val checked = root.checkoutSaleUseCase(
            CheckoutSaleRequest(added.sale.id, PaymentMethod.CASH, "offline-ck-1")
        )
        assertTrue(checked is com.smallshoping.app.domain.sales.CheckoutSaleResult.Success)
        assertEquals(8L, StockQuery(root.ledger).stockOf("P-1"))
    }

    @Test
    fun `Gate A：黄金语句在本地解析器下稳定映射`() {
        val root = newRoot()
        // veg-000 卖两斤土豆 → add_sale_item
        val r1 = root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        assertTrue(r1 is OrchestratorReply.Text)
        // veg-005 今天卖了多少钱 → get_today_sales（无销售时 0 笔）
        val r2 = root.orchestrator.handle(root.inputAdapter.fromText("今天卖了多少钱"))
        assertTrue(r2 is OrchestratorReply.Text)
        assertTrue((r2 as OrchestratorReply.Text).text.contains("0"))
    }
}

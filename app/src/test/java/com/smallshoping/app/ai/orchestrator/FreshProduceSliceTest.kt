package com.smallshoping.app.ai.orchestrator

import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.inventory.RecordLossRequest
import com.smallshoping.app.domain.inventory.StockQuery
import com.smallshoping.app.domain.purchase.PurchaseInRequest
import com.smallshoping.app.domain.sales.AddSaleItemRequest
import com.smallshoping.app.domain.sales.CheckoutSaleRequest
import com.smallshoping.app.domain.sales.PaymentMethod
import com.smallshoping.app.domain.sales.SaleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 033 验收：生鲜完整 Slice（采购→称重销售→结账→损耗→汇总）
 * 全链路经 AI 路径，账务事实逐项可追溯；人工兜底路径产生相同事实。
 */
class FreshProduceSliceTest {

    private val root = CompositionRoot()
    private val stock = StockQuery(root.ledger)

    private fun seedPotato() {
        root.products.saveProduct(
            Product(
                id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
                saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
                currentSalePrice = Money(380), currentCostPrice = null
            )
        )
    }

    @Test
    fun `黄金闭环：进100斤 → 卖2点36斤 → 结账 → 损耗半斤 → 汇总`() {
        seedPotato()
        // 1) 采购
        val purchase = root.orchestrator.handle(root.inputAdapter.fromText("进100斤土豆，成本2块8"))
        assertTrue((purchase as OrchestratorReply.Text).text.contains("已入库"))
        assertEquals(50000L, stock.stockOf("P-1"))

        // 2) 称重销售（2.36 斤 = 1180 克，有理数精确）
        val add = root.orchestrator.handle(root.inputAdapter.fromText("卖2.36斤土豆"))
        assertTrue((add as OrchestratorReply.Text).text.contains("已加入"))

        // 3) 结账
        val checkout = root.orchestrator.handle(root.inputAdapter.fromText("结账"))
        assertTrue(checkout is OrchestratorReply.NeedsConfirm)
        val done = root.orchestrator.confirm(
            (checkout as OrchestratorReply.NeedsConfirm).requestId, true
        )
        assertTrue((done as OrchestratorReply.Text).text.contains("结账完成"))

        // 4) 损耗半斤（250 克），MEDIUM 需确认
        val loss = root.orchestrator.handle(root.inputAdapter.fromText("损耗半斤土豆"))
        assertTrue(loss is OrchestratorReply.NeedsConfirm)
        val lossDone = root.orchestrator.confirm(
            (loss as OrchestratorReply.NeedsConfirm).requestId, true
        )
        assertTrue((lossDone as OrchestratorReply.Text).text.contains("已记录损耗"))

        // 5) 汇总：销售额 380×1180/500 = 897 分
        val summary = root.orchestrator.handle(root.inputAdapter.fromText("今天卖了多少钱"))
        assertTrue((summary as OrchestratorReply.Text).text.contains("897"))

        // —— 账务事实闭环 ——
        // 库存：50000 - 1180 - 250 = 48570 克（97.14 斤）
        assertEquals(48570L, stock.stockOf("P-1"))
        // 销售单 COMPLETED，总额 897
        val sale = root.sales.allSales().first { it.status == SaleStatus.COMPLETED }
        assertEquals(SaleStatus.COMPLETED, sale.status)
        assertEquals(Money(897), sale.total)
        // 损耗记录 1 条，成本快照 280×250/500 = 140 分
        assertEquals(1, root.losses.all().size)
        assertEquals(140L, root.losses.all()[0].costAmountMinor)
        // 成本已被进货加权平均更新为 280 分/斤
        assertEquals(Money(280), root.products.findProductById("P-1")!!.currentCostPrice)
    }

    @Test
    fun `人工兜底路径：与 AI 路径产生相同业务事实（Gate A）`() {
        seedPotato()
        // —— 完全不经 AI：直接调同一批 Domain UseCase ——
        root.purchaseInUseCase(
            PurchaseInRequest("STORE-1", "P-1", Quantity(50000, Unit.GRAM), Money(280), "manual-p")
        )
        val added = root.addSaleItemUseCase(
            AddSaleItemRequest("STORE-1", null, "P-1", Quantity(1180, Unit.GRAM))
        ) as com.smallshoping.app.domain.sales.AddSaleItemResult.Success
        val checked = root.checkoutSaleUseCase(
            CheckoutSaleRequest(added.sale.id, PaymentMethod.CASH, "manual-ck")
        )
        assertTrue(checked is com.smallshoping.app.domain.sales.CheckoutSaleResult.Success)
        root.recordLossUseCase(
            RecordLossRequest("P-1", Quantity(250, Unit.GRAM), "坏了", "manual-loss")
        )

        // 与 AI 路径的账务事实逐项一致
        assertEquals(48570L, stock.stockOf("P-1"))
        val sale = (checked as com.smallshoping.app.domain.sales.CheckoutSaleResult.Success).sale
        assertEquals(Money(897), sale.total)
        assertEquals(1, root.losses.all().size)
        assertEquals(140L, root.losses.all()[0].costAmountMinor)
    }

    @Test
    fun `损耗歧义：追问后报名字，确认后入账`() {
        seedPotato()
        root.products.saveProduct(
            Product(
                id = "P-2", storeId = "STORE-1", name = "土豆片", normalizedName = normalize("土豆片"),
                saleUnit = Unit.PIECE, purchaseUnit = Unit.PIECE,
                currentSalePrice = Money(500), currentCostPrice = null
            )
        )
        root.ledger.append(
            com.smallshoping.app.domain.ledger.LedgerEntry(
                scope = com.smallshoping.app.domain.ledger.LedgerScope(
                    com.smallshoping.app.domain.ledger.LedgerScopeType.STOCK, "P-1"
                ),
                movementType = com.smallshoping.app.domain.ledger.MovementType.PURCHASE_IN,
                delta = 5000,
                idempotencyKey = com.smallshoping.app.domain.ledger.IdempotencyKey("IN-033"),
                note = "测试入库"
            )
        )
        val first = root.orchestrator.handle(root.inputAdapter.fromText("损耗半斤土"))
        assertTrue(first is OrchestratorReply.NeedsConfirm)
        val ambiguous = root.orchestrator.confirm(
            (first as OrchestratorReply.NeedsConfirm).requestId, true
        )
        assertTrue((ambiguous as OrchestratorReply.Text).text.contains("是哪一个"))

        val resolved = root.orchestrator.handle(root.inputAdapter.fromText("土豆"))
        assertTrue(resolved is OrchestratorReply.NeedsConfirm)
        val done = root.orchestrator.confirm(
            (resolved as OrchestratorReply.NeedsConfirm).requestId, true
        )
        assertTrue((done as OrchestratorReply.Text).text.contains("已记录损耗"))
        assertEquals(4750L, stock.stockOf("P-1"))
    }
}

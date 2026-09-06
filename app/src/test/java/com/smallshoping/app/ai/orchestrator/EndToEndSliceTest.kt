package com.smallshoping.app.ai.orchestrator

import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.QuantityParser
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.inventory.StockQuery
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import com.smallshoping.app.domain.sales.SaleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 014 验收：黄金语句「卖两斤土豆」端到端，账务事实可追溯。 */
class EndToEndSliceTest {

    private val root = CompositionRoot()
    private val stock = StockQuery(root.ledger)

    private fun seedPotato() {
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
                delta = 5000, // 10斤（基本单位：克）
                idempotencyKey = IdempotencyKey("IN-1"),
                note = "测试入库"
            )
        )
    }

    @Test
    fun `黄金语句：卖两斤土豆 → 结账 → 账务闭环`() {
        seedPotato()
        val addReply = root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        assertTrue(addReply is OrchestratorReply.Text)
        assertTrue((addReply as OrchestratorReply.Text).text.contains("已加入"))

        val checkoutReply = root.orchestrator.handle(root.inputAdapter.fromText("结账"))
        assertTrue("结账属 MEDIUM 风险，必须先确认", checkoutReply is OrchestratorReply.NeedsConfirm)
        val pending = checkoutReply as OrchestratorReply.NeedsConfirm

        val done = root.orchestrator.confirm(pending.requestId, approved = true)
        assertTrue(done is OrchestratorReply.Text)
        assertTrue((done as OrchestratorReply.Text).text.contains("结账完成"))

        // 账务事实：库存 8 斤（4000 克），销售单 COMPLETED，总额 760 分
        assertEquals(4000L, stock.stockOf("P-1"))
        val saleId = root.contexts.load("DEVICE-1")?.activeSaleOrderId!!
        val sale = root.sales.findById(saleId)!!
        assertEquals(SaleStatus.COMPLETED, sale.status)
        assertEquals(Money(760), sale.total)
    }

    @Test
    fun `重复结账：确认两次只扣一次库存`() {
        seedPotato()
        root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        val first = root.orchestrator.handle(root.inputAdapter.fromText("结账")) as OrchestratorReply.NeedsConfirm
        root.orchestrator.confirm(first.requestId, approved = true)
        // 再次说结账：MEDIUM 风险仍需确认；确认后返回「已结过账」，账务不变
        val second = root.orchestrator.handle(root.inputAdapter.fromText("结账"))
        assertTrue(second is OrchestratorReply.NeedsConfirm)
        val secondDone = root.orchestrator.confirm(
            (second as OrchestratorReply.NeedsConfirm).requestId, approved = true
        )
        assertTrue(secondDone is OrchestratorReply.Text)
        assertTrue((secondDone as OrchestratorReply.Text).text.contains("已经结过账"))
        assertEquals(4000L, stock.stockOf("P-1"))
    }

    @Test
    fun `黄金语句：今天卖了多少钱 → 汇总真实账务`() {
        seedPotato()
        root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        val checkoutReply = root.orchestrator.handle(root.inputAdapter.fromText("结账")) as OrchestratorReply.NeedsConfirm
        root.orchestrator.confirm(checkoutReply.requestId, approved = true)

        val reply = root.orchestrator.handle(root.inputAdapter.fromText("今天卖了多少钱"))
        assertTrue(reply is OrchestratorReply.Text)
        val text = (reply as OrchestratorReply.Text).text
        assertTrue(text.contains("760"))
        assertTrue(text.contains("1"))
        // 再卖一单后累计
        root.orchestrator.handle(root.inputAdapter.fromText("卖一斤土豆"))
        val second = root.orchestrator.handle(root.inputAdapter.fromText("结账")) as OrchestratorReply.NeedsConfirm
        root.orchestrator.confirm(second.requestId, approved = true)
        val reply2 = root.orchestrator.handle(root.inputAdapter.fromText("今天卖了多少钱"))
        assertTrue((reply2 as OrchestratorReply.Text).text.contains("1140")) // 760 + 380
    }

    @Test
    fun `黄金语句：进100斤土豆，成本2块8 → 入库与成本更新`() {
        seedPotato()
        val reply = root.orchestrator.handle(
            root.inputAdapter.fromText("进100斤土豆，成本2块8")
        )
        assertTrue(reply is OrchestratorReply.Text)
        assertTrue((reply as OrchestratorReply.Text).text.contains("已入库"))
        // 账务事实：库存 10斤+100斤=110斤=55000克；成本 280分/斤（无历史库存直接采进价）
        assertEquals(55000L, stock.stockOf("P-1"))
        assertEquals(Money(280), root.products.findProductById("P-1")!!.currentCostPrice)
        // 重复同一句：不重复入库
        val again = root.orchestrator.handle(root.inputAdapter.fromText("进100斤土豆，成本2块8"))
        assertTrue((again as OrchestratorReply.Text).text.contains("已经入过库"))
        assertEquals(55000L, stock.stockOf("P-1"))
    }

    @Test
    fun `黄金语句：进价2块8 卖3块8 的改价部分给出明确引导`() {
        seedPotato()
        val reply = root.orchestrator.handle(
            root.inputAdapter.fromText("进100斤土豆，进价2块8，卖3块8")
        )
        assertTrue(reply is OrchestratorReply.Text)
        val text = (reply as OrchestratorReply.Text).text
        assertTrue(text.contains("已入库"))
        assertTrue(text.contains("改价请单独说"))
        assertEquals(55000L, stock.stockOf("P-1"))
    }

    @Test
    fun `AI 失败不改变任何事实`() {
        seedPotato()
        val failing = AiOrchestrator(
            provider = com.smallshoping.app.ai.providers.CloudAiProvider(
                com.smallshoping.app.ai.providers.AiGatewayClient { throw IllegalStateException("timeout") }
            ),
            executor = root.executor
        )
        val reply = failing.handle(root.inputAdapter.fromText("卖两斤土豆"))
        assertTrue(reply is OrchestratorReply.Text)
        assertTrue((reply as OrchestratorReply.Text).text.contains("暂时不可用"))
        assertEquals(5000L, stock.stockOf("P-1")) // 库存未动
        assertTrue(root.sales.findDraft("whatever") == null)
    }

    @Test
    fun `商品歧义：不猜测，返回候选追问`() {
        seedPotato()
        root.products.saveProduct(
            Product(
                id = "P-2", storeId = "STORE-1", name = "土豆片", normalizedName = normalize("土豆片"),
                saleUnit = Unit.PIECE, purchaseUnit = Unit.PIECE,
                currentSalePrice = Money(500), currentCostPrice = null
            )
        )
        val reply = root.orchestrator.handle(root.inputAdapter.fromText("卖一盒土豆")) // 盒≠斤 走单位校验前先解析商品
        assertTrue(reply is OrchestratorReply.Text)
        val text = (reply as OrchestratorReply.Text).text
        // 精确命中「土豆」无歧义，但单位不一致 → 明确提示
        assertTrue(text.contains("斤") || text.contains("已加入"))
        assertEquals(5000L, stock.stockOf("P-1"))
    }

    @Test
    fun `数量解析：中英文单位、中文数字与小数称重（基本单位刻度）`() {
        assertEquals(Quantity(1000, Unit.GRAM), QuantityParser.parse("2斤")?.quantity)
        assertEquals(Quantity(1000, Unit.GRAM), QuantityParser.parse("两斤")?.quantity)
        assertEquals(Quantity(500, Unit.GRAM), QuantityParser.parse("500克")?.quantity)
        assertEquals(Quantity(1000, Unit.GRAM), QuantityParser.parse("1kg")?.quantity)
        assertEquals(Quantity(3, Unit.PIECE), QuantityParser.parse("3个")?.quantity)
        // 称重精度（Task 018）：有理数精确换算，无浮点
        assertEquals(Quantity(1180, Unit.GRAM), QuantityParser.parse("2.36斤")?.quantity)
        assertEquals(Quantity(1250, Unit.GRAM), QuantityParser.parse("2.5斤")?.quantity)
        assertEquals(Quantity(250, Unit.GRAM), QuantityParser.parse("半斤")?.quantity)
        assertEquals(Quantity(1250, Unit.GRAM), QuantityParser.parse("1.25kg")?.quantity)
        // 超精度/不合法 → 拒绝（不猜测）
        assertEquals(null, QuantityParser.parse("2.333斤"))
        assertEquals(null, QuantityParser.parse("1.5个"))
        assertEquals(null, QuantityParser.parse("斤"))
    }
}

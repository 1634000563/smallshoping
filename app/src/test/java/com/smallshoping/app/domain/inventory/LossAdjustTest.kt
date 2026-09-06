package com.smallshoping.app.domain.inventory

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.data.ledger.InMemoryLedger
import com.smallshoping.app.data.repository.InMemoryLossRepository
import com.smallshoping.app.data.repository.InMemoryProductRepository
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 031 验收：损耗与盘点调整全部走库存流水，余额可由流水重建。 */
class LossAdjustTest {

    private val products = InMemoryProductRepository()
    private val ledger = InMemoryLedger()
    private val losses = InMemoryLossRepository(ledger)
    private val stock = StockQuery(ledger)
    private val recordLoss = RecordLossUseCase(losses, products, stock)
    private val adjust = AdjustStockUseCase(ledger, products, stock)

    private fun seedPotato(stockGrams: Long = 5000) {
        products.saveProduct(
            Product(
                id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
                saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
                currentSalePrice = Money(380), currentCostPrice = Money(280)
            )
        )
        ledger.append(
            LedgerEntry(
                scope = LedgerScope(LedgerScopeType.STOCK, "P-1"),
                movementType = MovementType.PURCHASE_IN,
                delta = stockGrams,
                idempotencyKey = com.smallshoping.app.domain.ledger.IdempotencyKey("IN-LOSS-1"),
                note = "测试入库"
            )
        )
    }

    @Test
    fun `损耗正常路径：流水 LOSS_OUT + 损耗单成本快照`() {
        seedPotato()
        val result = recordLoss(
            RecordLossRequest("P-1", Quantity(1000, Unit.GRAM), "坏了", "loss:L1")
        )
        assertTrue(result is RecordLossResult.Success)
        val success = result as RecordLossResult.Success
        assertEquals(4000L, success.stockAfter)
        // 成本快照：280 分/斤 × 1000克 / 500 = 560 分
        assertEquals(560L, success.loss.costAmountMinor)
        // 流水可追溯
        val entries = ledger.entries(LedgerScope(LedgerScopeType.STOCK, "P-1"))
        assertEquals(2, entries.size)
        assertEquals(MovementType.LOSS_OUT, entries[1].movementType)
        assertEquals(-1000L, entries[1].delta)
        assertEquals(1, losses.all().size)
    }

    @Test
    fun `损耗幂等：同键重复不重复扣库存`() {
        seedPotato()
        val request = RecordLossRequest("P-1", Quantity(1000, Unit.GRAM), "坏了", "loss:L1")
        recordLoss(request)
        val again = recordLoss(request)
        assertTrue(again is RecordLossResult.AlreadyCompleted)
        assertEquals(4000L, stock.stockOf("P-1"))
        assertEquals(1, losses.all().size)
    }

    @Test
    fun `损耗边界：超过库存拒绝、单位不符拒绝、商品不存在`() {
        seedPotato(stockGrams = 1000)
        val over = recordLoss(RecordLossRequest("P-1", Quantity(1500, Unit.GRAM), "坏了", "loss:L2"))
        assertTrue(over is RecordLossResult.InsufficientStock)
        assertEquals(1000L, stock.stockOf("P-1"))

        val unitBad = recordLoss(RecordLossRequest("P-1", Quantity(1, Unit.PIECE), "坏了", "loss:L3"))
        assertTrue(unitBad is RecordLossResult.UnitMismatch)

        val missing = recordLoss(RecordLossRequest("P-404", Quantity(1, Unit.GRAM), "坏了", "loss:L4"))
        assertTrue(missing is RecordLossResult.ProductNotFound)
    }

    @Test
    fun `盘点调增：目标大于当前走 ADJUST_IN`() {
        seedPotato(stockGrams = 1000)
        // 实际有 3000 克：差异 +2000
        val result = adjust(
            AdjustStockRequest("P-1", Quantity(3000, Unit.GRAM), "盘点", "adj:A1")
        )
        assertTrue(result is AdjustStockResult.Success)
        val success = result as AdjustStockResult.Success
        assertEquals(2000L, success.delta)
        assertEquals(3000L, stock.stockOf("P-1"))
        val entries = ledger.entries(LedgerScope(LedgerScopeType.STOCK, "P-1"))
        assertEquals(MovementType.ADJUST_IN, entries.last().movementType)
    }

    @Test
    fun `盘点调减：目标小于当前走 ADJUST_OUT，默认禁负库存`() {
        seedPotato(stockGrams = 5000)
        val result = adjust(
            AdjustStockRequest("P-1", Quantity(4500, Unit.GRAM), "盘点", "adj:A2")
        )
        assertTrue(result is AdjustStockResult.Success)
        assertEquals(-500L, (result as AdjustStockResult.Success).delta)
        assertEquals(MovementType.ADJUST_OUT, ledger.entries(LedgerScope(LedgerScopeType.STOCK, "P-1")).last().movementType)

        val over = adjust(
            AdjustStockRequest("P-1", Quantity(-100, Unit.GRAM), "盘点", "adj:A3")
        )
        assertTrue(over is AdjustStockResult.InsufficientStock)
        assertEquals(4500L, stock.stockOf("P-1"))
    }

    @Test
    fun `盘点零差与幂等：不产生流水`() {
        seedPotato(stockGrams = 5000)
        val unchanged = adjust(
            AdjustStockRequest("P-1", Quantity(5000, Unit.GRAM), "盘点", "adj:A4")
        )
        assertTrue(unchanged is AdjustStockResult.Unchanged)
        assertEquals(1, ledger.entries(LedgerScope(LedgerScopeType.STOCK, "P-1")).size)

        val first = adjust(
            AdjustStockRequest("P-1", Quantity(5500, Unit.GRAM), "盘点", "adj:A5")
        )
        assertTrue(first is AdjustStockResult.Success)
        val again = adjust(
            AdjustStockRequest("P-1", Quantity(5500, Unit.GRAM), "盘点", "adj:A5")
        )
        // 目标已达：状态幂等返回 Unchanged，不产生新流水
        assertTrue(again is AdjustStockResult.Unchanged)
        assertEquals(2, ledger.entries(LedgerScope(LedgerScopeType.STOCK, "P-1")).size)
        assertEquals(5500L, stock.stockOf("P-1"))
    }
}

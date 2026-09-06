package com.smallshoping.app.feature.home

import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 041：极简主界面状态流（纯 JVM，UI→ViewModel→编排器→Domain 唯一链路）。 */
class HomeViewModelTest {

    private val root = CompositionRoot()
    private val viewModel = HomeViewModel(root)

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
                delta = 5000,
                idempotencyKey = IdempotencyKey("IN-041"),
                note = "测试入库"
            )
        )
    }

    @Test
    fun `输入商品：回复与当前任务同步更新`() {
        seedPotato()
        val state = viewModel.handleInput("卖两斤土豆")
        assertTrue(state.reply.contains("已加入"))
        assertTrue(state.currentTask.contains("土豆"))
        assertTrue(state.currentTask.contains("760 分"))
        assertEquals(null, state.pendingConfirmId)
    }

    @Test
    fun `结账需要确认：确认后任务清空、账务落账`() {
        seedPotato()
        viewModel.handleInput("卖两斤土豆")
        val pendingState = viewModel.handleInput("结账")
        assertTrue(pendingState.pendingConfirmId != null)

        val done = viewModel.confirm(approved = true)
        assertTrue(done.reply.contains("结账完成"))
        assertTrue(done.pendingConfirmId == null)
        assertTrue(done.currentTask.contains("没有待结账"))
        assertEquals(4000L, com.smallshoping.app.domain.inventory.StockQuery(root.ledger).stockOf("P-1"))
    }

    @Test
    fun `拒绝确认：草稿单保留、库存不动`() {
        seedPotato()
        viewModel.handleInput("卖两斤土豆")
        val pendingState = viewModel.handleInput("结账")
        assertTrue(pendingState.pendingConfirmId != null)

        val rejected = viewModel.confirm(approved = false)
        assertTrue(rejected.reply.contains("已取消"))
        // 草稿单还在，库存未扣
        assertTrue(rejected.currentTask.contains("土豆"))
        assertEquals(5000L, com.smallshoping.app.domain.inventory.StockQuery(root.ledger).stockOf("P-1"))
    }

    @Test
    fun `空输入与无待确认操作：友好提示`() {
        val blank = viewModel.handleInput("  ")
        assertTrue(blank.reply.contains("请输入"))
        val noPending = viewModel.confirm(approved = true)
        assertTrue(noPending.reply.contains("没有待确认"))
    }
}

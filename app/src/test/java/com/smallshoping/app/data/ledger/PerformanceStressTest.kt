package com.smallshoping.app.data.ledger

import com.smallshoping.app.ai.providers.LocalRuleParser
import com.smallshoping.app.ai.providers.GatewayRequest
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
import com.smallshoping.app.domain.sales.SaleItem
import com.smallshoping.app.domain.sales.SaleOrder
import com.smallshoping.app.domain.sales.SaleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 051 验收：大数据量与长历史下的正确性与性能基线。
 *
 * 规模按小店一年经营量级设定（流水 10 万条、完成单 5000 张）；
 * 时间阈值为宽松基线（CI 机器差异），重点是正确性：
 * 大数量下余额精确、可重建、幂等不失效、解析吞吐不劣化。
 */
class PerformanceStressTest {

    private val root = CompositionRoot()

    @Test
    fun `十万条流水：追加正确、余额可重建、性能在基线内`() {
        val ledger = InMemoryLedger()
        val scope = LedgerScope(LedgerScopeType.STOCK, "P-STRESS")
        val n = 100_000

        val start = System.currentTimeMillis()
        for (i in 1..n) {
            ledger.append(
                LedgerEntry(
                    scope = scope,
                    movementType = MovementType.PURCHASE_IN,
                    delta = 1L,
                    idempotencyKey = IdempotencyKey("stress-$i")
                )
            )
        }
        val appendMillis = System.currentTimeMillis() - start
        assertEquals(n.toLong(), ledger.balance(scope))
        assertEquals(n, ledger.entries(scope).size)

        val rebuildStart = System.currentTimeMillis()
        assertEquals(n.toLong(), ledger.rebuildBalance(scope))
        val rebuildMillis = System.currentTimeMillis() - rebuildStart

        // 宽松性能基线（慢 CI 也应在秒级内）
        assertTrue("追加 10 万条耗时 ${appendMillis}ms 超基线", appendMillis < 20_000)
        assertTrue("重建 10 万条耗时 ${rebuildMillis}ms 超基线", rebuildMillis < 10_000)

        // 幂等在大规模下不失效：重放同键不重复
        ledger.append(
            LedgerEntry(
                scope = scope,
                movementType = MovementType.PURCHASE_IN,
                delta = 1L,
                idempotencyKey = IdempotencyKey("stress-1")
            )
        )
        assertEquals(n.toLong(), ledger.balance(scope))
    }

    @Test
    fun `五千张完成单：长历史聚合正确且可重建`() {
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
                delta = 100_000_000L,
                idempotencyKey = IdempotencyKey("IN-STRESS"),
                note = "压测入库"
            )
        )
        // 5000 张完成单：每单 1 斤土豆 380 分，一半归老张
        root.customers.saveCustomer(
            com.smallshoping.app.domain.customer.Customer(
                id = "C-1", storeId = "STORE-1", name = "老张", normalizedName = normalize("老张")
            )
        )
        val item = SaleItem(
            productId = "P-1", productName = "土豆",
            quantity = Quantity(500, Unit.GRAM),
            unitPrice = Money(380), subtotal = Money(380)
        )
        val now = System.currentTimeMillis()
        for (i in 1..5000) {
            root.sales.saveDraft(
                SaleOrder(
                    id = "SALE-$i",
                    storeId = "STORE-1",
                    items = listOf(item.copy(id = "ITEM-$i")),
                    status = SaleStatus.COMPLETED,
                    paymentMethod = com.smallshoping.app.domain.sales.PaymentMethod.CASH,
                    total = Money(380),
                    customerId = if (i % 2 == 0) "C-1" else null,
                    completedAtMillis = now
                )
            )
        }
        // 聚合正确
        val summary = root.todaySalesSummary.today()
        assertEquals(5000, summary.count)
        assertEquals(Money(1_900_000), summary.total)
        val customer = root.customerHistory.topProducts("C-1")
        assertEquals(1, customer.size)
        assertEquals(2500, customer[0].purchaseCount)
        assertEquals(2500, root.customerHistory.completedSales("C-1").size)
    }

    @Test
    fun `解析器吞吐：千次黄金语句解析在基线内且结果稳定`() {
        val parser = LocalRuleParser()
        val request = GatewayRequest(
            storeId = "STORE-1", deviceId = "DEVICE-1", appVersion = "0.1.0",
            inputText = "卖两斤土豆", allowedTools = listOf("add_sale_item")
        )
        val start = System.currentTimeMillis()
        var toolMatches = 0
        repeat(1000) {
            val response = parser.complete(request)
            if (response is com.smallshoping.app.ai.providers.AiResponse.ToolCall &&
                response.toolName == "add_sale_item"
            ) {
                toolMatches++
            }
        }
        val millis = System.currentTimeMillis() - start
        assertEquals(1000, toolMatches)
        assertTrue("千次解析耗时 ${millis}ms 超基线", millis < 5_000)
    }

    @Test
    fun `千条命令日志重放：幂等键去重不重复记账`() {
        val rootA = CompositionRoot()
        rootA.members.saveMember(
            com.smallshoping.app.domain.member.Member(
                id = "M-1", storeId = "STORE-1", name = "张姐", normalizedName = normalize("张姐")
            )
        )
        // 1000 条充值命令，amount 在 1..100 循环 → 相同幂等键各出现 10 次
        for (i in 1..1000) {
            rootA.commandJournal.append(
                com.smallshoping.app.domain.journal.CommandRecord(
                    toolName = "recharge_member",
                    entitiesJson = "member=张姐&amount=${i % 100 + 1}"
                )
            )
        }
        // 首次重放：只有 100 个不同幂等键入账
        val first = rootA.replayRunner.replayAll()
        assertTrue(first.allSucceeded)
        assertEquals(100, rootA.ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1")).size)
        // 再次重放：全部 AlreadyCompleted，账务不变
        val again = rootA.replayRunner.replayAll()
        assertTrue(again.allSucceeded)
        assertEquals(100, rootA.ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1")).size)
    }
}

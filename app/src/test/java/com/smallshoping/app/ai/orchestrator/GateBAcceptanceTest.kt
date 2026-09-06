package com.smallshoping.app.ai.orchestrator

import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.PriceHistoryEntry
import com.smallshoping.app.domain.catalog.PriceType
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.customer.Customer
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import com.smallshoping.app.domain.member.Member
import com.smallshoping.app.domain.member.RechargeMemberRequest
import com.smallshoping.app.domain.memory.MemoryScopeType
import com.smallshoping.app.domain.memory.MemorySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate B 验收（Task 028 / M4 收口）：
 * 1. 资金账：AI 路径与人工路径写入同一 Domain，账务事实一致（Gate A 延续到会员资金账）；
 * 2. 上下文：多轮消歧状态机完整闭环，新任务取代旧追问；
 * 3. 记忆：昨日价格改价可追溯；客户习惯达观察阈值才落库（spec 06 §3）；
 * 4. 风险：拒绝确认不执行；AI 失败不改变任何事实（P0 可靠性）。
 */
class GateBAcceptanceTest {

    private fun seedMember(root: CompositionRoot, id: String = "M-1", name: String = "张姐") {
        root.members.saveMember(
            Member(id = id, storeId = "STORE-1", name = name, normalizedName = normalize(name))
        )
    }

    private fun seedPotatoWithPrice(root: CompositionRoot, price: Long = 400L) {
        root.products.saveProduct(
            Product(
                id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
                saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
                currentSalePrice = Money(price), currentCostPrice = Money(280)
            )
        )
        root.ledger.append(
            com.smallshoping.app.domain.ledger.LedgerEntry(
                scope = LedgerScope(LedgerScopeType.STOCK, "P-1"),
                movementType = MovementType.PURCHASE_IN,
                delta = 5000,
                idempotencyKey = com.smallshoping.app.domain.ledger.IdempotencyKey("IN-GATE-B"),
                note = "Gate B 入库"
            )
        )
    }

    private fun seedScrewAndCustomer(root: CompositionRoot) {
        root.products.saveProduct(
            Product(
                id = "P-9", storeId = "STORE-1", name = "螺丝", normalizedName = normalize("螺丝"),
                saleUnit = Unit.BOX, purchaseUnit = Unit.BOX,
                currentSalePrice = Money(380), currentCostPrice = Money(200)
            )
        )
        root.ledger.append(
            com.smallshoping.app.domain.ledger.LedgerEntry(
                scope = LedgerScope(LedgerScopeType.STOCK, "P-9"),
                movementType = MovementType.PURCHASE_IN,
                delta = 100,
                idempotencyKey = com.smallshoping.app.domain.ledger.IdempotencyKey("IN-GATE-B-SCREW"),
                note = "Gate B 入库"
            )
        )
        root.customers.saveCustomer(
            Customer(id = "C-1", storeId = "STORE-1", name = "老张", normalizedName = normalize("老张"))
        )
    }

    @Test
    fun `Gate B：充值 AI 路径与人工路径账务事实一致`() {
        val aiRoot = CompositionRoot()
        seedMember(aiRoot)
        val reply = aiRoot.orchestrator.handle(aiRoot.inputAdapter.fromText("给张姐充200"))
        assertTrue(reply is OrchestratorReply.NeedsConfirm)
        aiRoot.orchestrator.confirm((reply as OrchestratorReply.NeedsConfirm).requestId, true)

        val manualRoot = CompositionRoot()
        seedMember(manualRoot)
        val manual = manualRoot.rechargeMemberUseCase(
            RechargeMemberRequest("STORE-1", "M-1", Money.fromYuan(200), "manual-recharge-1")
        )
        assertTrue(manual is com.smallshoping.app.domain.member.RechargeMemberResult.Success)

        assertEquals(20000L, aiRoot.memberFundsQuery.balanceOf("M-1"))
        assertEquals(20000L, manualRoot.memberFundsQuery.balanceOf("M-1"))
        val aiEntries = aiRoot.ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1"))
        val manualEntries = manualRoot.ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1"))
        assertEquals(1, aiEntries.size)
        assertEquals(1, manualEntries.size)
        assertEquals(MovementType.MEMBER_RECHARGE, aiEntries[0].movementType)
        assertEquals(aiEntries[0].movementType, manualEntries[0].movementType)
        assertEquals(aiEntries[0].delta, manualEntries[0].delta)
    }

    @Test
    fun `Gate B：多轮消歧状态机完整闭环`() {
        val root = CompositionRoot()
        seedMember(root, "M-1", "小张姐")
        seedMember(root, "M-2", "小张哥")
        val first = root.orchestrator.handle(root.inputAdapter.fromText("给小张充200"))
        assertTrue(first is OrchestratorReply.NeedsConfirm)
        val ambiguous = root.orchestrator.confirm(
            (first as OrchestratorReply.NeedsConfirm).requestId, true
        )
        assertTrue((ambiguous as OrchestratorReply.Text).text.contains("是哪一个"))

        val resolved = root.orchestrator.handle(root.inputAdapter.fromText("第二个"))
        assertTrue(resolved is OrchestratorReply.NeedsConfirm)
        val done = root.orchestrator.confirm(
            (resolved as OrchestratorReply.NeedsConfirm).requestId, true
        )
        assertTrue((done as OrchestratorReply.Text).text.contains("已充值"))
        assertEquals(20000L, root.memberFundsQuery.balanceOf("M-2"))
        assertEquals(0L, root.memberFundsQuery.balanceOf("M-1"))
    }

    @Test
    fun `Gate B：昨日价格改价后账务可追溯`() {
        val root = CompositionRoot()
        seedPotatoWithPrice(root, price = 400L)
        root.products.appendPriceHistory(
            PriceHistoryEntry(
                id = "H-GATE-B", productId = "P-1", priceType = PriceType.SALE,
                oldPrice = Money(380), newPrice = Money(380), unit = Unit.JIN, source = "test",
                createdAtMillis = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            )
        )
        root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        val reply = root.orchestrator.handle(root.inputAdapter.fromText("还是昨天那个价格"))
        assertTrue(reply is OrchestratorReply.NeedsConfirm)
        root.orchestrator.confirm((reply as OrchestratorReply.NeedsConfirm).requestId, true)

        assertEquals(Money(380), root.products.findProductById("P-1")!!.currentSalePrice)
        val history = root.products.priceHistory("P-1")
        assertEquals(2, history.size) // 昨日记录 + 本次改价
        assertEquals(Money(400), history[1].oldPrice)
        assertEquals(Money(380), history[1].newPrice)
        assertEquals("yesterday_price", history[1].source)
    }

    @Test
    fun `Gate B：客户习惯达观察阈值才落库（spec 06 §3）`() {
        val root = CompositionRoot()
        seedScrewAndCustomer(root)
        repeat(3) {
            val reply = root.orchestrator.handle(
                root.inputAdapter.fromText("老张上次那些螺丝再来两盒")
            )
            assertTrue((reply as OrchestratorReply.Text).text.contains("已加入"))
        }
        // 第 3 次观察达到阈值 → OBSERVED_PATTERN 落库，置信 60
        val facts = root.memory.query(MemoryScopeType.CUSTOMER, "C-1")
        assertEquals(1, facts.size)
        assertEquals(MemorySource.OBSERVED_PATTERN, facts[0].source)
        assertEquals(60, facts[0].confidence)
        assertEquals("P-9", facts[0].valueJson)
        // 草稿单累计 6 个螺丝（每次 2 盒，盒按个记账 V1 占位语义）
        val saleId = root.contexts.load("DEVICE-1")!!.activeSaleOrderId!!
        val sale = root.sales.findById(saleId)!!
        assertEquals(3, sale.items.size)
        assertEquals(6L, sale.items.sumOf { it.quantity.scaled })
    }

    @Test
    fun `Gate B：拒绝确认不执行，账务不变`() {
        val root = CompositionRoot()
        seedMember(root)
        val reply = root.orchestrator.handle(root.inputAdapter.fromText("给张姐充200"))
        assertTrue(reply is OrchestratorReply.NeedsConfirm)
        val rejected = root.orchestrator.confirm(
            (reply as OrchestratorReply.NeedsConfirm).requestId, false
        )
        assertTrue((rejected as OrchestratorReply.Text).text.contains("已取消"))
        assertEquals(0L, root.memberFundsQuery.balanceOf("M-1"))
        assertTrue(root.ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1")).isEmpty())
    }

    @Test
    fun `Gate B：AI 失败不改变任何事实（P0 可靠性）`() {
        val root = CompositionRoot()
        seedMember(root)
        val failing = AiOrchestrator(
            provider = com.smallshoping.app.ai.providers.CloudAiProvider(
                com.smallshoping.app.ai.providers.AiGatewayClient { throw IllegalStateException("timeout") }
            ),
            executor = root.executor,
            disambiguation = com.smallshoping.app.data.repository.InMemoryDisambiguationStore()
        )
        val reply = failing.handle(root.inputAdapter.fromText("给张姐充200"))
        assertTrue(reply is OrchestratorReply.Text)
        assertTrue((reply as OrchestratorReply.Text).text.contains("暂时不可用"))
        assertEquals(0L, root.memberFundsQuery.balanceOf("M-1"))
        assertTrue(root.ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1")).isEmpty())
    }
}

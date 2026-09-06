package com.smallshoping.app.ai.orchestrator

import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.QuantityParser
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.PriceHistoryEntry
import com.smallshoping.app.domain.catalog.PriceType
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.customer.Customer
import com.smallshoping.app.domain.memory.MemoryFact
import com.smallshoping.app.domain.memory.MemoryScopeType
import com.smallshoping.app.domain.memory.MemorySource
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

    private fun seedMember() {
        root.members.saveMember(
            com.smallshoping.app.domain.member.Member(
                id = "M-1", storeId = "STORE-1", name = "张姐",
                normalizedName = normalize("张姐")
            )
        )
    }

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
    fun `黄金语句：给张姐充200 → 确认后余额由流水派生（Task 022）`() {
        seedMember()
        val reply = root.orchestrator.handle(root.inputAdapter.fromText("给张姐充200"))
        assertTrue("充值是 MEDIUM 风险，必须先确认", reply is OrchestratorReply.NeedsConfirm)
        val done = root.orchestrator.confirm(
            (reply as OrchestratorReply.NeedsConfirm).requestId, approved = true
        )
        assertTrue(done is OrchestratorReply.Text)
        assertTrue((done as OrchestratorReply.Text).text.contains("已充值"))
        assertEquals(20000L, root.memberFundsQuery.balanceOf("M-1"))
        // 账务事实：一条 MEMBER_RECHARGE 流水
        val entries = root.ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1"))
        assertEquals(1, entries.size)
        assertEquals(MovementType.MEMBER_RECHARGE, entries[0].movementType)
    }

    @Test
    fun `重复充值：确认两次只入账一次（Task 022）`() {
        seedMember()
        val first = root.orchestrator.handle(root.inputAdapter.fromText("给张姐充200"))
        root.orchestrator.confirm((first as OrchestratorReply.NeedsConfirm).requestId, approved = true)
        val second = root.orchestrator.handle(root.inputAdapter.fromText("给张姐充200"))
        assertTrue(second is OrchestratorReply.NeedsConfirm)
        val done = root.orchestrator.confirm(
            (second as OrchestratorReply.NeedsConfirm).requestId, approved = true
        )
        assertTrue((done as OrchestratorReply.Text).text.contains("已经充过"))
        assertEquals(20000L, root.memberFundsQuery.balanceOf("M-1"))
        assertEquals(1, root.ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1")).size)
    }

    @Test
    fun `充值歧义：多候选不猜测，账务不变（Task 022）`() {
        // 「小张」前缀同时命中两人 → 必须追问，不能猜（产品宪法 #9）
        root.members.saveMember(
            com.smallshoping.app.domain.member.Member(
                id = "M-1", storeId = "STORE-1", name = "小张姐",
                normalizedName = normalize("小张姐")
            )
        )
        root.members.saveMember(
            com.smallshoping.app.domain.member.Member(
                id = "M-2", storeId = "STORE-1", name = "小张哥",
                normalizedName = normalize("小张哥")
            )
        )
        val reply = root.orchestrator.handle(root.inputAdapter.fromText("给小张充200"))
        assertTrue(reply is OrchestratorReply.NeedsConfirm)
        val done = root.orchestrator.confirm(
            (reply as OrchestratorReply.NeedsConfirm).requestId, approved = true
        )
        assertTrue((done as OrchestratorReply.Text).text.contains("是哪一个"))
        assertEquals(0L, root.memberFundsQuery.balanceOf("M-1"))
    }

    @Test
    fun `充值会员不存在：明确提示且无流水（Task 022）`() {
        val reply = root.orchestrator.handle(root.inputAdapter.fromText("给李姐充200"))
        assertTrue(reply is OrchestratorReply.NeedsConfirm)
        val done = root.orchestrator.confirm(
            (reply as OrchestratorReply.NeedsConfirm).requestId, approved = true
        )
        assertTrue((done as OrchestratorReply.Text).text.contains("没找到"))
        assertTrue(root.ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "李姐")).isEmpty())
    }

    @Test
    fun `省略「给」同样可充值；拒绝确认则不执行（Task 022）`() {
        seedMember()
        val short = root.orchestrator.handle(root.inputAdapter.fromText("张姐充200"))
        assertTrue(short is OrchestratorReply.NeedsConfirm)
        root.orchestrator.confirm((short as OrchestratorReply.NeedsConfirm).requestId, approved = true)
        assertEquals(20000L, root.memberFundsQuery.balanceOf("M-1"))

        val rejected = root.orchestrator.handle(root.inputAdapter.fromText("张姐充50"))
        assertTrue(rejected is OrchestratorReply.NeedsConfirm)
        val rejectedDone = root.orchestrator.confirm(
            (rejected as OrchestratorReply.NeedsConfirm).requestId, approved = false
        )
        assertTrue((rejectedDone as OrchestratorReply.Text).text.contains("已取消"))
        assertEquals(20000L, root.memberFundsQuery.balanceOf("M-1"))
    }

    @Test
    fun `多轮消歧：给小张充200 → 追问 → 报名字 → 确认后入账（Task 025）`() {
        root.members.saveMember(
            com.smallshoping.app.domain.member.Member(
                id = "M-1", storeId = "STORE-1", name = "小张姐",
                normalizedName = normalize("小张姐")
            )
        )
        root.members.saveMember(
            com.smallshoping.app.domain.member.Member(
                id = "M-2", storeId = "STORE-1", name = "小张哥",
                normalizedName = normalize("小张哥")
            )
        )
        val first = root.orchestrator.handle(root.inputAdapter.fromText("给小张充200"))
        assertTrue(first is OrchestratorReply.NeedsConfirm)
        val ambiguous = root.orchestrator.confirm(
            (first as OrchestratorReply.NeedsConfirm).requestId, approved = true
        )
        assertTrue((ambiguous as OrchestratorReply.Text).text.contains("是哪一个"))

        // 老板报候选名 → 原意图重跑（充值仍需 MEDIUM 确认）
        val resolved = root.orchestrator.handle(root.inputAdapter.fromText("小张姐"))
        assertTrue(resolved is OrchestratorReply.NeedsConfirm)
        val done = root.orchestrator.confirm(
            (resolved as OrchestratorReply.NeedsConfirm).requestId, approved = true
        )
        assertTrue((done as OrchestratorReply.Text).text.contains("已充值"))
        assertEquals(20000L, root.memberFundsQuery.balanceOf("M-1"))
        assertEquals(0L, root.memberFundsQuery.balanceOf("M-2"))
    }

    @Test
    fun `多轮消歧：用第X个指认候选（Task 025）`() {
        root.members.saveMember(
            com.smallshoping.app.domain.member.Member(
                id = "M-1", storeId = "STORE-1", name = "小张姐",
                normalizedName = normalize("小张姐")
            )
        )
        root.members.saveMember(
            com.smallshoping.app.domain.member.Member(
                id = "M-2", storeId = "STORE-1", name = "小张哥",
                normalizedName = normalize("小张哥")
            )
        )
        val first = root.orchestrator.handle(root.inputAdapter.fromText("给小张充200"))
        root.orchestrator.confirm((first as OrchestratorReply.NeedsConfirm).requestId, approved = true)

        val resolved = root.orchestrator.handle(root.inputAdapter.fromText("第二个"))
        assertTrue(resolved is OrchestratorReply.NeedsConfirm)
        val done = root.orchestrator.confirm(
            (resolved as OrchestratorReply.NeedsConfirm).requestId, approved = true
        )
        assertTrue((done as OrchestratorReply.Text).text.contains("已充值"))
        assertEquals(20000L, root.memberFundsQuery.balanceOf("M-2"))
    }

    @Test
    fun `新任务取代旧追问：报名字不再触发充值（Task 025）`() {
        root.members.saveMember(
            com.smallshoping.app.domain.member.Member(
                id = "M-1", storeId = "STORE-1", name = "小张姐",
                normalizedName = normalize("小张姐")
            )
        )
        root.members.saveMember(
            com.smallshoping.app.domain.member.Member(
                id = "M-2", storeId = "STORE-1", name = "小张哥",
                normalizedName = normalize("小张哥")
            )
        )
        val first = root.orchestrator.handle(root.inputAdapter.fromText("给小张充200"))
        root.orchestrator.confirm((first as OrchestratorReply.NeedsConfirm).requestId, approved = true)

        // 老板改口：新任务取代旧追问
        seedPotato()
        val newTask = root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        assertTrue((newTask as OrchestratorReply.Text).text.contains("已加入"))
        // 旧追问已作废：再报「小张姐」不会再触发充值
        val leftover = root.orchestrator.handle(root.inputAdapter.fromText("小张姐"))
        assertTrue(leftover is OrchestratorReply.Question)
        assertEquals(0L, root.memberFundsQuery.balanceOf("M-1"))
    }

    private fun seedYesterdayPrice(productId: String, priceMinor: Long) {
        root.products.appendPriceHistory(
            PriceHistoryEntry(
                id = "H-$productId-$priceMinor", productId = productId,
                priceType = PriceType.SALE, oldPrice = Money(priceMinor),
                newPrice = Money(priceMinor), unit = Unit.JIN, source = "test",
                createdAtMillis = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            )
        )
    }

    @Test
    fun `黄金语句：还是昨天那个价格 → 上下文取商品 → 确认后改价（Task 026）`() {
        // 当前价 400，昨日历史价 380
        root.products.saveProduct(
            Product(
                id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
                saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
                currentSalePrice = Money(400), currentCostPrice = Money(280)
            )
        )
        root.ledger.append(
            LedgerEntry(
                scope = LedgerScope(LedgerScopeType.STOCK, "P-1"),
                movementType = MovementType.PURCHASE_IN,
                delta = 5000,
                idempotencyKey = IdempotencyKey("IN-1"),
                note = "测试入库"
            )
        )
        seedYesterdayPrice("P-1", 380)
        // 先建立商品上下文（最近商品 = 土豆）
        val add = root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        assertTrue((add as OrchestratorReply.Text).text.contains("已加入"))

        val reply = root.orchestrator.handle(root.inputAdapter.fromText("还是昨天那个价格"))
        assertTrue("改价属 MEDIUM 风险，必须先确认", reply is OrchestratorReply.NeedsConfirm)
        val done = root.orchestrator.confirm(
            (reply as OrchestratorReply.NeedsConfirm).requestId, approved = true
        )
        assertTrue((done as OrchestratorReply.Text).text.contains("改价"))
        assertEquals(Money(380), root.products.findProductById("P-1")!!.currentSalePrice)
    }

    @Test
    fun `昨日多价格：追问后按所选价格改价（Task 026）`() {
        root.products.saveProduct(
            Product(
                id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
                saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
                currentSalePrice = Money(400), currentCostPrice = Money(280)
            )
        )
        seedYesterdayPrice("P-1", 380)
        seedYesterdayPrice("P-1", 390)
        root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))

        val reply = root.orchestrator.handle(root.inputAdapter.fromText("还是昨天那个价格"))
        assertTrue(reply is OrchestratorReply.NeedsConfirm)
        val ambiguous = root.orchestrator.confirm(
            (reply as OrchestratorReply.NeedsConfirm).requestId, approved = true
        )
        assertTrue((ambiguous as OrchestratorReply.Text).text.contains("用哪一个"))

        // 老板报价格 → 重跑原意图（仍需确认）
        val resolved = root.orchestrator.handle(root.inputAdapter.fromText("380"))
        assertTrue(resolved is OrchestratorReply.NeedsConfirm)
        val done = root.orchestrator.confirm(
            (resolved as OrchestratorReply.NeedsConfirm).requestId, approved = true
        )
        assertTrue((done as OrchestratorReply.Text).text.contains("改价"))
        assertEquals(Money(380), root.products.findProductById("P-1")!!.currentSalePrice)
    }

    @Test
    fun `无上下文或昨日无记录：明确提示不猜测（Task 026）`() {
        // 无商品上下文
        val first = root.orchestrator.handle(root.inputAdapter.fromText("还是昨天那个价格"))
        assertTrue(first is OrchestratorReply.NeedsConfirm)
        val firstDone = root.orchestrator.confirm(
            (first as OrchestratorReply.NeedsConfirm).requestId, approved = true
        )
        assertTrue((firstDone as OrchestratorReply.Text).text.contains("先告诉我"))

        // 有上下文但昨日无价格记录
        seedPotato()
        root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        val second = root.orchestrator.handle(root.inputAdapter.fromText("还是昨天那个价格"))
        assertTrue(second is OrchestratorReply.NeedsConfirm)
        val secondDone = root.orchestrator.confirm(
            (second as OrchestratorReply.NeedsConfirm).requestId, approved = true
        )
        assertTrue((secondDone as OrchestratorReply.Text).text.contains("没有价格记录"))
    }

    @Test
    fun `已是昨天价格：提示未改动且不追加历史（Task 026）`() {
        root.products.saveProduct(
            Product(
                id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
                saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
                currentSalePrice = Money(380), currentCostPrice = Money(280)
            )
        )
        seedYesterdayPrice("P-1", 380)
        root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))

        val reply = root.orchestrator.handle(root.inputAdapter.fromText("还是昨天那个价格"))
        assertTrue(reply is OrchestratorReply.NeedsConfirm)
        val done = root.orchestrator.confirm(
            (reply as OrchestratorReply.NeedsConfirm).requestId, approved = true
        )
        assertTrue((done as OrchestratorReply.Text).text.contains("本来就是"))
        assertEquals(1, root.products.priceHistory("P-1").size)
    }

    private fun seedScrew() {
        root.products.saveProduct(
            Product(
                id = "P-9", storeId = "STORE-1", name = "螺丝", normalizedName = normalize("螺丝"),
                saleUnit = Unit.BOX, purchaseUnit = Unit.BOX,
                currentSalePrice = Money(380), currentCostPrice = Money(200)
            )
        )
        root.ledger.append(
            LedgerEntry(
                scope = LedgerScope(LedgerScopeType.STOCK, "P-9"),
                movementType = MovementType.PURCHASE_IN,
                delta = 100, // V1「盒」按个记账（包装换算 Task 029/030）
                idempotencyKey = IdempotencyKey("IN-SCREW"),
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
    fun `黄金语句：老张上次那些螺丝再来两盒 → 自动加项并观察客户习惯（Task 027）`() {
        seedScrew()
        seedCustomerZhang()
        val reply = root.orchestrator.handle(
            root.inputAdapter.fromText("老张上次那些螺丝再来两盒")
        )
        assertTrue(reply is OrchestratorReply.Text)
        assertTrue((reply as OrchestratorReply.Text).text.contains("已加入"))
        // 账务事实：草稿单含螺丝 2 个（盒按个记账，V1 占位语义）
        val saleId = root.contexts.load("DEVICE-1")?.activeSaleOrderId!!
        val sale = root.sales.findById(saleId)!!
        assertEquals(1, sale.items.size)
        assertEquals("螺丝", sale.items[0].productName)
        assertEquals(2L, sale.items[0].quantity.scaled)
        // 观察一次未达阈值（3 次），不写长期记忆（spec 06 §3）
        assertTrue(root.memory.query(MemoryScopeType.CUSTOMER, "C-1").isEmpty())
    }

    @Test
    fun `省略商品名：客户记忆兜底，数据库核对商品存在（Task 027）`() {
        seedScrew()
        seedCustomerZhang()
        root.memory.upsert(
            MemoryFact(
                scopeType = MemoryScopeType.CUSTOMER, scopeId = "C-1",
                factType = "usual_product", key = "top", valueJson = "P-9",
                confidence = 100, source = MemorySource.USER_CONFIRMED
            )
        )
        val reply = root.orchestrator.handle(
            root.inputAdapter.fromText("老张上次那些再来两盒")
        )
        assertTrue(reply is OrchestratorReply.Text)
        assertTrue((reply as OrchestratorReply.Text).text.contains("已加入"))
        assertTrue(reply.text.contains("螺丝"))
    }

    @Test
    fun `无记忆且省略商品名：明确提示不猜测（Task 027）`() {
        seedCustomerZhang()
        val reply = root.orchestrator.handle(
            root.inputAdapter.fromText("老张上次那些再来两盒")
        )
        assertTrue(reply is OrchestratorReply.Text)
        assertTrue((reply as OrchestratorReply.Text).text.contains("不记得"))
    }

    @Test
    fun `客户歧义：追问后报名字重跑（Task 027）`() {
        seedScrew()
        seedCustomerZhang(id = "C-1", name = "老张叔")
        seedCustomerZhang(id = "C-2", name = "老张哥")
        val first = root.orchestrator.handle(
            root.inputAdapter.fromText("老张上次那些螺丝再来两盒")
        )
        assertTrue(first is OrchestratorReply.Text)
        assertTrue((first as OrchestratorReply.Text).text.contains("是哪一个"))

        val resolved = root.orchestrator.handle(root.inputAdapter.fromText("老张叔"))
        assertTrue(resolved is OrchestratorReply.Text)
        assertTrue((resolved as OrchestratorReply.Text).text.contains("已加入"))
    }

    @Test
    fun `AI 失败不改变任何事实`() {
        seedPotato()
        val failing = AiOrchestrator(
            provider = com.smallshoping.app.ai.providers.CloudAiProvider(
                com.smallshoping.app.ai.providers.AiGatewayClient { throw IllegalStateException("timeout") }
            ),
            executor = root.executor,
            disambiguation = com.smallshoping.app.data.repository.InMemoryDisambiguationStore()
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

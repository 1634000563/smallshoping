package com.smallshoping.app.feature.journey

import com.smallshoping.app.ai.orchestrator.OrchestratorReply
import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.BarcodeType
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductBarcode
import com.smallshoping.app.domain.customer.Customer
import com.smallshoping.app.domain.inventory.StockQuery
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import com.smallshoping.app.domain.member.Member
import com.smallshoping.app.domain.report.CloseOutcome
import com.smallshoping.app.domain.sales.AddSaleItemRequest
import com.smallshoping.app.domain.sales.CheckoutSaleRequest
import com.smallshoping.app.domain.sales.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 056：核心用户旅程端到端回归（spec 17 P0 路径 + V1 成功标准）。
 *
 * 每条旅程都验证三件事：
 * 1. 对话路径按「老板的话」走通（含确认与消歧）；
 * 2. 每个动作可追溯到确定性账务事实（流水/余额/库存）；
 * 3. 旅程结束全店一致性审计健康（Task 055）。
 */
class CoreJourneyRegressionTest {

    // ---------- 公共种子 ----------

    private fun seedPotato(root: CompositionRoot, stockGrams: Long = 50000L) {
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
                delta = stockGrams,
                idempotencyKey = IdempotencyKey("IN-JOURNEY"),
                note = "旅程种子库存"
            )
        )
    }

    private fun seedMember(root: CompositionRoot) {
        root.members.saveMember(
            Member(id = "M-1", storeId = "STORE-1", name = "张姐", normalizedName = normalize("张姐"))
        )
    }

    private fun seedCustomer(root: CompositionRoot) {
        root.customers.saveCustomer(
            Customer(id = "C-1", storeId = "STORE-1", name = "老张", normalizedName = normalize("老张"))
        )
    }

    private fun confirm(root: CompositionRoot, reply: OrchestratorReply): OrchestratorReply {
        assertTrue("期望确认卡片：$reply", reply is OrchestratorReply.NeedsConfirm)
        return root.orchestrator.confirm((reply as OrchestratorReply.NeedsConfirm).requestId, true)
    }

    // ---------- 旅程 A：菜店 P0（spec 17 路径 A） ----------

    @Test
    fun `旅程A 菜店 P0：进货→改价→卖2斤6→微信确认→库存97点4→今日销售额`() {
        val root = CompositionRoot()
        seedPotato(root, stockGrams = 0L) // 从零开始，仅目录有商品

        // 1) 进100斤土豆，2块8，卖3块8：入库 + 进价随逗号捕获（spec 17），售价按安全设计引导单独改
        val buy = root.orchestrator.handle(root.inputAdapter.fromText("进100斤土豆，2块8，卖3块8"))
        assertTrue((buy as OrchestratorReply.Text).text.contains("已入库"))
        assertEquals(Money(280), root.products.findProductById("P-1")!!.currentCostPrice)
        assertEquals(50000L, StockQuery(root.ledger).stockOf("P-1"))

        // 2) 售价单独确认
        val price = root.orchestrator.handle(root.inputAdapter.fromText("土豆改成3块8"))
        confirm(root, price)
        assertEquals(Money(380), root.products.findProductById("P-1")!!.currentSalePrice)

        // 3) 土豆两斤六 → 2.6斤 × 3.8元 = 9.88 元（小计 988 分）
        //    （spec 17 原文「卖两斤六」省略商品名依赖上下文记忆，V1 未支持，
        //      见报告「未解决问题」；显式商品形式为 V1 黄金语句 veg-001）
        val add = root.orchestrator.handle(root.inputAdapter.fromText("土豆两斤六"))
        assertTrue((add as OrchestratorReply.Text).text.contains("988"))

        // 4) 「微信。」口语结账 → 确认 → 完成，库存 97.4 斤
        val pay = root.orchestrator.handle(root.inputAdapter.fromText("微信"))
        confirm(root, pay)
        assertEquals(50000L - 1300L, StockQuery(root.ledger).stockOf("P-1"))

        // 5) 今天卖了多少钱 → 本地计算 988 分（9.88 元）
        val today = root.orchestrator.handle(root.inputAdapter.fromText("今天卖了多少钱"))
        assertTrue((today as OrchestratorReply.Text).text.contains("988"))

        // 6) 全店审计健康
        assertTrue(root.consistencyAudit.audit().healthy)
    }

    // ---------- 旅程 C：会员（spec 17 路径 C） ----------

    @Test
    fun `旅程C 会员：充值→查余额→会员消费→余额由流水重建`() {
        val root = CompositionRoot()
        seedPotato(root)
        seedMember(root)

        val recharge = root.orchestrator.handle(root.inputAdapter.fromText("给张姐充200"))
        confirm(root, recharge)
        assertEquals(20000L, root.memberFundsQuery.balanceOf("M-1"))

        val query = root.orchestrator.handle(root.inputAdapter.fromText("张姐还有多少钱"))
        assertTrue((query as OrchestratorReply.Text).text.contains("20000"))

        // 会员消费：卖两斤（7.6 元）→ 会员结账 → 余额 192.4 元
        root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        val checkout = root.orchestrator.handle(root.inputAdapter.fromText("会员结账"))
        confirm(root, checkout)
        assertEquals(20000L - 760L, root.memberFundsQuery.balanceOf("M-1"))
        // 余额可由流水重建（spec 04 §6）
        assertEquals(20000L - 760L, root.memberFundsQuery.rebuildBalance("M-1"))
        assertTrue(root.consistencyAudit.audit().healthy)
    }

    // ---------- 旅程 D：客户赊账（spec 17 路径 D） ----------

    @Test
    fun `旅程D 赊账：先记账→还款→欠款由流水重建`() {
        val root = CompositionRoot()
        seedPotato(root)
        seedCustomer(root)

        root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        val credit = root.orchestrator.handle(root.inputAdapter.fromText("老张先记账"))
        confirm(root, credit)
        assertEquals(760L, root.customerDebtQuery.debtOf("C-1"))

        val settle = root.orchestrator.handle(root.inputAdapter.fromText("老张还1块"))
        confirm(root, settle)
        assertEquals(760L - 100L, root.customerDebtQuery.debtOf("C-1"))
        // 欠款可由流水重建（spec 04 §7）
        assertEquals(760L - 100L, root.customerDebtQuery.rebuildDebt("C-1"))
        assertTrue(root.consistencyAudit.audit().healthy)
    }

    // ---------- 旅程 E：全天营业（语音+扫码+损耗+日结） ----------

    @Test
    fun `旅程E 全天营业：语音卖→扫码→损耗→结账→日结核对→审计与重启校验健康`() {
        val root = CompositionRoot()
        seedPotato(root)
        root.products.addBarcode(
            ProductBarcode(
                productId = "P-1", barcode = "6901234567890",
                barcodeType = BarcodeType.EAN13, isPrimary = true
            )
        )

        // 语音卖 2 斤（7.6 元）
        root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        // 扫码报商品（与语音/键盘同一输入链路）
        val scan = root.orchestrator.handle(root.inputAdapter.fromText("6901234567890"))
        assertTrue((scan as OrchestratorReply.Text).text.contains("土豆"))
        // 再来 2 斤（7.6 元）
        root.orchestrator.handle(root.inputAdapter.fromText("来两斤土豆"))
        // 损耗半斤
        val loss = root.orchestrator.handle(root.inputAdapter.fromText("损耗半斤土豆"))
        confirm(root, loss)
        // 现金结账
        val checkout = root.orchestrator.handle(root.inputAdapter.fromText("结账"))
        confirm(root, checkout)

        // 账务事实：库存 100斤 − 4斤 − 0.5斤 = 95.5 斤 = 47750 克
        assertEquals(47750L, StockQuery(root.ledger).stockOf("P-1"))
        // 日结核对：现金应收 15.2 元，实收一致 → 差异 0
        val summary = root.dayCloseService.checkSummary()
        assertEquals(1520L, summary.cashMinor)
        val closed = root.dayCloseService.closeDay(cashActualMinor = 1520L) as CloseOutcome.Closed
        assertEquals(0L, closed.dayClose.varianceMinor)

        assertTrue(root.consistencyAudit.audit().healthy)
        assertTrue(root.crashRecovery.verifyAfterRestart().healthy)
    }

    // ---------- 旅程 F：离线降级（AI 挂不影响营业，Gate A） ----------

    @Test
    fun `旅程F 离线降级：AI 不可用→手动加+人工结账→与 AI 路径同一事实→审计健康`() {
        val root = CompositionRoot()
        seedPotato(root)

        // AI 挂：编排层明确降级提示，账务不动
        val failing = com.smallshoping.app.ai.orchestrator.AiOrchestrator(
            provider = com.smallshoping.app.ai.providers.CloudAiProvider(
                com.smallshoping.app.ai.providers.AiGatewayClient { throw IllegalStateException("timeout") }
            ),
            executor = root.executor,
            disambiguation = com.smallshoping.app.data.repository.InMemoryDisambiguationStore()
        )
        val reply = failing.handle(root.inputAdapter.fromText("卖两斤土豆"))
        assertTrue((reply as OrchestratorReply.Text).text.contains("暂时不可用"))

        // 手动加 + 人工结账（同一 Domain，Gate A 事实一致）
        val added = root.addSaleItemUseCase(
            AddSaleItemRequest("STORE-1", null, "P-1", Quantity(1000, Unit.GRAM)) // 2斤
        ) as com.smallshoping.app.domain.sales.AddSaleItemResult.Success
        val checkout = root.checkoutSaleUseCase(
            CheckoutSaleRequest(
                saleId = added.sale.id,
                paymentMethod = PaymentMethod.CASH,
                idempotencyKey = "journey-f-checkout"
            )
        ) as com.smallshoping.app.domain.sales.CheckoutSaleResult.Success

        // 与 AI 路径相同的确定性事实：库存 −2 斤、现金 7.6 元
        assertEquals(49000L, StockQuery(root.ledger).stockOf("P-1"))
        assertEquals(760L, root.dayCloseService.checkSummary().cashMinor)
        assertTrue(root.consistencyAudit.audit().healthy)
    }
}

package com.smallshoping.app.feature.home

import com.smallshoping.app.ai.orchestrator.AiOrchestrator
import com.smallshoping.app.ai.orchestrator.OrchestratorReply
import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit as QuantityUnit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.customer.Customer
import com.smallshoping.app.domain.inventory.StockQuery
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import com.smallshoping.app.domain.member.Member
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 057：V1 非 AI 兜底能力完整性验收（AGENTS.md §6 V1 必须清单）。
 *
 * 全部用例零网络、零云端 Provider（CompositionRoot 默认 cloud=null，
 * 纯 LocalRuleParser 本地规则），验证「AI 挂/断网时老板仍可完整营业」；
 * 每个场景结束断言确定性账务事实与全店一致性审计（Task 055）。
 */
class FallbackCompletenessTest {

    private fun seedStore(root: CompositionRoot) {
        root.products.saveProduct(
            Product(
                id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
                saleUnit = QuantityUnit.JIN, purchaseUnit = QuantityUnit.JIN,
                currentSalePrice = Money(380), currentCostPrice = Money(280)
            )
        )
        root.ledger.append(
            LedgerEntry(
                scope = LedgerScope(LedgerScopeType.STOCK, "P-1"),
                movementType = MovementType.PURCHASE_IN,
                delta = 50000L,
                idempotencyKey = IdempotencyKey("IN-FB"),
                note = "验收库存"
            )
        )
        root.members.saveMember(
            Member(id = "M-1", storeId = "STORE-1", name = "张姐", normalizedName = normalize("张姐"))
        )
        root.customers.saveCustomer(
            Customer(id = "C-1", storeId = "STORE-1", name = "老张", normalizedName = normalize("老张"))
        )
    }

    private fun confirm(root: CompositionRoot, reply: OrchestratorReply): OrchestratorReply {
        assertTrue("期望确认卡片：$reply", reply is OrchestratorReply.NeedsConfirm)
        return root.orchestrator.confirm((reply as OrchestratorReply.NeedsConfirm).requestId, true)
    }

    @Test
    fun `离线清单：商品查询与扫码输入`() {
        val root = CompositionRoot()
        seedStore(root)
        val quote = root.orchestrator.handle(root.inputAdapter.fromText("土豆多少钱"))
        assertTrue((quote as OrchestratorReply.Text).text.contains("380"))
        root.products.addBarcode(
            com.smallshoping.app.domain.catalog.ProductBarcode(
                productId = "P-1", barcode = "6901234567890",
                barcodeType = com.smallshoping.app.domain.catalog.BarcodeType.EAN13, isPrimary = true
            )
        )
        val scan = root.orchestrator.handle(root.inputAdapter.fromText("6901234567890"))
        assertTrue((scan as OrchestratorReply.Text).text.contains("土豆"))
    }

    @Test
    fun `离线清单：销售与四种结账方式`() {
        val root = CompositionRoot()
        seedStore(root)
        // 现金
        root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        confirm(root, root.orchestrator.handle(root.inputAdapter.fromText("结账")))
        // 微信
        root.orchestrator.handle(root.inputAdapter.fromText("卖一斤土豆"))
        confirm(root, root.orchestrator.handle(root.inputAdapter.fromText("微信")))
        // 支付宝
        root.orchestrator.handle(root.inputAdapter.fromText("卖一斤土豆"))
        confirm(root, root.orchestrator.handle(root.inputAdapter.fromText("支付宝结账")))
        // 会员（先充值再会员结账）
        confirm(root, root.orchestrator.handle(root.inputAdapter.fromText("给张姐充200")))
        root.orchestrator.handle(root.inputAdapter.fromText("卖一斤土豆"))
        confirm(root, root.orchestrator.handle(root.inputAdapter.fromText("会员结账")))
        // 账务：4 单共 2+1+1+1=5 斤，库存 95 斤；会员余额 200-3.8=196.2 元
        assertEquals(47500L, StockQuery(root.ledger).stockOf("P-1"))
        assertEquals(19620L, root.memberFundsQuery.balanceOf("M-1"))
        assertTrue(root.consistencyAudit.audit().healthy)
    }

    @Test
    fun `离线清单：新建商品与采购`() {
        val root = CompositionRoot()
        seedStore(root)
        // 无网新建商品（Task 057 新增句式）
        val created = root.orchestrator.handle(root.inputAdapter.fromText("建商品螺丝，卖5块"))
        confirm(root, created)
        val screw = root.products.findByNormalizedName(normalize("螺丝"))!!
        assertEquals(Money(500), screw.currentSalePrice)
        // 采购入库
        val buy = root.orchestrator.handle(root.inputAdapter.fromText("进100个螺丝，进价1块"))
        assertTrue((buy as OrchestratorReply.Text).text.contains("已入库"))
        assertEquals(100L, StockQuery(root.ledger).stockOf(screw.id))
        assertTrue(root.consistencyAudit.audit().healthy)
    }

    @Test
    fun `离线清单：称重输入与损耗`() {
        val root = CompositionRoot()
        seedStore(root)
        root.orchestrator.handle(root.inputAdapter.fromText("卖2.36斤土豆")) // 称重式小数输入
        confirm(root, root.orchestrator.handle(root.inputAdapter.fromText("结账")))
        val loss = root.orchestrator.handle(root.inputAdapter.fromText("损耗半斤土豆"))
        confirm(root, loss)
        assertEquals(50000L - 1180L - 250L, StockQuery(root.ledger).stockOf("P-1"))
        assertTrue(root.consistencyAudit.audit().healthy)
    }

    @Test
    fun `离线清单：客户欠款与还款`() {
        val root = CompositionRoot()
        seedStore(root)
        root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        confirm(root, root.orchestrator.handle(root.inputAdapter.fromText("老张先记账")))
        assertEquals(760L, root.customerDebtQuery.debtOf("C-1"))
        confirm(root, root.orchestrator.handle(root.inputAdapter.fromText("老张还1块")))
        assertEquals(660L, root.customerDebtQuery.debtOf("C-1"))
        assertTrue(root.consistencyAudit.audit().healthy)
    }

    @Test
    fun `离线清单：改价与昨天价`() {
        val root = CompositionRoot()
        seedStore(root)
        confirm(root, root.orchestrator.handle(root.inputAdapter.fromText("土豆改成4块")))
        assertEquals(Money(400), root.products.findProductById("P-1")!!.currentSalePrice)
        // 昨天价：无商品上下文 → 确认后明确提示不猜测（Task 026 行为）
        val yesterday = root.orchestrator.handle(root.inputAdapter.fromText("还是昨天那个价格"))
        val yesterdayDone = confirm(root, yesterday)
        assertTrue((yesterdayDone as OrchestratorReply.Text).text.contains("先告诉我"))
    }

    @Test
    fun `离线清单：报表与日结`() {
        val root = CompositionRoot()
        seedStore(root)
        root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        confirm(root, root.orchestrator.handle(root.inputAdapter.fromText("结账")))
        val today = root.orchestrator.handle(root.inputAdapter.fromText("今天卖了多少钱"))
        assertTrue((today as OrchestratorReply.Text).text.contains("760"))
        val summary = root.dayCloseService.checkSummary()
        assertEquals(760L, summary.cashMinor)
        assertTrue(root.consistencyAudit.audit().healthy)
    }

    @Test
    fun `离线清单：AI 挂时人工兜底与 AI 路径同事实（Gate A）`() {
        val root = CompositionRoot()
        seedStore(root)
        // AI 不可用：HomeViewModel 人工按钮路径（同一 Domain）
        val failing = AiOrchestrator(
            provider = com.smallshoping.app.ai.providers.CloudAiProvider(
                com.smallshoping.app.ai.providers.AiGatewayClient { throw IllegalStateException("offline") }
            ),
            executor = root.executor,
            disambiguation = com.smallshoping.app.data.repository.InMemoryDisambiguationStore()
        )
        val vm = HomeViewModel(root, failing)
        val add = vm.manualAddItem("土豆", "2斤")
        assertEquals(com.smallshoping.app.feature.home.UiKind.NORMAL, add.kind)
        val checkout = vm.manualCheckout()
        assertTrue(checkout.reply.contains("人工结账完成"))
        // 与 AI 路径相同的确定性事实
        assertEquals(49000L, StockQuery(root.ledger).stockOf("P-1"))
        assertEquals(760L, root.dayCloseService.checkSummary().cashMinor)
        assertTrue(root.consistencyAudit.audit().healthy)
    }

    @Test
    fun `离线清单：未开放能力诚实失败，不伪造成功`() {
        val root = CompositionRoot()
        seedStore(root)
        // 退款（ADR-015：V1 不发布）→ 不在白名单/无句式 → 诚实失败
        val refund = root.orchestrator.handle(root.inputAdapter.fromText("退货"))
        assertTrue(
            "应诚实失败而非伪造退款：$refund",
            refund is OrchestratorReply.Question || refund is OrchestratorReply.Text
        )
        val adjust = root.orchestrator.handle(root.inputAdapter.fromText("调整库存"))
        assertTrue(
            "应诚实失败而非伪造调整：$adjust",
            adjust is OrchestratorReply.Question || adjust is OrchestratorReply.Text
        )
        // 无任何账务变化
        assertTrue(root.consistencyAudit.audit().healthy)
        assertEquals(50000L, StockQuery(root.ledger).stockOf("P-1"))
    }
}

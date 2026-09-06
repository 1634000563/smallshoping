package com.smallshoping.app.ai.orchestrator

import com.smallshoping.app.ai.eval.EvalRunner
import com.smallshoping.app.ai.providers.LocalRuleParser
import com.smallshoping.app.ai.risk.RiskLevel
import com.smallshoping.app.ai.tools.ToolResult
import com.smallshoping.app.ai.tools.V1ToolCatalog
import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductAttribute
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
import java.io.File

/**
 * Gate C 验收（Task 039 / M6 收口）：
 * 1. 通用模型跨行业：菜店与五金复用同一组 Domain/Tool/Ledger，无行业分支；
 * 2. 黄金语句集门禁：19 条全量通过（spec 15 §6）；
 * 3. 高风险零自动执行：全部 MEDIUM/HIGH 工具必须先确认（AI Eval Guard 底线）；
 * 4. 跨行业会话连续：同一会话五金+生鲜+会员操作账务互不污染。
 */
class GateCAcceptanceTest {

    private fun seedMixedStore(root: CompositionRoot) {
        root.products.saveProduct(
            Product(
                id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
                saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
                currentSalePrice = Money(380), currentCostPrice = Money(280)
            )
        )
        root.products.saveProduct(
            Product(
                id = "P-10", storeId = "STORE-1", name = "304 M8x30 外六角螺栓",
                normalizedName = normalize("304 M8x30 外六角螺栓"),
                saleUnit = Unit.PIECE, purchaseUnit = Unit.PIECE,
                currentSalePrice = Money(50), currentCostPrice = Money(30)
            )
        )
        root.products.addAttribute(
            ProductAttribute(
                productId = "P-10", name = "材质", normalizedName = normalize("材质"),
                value = "304", normalizedValue = normalize("304")
            )
        )
        root.ledger.append(
            LedgerEntry(
                scope = LedgerScope(LedgerScopeType.STOCK, "P-1"),
                movementType = MovementType.PURCHASE_IN,
                delta = 50000,
                idempotencyKey = IdempotencyKey("IN-GATEC-1"),
                note = "Gate C 入库"
            )
        )
        root.ledger.append(
            LedgerEntry(
                scope = LedgerScope(LedgerScopeType.STOCK, "P-10"),
                movementType = MovementType.PURCHASE_IN,
                delta = 100,
                idempotencyKey = IdempotencyKey("IN-GATEC-2"),
                note = "Gate C 入库"
            )
        )
        root.members.saveMember(
            Member(id = "M-1", storeId = "STORE-1", name = "张姐", normalizedName = normalize("张姐"))
        )
        root.customers.saveCustomer(
            Customer(id = "C-1", storeId = "STORE-1", name = "老张", normalizedName = normalize("老张"))
        )
    }

    @Test
    fun `Gate C：跨行业通用底座——同一 Ledger 双行业账务可重建`() {
        val root = CompositionRoot()
        seedMixedStore(root)
        // 生鲜线
        root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        val loss = root.orchestrator.handle(root.inputAdapter.fromText("损耗半斤土豆"))
        root.orchestrator.confirm((loss as OrchestratorReply.NeedsConfirm).requestId, true)
        // 五金线（同一组 Tool/Domain，无行业分支）
        root.orchestrator.handle(root.inputAdapter.fromText("卖5个304螺栓"))
        val checkout = root.orchestrator.handle(root.inputAdapter.fromText("结账"))
        root.orchestrator.confirm((checkout as OrchestratorReply.NeedsConfirm).requestId, true)

        val stock = StockQuery(root.ledger)
        assertEquals(50000L - 1000L - 250L, stock.stockOf("P-1")) // 土豆：-2斤-半斤
        assertEquals(95L, stock.stockOf("P-10")) // 螺栓：-5个
        // 两类商品的流水都在同一本库存账，可重建
        val p1Rebuilt = root.ledger.entries(LedgerScope(LedgerScopeType.STOCK, "P-1"))
            .fold(0L) { acc, e -> Math.addExact(acc, e.delta) }
        assertEquals(stock.stockOf("P-1"), p1Rebuilt)
        // 同一批 UseCase 实例服务两个行业（复用验证）
        val added = root.addSaleItemUseCase(
            com.smallshoping.app.domain.sales.AddSaleItemRequest(
                "STORE-1", null, "P-10",
                com.smallshoping.app.core.quantity.Quantity(3, Unit.PIECE)
            )
        )
        assertTrue(added is com.smallshoping.app.domain.sales.AddSaleItemResult.Success)
    }

    @Test
    fun `Gate C：黄金语句集门禁——19 条全量通过`() {
        val runner = EvalRunner(LocalRuleParser())
        val cases = EvalRunner.loadCases(
            File(System.getProperty("user.dir"), "../docs/evals/golden_cases.jsonl")
        )
        val report = runner.run(cases, V1ToolCatalog.all().map { it.ref.name })
        assertTrue(
            "Gate C 黄金集未通过（${report.accuracy}）：\n" +
                report.failures.joinToString("\n") { "  ${it.case.id} → ${it.detail}" },
            report.pass
        )
    }

    @Test
    fun `Gate C：高风险零自动执行——全部 MEDIUM_HIGH 工具必须先确认`() {
        val root = CompositionRoot()
        val writeTools = V1ToolCatalog.all().filter {
            it.idempotencyRequired && it.riskLevel != RiskLevel.LOW
        }
        assertTrue("应有多个高风险写工具", writeTools.size >= 10)
        for (contract in writeTools) {
            val type = IntentType.fromTool(contract.ref.name)!!
            // 必填实体塞占位值，风险门在任何业务执行之前
            val entities = type.requiredEntities.associateWith { "x" }
            val result = root.executor.execute(Intent(type = type, entities = entities))
            assertTrue(
                "${contract.ref.fullName}（${contract.riskLevel}）必须进入确认流，实际：$result",
                result is ToolResult.NeedsConfirmation
            )
        }
    }

    @Test
    fun `Gate C：跨行业会话连续——账务互不污染`() {
        val root = CompositionRoot()
        seedMixedStore(root)
        // 五金赊账
        root.orchestrator.handle(root.inputAdapter.fromText("卖5个304螺栓"))
        val credit = root.orchestrator.handle(root.inputAdapter.fromText("老张赊200"))
        root.orchestrator.confirm((credit as OrchestratorReply.NeedsConfirm).requestId, true)
        // 会员充值
        val recharge = root.orchestrator.handle(root.inputAdapter.fromText("给张姐充200"))
        root.orchestrator.confirm((recharge as OrchestratorReply.NeedsConfirm).requestId, true)
        // 生鲜损耗
        val loss = root.orchestrator.handle(root.inputAdapter.fromText("损耗半斤土豆"))
        root.orchestrator.confirm((loss as OrchestratorReply.NeedsConfirm).requestId, true)

        // 三类账务互不污染：欠款只进客户账、充值只进会员账、损耗只进库存账
        assertEquals(20000L, root.memberFundsQuery.balanceOf("M-1"))
        assertEquals(20000L, root.customerDebtQuery.debtOf("C-1"))
        assertEquals(50000L - 250L, StockQuery(root.ledger).stockOf("P-1"))
        assertEquals(100L, StockQuery(root.ledger).stockOf("P-10"))
        // 账本隔离：会员账 1 条、客户账 1 条、库存账 3 条
        assertEquals(1, root.ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1")).size)
        assertEquals(1, root.ledger.entries(LedgerScope(LedgerScopeType.CUSTOMER, "C-1")).size)
    }
}

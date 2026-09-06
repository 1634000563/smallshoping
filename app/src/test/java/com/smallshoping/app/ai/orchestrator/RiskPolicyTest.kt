package com.smallshoping.app.ai.orchestrator

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
import com.smallshoping.app.domain.member.Member
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 040：口语确认/拒绝、删除拒绝、改价大额警示。 */
class RiskPolicyTest {

    private val root = CompositionRoot()

    private fun seedMemberAndPotato() {
        root.members.saveMember(
            Member(id = "M-1", storeId = "STORE-1", name = "张姐", normalizedName = normalize("张姐"))
        )
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
                idempotencyKey = IdempotencyKey("IN-040"),
                note = "测试入库"
            )
        )
    }

    @Test
    fun `口语确认：说是 直接确认最近待确认请求（spec 08 §9）`() {
        seedMemberAndPotato()
        val pending = root.orchestrator.handle(root.inputAdapter.fromText("给张姐充200"))
        assertTrue(pending is OrchestratorReply.NeedsConfirm)

        val done = root.orchestrator.handle(root.inputAdapter.fromText("是"))
        assertTrue((done as OrchestratorReply.Text).text.contains("已充值"))
        assertEquals(20000L, root.memberFundsQuery.balanceOf("M-1"))
    }

    @Test
    fun `口语拒绝：说算了 拒绝最近待确认请求，账务不变`() {
        seedMemberAndPotato()
        val pending = root.orchestrator.handle(root.inputAdapter.fromText("给张姐充200"))
        assertTrue(pending is OrchestratorReply.NeedsConfirm)

        val rejected = root.orchestrator.handle(root.inputAdapter.fromText("算了"))
        assertTrue((rejected as OrchestratorReply.Text).text.contains("已取消"))
        assertEquals(0L, root.memberFundsQuery.balanceOf("M-1"))
    }

    @Test
    fun `无待确认时说是不是误确认`() {
        seedMemberAndPotato()
        val reply = root.orchestrator.handle(root.inputAdapter.fromText("是"))
        // 没有挂起请求 → 走普通解析 → 澄清（不影响任何事实）
        assertTrue(reply is OrchestratorReply.Question)
        assertEquals(0L, root.memberFundsQuery.balanceOf("M-1"))
    }

    @Test
    fun `删除语句：确定性拒绝，账务事实不受影响（spec 04 只追加）`() {
        seedMemberAndPotato()
        root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        val reply = root.orchestrator.handle(root.inputAdapter.fromText("删除昨天的销售"))
        assertTrue(reply is OrchestratorReply.Text)
        assertTrue((reply as OrchestratorReply.Text).text.contains("不提供删除"))
        // 库存与草稿单未被删除/改动
        assertEquals(5000L, com.smallshoping.app.domain.inventory.StockQuery(root.ledger).stockOf("P-1"))
        assertTrue(root.contexts.load("DEVICE-1")?.activeSaleOrderId != null)
    }

    @Test
    fun `改价大额变化：确认文案带异常警示（spec 08 §8）`() {
        seedMemberAndPotato()
        val pending = root.orchestrator.handle(root.inputAdapter.fromText("土豆改成四十块"))
        assertTrue(pending is OrchestratorReply.NeedsConfirm)
        val done = root.orchestrator.confirm(
            (pending as OrchestratorReply.NeedsConfirm).requestId, true
        )
        assertTrue((done as OrchestratorReply.Text).text.contains("价格变化很大"))
        assertEquals(Money(4000), root.products.findProductById("P-1")!!.currentSalePrice)
        // 小幅改价（4000 → 3800，约 5%）不警示
        val small = root.orchestrator.handle(root.inputAdapter.fromText("土豆改价38块"))
        assertTrue(small is OrchestratorReply.NeedsConfirm)
        val smallDone = root.orchestrator.confirm(
            (small as OrchestratorReply.NeedsConfirm).requestId, true
        )
        assertTrue(!(smallDone as OrchestratorReply.Text).text.contains("价格变化很大"))
        assertEquals(Money(3800), root.products.findProductById("P-1")!!.currentSalePrice)
    }
}

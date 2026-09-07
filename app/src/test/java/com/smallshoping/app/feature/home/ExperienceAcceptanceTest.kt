package com.smallshoping.app.feature.home

import com.smallshoping.app.ai.orchestrator.OrchestratorReply
import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit as QuantityUnit
import com.smallshoping.app.domain.catalog.Product
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
 * Task 058：V1 产品体验/极简操作验收。
 *
 * 验收维度（spec 12 / spec 24 Usability）：
 * 1. 反馈四要素（§4）：做了什么、金额数量、是否完成、卡在哪里；
 * 2. 操作步数最小化：低风险一句话直接执行，中/高风险说+确认两步；
 * 3. 语音纠错（§6）：老板可中断、修改、撤销当前草稿，不创建错误交易；
 * 4. 口语确认/拒绝代替按钮（spec 08 §9）。
 */
class ExperienceAcceptanceTest {

    private fun seedStore(root: CompositionRoot) {
        root.products.saveProduct(
            Product(
                id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
                saleUnit = QuantityUnit.JIN, purchaseUnit = QuantityUnit.JIN,
                currentSalePrice = Money(380), currentCostPrice = Money(280)
            )
        )
        root.products.saveProduct(
            Product(
                id = "P-2", storeId = "STORE-1", name = "红薯", normalizedName = normalize("红薯"),
                saleUnit = QuantityUnit.JIN, purchaseUnit = QuantityUnit.JIN,
                currentSalePrice = Money(250), currentCostPrice = Money(150)
            )
        )
        root.ledger.append(
            LedgerEntry(
                scope = LedgerScope(LedgerScopeType.STOCK, "P-1"),
                movementType = MovementType.PURCHASE_IN,
                delta = 50000L,
                idempotencyKey = IdempotencyKey("IN-EXP"),
                note = "验收库存"
            )
        )
        root.ledger.append(
            LedgerEntry(
                scope = LedgerScope(LedgerScopeType.STOCK, "P-2"),
                movementType = MovementType.PURCHASE_IN,
                delta = 50000L,
                idempotencyKey = IdempotencyKey("IN-EXP-2"),
                note = "验收库存"
            )
        )
        root.members.saveMember(
            Member(id = "M-1", storeId = "STORE-1", name = "张姐", normalizedName = normalize("张姐"))
        )
    }

    private fun say(root: CompositionRoot, text: String): OrchestratorReply =
        root.orchestrator.handle(root.inputAdapter.fromText(text))

    private fun confirm(root: CompositionRoot, reply: OrchestratorReply): OrchestratorReply {
        assertTrue("期望确认卡片：$reply", reply is OrchestratorReply.NeedsConfirm)
        return root.orchestrator.confirm((reply as OrchestratorReply.NeedsConfirm).requestId, true)
    }

    // ---------- 1. 反馈四要素 ----------

    @Test
    fun `反馈四要素：加购回复含商品数量与小计，结账回复含总额与到账确认`() {
        val root = CompositionRoot()
        seedStore(root)
        val add = say(root, "土豆两斤六")
        val addText = (add as OrchestratorReply.Text).text
        // 做了什么 + 数量 + 金额
        assertTrue(addText.contains("已加入"))
        assertTrue(addText.contains("土豆"))
        assertTrue(addText.contains("2.6"))
        assertTrue(addText.contains("988"))
        // 是否完成 + 待办（结账确认）
        val checkout = confirm(root, say(root, "结账"))
        val checkoutText = (checkout as OrchestratorReply.Text).text
        assertTrue(checkoutText.contains("结账完成"))
        assertTrue(checkoutText.contains("988"))
        assertTrue(checkoutText.contains("请确认到账"))
    }

    @Test
    fun `反馈四要素：失败时明确卡在哪里，不伪造成功`() {
        val root = CompositionRoot()
        seedStore(root)
        // 库存 100 斤，卖 150 斤 → 结账应明确报库存不够且没动账
        say(root, "卖150斤土豆")
        val done = confirm(root, say(root, "结账"))
        val text = (done as OrchestratorReply.Text).text
        assertTrue("应说明卡在库存：$text", text.contains("库存不够"))
        assertTrue("应说明没动账：$text", text.contains("没动账"))
        // 库存未变、无完成单
        assertEquals(50000L, StockQuery(root.ledger).stockOf("P-1"))
    }

    // ---------- 2. 操作步数 ----------

    @Test
    fun `操作步数：低风险一句话直接执行，中风险说加确认两步`() {
        val root = CompositionRoot()
        seedStore(root)
        // 低风险：卖两斤土豆 → 直接加入，无确认卡片（1 步）
        val add = say(root, "卖两斤土豆")
        assertTrue(add is OrchestratorReply.Text)
        assertTrue((add as OrchestratorReply.Text).text.contains("已加入"))
        // 中风险：充值 → 确认卡片（第 1 步），确认（第 2 步）
        val recharge = say(root, "给张姐充200")
        assertTrue(recharge is OrchestratorReply.NeedsConfirm)
        confirm(root, recharge)
        assertEquals(20000L, root.memberFundsQuery.balanceOf("M-1"))
    }

    // ---------- 3. 语音纠错（spec 12 §6） ----------

    @Test
    fun `语音纠错：不是土豆是红薯 → 先拿掉错的，不创建错误交易，重说后只有对的入账`() {
        val root = CompositionRoot()
        seedStore(root)
        // 老板说错：加了两斤六土豆
        say(root, "土豆两斤六")
        // 老板纠错：不是土豆，是红薯 → 确认后拿掉刚才那个（纠错 = 说 + 确认）
        val fix = confirm(root, say(root, "不是土豆，是红薯"))
        assertTrue((fix as OrchestratorReply.Text).text.contains("拿掉"))
        // 重说正确的商品
        val redo = say(root, "红薯两斤六")
        assertTrue((redo as OrchestratorReply.Text).text.contains("红薯"))
        // 结账：单上只有红薯，没有错误交易（2.5元×2.6斤=650分）
        val done = confirm(root, say(root, "结账"))
        val text = (done as OrchestratorReply.Text).text
        assertTrue(text.contains("650"))
        assertEquals(650L, root.dayCloseService.checkSummary().cashMinor)
        // 库存：红薯 -2.6 斤；土豆未被扣（错误交易未创建）
        assertEquals(50000L, StockQuery(root.ledger).stockOf("P-1"))
        assertEquals(50000L - 1300L, StockQuery(root.ledger).stockOf("P-2"))
    }

    // ---------- 4. 口语确认/拒绝（spec 08 §9） ----------

    @Test
    fun `口语确认拒绝：说「不」取消不执行，说「是」执行`() {
        val root = CompositionRoot()
        seedStore(root)
        // 说「不」→ 取消
        say(root, "给张姐充200")
        val rejected = say(root, "不")
        assertTrue((rejected as OrchestratorReply.Text).text.contains("已取消"))
        assertEquals(0L, root.memberFundsQuery.balanceOf("M-1"))
        // 再说一次，说「是」→ 执行
        say(root, "给张姐充200")
        val accepted = say(root, "是")
        assertTrue(accepted is OrchestratorReply.Text)
        assertEquals(20000L, root.memberFundsQuery.balanceOf("M-1"))
        assertTrue(root.consistencyAudit.audit().healthy)
    }
}

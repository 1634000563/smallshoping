package com.smallshoping.app.domain.audit

import com.smallshoping.app.ai.orchestrator.OrchestratorReply
import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit as QuantityUnit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.customer.Customer
import com.smallshoping.app.domain.inventory.StockQuery
import com.smallshoping.app.domain.ledger.DataIntegrityException
import com.smallshoping.app.domain.ledger.IdempotencyConflictException
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import com.smallshoping.app.domain.member.Member
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Task 055：数据一致性/账务灾难演练（spec 04 §13）。
 *
 * 演练清单：
 * 1. 正常营业后全店审计健康；
 * 2. 缓存篡改：三本账同时受损，审计列出差异明细且绝不静默覆盖；
 * 3. 断电式中断：批次冲突整体回滚，无部分写入；
 * 4. 备份恢复：导出→篡改→新店恢复→审计健康；
 * 5. 数据擦除：擦除后账务为空且审计健康；
 * 6. 库存重建入口与会员/客户对称。
 */
class DisasterDrillTest {

    private val stockScope = LedgerScope(LedgerScopeType.STOCK, "P-1")
    private val memberScope = LedgerScope(LedgerScopeType.MEMBER, "M-1")
    private val customerScope = LedgerScope(LedgerScopeType.CUSTOMER, "C-1")

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
                scope = stockScope,
                movementType = MovementType.PURCHASE_IN,
                delta = 50000L,
                idempotencyKey = IdempotencyKey("IN-DRILL-1"),
                note = "演练入库"
            )
        )
        root.members.saveMember(
            Member(id = "M-1", storeId = "STORE-1", name = "张姐", normalizedName = normalize("张姐"))
        )
        root.customers.saveCustomer(
            Customer(id = "C-1", storeId = "STORE-1", name = "老张", normalizedName = normalize("老张"))
        )
    }

    /** 正常营业：销售（库存账）+ 充值（会员账）+ 赊账（客户账）三本账都产生流水。 */
    private fun runBusiness(root: CompositionRoot) {
        root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        val checkout = root.orchestrator.handle(root.inputAdapter.fromText("结账"))
        root.orchestrator.confirm((checkout as OrchestratorReply.NeedsConfirm).requestId, true)
        val recharge = root.orchestrator.handle(root.inputAdapter.fromText("给张姐充200"))
        root.orchestrator.confirm((recharge as OrchestratorReply.NeedsConfirm).requestId, true)
        val credit = root.orchestrator.handle(root.inputAdapter.fromText("老张赊200"))
        root.orchestrator.confirm((credit as OrchestratorReply.NeedsConfirm).requestId, true)
    }

    private fun expectedBalance(root: CompositionRoot, scope: LedgerScope): Long =
        root.ledger.entries(scope).sumOf { it.delta }

    @Test
    fun `演练1 正常营业：全店三本账审计健康`() {
        val root = CompositionRoot()
        seedStore(root)
        runBusiness(root)
        val report = root.consistencyAudit.audit()
        assertTrue(report.healthy)
        assertTrue("应覆盖库存+会员+客户三本账", report.checkedScopes >= 3)
    }

    @Test
    fun `演练2 缓存篡改：三本账同时受损，审计列出明细且不静默覆盖`() {
        val root = CompositionRoot()
        seedStore(root)
        runBusiness(root)
        root.ledger.debugCorruptCachedBalance(stockScope, 1L)
        root.ledger.debugCorruptCachedBalance(memberScope, 2L)
        root.ledger.debugCorruptCachedBalance(customerScope, 3L)

        val report = root.consistencyAudit.audit()
        assertTrue(!report.healthy)
        assertEquals(3, report.mismatches.size)
        for (mismatch in report.mismatches) {
            // 明细：重建值 = 流水真实余额，缓存值 = 篡改值
            assertEquals(expectedBalance(root, mismatch.scope), mismatch.rebuiltMinor)
            assertTrue("缓存值应为篡改值而非被覆盖", mismatch.cachedMinor != mismatch.rebuiltMinor)
        }
        // 不静默覆盖：查询仍返回篡改后的缓存值，等待人工处理
        assertEquals(1L, StockQuery(root.ledger).stockOf("P-1"))
    }

    @Test
    fun `演练3 断电式中断：批次冲突整体回滚，无部分写入`() {
        val root = CompositionRoot()
        seedStore(root)
        val freshScope = LedgerScope(LedgerScopeType.STOCK, "P-9")
        assertThrows(IdempotencyConflictException::class.java) {
            root.ledger.transact(
                listOf(
                    LedgerEntry(
                        scope = freshScope, movementType = MovementType.PURCHASE_IN,
                        delta = 100L, idempotencyKey = IdempotencyKey("NEW-DRILL-1"), note = "新流水"
                    ),
                    LedgerEntry(
                        scope = stockScope, movementType = MovementType.PURCHASE_IN,
                        delta = 500L, idempotencyKey = IdempotencyKey("IN-DRILL-1"), note = "重复键"
                    )
                )
            )
        }
        // 新范围未产生任何流水（批次整体未生效，无部分写入）
        assertTrue(root.ledger.entries(freshScope).isEmpty())
        val report = root.consistencyAudit.audit()
        assertTrue(report.healthy)
    }

    @Test
    fun `演练4 备份恢复：导出→篡改→新店恢复→审计健康`() {
        val root = CompositionRoot()
        seedStore(root)
        runBusiness(root)
        val snapshot = root.backupService.export()

        // 灾难：原店缓存被篡改，审计应检出
        root.ledger.debugCorruptCachedBalance(stockScope, 1L)
        assertTrue(!root.consistencyAudit.audit().healthy)

        // 新店从备份恢复：流水按幂等键重放，缓存重建
        val fresh = CompositionRoot()
        assertTrue(fresh.backupService.restore(snapshot))
        val report = fresh.consistencyAudit.audit()
        assertTrue("恢复后全店账务应一致：${report.mismatches}", report.healthy)
        // 关键事实恢复且余额正确
        assertTrue(fresh.products.findProductById("P-1") != null)
        assertEquals(expectedBalance(root, stockScope), StockQuery(fresh.ledger).stockOf("P-1"))
    }

    @Test
    fun `演练5 数据擦除：擦除后账务为空且审计健康`() {
        val root = CompositionRoot()
        seedStore(root)
        runBusiness(root)
        root.dataWipeService.wipe()
        assertTrue(root.dataWipeService.verifyWiped())
        val audit = root.consistencyAudit.audit()
        assertEquals(0, audit.checkedScopes)
        assertTrue(audit.healthy)
    }

    @Test
    fun `演练6 库存重建入口：与会员客户对称，篡改即抛异常`() {
        val root = CompositionRoot()
        seedStore(root)
        runBusiness(root)
        val stock = StockQuery(root.ledger)
        assertEquals(expectedBalance(root, stockScope), stock.rebuildStock("P-1"))
        root.ledger.debugCorruptCachedBalance(stockScope, 7L)
        assertThrows(DataIntegrityException::class.java) { stock.rebuildStock("P-1") }
    }
}

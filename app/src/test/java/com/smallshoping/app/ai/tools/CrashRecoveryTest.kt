package com.smallshoping.app.ai.tools

import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.customer.Customer
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.member.Member
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 045 验收：崩溃恢复——命令日志 + 幂等重放不重复记账，
 * 重启校验发现缓存损坏不静默覆盖。
 */
class CrashRecoveryTest {

    private fun seedBusiness(root: CompositionRoot) {
        root.products.saveProduct(
            Product(
                id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
                saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
                currentSalePrice = Money(380), currentCostPrice = Money(280)
            )
        )
        root.members.saveMember(
            Member(id = "M-1", storeId = "STORE-1", name = "张姐", normalizedName = normalize("张姐"))
        )
        root.customers.saveCustomer(
            Customer(id = "C-1", storeId = "STORE-1", name = "老张", normalizedName = normalize("老张"))
        )
    }

    private fun executeBusiness(root: CompositionRoot) {
        // 三条写命令（幂等键由入参确定性生成）
        root.orchestrator.handle(root.inputAdapter.fromText("进100斤土豆，成本2块8"))
        val recharge = root.orchestrator.handle(root.inputAdapter.fromText("给张姐充200"))
        root.orchestrator.confirm((recharge as com.smallshoping.app.ai.orchestrator.OrchestratorReply.NeedsConfirm).requestId, true)
        val credit = root.orchestrator.handle(root.inputAdapter.fromText("老张赊50"))
        root.orchestrator.confirm((credit as com.smallshoping.app.ai.orchestrator.OrchestratorReply.NeedsConfirm).requestId, true)
    }

    @Test
    fun `崩溃后按命令日志重放：账务与崩溃前一致且不重复记账`() {
        val before = CompositionRoot()
        seedBusiness(before)
        executeBusiness(before)

        // 崩溃前事实
        assertEquals(50000L, com.smallshoping.app.domain.inventory.StockQuery(before.ledger).stockOf("P-1"))
        assertEquals(20000L, before.memberFundsQuery.balanceOf("M-1"))
        assertEquals(5000L, before.customerDebtQuery.debtOf("C-1"))
        assertEquals(3, before.commandJournal.all().size)

        // 模拟崩溃：内存账本丢失，命令日志保留 → 新实例 + 同一日志重放
        val after = CompositionRoot()
        seedBusiness(after)
        for (record in before.commandJournal.all()) {
            after.commandJournal.append(record)
        }

        val result = after.replayRunner.replayAll()
        assertTrue(
            "重放不应有失败：${result.failed}",
            result.allSucceeded
        )
        assertEquals(3, result.replayed)

        // 重放后账务与崩溃前一致（幂等键去重，不重复记账）
        assertEquals(50000L, com.smallshoping.app.domain.inventory.StockQuery(after.ledger).stockOf("P-1"))
        assertEquals(20000L, after.memberFundsQuery.balanceOf("M-1"))
        assertEquals(5000L, after.customerDebtQuery.debtOf("C-1"))

        // 再次重放：依然不重复（AlreadyCompleted 语义）
        val again = after.replayRunner.replayAll()
        assertTrue(again.allSucceeded)
        assertEquals(20000L, after.memberFundsQuery.balanceOf("M-1"))
        assertEquals(
            before.ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1")).size,
            after.ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1")).size
        )
    }

    @Test
    fun `重启校验：缓存损坏被发现且不静默覆盖`() {
        val root = CompositionRoot()
        seedBusiness(root)
        executeBusiness(root)

        // 故意破坏一个范围的缓存
        root.ledger.debugCorruptCachedBalance(LedgerScope(LedgerScopeType.MEMBER, "M-1"), 1L)

        val report = root.crashRecovery.verifyAfterRestart()
        assertTrue(!report.healthy)
        assertEquals(1, report.corruptedScopes.size)
        assertEquals(LedgerScope(LedgerScopeType.MEMBER, "M-1"), report.corruptedScopes[0])
        // 其他范围不受影响
        assertTrue(report.checkedScopes >= 2)
    }

    @Test
    fun `重放不经风险门：MEDIUM 命令直接执行不挂起确认`() {
        val root = CompositionRoot()
        seedBusiness(root)
        val intent = com.smallshoping.app.ai.orchestrator.Intent(
            type = com.smallshoping.app.ai.orchestrator.IntentType.RECHARGE_MEMBER,
            entities = mapOf("member" to "张姐", "amount" to "20000")
        )
        // 重放路径直接执行（此前已确认过），不返回 NeedsConfirmation
        val outcome = root.executor.replay(intent)
        assertTrue(outcome is ToolResult.Success)
        assertEquals(20000L, root.memberFundsQuery.balanceOf("M-1"))
    }
}

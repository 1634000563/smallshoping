package com.smallshoping.app.domain.backup

import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.customer.Customer
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import com.smallshoping.app.domain.member.Member
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 046 验收：备份→恢复账务一致；校验失败拒绝恢复；重复恢复幂等。 */
class BackupServiceTest {

    private fun seedBusiness(root: CompositionRoot) {
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
                idempotencyKey = IdempotencyKey("IN-1"),
                note = "入库"
            )
        )
        root.ledger.append(
            LedgerEntry(
                scope = LedgerScope(LedgerScopeType.MEMBER, "M-1"),
                movementType = MovementType.MEMBER_RECHARGE,
                delta = 20000,
                idempotencyKey = IdempotencyKey("recharge:M-1:20000"),
                note = "充值"
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
    fun `导出→恢复：账务事实与目录完全一致`() {
        val before = CompositionRoot()
        seedBusiness(before)
        val snapshot = before.backupService.export()
        assertNotNull(snapshot.checksum)
        assertTrue(snapshot.payload.contains("P|P-1|土豆"))
        assertTrue(snapshot.payload.contains("L|STOCK|P-1|PURCHASE_IN|5000"))
        assertNull(before.backupService.validate(snapshot))

        // 「换新手机」：全新实例 + 恢复
        val after = CompositionRoot()
        assertTrue(after.backupService.restore(snapshot))

        assertEquals(5000L, com.smallshoping.app.domain.inventory.StockQuery(after.ledger).stockOf("P-1"))
        assertEquals(20000L, after.memberFundsQuery.balanceOf("M-1"))
        assertEquals(Money(380), after.products.findProductById("P-1")!!.currentSalePrice)
        assertEquals("张姐", after.members.findMemberById("M-1")!!.name)
        assertEquals("老张", after.customers.findCustomerById("C-1")!!.name)
        // 恢复后重建校验（Task 045 闭环）
        assertTrue(after.crashRecovery.verifyAfterRestart().healthy)
    }

    @Test
    fun `篡改备份：checksum 校验失败拒绝恢复`() {
        val root = CompositionRoot()
        seedBusiness(root)
        val snapshot = root.backupService.export()
        val tampered = snapshot.copy(payload = snapshot.payload.replace("5000", "9999"))

        assertNotNull(root.backupService.validate(tampered))
        assertFalse(root.backupService.restore(tampered))
    }

    @Test
    fun `版本不符：拒绝恢复（spec 19 §5）`() {
        val root = CompositionRoot()
        seedBusiness(root)
        val snapshot = root.backupService.export()
        val wrongVersion = snapshot.copy(schemaVersion = 999)
        assertTrue(root.backupService.validate(wrongVersion)!!.contains("版本"))
        assertFalse(root.backupService.restore(wrongVersion))
    }

    @Test
    fun `重复恢复：幂等键去重不重复记账`() {
        val before = CompositionRoot()
        seedBusiness(before)
        val snapshot = before.backupService.export()

        val after = CompositionRoot()
        assertTrue(after.backupService.restore(snapshot))
        assertTrue(after.backupService.restore(snapshot)) // 再次恢复同一备份
        assertEquals(1, after.ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1")).size)
        assertEquals(5000L, com.smallshoping.app.domain.inventory.StockQuery(after.ledger).stockOf("P-1"))
        // 命令日志也按幂等去重语义（恢复两次 = 同批命令两遍，重放仍不重复）
        val replay = after.replayRunner.replayAll()
        assertTrue(replay.allSucceeded)
        assertEquals(20000L, after.memberFundsQuery.balanceOf("M-1"))
    }
}

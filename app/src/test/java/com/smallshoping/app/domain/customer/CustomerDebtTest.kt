package com.smallshoping.app.domain.customer

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.data.ledger.InMemoryLedger
import com.smallshoping.app.data.repository.InMemoryCustomerRepository
import com.smallshoping.app.domain.ledger.DataIntegrityException
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 021 验收：客户欠款必须全部走 customer_ledger，欠款由流水派生。 */
class CustomerDebtTest {

    private val customers = InMemoryCustomerRepository()
    private val ledger = InMemoryLedger()
    private val credit = RecordCustomerCreditUseCase(customers, ledger)
    private val payment = ReceiveCustomerPaymentUseCase(customers, ledger)
    private val debt = CustomerDebtQuery(customers, ledger)

    private fun seedCustomer(id: String = "C-1", name: String = "老张"): Customer {
        val customer = Customer(
            id = id, storeId = "STORE-1", name = name,
            normalizedName = normalize(name), alias = "张老板", creditEnabled = true
        )
        customers.saveCustomer(customer)
        return customer
    }

    @Test
    fun `正常路径：赊账后欠款由流水派生`() {
        seedCustomer()
        val result = credit(
            CustomerDebtRequest("STORE-1", "C-1", Money.fromYuan(36), "credit:C-1:3600", "赊账销售")
        )
        assertTrue(result is CustomerDebtResult.Success)
        assertEquals(3600L, (result as CustomerDebtResult.Success).balanceAfterMinor)
        assertEquals(3600L, debt.debtOf("C-1"))

        val entries = ledger.entries(LedgerScope(LedgerScopeType.CUSTOMER, "C-1"))
        assertEquals(1, entries.size)
        assertEquals(MovementType.CUSTOMER_CREDIT, entries[0].movementType)
        assertEquals(3600L, entries[0].delta)
    }

    @Test
    fun `收款冲减欠款：不删除历史流水`() {
        seedCustomer()
        credit(CustomerDebtRequest("STORE-1", "C-1", Money.fromYuan(36), "credit:C-1:a", "赊账销售"))
        val result = payment(
            CustomerDebtRequest("STORE-1", "C-1", Money.fromYuan(20), "payment:C-1:a", "现金还款")
        )
        assertTrue(result is CustomerDebtResult.Success)
        assertEquals(1600L, (result as CustomerDebtResult.Success).balanceAfterMinor)
        assertEquals(1600L, debt.debtOf("C-1"))
        // 两条流水都在（追加式），还款为负 delta
        val entries = ledger.entries(LedgerScope(LedgerScopeType.CUSTOMER, "C-1"))
        assertEquals(2, entries.size)
        assertEquals(MovementType.CUSTOMER_PAYMENT, entries[1].movementType)
        assertEquals(-2000L, entries[1].delta)
    }

    @Test
    fun `幂等：同键重复赊账或收款不重复入账`() {
        seedCustomer()
        val creditRequest = CustomerDebtRequest("STORE-1", "C-1", Money.fromYuan(36), "credit:C-1:x", "赊账")
        val first = credit(creditRequest)
        val second = credit(creditRequest)
        assertTrue(first is CustomerDebtResult.Success)
        assertTrue(second is CustomerDebtResult.AlreadyCompleted)
        assertEquals(3600L, (second as CustomerDebtResult.AlreadyCompleted).balanceAfterMinor)
        assertEquals(1, ledger.entries(LedgerScope(LedgerScopeType.CUSTOMER, "C-1")).size)

        val payRequest = CustomerDebtRequest("STORE-1", "C-1", Money.fromYuan(10), "payment:C-1:x", "还款")
        payment(payRequest)
        assertTrue(payment(payRequest) is CustomerDebtResult.AlreadyCompleted)
        assertEquals(2600L, debt.debtOf("C-1"))
    }

    @Test
    fun `客户不存在：拒绝变动且不产生流水`() {
        val result = credit(
            CustomerDebtRequest("STORE-1", "C-404", Money.fromYuan(36), "credit:C-404:x", "赊账")
        )
        assertTrue(result is CustomerDebtResult.CustomerNotFound)
        assertNull(debt.debtOf("C-404"))
        assertEquals(0, ledger.entries(LedgerScope(LedgerScopeType.CUSTOMER, "C-404")).size)
    }

    @Test
    fun `边界输入：零或负金额直接拒绝`() {
        seedCustomer()
        assertThrows(IllegalArgumentException::class.java) {
            credit(CustomerDebtRequest("STORE-1", "C-1", Money.ZERO, "credit:C-1:z", "赊账"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            payment(CustomerDebtRequest("STORE-1", "C-1", Money(-1), "payment:C-1:n", "还款"))
        }
        assertEquals(0L, debt.debtOf("C-1"))
        assertEquals(0, ledger.entries(LedgerScope(LedgerScopeType.CUSTOMER, "C-1")).size)
    }

    @Test
    fun `欠款重建：与缓存一致返回派生值；缓存被破坏抛异常不静默覆盖`() {
        seedCustomer()
        credit(CustomerDebtRequest("STORE-1", "C-1", Money.fromYuan(36), "credit:C-1:r", "赊账"))
        assertEquals(3600L, debt.rebuildDebt("C-1"))
        assertNull(debt.rebuildDebt("C-404"))

        ledger.debugCorruptCachedBalance(LedgerScope(LedgerScopeType.CUSTOMER, "C-1"), 1L)
        assertThrows(DataIntegrityException::class.java) { debt.rebuildDebt("C-1") }
    }
}

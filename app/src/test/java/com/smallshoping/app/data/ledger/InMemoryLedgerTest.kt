package com.smallshoping.app.data.ledger

import com.smallshoping.app.domain.ledger.AppendResult
import com.smallshoping.app.domain.ledger.DataIntegrityException
import com.smallshoping.app.domain.ledger.IdempotencyConflictException
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryLedgerTest {

    private val ledger = InMemoryLedger()
    private val productScope = LedgerScope(LedgerScopeType.STOCK, "PRODUCT-1")

    private fun entry(key: String, delta: Long, type: MovementType = MovementType.ADJUST_IN) =
        LedgerEntry(
            scope = productScope,
            movementType = type,
            delta = delta,
            idempotencyKey = IdempotencyKey(key),
            createdAtMillis = 1000L
        )

    @Test
    fun `正常路径：追加流水并派生余额`() {
        ledger.append(entry("K1", 100))
        ledger.append(entry("K2", 50))
        assertEquals(150L, ledger.balance(productScope))
        assertEquals(2, ledger.entries(productScope).size)
        // 流水保持追加顺序
        assertEquals(listOf("K1", "K2"), ledger.entries(productScope).map { it.idempotencyKey.value })
    }

    @Test
    fun `边界输入：空账本余额为零，进出抵消`() {
        assertEquals(0L, ledger.balance(LedgerScope(LedgerScopeType.MEMBER, "M-1")))
        ledger.append(entry("K1", 100, MovementType.MEMBER_RECHARGE).copy(
            scope = LedgerScope(LedgerScopeType.MEMBER, "M-1")))
        ledger.append(entry("K2", -100, MovementType.MEMBER_CONSUME).copy(
            scope = LedgerScope(LedgerScopeType.MEMBER, "M-1")))
        assertEquals(0L, ledger.balance(LedgerScope(LedgerScopeType.MEMBER, "M-1")))
    }

    @Test
    fun `幂等：同键重复追加返回原流水且不重复记账`() {
        val first = ledger.append(entry("K1", 100))
        assertTrue(first is AppendResult.Appended)
        val second = ledger.append(entry("K1", 100))
        assertTrue(second is AppendResult.Duplicate)
        assertEquals((first as AppendResult.Appended).entry.id, (second as AppendResult.Duplicate).original.id)
        assertEquals(100L, ledger.balance(productScope))
        assertEquals(1, ledger.entries(productScope).size)
    }

    @Test
    fun `原子性：批量中任一幂等键冲突则整批不生效`() {
        ledger.append(entry("OLD", 10))
        assertThrows(IdempotencyConflictException::class.java) {
            ledger.transact(listOf(entry("NEW-1", 5), entry("OLD", 5), entry("NEW-2", 5)))
        }
        // 整批回滚：NEW-1/NEW-2 均未写入
        assertEquals(10L, ledger.balance(productScope))
        assertEquals(1, ledger.entries(productScope).size)
    }

    @Test
    fun `原子性：批内自重复键同样整体拒绝`() {
        assertThrows(IdempotencyConflictException::class.java) {
            ledger.transact(listOf(entry("DUP", 5), entry("DUP", 5)))
        }
        assertEquals(0L, ledger.balance(productScope))
    }

    @Test
    fun `重建：正常时重算与缓存一致`() {
        ledger.append(entry("K1", 100))
        ledger.append(entry("K2", 50))
        assertEquals(150L, ledger.rebuildBalance(productScope))
    }

    @Test
    fun `重建：缓存被破坏时抛异常且不静默覆盖`() {
        ledger.append(entry("K1", 100))
        ledger.debugCorruptCachedBalance(productScope, 999L)
        assertThrows(DataIntegrityException::class.java) {
            ledger.rebuildBalance(productScope)
        }
    }

    @Test
    fun `异常路径：余额溢出快速失败`() {
        ledger.append(entry("K1", Long.MAX_VALUE - 1))
        assertThrows(ArithmeticException::class.java) {
            ledger.append(entry("K2", Long.MAX_VALUE - 1))
        }
    }

    @Test
    fun `异常路径：delta 符号与变动类型不符被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            entry("BAD", 5, MovementType.SALE_OUT)
        }
    }

    @Test
    fun `并发：同键并发追加只记一笔`() {
        val threads = 8
        val latch = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val appended = AtomicInteger()
        val duplicated = AtomicInteger()
        repeat(threads) {
            pool.submit {
                latch.await()
                when (ledger.append(entry("CONC", 100))) {
                    is AppendResult.Appended -> appended.incrementAndGet()
                    is AppendResult.Duplicate -> duplicated.incrementAndGet()
                }
            }
        }
        latch.countDown()
        pool.shutdown()
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(1, appended.get())
        assertEquals(threads - 1, duplicated.get())
        assertEquals(100L, ledger.balance(productScope))
        assertEquals(1, ledger.entries(productScope).size)
    }
}

package com.smallshoping.app.data.repository

import com.smallshoping.app.domain.payment.Payment
import com.smallshoping.app.domain.payment.PaymentRepository
import com.smallshoping.app.domain.payment.PaymentStatus
import com.smallshoping.app.domain.payment.RecordPaymentResult
import com.smallshoping.app.domain.sales.PaymentMethod

/**
 * 内存支付记录实现：线程安全，幂等键去重，状态迁移只改本行。
 */
class InMemoryPaymentRepository : PaymentRepository {

    private val lock = Any()
    private val byId = LinkedHashMap<String, Payment>()
    private val byKey = HashMap<String, Payment>()

    override fun record(payment: Payment): RecordPaymentResult = synchronized(lock) {
        byKey[payment.idempotencyKey]?.let {
            return RecordPaymentResult.AlreadyRecorded(it)
        }
        byId[payment.id] = payment
        byKey[payment.idempotencyKey] = payment
        RecordPaymentResult.Recorded(payment)
    }

    override fun bySale(saleOrderId: String): List<Payment> = synchronized(lock) {
        byId.values.filter { it.saleOrderId == saleOrderId }
    }

    override fun all(): List<Payment> = synchronized(lock) {
        byId.values.toList()
    }

    override fun confirm(paymentId: String): Payment? = synchronized(lock) {
        val payment = byId[paymentId] ?: return null
        if (payment.status != PaymentStatus.PENDING) return null
        val updated = payment.copy(
            status = PaymentStatus.CONFIRMED,
            confirmedAtMillis = System.currentTimeMillis()
        )
        byId[paymentId] = updated
        updated
    }

    override fun mark(paymentId: String, status: PaymentStatus): Payment? = synchronized(lock) {
        val payment = byId[paymentId] ?: return null
        if (payment.status != PaymentStatus.PENDING) return null
        val updated = payment.copy(status = status)
        byId[paymentId] = updated
        updated
    }

    /** 数据擦除（spec 13 §5，仅 DataWipeService 调用）。 */
    fun wipe() = synchronized(lock) {
        byId.clear()
        byKey.clear()
    }
}

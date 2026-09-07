package com.smallshoping.app.data.sqlite

import com.smallshoping.app.ai.context.SessionContext
import com.smallshoping.app.domain.catalog.PriceHistoryEntry
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductAlias
import com.smallshoping.app.domain.catalog.ProductAttribute
import com.smallshoping.app.domain.catalog.ProductBarcode
import com.smallshoping.app.domain.catalog.UnitConversion
import com.smallshoping.app.domain.customer.Customer
import com.smallshoping.app.domain.inventory.LossRecord
import com.smallshoping.app.domain.journal.CommandRecord
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.member.Member
import com.smallshoping.app.domain.memory.MemoryFact
import com.smallshoping.app.domain.payment.Payment
import com.smallshoping.app.domain.purchase.PurchaseOrder
import com.smallshoping.app.domain.report.DayClose
import com.smallshoping.app.domain.sales.SaleOrder

/**
 * 各仓库的写穿钩子（Task 059 SQLite 持久化）：
 * InMemory 仓库注入这些钩子后，每次事实变更同步落盘；null 则纯内存（单元测试默认）。
 * 由 [ShopPersistence] 实现全部接口。
 */
interface ProductPersistence {
    fun onSaveProduct(p: Product)
    fun onAlias(a: ProductAlias)
    fun onAttribute(a: ProductAttribute)
    fun onPriceHistory(e: PriceHistoryEntry)
    fun onBarcode(b: ProductBarcode)
    fun onConversion(c: UnitConversion)
}

interface SalePersistence {
    fun onSale(order: SaleOrder)
    fun onSaleWipe()
}

interface PaymentPersistence {
    fun onPayment(p: Payment)
    fun onPaymentWipe()
}

interface PurchasePersistence {
    fun onPurchase(order: PurchaseOrder)
}

interface LossPersistence {
    fun onLoss(record: LossRecord)
}

interface MemberPersistence {
    fun onMember(m: Member)
}

interface CustomerPersistence {
    fun onCustomer(c: Customer)
}

interface DayClosePersistence {
    fun onDayClose(d: DayClose)
}

interface JournalPersistence {
    fun onAppend(record: CommandRecord)
    fun onClear()
}

interface ContextPersistence {
    fun onSave(c: SessionContext)
    fun onClear(deviceSessionId: String)
}

interface MemoryPersistence {
    fun onUpsert(m: MemoryFact)
    fun onDeactivate(id: String)
}

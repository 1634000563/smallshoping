package com.smallshoping.app.data.sqlite

import android.content.ContentValues
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * 写穿持久化（Task 059）：把内存事实同步写入 SQLite。
 * 所有方法幂等（INSERT OR REPLACE / INSERT OR IGNORE），重复调用安全；
 * 与 InMemory 语义一致：账本流水只追加，余额永远可重建。
 * 实现全部 [PersistenceHooks] 接口，供 InMemory 仓库注入。
 */
class ShopPersistence(private val db: ShopDatabase) : ProductPersistence, SalePersistence,
    PaymentPersistence, PurchasePersistence, LossPersistence, MemberPersistence,
    CustomerPersistence, DayClosePersistence, JournalPersistence, ContextPersistence,
    MemoryPersistence {

    /** 数据库访问（启动水合用）。 */
    val database: ShopDatabase get() = db

    // ── 钩子接口实现 ──

    override fun onSaveProduct(p: Product) = saveProduct(p)
    override fun onAlias(a: ProductAlias) = saveAlias(a)
    override fun onAttribute(a: ProductAttribute) = saveAttribute(a)
    override fun onPriceHistory(e: PriceHistoryEntry) = savePriceHistory(e)
    override fun onBarcode(b: ProductBarcode) = saveBarcode(b)
    override fun onConversion(c: UnitConversion) = saveConversion(c)
    override fun onSale(order: SaleOrder) = saveSale(order)
    override fun onSaleWipe() {
        db.writableDatabase.delete("sales", null, null)
        db.writableDatabase.delete("sale_items", null, null)
    }
    override fun onPayment(p: Payment) = recordPayment(p)
    override fun onPaymentWipe() {
        db.writableDatabase.delete("payments", null, null)
    }
    override fun onPurchase(order: PurchaseOrder) = savePurchase(order)
    override fun onLoss(record: LossRecord) = saveLoss(record)
    override fun onMember(m: Member) = saveMember(m)
    override fun onCustomer(c: Customer) = saveCustomer(c)
    override fun onDayClose(d: DayClose) = saveDayClose(d)
    override fun onAppend(record: CommandRecord) = appendJournal(record)
    override fun onClear() = clearJournal()
    override fun onSave(c: SessionContext) = saveContext(c)
    override fun onClear(deviceSessionId: String) = clearContext(deviceSessionId)
    override fun onUpsert(m: MemoryFact) = upsertMemory(m)
    override fun onDeactivate(id: String) = deactivateMemory(id)

    /** 账本擦除（DataWipeService → ledger.wipe）。 */
    fun clearLedger() {
        db.writableDatabase.delete("ledger_entries", null, null)
    }

    // ── 商品与目录 ──

    fun saveProduct(p: Product) {
        db.writableDatabase.insertWithOnConflict(
            "products", null,
            ContentValues().apply {
                put("id", p.id); put("store_id", p.storeId)
                put("name", p.name); put("normalized_name", p.normalizedName)
                put("sale_unit", p.saleUnit.code); put("purchase_unit", p.purchaseUnit.code)
                put("sale_price_minor", p.currentSalePrice.minor)
                put("cost_price_minor", p.currentCostPrice?.minor)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun saveAlias(a: ProductAlias) {
        db.writableDatabase.insertWithOnConflict(
            "product_aliases", null,
            ContentValues().apply {
                put("id", a.id); put("product_id", a.productId)
                put("alias", a.alias); put("normalized_alias", a.normalizedAlias)
                put("source", a.source.name); put("weight", a.confidence)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun saveAttribute(a: ProductAttribute) {
        db.writableDatabase.insertWithOnConflict(
            "product_attributes", null,
            ContentValues().apply {
                put("id", a.id); put("product_id", a.productId)
                put("attr_key", a.name); put("attr_value", a.value)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun saveBarcode(b: ProductBarcode) {
        db.writableDatabase.insertWithOnConflict(
            "product_barcodes", null,
            ContentValues().apply {
                put("id", b.id); put("product_id", b.productId); put("barcode", b.barcode)
                put("barcode_type", b.barcodeType.name)
                put("is_primary", if (b.isPrimary) 1 else 0)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun saveConversion(c: UnitConversion) {
        db.writableDatabase.insertWithOnConflict(
            "unit_conversions", null,
            ContentValues().apply {
                put("id", c.id); put("product_id", c.productId)
                put("from_unit", c.fromUnit.code); put("to_unit", c.toUnit.code)
                put("ratio_numerator", c.ratioNumerator); put("ratio_denominator", c.ratioDenominator)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun savePriceHistory(e: PriceHistoryEntry) {
        db.writableDatabase.insertWithOnConflict(
            "price_history", null,
            ContentValues().apply {
                put("id", e.id); put("product_id", e.productId)
                put("price_type", e.priceType.name)
                put("old_minor", e.oldPrice?.minor); put("new_minor", e.newPrice.minor)
                put("unit", e.unit.code); put("source", e.source)
                put("changed_at", e.createdAtMillis)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ── 三本账流水 ──

    fun appendLedger(e: LedgerEntry) {
        db.writableDatabase.insertWithOnConflict(
            "ledger_entries", null,
            ContentValues().apply {
                put("id", e.id); put("scope_type", e.scope.type.name)
                put("scope_id", e.scope.scopeId)
                put("movement_type", e.movementType.name); put("delta", e.delta)
                put("reference_type", e.referenceType); put("reference_id", e.referenceId)
                put("idem_key", e.idempotencyKey.value); put("note", e.note)
                put("created_at", e.createdAtMillis)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE // 幂等键唯一：重复追加忽略
        )
    }

    // ── 销售与支付 ──

    fun saveSale(o: SaleOrder) {
        val dbw = db.writableDatabase
        dbw.insertWithOnConflict(
            "sales", null,
            ContentValues().apply {
                put("id", o.id); put("store_id", o.storeId); put("status", o.status.name)
                put("payment_method", o.paymentMethod?.name)
                put("total_minor", o.total.minor)
                put("checkout_idem_key", o.checkoutIdempotencyKey)
                put("customer_id", o.customerId); put("member_id", o.memberId)
                put("created_at", o.createdAtMillis); put("completed_at", o.completedAtMillis)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
        dbw.delete("sale_items", "sale_id=?", arrayOf(o.id))
        o.items.forEachIndexed { index, item ->
            dbw.insert(
                "sale_items", null,
                ContentValues().apply {
                    put("id", item.id); put("sale_id", o.id); put("idx", index)
                    put("product_id", item.productId); put("product_name", item.productName)
                    put("quantity_scaled", item.quantity.scaled)
                    put("quantity_unit", item.quantity.unit.code)
                    put("unit_price_minor", item.unitPrice.minor)
                    put("subtotal_minor", item.subtotal.minor)
                }
            )
        }
    }

    fun recordPayment(p: Payment) {
        db.writableDatabase.insertWithOnConflict(
            "payments", null,
            ContentValues().apply {
                put("id", p.id); put("sale_id", p.saleOrderId)
                put("method", p.method.name); put("status", p.status.name)
                put("amount_minor", p.amountMinor)
                put("external_reference", p.externalReference)
                put("idem_key", p.idempotencyKey)
                put("created_at", p.createdAtMillis); put("confirmed_at", p.confirmedAtMillis)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ── 采购 / 损耗 ──

    fun savePurchase(o: PurchaseOrder) {
        db.writableDatabase.insertWithOnConflict(
            "purchases", null,
            ContentValues().apply {
                put("id", o.id); put("store_id", o.storeId)
                put("product_id", o.items.firstOrNull()?.productId)
                put("quantity_scaled", o.items.firstOrNull()?.quantity?.scaled)
                put("quantity_unit", o.items.firstOrNull()?.quantity?.unit?.code)
                put("unit_cost_minor", o.items.firstOrNull()?.unitCost?.minor)
                put("idem_key", o.idempotencyKey); put("created_at", o.createdAtMillis)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun saveLoss(r: LossRecord) {
        db.writableDatabase.insertWithOnConflict(
            "losses", null,
            ContentValues().apply {
                put("id", r.id); put("product_id", r.productId)
                put("quantity_scaled", r.quantity.scaled); put("quantity_unit", r.quantity.unit.code)
                put("reason", r.reason); put("cost_minor", r.costAmountMinor)
                put("stock_entry_id", r.stockLedgerEntryId); put("created_at", r.createdAtMillis)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ── 会员 / 客户 ──

    fun saveMember(m: Member) {
        db.writableDatabase.insertWithOnConflict(
            "members", null,
            ContentValues().apply {
                put("id", m.id); put("store_id", m.storeId)
                put("name", m.name); put("normalized_name", m.normalizedName)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun saveCustomer(c: Customer) {
        db.writableDatabase.insertWithOnConflict(
            "customers", null,
            ContentValues().apply {
                put("id", c.id); put("store_id", c.storeId)
                put("name", c.name); put("normalized_name", c.normalizedName)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ── 日结 ──

    fun saveDayClose(d: DayClose) {
        db.writableDatabase.insertWithOnConflict(
            "day_closes", null,
            ContentValues().apply {
                put("id", d.id); put("business_date", d.businessDate.toString())
                put("opened_at", d.openedAtMillis); put("closed_at", d.closedAtMillis)
                put("cash_expected", d.cashExpectedMinor); put("cash_actual", d.cashActualMinor)
                put("variance", d.varianceMinor); put("status", d.status.name)
                put("note", d.note)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ── 命令日志 ──

    fun appendJournal(r: CommandRecord) {
        db.writableDatabase.insertWithOnConflict(
            "command_journal", null,
            ContentValues().apply {
                put("id", r.id); put("tool_name", r.toolName)
                put("entities_json", r.entitiesJson)
                put("model_id", r.modelId); put("prompt_version", r.promptVersion)
                put("tool_schema_version", r.toolSchemaVersion); put("app_version", r.appVersion)
                put("created_at", r.createdAtMillis)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun clearJournal() {
        db.writableDatabase.delete("command_journal", null, null)
    }

    // ── 会话上下文 ──

    fun saveContext(c: SessionContext) {
        val json = JSONObject().apply {
            put("deviceSessionId", c.deviceSessionId)
            put("activeSaleOrderId", c.activeSaleOrderId)
            put("lastProductId", c.lastProductId)
            put("lastCustomerId", c.lastCustomerId)
            put("lastMemberId", c.lastMemberId)
            put("lastIntent", c.lastIntent)
            put("contextJson", JSONObject(c.contextJson))
            put("expiresAtMillis", c.expiresAtMillis)
            put("updatedAtMillis", c.updatedAtMillis)
        }
        db.writableDatabase.insertWithOnConflict(
            "session_contexts", null,
            ContentValues().apply {
                put("device_id", c.deviceSessionId); put("json", json.toString())
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun clearContext(deviceSessionId: String) {
        db.writableDatabase.delete("session_contexts", "device_id=?", arrayOf(deviceSessionId))
    }

    // ── 记忆 ──

    fun upsertMemory(m: MemoryFact) {
        db.writableDatabase.insertWithOnConflict(
            "memory_facts", null,
            ContentValues().apply {
                put("id", m.id); put("scope_type", m.scopeType.name)
                put("scope_id", m.scopeId); put("fact_type", m.factType)
                put("fact_key", m.key); put("value_json", m.valueJson)
                put("confidence", m.confidence); put("source", m.source.name)
                put("last_confirmed_at", m.lastConfirmedAtMillis)
                put("created_at", m.createdAtMillis); put("updated_at", m.updatedAtMillis)
                put("active", if (m.active) 1 else 0)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun deactivateMemory(id: String) {
        db.writableDatabase.execSQL("UPDATE memory_facts SET active=0 WHERE id=?", arrayOf(id))
    }
}

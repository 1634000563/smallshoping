package com.smallshoping.app.data.sqlite

import android.database.Cursor
import com.smallshoping.app.ai.context.SessionContext
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.data.ledger.InMemoryLedger
import com.smallshoping.app.data.repository.InMemoryLossRepository
import com.smallshoping.app.data.repository.InMemoryProductRepository
import com.smallshoping.app.data.repository.InMemoryPurchaseRepository
import com.smallshoping.app.data.repository.InMemorySaleRepository
import com.smallshoping.app.domain.catalog.AliasSource
import com.smallshoping.app.domain.catalog.BarcodeType
import com.smallshoping.app.domain.catalog.PriceHistoryEntry
import com.smallshoping.app.domain.catalog.PriceType
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductAlias
import com.smallshoping.app.domain.catalog.ProductAttribute
import com.smallshoping.app.domain.catalog.ProductBarcode
import com.smallshoping.app.domain.catalog.UnitConversion
import com.smallshoping.app.domain.customer.Customer
import com.smallshoping.app.domain.inventory.LossRecord
import com.smallshoping.app.domain.journal.CommandRecord
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import com.smallshoping.app.domain.member.Member
import com.smallshoping.app.domain.memory.MemoryFact
import com.smallshoping.app.domain.memory.MemoryScopeType
import com.smallshoping.app.domain.memory.MemorySource
import com.smallshoping.app.domain.payment.Payment
import com.smallshoping.app.domain.payment.PaymentStatus
import com.smallshoping.app.domain.purchase.PurchaseItem
import com.smallshoping.app.domain.purchase.PurchaseOrder
import com.smallshoping.app.domain.purchase.PurchaseStatus
import com.smallshoping.app.domain.report.DayClose
import com.smallshoping.app.domain.report.DayCloseStatus
import com.smallshoping.app.domain.sales.PaymentMethod
import com.smallshoping.app.domain.sales.SaleItem
import com.smallshoping.app.domain.sales.SaleOrder
import com.smallshoping.app.domain.sales.SaleStatus
import org.json.JSONObject
import java.time.LocalDate

/**
 * 启动水合（Task 059 SQLite 持久化）：
 * App 启动时把 SQLite 里的全部事实重建进内存仓库（内存仍是运行时主存）。
 * 恢复语义：账本流水按幂等键追加（余额重建）；已完成销售单/采购单/损耗单
 * 直接恢复单证，不重复记库存流水（流水已在账本表恢复）。
 */
object Hydrator {

    fun load(
        db: ShopDatabase,
        ledger: InMemoryLedger,
        products: InMemoryProductRepository,
        sales: InMemorySaleRepository,
        purchases: InMemoryPurchaseRepository,
        losses: InMemoryLossRepository,
        payments: com.smallshoping.app.domain.payment.PaymentRepository,
        members: com.smallshoping.app.domain.member.MemberRepository,
        customers: com.smallshoping.app.domain.customer.CustomerRepository,
        dayCloses: com.smallshoping.app.domain.report.DayCloseRepository,
        journal: com.smallshoping.app.domain.journal.CommandJournal,
        contexts: com.smallshoping.app.ai.context.SessionContextStore,
        memory: com.smallshoping.app.domain.memory.MemoryStore
    ) {
        val r = db.readableDatabase

        // 1) 商品（先恢复，后续销售/采购明细引用商品名）
        r.rawQuery(
            "SELECT id,store_id,name,normalized_name,sale_unit,purchase_unit,sale_price_minor,cost_price_minor FROM products",
            null
        ).use { c ->
            while (c.moveToNext()) {
                products.saveProduct(
                    Product(
                        id = c.str("id"), storeId = c.str("store_id"),
                        name = c.str("name"), normalizedName = c.str("normalized_name"),
                        saleUnit = unitByCode(c.str("sale_unit")),
                        purchaseUnit = unitByCode(c.str("purchase_unit")),
                        currentSalePrice = Money(c.lng("sale_price_minor")),
                        currentCostPrice = c.optLongOrNull("cost_price_minor")?.let { Money(it) }
                    )
                )
            }
        }
        r.rawQuery("SELECT id,product_id,alias,normalized_alias,source,weight FROM product_aliases", null)
            .use { c ->
                while (c.moveToNext()) {
                    products.addAlias(
                        ProductAlias(
                            id = c.str("id"), productId = c.str("product_id"),
                            alias = c.str("alias"), normalizedAlias = c.str("normalized_alias"),
                            source = AliasSource.valueOf(c.str("source")),
                            confidence = c.int("weight")
                        )
                    )
                }
            }
        r.rawQuery("SELECT id,product_id,attr_key,attr_value FROM product_attributes", null)
            .use { c ->
                while (c.moveToNext()) {
                    products.addAttribute(
                        ProductAttribute(
                            id = c.str("id"), productId = c.str("product_id"),
                            name = c.str("attr_key"), normalizedName = normalize(c.str("attr_key")),
                            value = c.str("attr_value"), normalizedValue = normalize(c.str("attr_value"))
                        )
                    )
                }
            }
        r.rawQuery(
            "SELECT id,product_id,barcode,barcode_type,is_primary FROM product_barcodes", null
        ).use { c ->
            while (c.moveToNext()) {
                products.addBarcode(
                    ProductBarcode(
                        id = c.str("id"), productId = c.str("product_id"),
                        barcode = c.str("barcode"),
                        barcodeType = BarcodeType.valueOf(c.str("barcode_type")),
                        isPrimary = c.int("is_primary") == 1
                    )
                )
            }
        }
        r.rawQuery(
            "SELECT id,product_id,from_unit,to_unit,ratio_numerator,ratio_denominator FROM unit_conversions", null
        ).use { c ->
            while (c.moveToNext()) {
                products.addConversion(
                    UnitConversion(
                        id = c.str("id"), productId = c.str("product_id"),
                        fromUnit = unitByCode(c.str("from_unit")),
                        toUnit = unitByCode(c.str("to_unit")),
                        ratioNumerator = c.lng("ratio_numerator"),
                        ratioDenominator = c.lng("ratio_denominator")
                    )
                )
            }
        }
        r.rawQuery(
            "SELECT id,product_id,price_type,old_minor,new_minor,unit,source,changed_at FROM price_history", null
        ).use { c ->
            while (c.moveToNext()) {
                products.appendPriceHistory(
                    PriceHistoryEntry(
                        id = c.str("id"), productId = c.str("product_id"),
                        priceType = PriceType.valueOf(c.str("price_type")),
                        oldPrice = c.optLongOrNull("old_minor")?.let { Money(it) },
                        newPrice = Money(c.lng("new_minor")),
                        unit = unitByCode(c.str("unit")),
                        source = c.str("source"),
                        createdAtMillis = c.lng("changed_at")
                    )
                )
            }
        }

        // 2) 三本账流水（幂等键唯一，重复恢复自动去重；余额由内存账本重建）
        r.rawQuery(
            "SELECT id,scope_type,scope_id,movement_type,delta,reference_type,reference_id,idem_key,note,created_at FROM ledger_entries ORDER BY created_at",
            null
        ).use { c ->
            while (c.moveToNext()) {
                ledger.append(
                    LedgerEntry(
                        id = c.str("id"),
                        scope = LedgerScope(
                            LedgerScopeType.valueOf(c.str("scope_type")), c.str("scope_id")
                        ),
                        movementType = MovementType.valueOf(c.str("movement_type")),
                        delta = c.lng("delta"),
                        referenceType = c.optStrOrNull("reference_type"),
                        referenceId = c.optStrOrNull("reference_id"),
                        idempotencyKey = IdempotencyKey(c.str("idem_key")),
                        note = c.optStrOrNull("note"),
                        createdAtMillis = c.lng("created_at")
                    )
                )
            }
        }

        // 3) 会员 / 客户
        r.rawQuery("SELECT id,store_id,name,normalized_name FROM members", null).use { c ->
            while (c.moveToNext()) {
                members.saveMember(
                    Member(
                        id = c.str("id"), storeId = c.str("store_id"),
                        name = c.str("name"), normalizedName = c.str("normalized_name")
                    )
                )
            }
        }
        r.rawQuery("SELECT id,store_id,name,normalized_name FROM customers", null).use { c ->
            while (c.moveToNext()) {
                customers.saveCustomer(
                    Customer(
                        id = c.str("id"), storeId = c.str("store_id"),
                        name = c.str("name"), normalizedName = c.str("normalized_name")
                    )
                )
            }
        }

        // 4) 销售单（已完成单直接恢复；草稿单恢复待继续结账）
        val itemCache = HashMap<String, List<SaleItem>>()
        r.rawQuery(
            "SELECT id,sale_id,product_id,product_name,quantity_scaled,quantity_unit,unit_price_minor,subtotal_minor FROM sale_items ORDER BY sale_id,idx",
            null
        ).use { c ->
            while (c.moveToNext()) {
                itemCache.getOrPut(c.str("sale_id")) { ArrayList() }.let {
                    (it as MutableList).add(
                        SaleItem(
                            id = c.str("id"),
                            productId = c.str("product_id"),
                            productName = c.str("product_name"),
                            quantity = Quantity(c.lng("quantity_scaled"), unitByCode(c.str("quantity_unit"))),
                            unitPrice = Money(c.lng("unit_price_minor")),
                            subtotal = Money(c.lng("subtotal_minor"))
                        )
                    )
                }
            }
        }
        r.rawQuery(
            "SELECT id,store_id,status,payment_method,total_minor,checkout_idem_key,customer_id,member_id,created_at,completed_at FROM sales",
            null
        ).use { c ->
            while (c.moveToNext()) {
                sales.restore(
                    SaleOrder(
                        id = c.str("id"), storeId = c.str("store_id"),
                        items = itemCache[c.str("id")] ?: emptyList(),
                        status = SaleStatus.valueOf(c.str("status")),
                        paymentMethod = c.optStrOrNull("payment_method")?.let { PaymentMethod.valueOf(it) },
                        total = Money(c.lng("total_minor")),
                        checkoutIdempotencyKey = c.optStrOrNull("checkout_idem_key"),
                        customerId = c.optStrOrNull("customer_id"),
                        memberId = c.optStrOrNull("member_id"),
                        createdAtMillis = c.lng("created_at"),
                        completedAtMillis = c.optLongOrNull("completed_at")
                    )
                )
            }
        }

        // 5) 支付记录
        r.rawQuery(
            "SELECT id,sale_id,method,status,amount_minor,external_reference,idem_key,created_at,confirmed_at FROM payments",
            null
        ).use { c ->
            while (c.moveToNext()) {
                payments.record(
                    Payment(
                        id = c.str("id"), saleOrderId = c.str("sale_id"),
                        method = PaymentMethod.valueOf(c.str("method")),
                        status = PaymentStatus.valueOf(c.str("status")),
                        amountMinor = c.lng("amount_minor"),
                        externalReference = c.optStrOrNull("external_reference"),
                        idempotencyKey = c.str("idem_key"),
                        createdAtMillis = c.lng("created_at"),
                        confirmedAtMillis = c.optLongOrNull("confirmed_at")
                    )
                )
            }
        }

        // 6) 采购单 / 损耗单
        r.rawQuery(
            "SELECT id,store_id,product_id,quantity_scaled,quantity_unit,unit_cost_minor,idem_key,created_at FROM purchases",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val productId = c.optStrOrNull("product_id")
                val productName = productId?.let { products.findProductById(it)?.name } ?: ""
                val quantity = Quantity(
                    c.lng("quantity_scaled"), unitByCode(c.str("quantity_unit"))
                )
                val unitCost = c.optLongOrNull("unit_cost_minor")?.let { Money(it) }
                purchases.restore(
                    PurchaseOrder(
                        id = c.str("id"), storeId = c.str("store_id"),
                        items = listOf(
                            PurchaseItem(
                                productId = productId ?: "",
                                productName = productName,
                                quantity = quantity,
                                unitCost = unitCost,
                                subtotalCost = unitCost?.timesRatio(quantity.scaled, quantity.unit.scale)
                            )
                        ),
                        status = PurchaseStatus.COMPLETED,
                        idempotencyKey = c.str("idem_key"),
                        createdAtMillis = c.lng("created_at")
                    )
                )
            }
        }
        r.rawQuery(
            "SELECT id,product_id,quantity_scaled,quantity_unit,reason,cost_minor,stock_entry_id,created_at FROM losses",
            null
        ).use { c ->
            while (c.moveToNext()) {
                losses.restore(
                    LossRecord(
                        id = c.str("id"), productId = c.str("product_id"),
                        quantity = Quantity(c.lng("quantity_scaled"), unitByCode(c.str("quantity_unit"))),
                        reason = c.str("reason"),
                        costAmountMinor = c.lng("cost_minor"),
                        stockLedgerEntryId = c.str("stock_entry_id"),
                        createdAtMillis = c.lng("created_at")
                    )
                )
            }
        }

        // 7) 日结快照
        r.rawQuery(
            "SELECT id,business_date,opened_at,closed_at,cash_expected,cash_actual,variance,status,note FROM day_closes",
            null
        ).use { c ->
            while (c.moveToNext()) {
                dayCloses.close(
                    DayClose(
                        id = c.str("id"), businessDate = LocalDate.parse(c.str("business_date")),
                        openedAtMillis = c.lng("opened_at"),
                        closedAtMillis = c.lng("closed_at"),
                        cashExpectedMinor = c.lng("cash_expected"),
                        cashActualMinor = c.lng("cash_actual"),
                        varianceMinor = c.lng("variance"),
                        status = DayCloseStatus.valueOf(c.str("status")),
                        note = c.str("note")
                    )
                )
            }
        }

        // 8) 命令日志
        r.rawQuery(
            "SELECT id,tool_name,entities_json,model_id,prompt_version,tool_schema_version,app_version,created_at FROM command_journal",
            null
        ).use { c ->
            while (c.moveToNext()) {
                journal.append(
                    CommandRecord(
                        id = c.str("id"), toolName = c.str("tool_name"),
                        entitiesJson = c.str("entities_json"),
                        createdAtMillis = c.lng("created_at"),
                        modelId = c.str("model_id"),
                        promptVersion = c.str("prompt_version"),
                        toolSchemaVersion = c.str("tool_schema_version"),
                        appVersion = c.str("app_version")
                    )
                )
            }
        }

        // 9) 会话上下文
        r.rawQuery("SELECT device_id,json FROM session_contexts", null).use { c ->
            while (c.moveToNext()) {
                val j = JSONObject(c.str("json"))
                val contextJson = HashMap<String, String>()
                val inner = j.optJSONObject("contextJson")
                inner?.keys()?.forEach { k -> contextJson[k] = inner.optString(k) }
                contexts.save(
                    SessionContext(
                        deviceSessionId = j.getString("deviceSessionId"),
                        activeSaleOrderId = j.optStringOrNull("activeSaleOrderId"),
                        lastProductId = j.optStringOrNull("lastProductId"),
                        lastCustomerId = j.optStringOrNull("lastCustomerId"),
                        lastMemberId = j.optStringOrNull("lastMemberId"),
                        lastIntent = j.optStringOrNull("lastIntent"),
                        contextJson = contextJson,
                        expiresAtMillis = j.getLong("expiresAtMillis"),
                        updatedAtMillis = j.optLong("updatedAtMillis")
                    )
                )
            }
        }

        // 10) 记忆
        r.rawQuery(
            "SELECT id,scope_type,scope_id,fact_type,fact_key,value_json,confidence,source,last_confirmed_at,created_at,updated_at,active FROM memory_facts",
            null
        ).use { c ->
            while (c.moveToNext()) {
                memory.upsert(
                    MemoryFact(
                        id = c.str("id"),
                        scopeType = MemoryScopeType.valueOf(c.str("scope_type")),
                        scopeId = c.str("scope_id"),
                        factType = c.str("fact_type"),
                        key = c.str("fact_key"),
                        valueJson = c.str("value_json"),
                        confidence = c.int("confidence"),
                        source = MemorySource.valueOf(c.str("source")),
                        lastConfirmedAtMillis = c.optLongOrNull("last_confirmed_at"),
                        createdAtMillis = c.lng("created_at"),
                        updatedAtMillis = c.lng("updated_at"),
                        active = c.int("active") == 1
                    )
                )
            }
        }
    }

    private fun unitByCode(code: String): Unit =
        listOf(Unit.GRAM, Unit.KILOGRAM, Unit.JIN, Unit.PIECE, Unit.BOX, Unit.METER)
            .firstOrNull { it.code == code }
            ?: error("未知单位编码：$code")

    private fun Cursor.str(col: String): String = getString(getColumnIndexOrThrow(col))
    private fun Cursor.optStrOrNull(col: String): String? =
        if (isNull(getColumnIndexOrThrow(col))) null else getString(getColumnIndexOrThrow(col))
    private fun Cursor.lng(col: String): Long = getLong(getColumnIndexOrThrow(col))
    private fun Cursor.optLongOrNull(col: String): Long? =
        if (isNull(getColumnIndexOrThrow(col))) null else getLong(getColumnIndexOrThrow(col))
    private fun Cursor.int(col: String): Int = getInt(getColumnIndexOrThrow(col))

    private fun JSONObject.optStringOrNull(key: String): String? {
        val v = optString(key)
        return v.takeIf { it.isNotEmpty() }
    }
}

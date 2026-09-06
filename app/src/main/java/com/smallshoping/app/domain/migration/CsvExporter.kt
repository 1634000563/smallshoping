package com.smallshoping.app.domain.migration

import com.smallshoping.app.domain.catalog.ProductRepository
import com.smallshoping.app.domain.inventory.StockQuery
import com.smallshoping.app.domain.ledger.Ledger
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import com.smallshoping.app.domain.payment.PaymentRepository
import com.smallshoping.app.domain.sales.SaleRepository
import com.smallshoping.app.domain.sales.SaleStatus
import java.time.Instant

/**
 * CSV 导出（spec 19 §4 V1 至少支持：商品、当前库存、销售明细、会员流水、客户欠款、采购）。
 * 全部从事实聚合，只读。
 */
class CsvExporter(
    private val products: ProductRepository,
    private val sales: SaleRepository,
    private val payments: PaymentRepository,
    private val ledger: Ledger
) {

    private val stock = StockQuery(ledger)

    /** 商品与当前库存。 */
    fun exportProducts(): String {
        val rows = mutableListOf("name,sale_unit,sale_price_minor,cost_price_minor,stock")
        for (p in products.allProducts()) {
            rows.add(
                CsvIo.encodeRow(
                    listOf(
                        p.name,
                        p.saleUnit.name,
                        p.currentSalePrice.minor.toString(),
                        p.currentCostPrice?.minor?.toString() ?: "",
                        stock.stockOf(p.id).toString()
                    )
                )
            )
        }
        return rows.joinToString("\n")
    }

    /** 销售明细（完成单的行级明细）。 */
    fun exportSales(): String {
        val rows = mutableListOf("sale_id,completed_at,product_name,quantity_scaled,unit_price_minor,subtotal_minor,method")
        for (sale in sales.allSales().filter { it.status == SaleStatus.COMPLETED }
            .sortedBy { it.completedAtMillis }) {
            val method = sale.paymentMethod?.name ?: ""
            val completedAt = sale.completedAtMillis?.let { Instant.ofEpochMilli(it).toString() } ?: ""
            for (item in sale.items) {
                rows.add(
                    CsvIo.encodeRow(
                        listOf(
                            sale.id, completedAt, item.productName,
                            item.quantity.scaled.toString(),
                            item.unitPrice.minor.toString(),
                            item.subtotal.minor.toString(),
                            method
                        )
                    )
                )
            }
        }
        return rows.joinToString("\n")
    }

    /** 会员流水。 */
    fun exportMemberLedger(): String {
        val rows = mutableListOf("member_id,movement_type,delta_minor,created_at,note")
        for (scope in ledger.allScopes().filter { it.type == LedgerScopeType.MEMBER }) {
            for (entry in ledger.entries(scope)) {
                rows.add(
                    CsvIo.encodeRow(
                        listOf(
                            scope.scopeId,
                            entry.movementType.name,
                            entry.delta.toString(),
                            Instant.ofEpochMilli(entry.createdAtMillis).toString(),
                            entry.note ?: ""
                        )
                    )
                )
            }
        }
        return rows.joinToString("\n")
    }

    /** 客户欠款流水。 */
    fun exportCustomerDebt(): String {
        val rows = mutableListOf("customer_id,movement_type,delta_minor,created_at,note")
        for (scope in ledger.allScopes().filter { it.type == LedgerScopeType.CUSTOMER }) {
            for (entry in ledger.entries(scope)) {
                rows.add(
                    CsvIo.encodeRow(
                        listOf(
                            scope.scopeId,
                            entry.movementType.name,
                            entry.delta.toString(),
                            Instant.ofEpochMilli(entry.createdAtMillis).toString(),
                            entry.note ?: ""
                        )
                    )
                )
            }
        }
        return rows.joinToString("\n")
    }

    /** 采购流水（入库事实）。 */
    fun exportPurchases(): String {
        val rows = mutableListOf("product_id,delta,created_at,note")
        for (scope in ledger.allScopes().filter { it.type == LedgerScopeType.STOCK }) {
            for (entry in ledger.entries(scope)) {
                if (entry.movementType != MovementType.PURCHASE_IN) continue
                rows.add(
                    CsvIo.encodeRow(
                        listOf(
                            scope.scopeId,
                            entry.delta.toString(),
                            Instant.ofEpochMilli(entry.createdAtMillis).toString(),
                            entry.note ?: ""
                        )
                    )
                )
            }
        }
        return rows.joinToString("\n")
    }

    /** 支付记录明细。 */
    fun exportPayments(): String {
        val rows = mutableListOf("sale_id,method,status,amount_minor,confirmed_at")
        for (payment in payments.all()) {
            rows.add(
                listOf(
                    payment.saleOrderId,
                    payment.method.name,
                    payment.status.name,
                    payment.amountMinor.toString(),
                    payment.confirmedAtMillis?.let { Instant.ofEpochMilli(it).toString() } ?: ""
                ).joinToString(",")
            )
        }
        return rows.joinToString("\n")
    }
}

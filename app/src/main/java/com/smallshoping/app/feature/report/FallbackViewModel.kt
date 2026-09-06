package com.smallshoping.app.feature.report

import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.domain.inventory.StockQuery

/**
 * 兜底查询页 ViewModel（Task 043）：
 * 传统页面仅作兜底——只读查询（历史/商品/报表），
 * 不提供任何写操作入口（Route Guard：不得演变成传统 POS 主流程）。
 *
 * 所有查询经 Domain 查询服务，只展示事实，不修改任何数据。
 */
class FallbackViewModel(private val root: CompositionRoot) {

    private val stock = StockQuery(root.ledger)

    /** 今日销售汇总（spec 03 §6 业务日）。 */
    fun todaySales(): String {
        val summary = root.todaySalesSummary.today()
        return "今天（${summary.businessDate}）\n" +
            "成交 ${summary.count} 单，共 ${summary.total.minor} 分"
    }

    /** 商品列表与当前库存（只读）。 */
    fun productList(): String {
        val products = root.products.allProducts()
        if (products.isEmpty()) return "还没有商品，先语音说「建个商品」吧"
        return products.joinToString("\n") { p ->
            "${p.name}｜售价 ${p.currentSalePrice.minor} 分/${p.saleUnit.name}｜库存 ${stock.stockOf(p.id)} ${p.saleUnit.name}"
        }
    }

    /** 会员余额与客户欠款一览（只读，余额全部由流水派生）。 */
    fun balances(): String {
        val members = root.members.allMembers().map { m ->
            "会员 ${m.name}：余额 ${root.memberFundsQuery.balanceOf(m.id) ?: 0L} 分"
        }
        val customers = root.customers.allCustomers().map { c ->
            "客户 ${c.name}：欠款 ${root.customerDebtQuery.debtOf(c.id) ?: 0L} 分"
        }
        if (members.isEmpty() && customers.isEmpty()) return "还没有会员和客户"
        return (members + customers).joinToString("\n")
    }

    /** 客户历史（按名字精确，只读）。 */
    fun customerHistory(name: String): String {
        val customer = root.customers.findByNormalizedName(normalize(name))
            ?: return "没找到「$name」这个客户"
        val sales = root.customerHistory.completedSales(customer.id)
        if (sales.isEmpty()) return "「${customer.name}」还没有消费记录"
        val lines = sales.map { s ->
            val items = s.items.joinToString("；") { "${it.productName} ×${it.quantity.scaled}" }
            "${s.completedAtMillis}｜${items}｜${s.total.minor} 分"
        }
        val top = root.customerHistory.topProducts(customer.id, limit = 3)
            .joinToString("、") { it.productName }
        return "「${customer.name}」共 ${sales.size} 单，累计 " +
            "${root.customerHistory.totalSpentMinor(customer.id)} 分\n常用：$top\n" +
            lines.joinToString("\n")
    }

    /** 商品价格历史（只读，只追加不覆盖）。 */
    fun priceHistory(productName: String): String {
        val product = root.products.findByNormalizedName(normalize(productName))
            ?: return "没找到「$productName」这个商品"
        val history = root.products.priceHistory(product.id)
        if (history.isEmpty()) return "「${product.name}」还没有价格变动记录"
        return history.joinToString("\n") { e ->
            val old = e.oldPrice?.minor?.toString() ?: "无"
            "${e.priceType.name}：$old 分 → ${e.newPrice.minor} 分（${e.source}）"
        }
    }
}

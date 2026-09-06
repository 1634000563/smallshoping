package com.smallshoping.app.feature.report

import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.customer.Customer
import com.smallshoping.app.domain.customer.CustomerDebtRequest
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import com.smallshoping.app.domain.member.Member
import com.smallshoping.app.domain.member.RechargeMemberRequest
import com.smallshoping.app.domain.sales.AddSaleItemRequest
import com.smallshoping.app.domain.sales.CheckoutSaleRequest
import com.smallshoping.app.domain.sales.PaymentMethod
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 043：兜底查询页只读视图模型（历史/商品/报表，不提供写操作）。 */
class FallbackViewModelTest {

    private val root = CompositionRoot()
    private val viewModel = FallbackViewModel(root)

    private fun seedAll() {
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
                idempotencyKey = IdempotencyKey("IN-043"),
                note = "测试入库"
            )
        )
        root.members.saveMember(
            Member(id = "M-1", storeId = "STORE-1", name = "张姐", normalizedName = normalize("张姐"))
        )
        root.rechargeMemberUseCase(
            RechargeMemberRequest("STORE-1", "M-1", Money.fromYuan(200), "recharge-043")
        )
        root.customers.saveCustomer(
            Customer(id = "C-1", storeId = "STORE-1", name = "老张", normalizedName = normalize("老张"))
        )
        root.recordCustomerCreditUseCase(
            CustomerDebtRequest("STORE-1", "C-1", Money(500), "credit-043", "赊账")
        )
        // 一单完成的销售
        val added = root.addSaleItemUseCase(
            AddSaleItemRequest("STORE-1", null, "P-1", Quantity(1000, Unit.GRAM))
        ) as com.smallshoping.app.domain.sales.AddSaleItemResult.Success
        root.checkoutSaleUseCase(
            CheckoutSaleRequest(added.sale.id, PaymentMethod.CASH, "ck-043", customerId = "C-1")
        )
    }

    @Test
    fun `今日销售与商品库存：事实来自账本`() {
        seedAll()
        val sales = viewModel.todaySales()
        assertTrue(sales.contains("1 单"))
        assertTrue(sales.contains("760 分"))

        val products = viewModel.productList()
        assertTrue(products.contains("土豆"))
        assertTrue(products.contains("380 分/斤"))
        assertTrue(products.contains("库存 4000")) // 5000 - 2斤(1000克)
    }

    @Test
    fun `会员客户一览：余额与欠款由流水派生`() {
        seedAll()
        val balances = viewModel.balances()
        assertTrue(balances.contains("张姐"))
        assertTrue(balances.contains("20000 分"))
        assertTrue(balances.contains("老张"))
        assertTrue(balances.contains("500 分"))
    }

    @Test
    fun `客户历史：消费记录与常用商品`() {
        seedAll()
        val history = viewModel.customerHistory("老张")
        assertTrue(history.contains("1 单"))
        assertTrue(history.contains("累计 760 分"))
        assertTrue(history.contains("常用：土豆"))
        assertTrue(viewModel.customerHistory("不存在").contains("没找到"))
    }

    @Test
    fun `价格历史：空历史与不存在商品提示`() {
        seedAll()
        assertTrue(viewModel.priceHistory("土豆").contains("还没有价格变动记录"))
        assertTrue(viewModel.priceHistory("不存在").contains("没找到"))
    }
}

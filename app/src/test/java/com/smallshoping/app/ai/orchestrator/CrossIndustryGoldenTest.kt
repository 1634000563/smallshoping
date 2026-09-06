package com.smallshoping.app.ai.orchestrator

import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.BarcodeType
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductAttribute
import com.smallshoping.app.domain.catalog.ProductBarcode
import com.smallshoping.app.domain.customer.Customer
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import com.smallshoping.app.domain.member.Member
import com.smallshoping.app.domain.sales.SaleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 037 验收：五金/生鲜跨行业黄金语句集——
 * 同一店铺（同一 CompositionRoot 通用底座）混业经营，
 * 菜店与五金复用同一套商品/单位/Tool/Domain（跨行业约束），
 * 每句黄金语句最终落到可追溯的确定性账务事实。
 */
class CrossIndustryGoldenTest {

    private val root = CompositionRoot()

    private fun seedMixedStore() {
        // 生鲜：土豆
        root.products.saveProduct(
            Product(
                id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
                saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
                currentSalePrice = Money(380), currentCostPrice = null
            )
        )
        // 五金：螺栓（属性化表达 + 条码）
        root.products.saveProduct(
            Product(
                id = "P-10", storeId = "STORE-1", name = "304 M8x30 外六角螺栓",
                normalizedName = normalize("304 M8x30 外六角螺栓"),
                saleUnit = Unit.PIECE, purchaseUnit = Unit.PIECE,
                currentSalePrice = Money(50), currentCostPrice = Money(30)
            )
        )
        root.products.addAttribute(
            ProductAttribute(
                productId = "P-10", name = "材质", normalizedName = normalize("材质"),
                value = "304", normalizedValue = normalize("304")
            )
        )
        root.products.addBarcode(
            ProductBarcode(
                productId = "P-10", barcode = "6901234567891",
                barcodeType = BarcodeType.EAN13, isPrimary = true
            )
        )
        root.ledger.append(
            LedgerEntry(
                scope = LedgerScope(LedgerScopeType.STOCK, "P-10"),
                movementType = MovementType.PURCHASE_IN,
                delta = 100,
                idempotencyKey = IdempotencyKey("IN-HW-037"),
                note = "测试入库"
            )
        )
        root.members.saveMember(
            Member(id = "M-1", storeId = "STORE-1", name = "张姐", normalizedName = normalize("张姐"))
        )
        root.customers.saveCustomer(
            Customer(id = "C-1", storeId = "STORE-1", name = "老张", normalizedName = normalize("老张"))
        )
    }

    private fun confirmNeeds(handle: OrchestratorReply): OrchestratorReply {
        assertTrue(handle is OrchestratorReply.NeedsConfirm)
        return root.orchestrator.confirm((handle as OrchestratorReply.NeedsConfirm).requestId, true)
    }

    @Test
    fun `跨行业黄金语句：同一底座混业经营全链路`() {
        seedMixedStore()
        // —— 生鲜：进货 → 称重销售 ——
        val purchase = root.orchestrator.handle(root.inputAdapter.fromText("进100斤土豆，成本2块8"))
        assertTrue((purchase as OrchestratorReply.Text).text.contains("已入库"))
        val vegAdd = root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        assertTrue((vegAdd as OrchestratorReply.Text).text.contains("已加入"))

        // —— 五金：规格报价 → 条码 → 销售 ——
        val quote = root.orchestrator.handle(root.inputAdapter.fromText("304的螺栓多少钱"))
        assertTrue((quote as OrchestratorReply.Text).text.contains("50"))
        val scan = root.orchestrator.handle(root.inputAdapter.fromText("6901234567891"))
        assertTrue((scan as OrchestratorReply.Text).text.contains("螺栓"))
        val hwAdd = root.orchestrator.handle(root.inputAdapter.fromText("卖5个304螺栓"))
        assertTrue((hwAdd as OrchestratorReply.Text).text.contains("已加入"))

        // —— 混合结账：760（土豆2斤）+ 250（螺栓5个）= 1010 分 ——
        val checkout = confirmNeeds(root.orchestrator.handle(root.inputAdapter.fromText("结账")))
        assertTrue((checkout as OrchestratorReply.Text).text.contains("结账完成"))

        // —— 会员充值（跨行业共享）——
        val recharge = confirmNeeds(root.orchestrator.handle(root.inputAdapter.fromText("给张姐充200")))
        assertTrue((recharge as OrchestratorReply.Text).text.contains("已充值"))

        // —— 客户赊账：五金再卖 1 个 → 先记账 ——
        root.orchestrator.handle(root.inputAdapter.fromText("卖1个304螺栓"))
        val credit = confirmNeeds(root.orchestrator.handle(root.inputAdapter.fromText("老张先记账")))
        assertTrue((credit as OrchestratorReply.Text).text.contains("已记账"))

        // —— 生鲜损耗 ——
        val loss = confirmNeeds(root.orchestrator.handle(root.inputAdapter.fromText("损耗半斤土豆")))
        assertTrue((loss as OrchestratorReply.Text).text.contains("已记录损耗"))

        // —— 汇总：两单完成 = 1010 + 50 = 1060 分 ——
        val summary = root.orchestrator.handle(root.inputAdapter.fromText("今天卖了多少钱"))
        assertTrue((summary as OrchestratorReply.Text).text.contains("1060"))

        // —— 确定性账务事实（逐项可追溯）——
        val stock = com.smallshoping.app.domain.inventory.StockQuery(root.ledger)
        // 土豆：50000 - 1000 - 250 = 48750 克
        assertEquals(48750L, stock.stockOf("P-1"))
        // 螺栓：100 - 5 - 1 = 94 个
        assertEquals(94L, stock.stockOf("P-10"))
        // 会员余额 20000 分，流水 1 条
        assertEquals(20000L, root.memberFundsQuery.balanceOf("M-1"))
        assertEquals(1, root.ledger.entries(LedgerScope(LedgerScopeType.MEMBER, "M-1")).size)
        // 老张欠 50 分
        assertEquals(50L, root.customerDebtQuery.debtOf("C-1"))
        // 客户历史：老张 1 单，常用商品螺栓
        val top = root.customerHistory.topProducts("C-1")
        assertEquals(1, top.size)
        assertEquals("304 M8x30 外六角螺栓", top[0].productName)
        // 两单 COMPLETED
        val completed = root.sales.allSales().filter { it.status == SaleStatus.COMPLETED }
        assertEquals(2, completed.size)
        assertEquals(1010L, completed[0].total.minor)
        assertEquals(50L, completed[1].total.minor)
        assertEquals("C-1", completed[1].customerId)
        // 损耗 1 条，成本快照 280×250/500=140
        assertEquals(1, root.losses.all().size)
        assertEquals(140L, root.losses.all()[0].costAmountMinor)
    }
}

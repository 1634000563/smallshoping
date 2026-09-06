package com.smallshoping.app.app.di

import com.smallshoping.app.ai.context.SessionContextStore
import com.smallshoping.app.ai.entityresolution.ProductResolver
import com.smallshoping.app.ai.orchestrator.AiOrchestrator
import com.smallshoping.app.ai.orchestrator.InputAdapter
import com.smallshoping.app.ai.orchestrator.StoreSession
import com.smallshoping.app.ai.providers.LocalRuleParser
import com.smallshoping.app.ai.risk.ConfirmationGate
import com.smallshoping.app.ai.risk.RiskGate
import com.smallshoping.app.ai.tools.AddSaleItemHandler
import com.smallshoping.app.ai.tools.CheckoutSaleHandler
import com.smallshoping.app.ai.tools.CreateProductHandler
import com.smallshoping.app.ai.tools.FindProductHandler
import com.smallshoping.app.ai.tools.GetContextHandler
import com.smallshoping.app.ai.tools.GetTodaySalesHandler
import com.smallshoping.app.ai.tools.PurchaseInHandler
import com.smallshoping.app.ai.tools.ToolExecutor
import com.smallshoping.app.ai.tools.ToolRef
import com.smallshoping.app.ai.tools.V1ToolCatalog
import com.smallshoping.app.data.ledger.InMemoryLedger
import com.smallshoping.app.data.repository.InMemoryProductRepository
import com.smallshoping.app.data.repository.InMemoryPurchaseRepository
import com.smallshoping.app.data.repository.InMemorySaleRepository
import com.smallshoping.app.data.repository.InMemorySessionContextStore
import com.smallshoping.app.domain.inventory.StockQuery
import com.smallshoping.app.domain.purchase.PurchaseInUseCase
import com.smallshoping.app.domain.report.TodaySalesSummary
import com.smallshoping.app.domain.sales.AddSaleItemUseCase
import com.smallshoping.app.domain.sales.CheckoutSaleUseCase

/**
 * 组合根：V1 手动装配（不引入 DI 框架）。
 *
 * 应用生命周期内创建一个实例；测试每个用例各自 new，避免共享状态。
 * 当前为内存实现 + 本地规则解析；Room 持久化与云端 Provider 接入后
 * 只改这里与对应实现，其余代码不变。
 */
class CompositionRoot {

    val ledger = InMemoryLedger()
    val products = InMemoryProductRepository()
    val sales = InMemorySaleRepository(ledger)
    val contexts: SessionContextStore = InMemorySessionContextStore()

    val session = StoreSession(storeId = "STORE-1", deviceId = "DEVICE-1")

    private val resolver = ProductResolver(products)

    /** Domain UseCase 公开暴露：AI 与人工路径必须复用同一实例（Gate A）。 */
    val addSaleItemUseCase = AddSaleItemUseCase(sales, products)
    val checkoutSaleUseCase = CheckoutSaleUseCase(sales)
    val purchases = InMemoryPurchaseRepository(ledger, products)
    val purchaseInUseCase = PurchaseInUseCase(purchases, products, StockQuery(ledger))

    val executor = ToolExecutor(
        catalog = V1ToolCatalog,
        riskGate = RiskGate(),
        confirmationGate = ConfirmationGate(),
        handlers = mapOf(
            ToolRef("find_product") to FindProductHandler(resolver),
            ToolRef("create_product") to CreateProductHandler(products, session.storeId),
            ToolRef("get_context") to GetContextHandler(contexts, session.deviceId),
            ToolRef("add_sale_item") to AddSaleItemHandler(
                resolver,
                addSaleItemUseCase,
                contexts,
                session
            ),
            ToolRef("checkout_sale") to CheckoutSaleHandler(
                checkoutSaleUseCase,
                contexts,
                session
            ),
            ToolRef("get_today_sales") to GetTodaySalesHandler(TodaySalesSummary(sales)),
            ToolRef("purchase_in") to PurchaseInHandler(resolver, purchaseInUseCase, session)
        )
    )

    val orchestrator = AiOrchestrator(provider = LocalRuleParser(), executor = executor)

    val allowedTools = listOf(
        "find_product", "create_product", "get_context", "add_sale_item",
        "checkout_sale", "get_today_sales", "purchase_in"
    )

    val inputAdapter = InputAdapter(session = session, allowedTools = allowedTools)
}

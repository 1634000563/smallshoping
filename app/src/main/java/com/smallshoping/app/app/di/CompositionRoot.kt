package com.smallshoping.app.app.di

import com.smallshoping.app.ai.context.SessionContextStore
import com.smallshoping.app.ai.entityresolution.CustomerResolver
import com.smallshoping.app.ai.entityresolution.MemberResolver
import com.smallshoping.app.ai.entityresolution.ProductResolver
import com.smallshoping.app.ai.orchestrator.AiOrchestrator
import com.smallshoping.app.ai.orchestrator.InputAdapter
import com.smallshoping.app.ai.orchestrator.StoreSession
import com.smallshoping.app.ai.providers.AiProvider
import com.smallshoping.app.ai.providers.FallbackAiProvider
import com.smallshoping.app.ai.providers.LocalRuleParser
import com.smallshoping.app.ai.risk.ConfirmationGate
import com.smallshoping.app.ai.risk.RiskGate
import com.smallshoping.app.ai.tools.AddSaleItemHandler
import com.smallshoping.app.ai.tools.ApplyYesterdayPriceHandler
import com.smallshoping.app.ai.tools.CheckoutSaleHandler
import com.smallshoping.app.ai.tools.CreateProductHandler
import com.smallshoping.app.ai.tools.ChangePriceHandler
import com.smallshoping.app.ai.tools.RecordCustomerCreditHandler
import com.smallshoping.app.ai.tools.RemoveSaleItemHandler
import com.smallshoping.app.ai.tools.SettleCustomerDebtHandler
import com.smallshoping.app.ai.tools.FindProductByBarcodeHandler
import com.smallshoping.app.ai.tools.FindProductHandler
import com.smallshoping.app.ai.tools.GetContextHandler
import com.smallshoping.app.ai.tools.FindMemberHandler
import com.smallshoping.app.ai.tools.GetMemberBalanceHandler
import com.smallshoping.app.ai.tools.GetTodaySalesHandler
import com.smallshoping.app.ai.tools.PurchaseInHandler
import com.smallshoping.app.ai.tools.RechargeMemberHandler
import com.smallshoping.app.ai.tools.RecordLossHandler
import com.smallshoping.app.ai.tools.ReorderLastItemHandler
import com.smallshoping.app.ai.tools.ToolExecutor
import com.smallshoping.app.ai.tools.ToolRef
import com.smallshoping.app.ai.tools.ReplayRunner
import com.smallshoping.app.ai.tools.V1ToolCatalog
import com.smallshoping.app.data.ledger.InMemoryLedger
import com.smallshoping.app.data.repository.InMemoryCommandJournal
import com.smallshoping.app.data.repository.InMemoryCustomerRepository
import com.smallshoping.app.data.repository.InMemoryLossRepository
import com.smallshoping.app.data.repository.InMemoryDayCloseRepository
import com.smallshoping.app.data.repository.InMemoryDisambiguationStore
import com.smallshoping.app.data.repository.InMemoryMemberRepository
import com.smallshoping.app.data.repository.InMemoryMemoryStore
import com.smallshoping.app.data.repository.InMemoryPaymentRepository
import com.smallshoping.app.data.repository.InMemoryProductRepository
import com.smallshoping.app.data.repository.InMemoryPurchaseRepository
import com.smallshoping.app.data.repository.InMemorySaleRepository
import com.smallshoping.app.data.repository.InMemorySessionContextStore
import com.smallshoping.app.domain.backup.BackupService
import com.smallshoping.app.domain.catalog.ChangeProductPriceUseCase
import com.smallshoping.app.domain.catalog.YesterdayPriceQuery
import com.smallshoping.app.domain.customer.CustomerDebtQuery
import com.smallshoping.app.domain.customer.ReceiveCustomerPaymentUseCase
import com.smallshoping.app.domain.customer.RecordCustomerCreditUseCase
import com.smallshoping.app.domain.inventory.AdjustStockUseCase
import com.smallshoping.app.domain.inventory.RecordLossUseCase
import com.smallshoping.app.domain.inventory.StockQuery
import com.smallshoping.app.domain.member.MemberFundsQuery
import com.smallshoping.app.domain.member.RechargeMemberUseCase
import com.smallshoping.app.domain.migration.CsvExporter
import com.smallshoping.app.domain.migration.CsvImporter
import com.smallshoping.app.domain.payment.ConfirmPaymentUseCase
import com.smallshoping.app.domain.memory.MemoryWritePolicy
import com.smallshoping.app.domain.purchase.PurchaseInUseCase
import com.smallshoping.app.domain.report.CustomerHistory
import com.smallshoping.app.domain.report.DayCloseService
import com.smallshoping.app.domain.security.DataWipeService
import com.smallshoping.app.domain.report.ProductHistory
import com.smallshoping.app.domain.report.TodaySalesSummary
import com.smallshoping.app.domain.catalog.PriceHistoryQuery
import com.smallshoping.app.domain.sales.AddSaleItemUseCase
import com.smallshoping.app.domain.sales.CheckoutSaleUseCase
import com.smallshoping.app.domain.sales.RemoveSaleItemUseCase

/**
 * 组合根：V1 手动装配（不引入 DI 框架）。
 *
 * 应用生命周期内创建一个实例；测试每个用例各自 new，避免共享状态。
 * 当前为内存实现 + 本地规则解析；Room 持久化与云端 Provider 接入后
 * 只改这里与对应实现，其余代码不变。
 */
class CompositionRoot(
    /** 云端模型 Provider（Task 044）：未配置密钥时为空，纯本地离线运行。 */
    cloudProvider: AiProvider? = null
) {

    val ledger = InMemoryLedger()
    val products = InMemoryProductRepository()
    val sales = InMemorySaleRepository(ledger)
    val contexts: SessionContextStore = InMemorySessionContextStore()

    val session = StoreSession(storeId = "STORE-1", deviceId = "DEVICE-1")

    private val resolver = ProductResolver(products)

    val changeProductPriceUseCase = ChangeProductPriceUseCase(products)
    val yesterdayPriceQuery = YesterdayPriceQuery(products)

    /** 历史查询（Task 035）：销售事实聚合，只读。 */
    val customerHistory = CustomerHistory(sales)
    val productHistory = ProductHistory(sales)
    val priceHistoryQuery = PriceHistoryQuery(products)
    val todaySalesSummary = TodaySalesSummary(sales)

    /** Domain UseCase 公开暴露：AI 与人工路径必须复用同一实例（Gate A）。 */
    val addSaleItemUseCase = AddSaleItemUseCase(sales, products)
    val removeSaleItemUseCase = RemoveSaleItemUseCase(sales)
    val purchases = InMemoryPurchaseRepository(ledger, products)
    val purchaseInUseCase = PurchaseInUseCase(purchases, products, StockQuery(ledger))

    val losses = InMemoryLossRepository(ledger)
    val recordLossUseCase = RecordLossUseCase(losses, products, StockQuery(ledger))
    val adjustStockUseCase = AdjustStockUseCase(ledger, products, StockQuery(ledger))

    val members = InMemoryMemberRepository()
    val rechargeMemberUseCase = RechargeMemberUseCase(members, ledger)
    val memberFundsQuery = MemberFundsQuery(members, ledger)

    /** 支付记录（Task 047）：结账写支付记录，会员余额消费同批落账。 */
    val payments = InMemoryPaymentRepository()
    val checkoutSaleUseCase = CheckoutSaleUseCase(sales, ledger, members, payments)
    val confirmPaymentUseCase = ConfirmPaymentUseCase(payments)

    /** 日结/经营核对（Task 048）：快照不修改历史销售。 */
    val dayCloses = InMemoryDayCloseRepository()
    val dayCloseService = DayCloseService(sales, payments, dayCloses, ledger)

    /** CSV 导入导出/旧系统迁移（Task 049）。 */
    val csvExporter = CsvExporter(products, sales, payments, ledger)
    val csvImporter = CsvImporter(products, ledger)

    val customers = InMemoryCustomerRepository()
    val recordCustomerCreditUseCase = RecordCustomerCreditUseCase(customers, ledger)
    val receiveCustomerPaymentUseCase = ReceiveCustomerPaymentUseCase(customers, ledger)
    val customerDebtQuery = CustomerDebtQuery(customers, ledger)

    private val memberResolver = MemberResolver(members)
    private val customerResolver = CustomerResolver(customers)

    /** 店铺长期记忆（spec 06）：只存偏好/别名/规则/引用，不复制账务事实。 */
    val memory = InMemoryMemoryStore()
    private val memoryWritePolicy = MemoryWritePolicy(memory)

    /** 命令日志与重放（Task 045 崩溃恢复）。 */
    val commandJournal = InMemoryCommandJournal()
    val replayRunner: ReplayRunner by lazy { ReplayRunner(commandJournal, executor) }
    val crashRecovery = com.smallshoping.app.domain.journal.CrashRecoveryService(ledger)

    /** 备份/恢复（Task 046）：账务事实+目录+命令日志快照，恢复前校验 checksum 与版本。 */
    val backupService = BackupService(
        ledger = ledger,
        products = products,
        members = members,
        customers = customers,
        journal = commandJournal,
        storeId = session.storeId
    )

    /** 数据擦除（Task 050，spec 13 §5）：唯一受控入口，普通 AI 指令不可触发。 */
    val dataWipeService = DataWipeService(
        ledger = ledger,
        journal = commandJournal,
        wipeSales = { (sales as InMemorySaleRepository).wipe() },
        wipePayments = { payments.wipe() },
        wipeDayCloses = { dayCloses.wipe() }
    )

    private val addItemHandler = AddSaleItemHandler(resolver, addSaleItemUseCase, contexts, session)

    val executor = ToolExecutor(
        catalog = V1ToolCatalog,
        riskGate = RiskGate(),
        confirmationGate = ConfirmationGate(),
        journal = commandJournal,
        handlers = mapOf(
            ToolRef("find_product") to FindProductHandler(resolver),
            ToolRef("find_product_by_barcode") to FindProductByBarcodeHandler(products),
            ToolRef("create_product") to CreateProductHandler(products, session.storeId),
            ToolRef("get_context") to GetContextHandler(contexts, session.deviceId),
            ToolRef("add_sale_item") to addItemHandler,
            ToolRef("checkout_sale") to CheckoutSaleHandler(
                checkoutSaleUseCase,
                contexts,
                session
            ),
            ToolRef("get_today_sales") to GetTodaySalesHandler(todaySalesSummary),
            ToolRef("purchase_in") to PurchaseInHandler(resolver, purchaseInUseCase, session),
            ToolRef("find_member") to FindMemberHandler(memberResolver),
            ToolRef("get_member_balance") to GetMemberBalanceHandler(memberResolver, memberFundsQuery),
            ToolRef("recharge_member") to RechargeMemberHandler(
                memberResolver, rechargeMemberUseCase, session, contexts
            ),
            ToolRef("apply_yesterday_price") to ApplyYesterdayPriceHandler(
                products, contexts, session, yesterdayPriceQuery, changeProductPriceUseCase
            ),
            ToolRef("reorder_last_item") to ReorderLastItemHandler(
                customerResolver, resolver, addItemHandler,
                products, memory, memoryWritePolicy
            ),
            ToolRef("record_loss") to RecordLossHandler(resolver, recordLossUseCase),
            ToolRef("record_customer_credit") to RecordCustomerCreditHandler(
                customerResolver, recordCustomerCreditUseCase, checkoutSaleUseCase,
                sales, contexts, session
            ),
            ToolRef("settle_customer_debt") to SettleCustomerDebtHandler(
                customerResolver, receiveCustomerPaymentUseCase, session
            ),
            ToolRef("remove_sale_item") to RemoveSaleItemHandler(
                removeSaleItemUseCase, contexts, session
            ),
            ToolRef("change_price") to ChangePriceHandler(resolver, changeProductPriceUseCase)
        )
    )

    val orchestrator = AiOrchestrator(
        // 离线优先：本地规则 → 云端兜底 → 云端失败降级文本（spec 05 §5）
        provider = FallbackAiProvider(LocalRuleParser(), cloudProvider),
        executor = executor,
        disambiguation = InMemoryDisambiguationStore()
    )

    val allowedTools = listOf(
        "find_product", "find_product_by_barcode", "create_product", "get_context", "add_sale_item",
        "checkout_sale", "get_today_sales", "purchase_in",
        "find_member", "get_member_balance", "recharge_member",
        "apply_yesterday_price", "reorder_last_item", "record_loss",
        "record_customer_credit", "settle_customer_debt",
        "remove_sale_item", "change_price"
    )

    val inputAdapter = InputAdapter(session = session, allowedTools = allowedTools)
}

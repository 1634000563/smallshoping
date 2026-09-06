package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.entityresolution.CustomerResolver
import com.smallshoping.app.ai.entityresolution.ProductResolver
import com.smallshoping.app.ai.entityresolution.Resolution
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductRepository
import com.smallshoping.app.domain.memory.MemoryFact
import com.smallshoping.app.domain.memory.MemoryScopeType
import com.smallshoping.app.domain.memory.MemorySource
import com.smallshoping.app.domain.memory.MemoryStore
import com.smallshoping.app.domain.memory.MemoryWritePolicy

/**
 * reorder_last_item：老张上次那些螺丝再来两盒（spec 01 循环 D 补单）。
 *
 * 解析顺序：
 * 1. 客户解析（歧义追问，复用 Task 025 状态机）；
 * 2. 商品：老板明说的商品名优先（事实优先级 spec 06 §2），
 *    省略商品名时查客户长期记忆 usual_product，再回数据库核对商品存在；
 * 3. 加项复用 [AddSaleItemHandler]（同一 Domain 路径，不复制业务逻辑）；
 * 4. 加项成功后按 OBSERVED_PATTERN 观察一次「该客户的常用商品」
 *    （spec 06 §3 达阈值才落库，观察不阻塞销售）。
 */
class ReorderLastItemHandler(
    private val customerResolver: CustomerResolver,
    private val productResolver: ProductResolver,
    private val addItem: AddSaleItemHandler,
    private val products: ProductRepository,
    private val memory: MemoryStore,
    private val memoryWritePolicy: MemoryWritePolicy
) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val customerQuery = entities.getValue("customer")
        val quantity = entities.getValue("quantity")
        val productName = entities["product"]?.takeIf { it.isNotBlank() }

        // 1) 客户解析
        return when (val customerResolution = customerResolver.resolve(customerQuery)) {
            is Resolution.NotFound -> mapOf(
                "status" to "NOT_FOUND", "item_id" to "",
                "message" to "没找到「$customerQuery」这个客户，先建一个？"
            )

            is Resolution.Ambiguous -> mapOf(
                "status" to "AMBIGUOUS", "item_id" to "",
                "message" to "有好几个像「$customerQuery」的客户：" +
                    customerResolution.candidates.joinToString("、") { it.value.name } + "，是哪一个？",
                "ambiguous_key" to "customer",
                "ambiguous_tool" to "reorder_last_item",
                "candidates" to customerResolution.candidates.joinToString("|") {
                    "${it.value.id}=${it.value.name}"
                },
                "intent_entities" to encodeEntities(entities)
            )

            is Resolution.Resolved -> {
                val customer = customerResolution.value
                // 2) 商品：明说优先，否则客户记忆兜底
                val productResolution = resolveProduct(productName, customer.id)
                when (productResolution) {
                    is Resolution.NotFound -> mapOf(
                        "status" to "NOT_FOUND", "item_id" to "",
                        "message" to "没找到这个商品" +
                            if (productName == null) "，也不记得「${customer.name}」上次买了什么，请说下商品名"
                            else "：「$productName」"
                    )

                    is Resolution.Ambiguous -> mapOf(
                        "status" to "AMBIGUOUS", "item_id" to "",
                        "message" to "有好几个像「$productName」的商品：" +
                            productResolution.candidates.joinToString("、") { it.value.name } + "，是哪一个？",
                        "ambiguous_key" to "product",
                        "ambiguous_tool" to "reorder_last_item",
                        "candidates" to productResolution.candidates.joinToString("|") {
                            "${it.value.id}=${it.value.name}"
                        },
                        "intent_entities" to encodeEntities(entities)
                    )

                    is Resolution.Resolved -> {
                        // 3) 复用加项链路（同一 Domain 路径，Gate A）
                        val result = addItem.execute(
                            mapOf("product" to productResolution.value.name, "quantity" to quantity)
                        )
                        // 4) 成功后观察客户常用商品（记忆写入策略阈值 3 次，不阻塞）
                        if (result["status"] == "OK") {
                            observeUsualProduct(customer.id, productResolution.value)
                        }
                        result
                    }
                }
            }
        }
    }

    /** 商品解析：明说商品名走解析器；省略时查客户记忆 usual_product 并回库核对。 */
    private fun resolveProduct(productName: String?, customerId: String): Resolution<Product> {
        if (productName != null) return productResolver.resolve(productName)
        val rememberedId = memory.findByKey(
            MemoryScopeType.CUSTOMER, customerId, FACT_TYPE_USUAL_PRODUCT, KEY_TOP
        )?.valueJson ?: return Resolution.NotFound
        val product = products.findProductById(rememberedId) ?: return Resolution.NotFound
        return Resolution.Resolved(product, 100, "memory_usual_product")
    }

    private fun observeUsualProduct(customerId: String, product: Product) {
        // 观察不阻塞：拒绝/未达阈值都静默（spec 06 §3）
        memoryWritePolicy.observe(
            MemoryFact(
                scopeType = MemoryScopeType.CUSTOMER,
                scopeId = customerId,
                factType = FACT_TYPE_USUAL_PRODUCT,
                key = KEY_TOP,
                // V1 简化：值直接存商品 id 字符串（JSON 包装由 Task 029 规格模型补齐）
                valueJson = product.id,
                confidence = 60,
                source = MemorySource.OBSERVED_PATTERN
            )
        )
    }

    private companion object {
        const val FACT_TYPE_USUAL_PRODUCT = "usual_product"
        const val KEY_TOP = "top"
    }
}

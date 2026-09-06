package com.smallshoping.app.ai.orchestrator

import com.smallshoping.app.ai.tools.ToolRef
import com.smallshoping.app.ai.tools.V1ToolCatalog
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentSchemaValidatorTest {

    @Test
    fun `正常路径：必填实体齐全的意图校验通过`() {
        IntentSchemaValidator.validate(
            Intent(
                type = IntentType.ADD_SALE_ITEM,
                entities = mapOf("product" to "土豆", "quantity" to "2斤"),
                confidence = 0.9
            )
        )
        IntentSchemaValidator.validate(
            Intent(
                type = IntentType.GET_TODAY_SALES,
                entities = emptyMap()
            )
        )
        IntentSchemaValidator.validate(
            Intent(
                type = IntentType.CHECKOUT_SALE,
                entities = mapOf("payment_method" to "cash")
            )
        )
    }

    @Test
    fun `异常路径：缺实体被拒绝且带机器可读错误码`() {
        val e = assertThrows(SchemaValidationException::class.java) {
            IntentSchemaValidator.validate(
                Intent(type = IntentType.ADD_SALE_ITEM, entities = mapOf("product" to "土豆"))
            )
        }
        assertTrue(e.errorCodes.contains("MISSING_ENTITY:quantity"))
    }

    @Test
    fun `异常路径：空串实体视为缺失`() {
        assertThrows(SchemaValidationException::class.java) {
            IntentSchemaValidator.validate(
                Intent(
                    type = IntentType.RECHARGE_MEMBER,
                    entities = mapOf("member" to "张姐", "amount" to "  ")
                )
            )
        }
    }

    @Test
    fun `边界输入：confidence 与 clarification 校验`() {
        assertThrows(IllegalArgumentException::class.java) {
            Intent(type = IntentType.GET_STOCK, confidence = 1.5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Intent(type = IntentType.GET_STOCK, clarification = " ")
        }
        // 有追问的意图仍可被校验（由上层决定暂停执行）
        IntentSchemaValidator.validate(
            Intent(type = IntentType.GET_STOCK, clarification = "要查哪个商品？")
        )
    }

    @Test
    fun `交叉契约：每个意图类型都有对应 Tool，必填实体均在契约入参内`() {
        for (type in IntentType.values()) {
            val contract = V1ToolCatalog.tool(ToolRef(type.tool))
            assertTrue("意图 ${type.name} 缺少对应工具 ${type.tool}", contract != null)
            val allowed = contract!!.inputRequired + contract.inputOptional
            for (key in type.requiredEntities) {
                assertTrue(
                    "意图 ${type.name} 的必填实体 $key 不在 ${contract.ref.fullName} 入参 Schema 内",
                    allowed.contains(key)
                )
            }
        }
    }
}

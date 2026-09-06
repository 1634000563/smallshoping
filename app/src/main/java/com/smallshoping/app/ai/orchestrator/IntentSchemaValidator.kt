package com.smallshoping.app.ai.orchestrator

/** Schema 校验失败：携带机器可读错误码，AI 不得声称执行成功（AI 宪法 #5）。 */
class SchemaValidationException(val errorCodes: Set<String>, message: String) :
    IllegalArgumentException(message)

/**
 * 意图 Schema 校验：按意图类型检查必填实体（AI 宪法 #1）。
 *
 * 校验通过是进入 Risk Engine / Tool Executor 的前提；
 * 校验失败一律拒绝执行，绝不猜测补全关键实体（产品宪法 #9）。
 */
object IntentSchemaValidator {

    fun validate(intent: Intent) {
        val codes = LinkedHashSet<String>()
        for (key in intent.type.requiredEntities) {
            val value = intent.entities[key]
            if (value.isNullOrBlank()) {
                codes.add("MISSING_ENTITY:$key")
            }
        }
        // 有追问内容时视为信息不完整，必须等待人工回答（非错误，但也不得直接执行）
        if (codes.isNotEmpty()) {
            throw SchemaValidationException(
                codes,
                "意图 ${intent.type.name} 缺少必填实体：${codes.joinToString()}"
            )
        }
    }
}

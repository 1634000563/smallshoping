package com.smallshoping.app.ai.tools

/**
 * 意图参数编码（`key=value&key=value`）：
 * Handler 在 AMBIGUOUS 响应中把原始入参带回，供编排层挂起追问时恢复原意图
 * （确认后执行路径拿不到原始 entities，必须由 Handler 自报）。
 */
fun encodeEntities(entities: Map<String, String>): String =
    entities.entries.joinToString("&") { "${it.key}=${it.value}" }

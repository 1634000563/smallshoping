package com.smallshoping.app.ai.context

import com.smallshoping.app.core.common.normalize

/** 消歧候选引用：id + 展示名。 */
data class CandidateRef(val id: String, val name: String)

/**
 * 待消歧问题（spec 08 §8：如「给张姐充200」找到两个张姐必须消歧）。
 *
 * 保留原意图参数与歧义字段；老板可用候选名精确回答
 * 或用「第X个」指认，其余输入不算选择（由上层决定保留或放弃追问）。
 */
data class PendingQuestion(
    val toolName: String,
    val entities: Map<String, String>,
    /** 消歧后替换的参数字段（如 member / product / query）。 */
    val ambiguousKey: String,
    val candidates: List<CandidateRef>,
    val expiresAtMillis: Long
) {

    /** 老板的选择：候选名精确（归一化）或「第X个」；都不是返回 null。 */
    fun choose(input: String): CandidateRef? {
        val q = normalize(input)
        if (q.isBlank()) return null
        ORDINAL_PATTERN.matchEntire(q)?.let { m ->
            val index = ORDINALS[m.groupValues[1]]
                ?: m.groupValues[1].toIntOrNull()
                ?: return null
            return candidates.getOrNull(index - 1)
        }
        return candidates.firstOrNull { normalize(it.name) == q }
    }

    private companion object {
        /** 「第一个」「1」「一」均可；超出候选范围返回 null。 */
        val ORDINAL_PATTERN = Regex("第?([一二三四五六七八九十]|[1-9])(?:个|名)?")
        val ORDINALS = mapOf(
            "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
            "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10
        )
    }
}

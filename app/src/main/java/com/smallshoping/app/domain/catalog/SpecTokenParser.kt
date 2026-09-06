package com.smallshoping.app.domain.catalog

/**
 * 规格 token 解析（Task 032）：从口语/文本中提取规格片段，
 * 供 Entity Resolution 的属性匹配使用。
 *
 * 通用实现，不绑定五金行业（跨行业约束）：字母数字组合
 * （M8x30、GB5782）与多位数字（304、10）都视为规格候选；
 * 单数字与常见量词噪声（1、2）过泛不提取，避免误命中。
 */
object SpecTokenParser {

    /** 字母数字混合规格：M8x30 / m8*30 / 2.5mm 等。 */
    private val ALPHANUM_PATTERN = Regex("[a-z]+\\d[\\w.xX*]*|\\d+(?:\\.\\d+)?[a-z]+")

    /** 多位纯数字规格：304、10（过滤单数字）。 */
    private val NUMBER_PATTERN = Regex("\\d{2,}")

    /** 提取规格 token（大小写不敏感、去重、按出现顺序）。 */
    fun extract(text: String): List<String> {
        val lower = text.lowercase()
        val alphanumeric = ALPHANUM_PATTERN.findAll(lower).map { it.value }.toList()

        data class Hit(val start: Int, val token: String)
        val hits = ArrayList<Hit>()
        ALPHANUM_PATTERN.findAll(lower).forEach { hits.add(Hit(it.range.first, it.value)) }
        // 纯数字 token：过滤已包含在字母数字 token 内的子串（M8x30 中的 30）
        NUMBER_PATTERN.findAll(lower)
            .map { it.value to it.range.first }
            .filter { (n, _) -> alphanumeric.none { n in it } }
            .forEach { (n, start) -> hits.add(Hit(start, n)) }

        val result = ArrayList<String>()
        for (hit in hits.sortedBy { it.start }) {
            if (hit.token !in result) result.add(hit.token)
        }
        return result
    }
}

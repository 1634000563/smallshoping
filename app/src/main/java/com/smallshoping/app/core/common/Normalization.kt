package com.smallshoping.app.core.common

/**
 * 文本归一化：小写、去首尾空白、折叠内部连续空白。
 *
 * 商品名与别名统一经此处理后再比较/存储（spec 03：normalized_name / normalized_alias），
 * 模糊搜索算法由 Task 032 强化，此处只保证「土豆」与「 土豆 」命中同一商品。
 */
fun normalize(text: String): String =
    text.lowercase().trim().replace(Regex("\\s+"), " ")

package com.smallshoping.app.core.money

/**
 * 金额文本解析（元→分），全程无 Float/Double（数据宪法 #2）。
 *
 * 支持：「200」「2.8」「2块8」「3块」「200元」「2块8毛」。
 * 最多两位小数；解析失败返回 null，调用方走澄清。
 */
object MoneyParser {

    private val INTEGER = Regex("^(\\d+)$")
    private val DECIMAL = Regex("^(\\d+)\\.(\\d{1,2})$")
    private val YUAN_SUFFIX = Regex("^(\\d+)元$")
    private val BLOCK = Regex("^(\\d+)块$")
    private val BLOCK_JIAO = Regex("^(\\d+)块(\\d)$")
    private val BLOCK_JIAO_MAO = Regex("^(\\d+)块(\\d)毛$")

    fun parseYuanToMinor(text: String): Long? {
        val t = text.trim()
        INTEGER.find(t)?.let { return multiplyYuan(it.groupValues[1].toLongOrNull() ?: return null) }
        DECIMAL.find(t)?.let {
            val yuan = it.groupValues[1].toLongOrNull() ?: return null
            val frac = it.groupValues[2].padEnd(2, '0')
            return Math.addExact(multiplyYuan(yuan), frac.toLong())
        }
        YUAN_SUFFIX.find(t)?.let { return multiplyYuan(it.groupValues[1].toLongOrNull() ?: return null) }
        BLOCK_JIAO_MAO.find(t)?.let { return blockToMinor(it.groupValues[1], it.groupValues[2]) }
        BLOCK_JIAO.find(t)?.let { return blockToMinor(it.groupValues[1], it.groupValues[2]) }
        BLOCK.find(t)?.let { return multiplyYuan(it.groupValues[1].toLongOrNull() ?: return null) }
        return null
    }

    private fun multiplyYuan(yuan: Long): Long = Math.multiplyExact(yuan, 100L)

    private fun blockToMinor(yuanStr: String, jiaoStr: String): Long {
        val yuan = yuanStr.toLongOrNull() ?: return -1
        val jiao = jiaoStr.toLongOrNull() ?: return -1
        return Math.addExact(multiplyYuan(yuan), Math.multiplyExact(jiao, 10L))
    }
}

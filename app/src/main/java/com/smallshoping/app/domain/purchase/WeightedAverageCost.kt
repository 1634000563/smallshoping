package com.smallshoping.app.domain.purchase

/**
 * 加权平均成本（spec 04 §9：V1 默认加权平均）。
 *
 * 全程 Long 整数运算（数据宪法 #2），除法向下取整。
 * 无历史成本时（oldCostMinor == null 或旧库存 <= 0）直接采用本次进价。
 */
object WeightedAverageCost {

    fun compute(
        oldStock: Long,
        oldCostMinor: Long?,
        inQuantity: Long,
        inCostMinor: Long
    ): Long {
        require(inQuantity > 0) { "入库数量必须为正：$inQuantity" }
        require(inCostMinor >= 0) { "进价不能为负：$inCostMinor" }
        if (oldStock <= 0 || oldCostMinor == null) return inCostMinor
        val oldTotal = Math.multiplyExact(oldStock, oldCostMinor)
        val newTotal = Math.multiplyExact(inQuantity, inCostMinor)
        val totalStock = Math.addExact(oldStock, inQuantity)
        val totalCost = Math.addExact(oldTotal, newTotal)
        return totalCost / totalStock
    }
}

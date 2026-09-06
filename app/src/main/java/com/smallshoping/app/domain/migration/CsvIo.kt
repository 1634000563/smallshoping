package com.smallshoping.app.domain.migration

/**
 * 最小 CSV 工具（Task 049）：RFC 4180 子集——
 * 含逗号/引号/换行的字段用引号包裹，内部引号翻倍；解析还原。
 * 无外部依赖，纯 JVM 可测试；Excel 场景 V1 以 CSV 覆盖（spec 19）。
 */
object CsvIo {

    /** 一行字段 → CSV 行（不含行尾换行）。 */
    fun encodeRow(fields: List<String>): String = fields.joinToString(",") { field ->
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
    }

    /** CSV 行 → 字段列表。 */
    fun decodeRow(line: String): List<String> {
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }

                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }

                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }
}

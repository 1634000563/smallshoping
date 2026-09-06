package com.smallshoping.app.domain.customer

/**
 * 客户目录端口（Domain 侧契约）。
 *
 * 查询一律使用归一化值；Data 层实现负责持久化，
 * AI 与 UI 不得直接持有本端口之外的数据访问能力。
 */
interface CustomerRepository {

    fun saveCustomer(customer: Customer)

    fun findCustomerById(id: String): Customer?

    /** 全部客户（Entity Resolution 候选扫描用）。 */
    fun allCustomers(): List<Customer>

    /** 按归一化客户名精确查找。 */
    fun findByNormalizedName(normalizedName: String): Customer?

    /** 归一化查询：匹配归一化名称、归一化别名或电话（Task 034 消歧候选用）。 */
    fun searchCustomers(normalizedQuery: String): List<Customer>
}

package com.smallshoping.app.data.repository

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.domain.customer.Customer
import com.smallshoping.app.domain.customer.CustomerRepository

/**
 * 内存客户目录实现：线程安全。
 *
 * 语义基线同 [com.smallshoping.app.data.ledger.InMemoryLedger]：
 * 真实持久化实现（Room/SQLite）须通过同一组测试。
 */
class InMemoryCustomerRepository : CustomerRepository {

    private val lock = Any()
    private val byId = LinkedHashMap<String, Customer>()
    private val byName = HashMap<String, Customer>()

    override fun saveCustomer(customer: Customer) = synchronized(lock) {
        byId[customer.id] = customer
        byName[customer.normalizedName] = customer
    }

    override fun findCustomerById(id: String): Customer? = synchronized(lock) {
        byId[id]
    }

    override fun allCustomers(): List<Customer> = synchronized(lock) {
        byId.values.toList()
    }

    override fun findByNormalizedName(normalizedName: String): Customer? = synchronized(lock) {
        byName[normalizedName]
    }

    override fun searchCustomers(normalizedQuery: String): List<Customer> = synchronized(lock) {
        byId.values.filter { customer ->
            customer.normalizedName == normalizedQuery ||
                (customer.alias != null && normalize(customer.alias) == normalizedQuery) ||
                customer.phone == normalizedQuery
        }
    }
}

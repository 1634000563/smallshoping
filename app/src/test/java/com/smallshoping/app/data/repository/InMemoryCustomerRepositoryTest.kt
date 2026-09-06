package com.smallshoping.app.data.repository

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.domain.customer.Customer
import com.smallshoping.app.domain.customer.CustomerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 021：客户目录内存实现语义基线。 */
class InMemoryCustomerRepositoryTest {

    private val repo = InMemoryCustomerRepository()

    private fun seed(name: String = "老张"): Customer {
        val customer = Customer(
            id = "C-1", storeId = "STORE-1", name = name,
            normalizedName = normalize(name), alias = "张老板", phone = "13900000000",
            creditEnabled = true
        )
        repo.saveCustomer(customer)
        return customer
    }

    @Test
    fun `保存与查找：按 id 与归一化名称`() {
        val customer = seed()
        assertEquals(customer, repo.findCustomerById("C-1"))
        assertEquals(customer, repo.findByNormalizedName(normalize("老张")))
        assertNull(repo.findCustomerById("C-404"))
    }

    @Test
    fun `searchCustomers：名称、别名、电话均可命中（归一化比较）`() {
        seed()
        assertEquals(1, repo.searchCustomers(normalize("老张")).size)
        assertEquals(1, repo.searchCustomers(normalize("张老板")).size)
        assertEquals(1, repo.searchCustomers("13900000000").size)
        assertTrue(repo.searchCustomers(normalize("老王")).isEmpty())
    }

    @Test
    fun `allCustomers：返回全部客户，保持保存顺序与状态`() {
        seed()
        repo.saveCustomer(
            Customer("C-2", "STORE-1", "王叔", normalize("王叔"))
        )
        val all = repo.allCustomers()
        assertEquals(2, all.size)
        assertEquals("C-1", all[0].id)
        assertTrue(all[0].creditEnabled)
        assertEquals(CustomerStatus.ACTIVE, all[1].status)
    }
}

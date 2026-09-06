package com.smallshoping.app.ai.entityresolution

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.data.repository.InMemoryCustomerRepository
import com.smallshoping.app.domain.customer.Customer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 027：客户实体解析打分与消歧。 */
class CustomerResolverTest {

    private val customers = InMemoryCustomerRepository()
    private val resolver = CustomerResolver(customers)

    private fun seed(
        id: String,
        name: String,
        alias: String? = null,
        phone: String? = null
    ) {
        customers.saveCustomer(
            Customer(id = id, storeId = "STORE-1", name = name, normalizedName = normalize(name),
                alias = alias, phone = phone)
        )
    }

    @Test
    fun `名称精确命中`() {
        seed("C-1", "老张")
        val r = resolver.resolve("老张")
        assertTrue(r is Resolution.Resolved)
        val resolved = r as Resolution.Resolved<Customer>
        assertEquals("C-1", resolved.value.id)
        assertEquals(100, resolved.score)
    }

    @Test
    fun `别名与电话精确命中`() {
        seed("C-1", "张老板", alias = "老张", phone = "13900000000")
        assertEquals("C-1", (resolver.resolve("老张") as Resolution.Resolved<Customer>).value.id)
        assertEquals("C-1", (resolver.resolve("13900000000") as Resolution.Resolved<Customer>).value.id)
    }

    @Test
    fun `多候选返回歧义不猜测`() {
        seed("C-1", "老张叔")
        seed("C-2", "老张哥")
        val r = resolver.resolve("老张")
        assertTrue(r is Resolution.Ambiguous)
        assertEquals(2, (r as Resolution.Ambiguous<Customer>).candidates.size)
    }

    @Test
    fun `无候选返回 NotFound`() {
        seed("C-1", "老张")
        assertTrue(resolver.resolve("老王") is Resolution.NotFound)
        assertTrue(resolver.resolve("  ") is Resolution.NotFound)
    }
}

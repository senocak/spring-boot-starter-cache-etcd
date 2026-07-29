package com.github.senocak.etcd

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.senocak.etcd.core.EtcdCacheManager
import com.github.senocak.etcd.model.User
import io.etcd.jetcd.Client
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cache.Cache

/**
 * Expiry is enforced by etcd leases rather than by any application-side scheduler, so it can only
 * be verified against a real etcd. etcd grants leases in whole seconds, which is why this test
 * uses a seconds-scale TTL instead of the millisecond values a pure unit test could afford.
 */
@SpringBootTest(
    properties = [
        "spring.etcd.cache.key-prefix=/lease-ttl-it-\${random.uuid}",
        "spring.etcd.cache.entry-ttl=2s"
    ]
)
class EtcdLeaseTtlIntegrationTest : EtcdContainerIntegrationTestSupport() {
    @Autowired
    @Qualifier(value = "etcdCacheManager")
    private lateinit var cacheManager: EtcdCacheManager

    @Autowired private lateinit var etcdClient: Client

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    fun `etcd reclaims cache entries once their lease expires`() {
        val cache: Cache = requireNotNull(value = cacheManager.getCache("users"))
        val key: String = UUID.randomUUID().toString()
        val user: User = newUser(id = key, username = "lease-ttl")

        cache.put(key, user)
        assertEquals(expected = user, actual = cache.get(key, User::class.java))

        // Poll rather than sleeping a fixed span: lease revocation is asynchronous inside etcd,
        // so a key can outlive its nominal TTL by a short and unpredictable margin.
        assertTrue(
            actual = pollUntil(timeoutMillis = 30_000) { cache.get(key) == null },
            message = "Expected etcd to reclaim the key after its 2s lease expired"
        )
        assertNull(actual = cache.get(key))
    }

    @Test
    fun `entries written without a ttl are never reclaimed`() {
        // A manager configured with no entry TTL writes unleased keys, which etcd keeps forever.
        // It needs the Spring ObjectMapper: the library default has no Kotlin module, so it cannot
        // construct a data class like User.
        val cache: Cache = requireNotNull(
            value = EtcdCacheManager(
                etcdClient,
                "/lease-ttl-it-none-${UUID.randomUUID()}",
                objectMapper,
                { key: Any -> key.toString() },
                null,
                null
            ).getCache("users")
        )
        val key: String = UUID.randomUUID().toString()
        val user: User = newUser(id = key, username = "no-ttl")

        cache.put(key, user)
        Thread.sleep(4_000)

        assertEquals(expected = user, actual = cache.get(key, User::class.java))
    }

    private fun newUser(id: String, username: String): User =
        User(
            id = id,
            username = username,
            email = "$username@example.com",
            firstName = "Lease",
            lastName = "Ttl"
        )

    private fun pollUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline: Long = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(250)
        }
        return condition()
    }
}

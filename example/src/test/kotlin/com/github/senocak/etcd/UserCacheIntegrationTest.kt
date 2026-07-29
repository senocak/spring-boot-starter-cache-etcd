package com.github.senocak.etcd

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.senocak.etcd.config.EtcdCacheProperties
import com.github.senocak.etcd.controller.EtcdCacheController
import com.github.senocak.etcd.core.EtcdCacheManager
import com.github.senocak.etcd.model.CreateUserRequest
import com.github.senocak.etcd.model.User
import io.etcd.jetcd.Client
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cache.Cache

@SpringBootTest(
    properties = [
        "spring.etcd.cache.key-prefix=/user-cache-it-\${random.uuid}",
        "spring.etcd.cache.entry-ttl=0ms"
    ]
)
class UserCacheIntegrationTest : EtcdContainerIntegrationTestSupport() {
    @Autowired private lateinit var etcdCacheController: EtcdCacheController

    @Autowired
    @Qualifier(value = "etcdCacheManager")
    private lateinit var cacheManager: EtcdCacheManager

    @Autowired private lateinit var etcdClient: Client

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var etcdCacheProperties: EtcdCacheProperties

    @AfterEach
    fun clearCaches() {
        cacheManager.clearAll()
    }

    @Test
    fun `cache put stores created users in the etcd backed users cache`() {
        val user: User = etcdCacheController.createUser(
            request = CreateUserRequest(
                username = uniqueUsername(prefix = "etcd-cache-ada"),
                email = "ada@example.com",
                firstName = "Ada",
                lastName = "Lovelace"
            )
        )

        val usersCache: Cache = requireNotNull(value = cacheManager.getCache("users"))

        assertEquals(expected = user, actual = usersCache.get(user.id, User::class.java))
        assertEquals(expected = user, actual = reloadedCacheValue(cacheName = "users", key = user.id))
    }

    @Test
    fun `cacheable username lookup writes to a dedicated username cache region`() {
        val username: String = uniqueUsername(prefix = "etcd-cache-grace")
        val user: User = etcdCacheController.createUser(
            request = CreateUserRequest(
                username = username,
                email = "grace@example.com",
                firstName = "Grace",
                lastName = "Hopper"
            )
        )

        val found: User = etcdCacheController.getUserByUsername(username = username)
        val usernameCache: Cache = requireNotNull(value = cacheManager.getCache("usersByUsername"))

        assertEquals(expected = user, actual = found)
        assertEquals(expected = user, actual = usernameCache.get(username, User::class.java))
        assertEquals(expected = user, actual = reloadedCacheValue(cacheName = "usersByUsername", key = username))
    }

    @Test
    fun `cache put updates existing cached users`() {
        val user: User = etcdCacheController.createUser(
            request = CreateUserRequest(
                username = uniqueUsername(prefix = "etcd-cache-katherine"),
                email = "katherine@example.com",
                firstName = "Katherine",
                lastName = "Johnson"
            )
        )

        val usersCache: Cache = requireNotNull(value = cacheManager.getCache("users"))
        val updated: User = user.copy(email = "katherine+updated@example.com")
        usersCache.put(user.id, updated)

        assertEquals(expected = updated, actual = usersCache.get(user.id, User::class.java))
        assertEquals(expected = updated, actual = reloadedCacheValue(cacheName = "users", key = user.id))
    }

    @Test
    fun `cache evict removes deleted users from the users cache`() {
        val user: User = etcdCacheController.createUser(
            request = CreateUserRequest(
                username = uniqueUsername(prefix = "etcd-cache-margaret"),
                email = "margaret@example.com",
                firstName = "Margaret",
                lastName = "Hamilton"
            )
        )
        val usersCache: Cache = requireNotNull(value = cacheManager.getCache("users"))

        assertEquals(expected = user, actual = usersCache.get(user.id, User::class.java))
        assertTrue(actual = etcdCacheController.deleteUser(userId = user.id))

        assertNull(actual = usersCache.get(user.id))
        assertNull(actual = reloadedCacheValue(cacheName = "users", key = user.id))
    }

    /** Reads through a brand new manager to prove the value really lives in etcd, not in process memory. */
    private fun reloadedCacheValue(cacheName: String, key: String): User? =
        requireNotNull(value = createReloadedCacheManager().getCache(cacheName))
            .get(key, User::class.java)

    private fun createReloadedCacheManager(): EtcdCacheManager =
        EtcdCacheManager(
            etcdClient,
            etcdCacheProperties.keyPrefix,
            objectMapper,
            { key: Any -> key.toString() },
            null,
            null
        )

    private fun uniqueUsername(prefix: String): String =
        "$prefix-${UUID.randomUUID()}"
}

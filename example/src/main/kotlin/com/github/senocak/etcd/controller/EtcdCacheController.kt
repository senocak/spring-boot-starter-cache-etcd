package com.github.senocak.etcd.controller

import com.github.senocak.etcd.core.EtcdCacheManager
import com.github.senocak.etcd.core.event.CacheEvictedEvent
import com.github.senocak.etcd.core.event.CacheInsertedEvent
import com.github.senocak.etcd.logger
import com.github.senocak.etcd.model.CreateUserRequest
import com.github.senocak.etcd.model.User
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.getValue
import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.context.event.EventListener
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(value = ["/etcd/users"])
class EtcdCacheController(
    @Qualifier(value = "etcdCacheManager") private val cacheManager: CacheManager,
) {
    private val log: Logger by logger()
    private val userStorage = ConcurrentHashMap<String, User>()

    init {
        createUser(request = CreateUserRequest(username = "user1", email = "john.doe@example.com", firstName = "John", lastName = "Doe"))
        createUser(request = CreateUserRequest(username = "user2", email = "jane.smith@example.com", firstName = "Jane", lastName = "Smith"))
        createUser(request = CreateUserRequest(username = "user3", email = "bob.wilson@example.com", firstName = "Bob", lastName = "Wilson"))
    }

    @GetMapping
    fun getAllUsers(): List<User> =
        userStorage.values.toList()

    @GetMapping(value = ["/username/{username}"])
    @Cacheable(value = ["usersByUsername"], key = "#username", cacheManager = "etcdCacheManager")
    fun getUserByUsername(@PathVariable username: String): User {
        log.info("Loading user by username from storage: $username")
        Thread.sleep(100)
        return userStorage.values.find { it.username == username }
            ?: throw NoSuchElementException("User not found with username: $username")
    }

    @PostMapping
    @CachePut(value = ["users"], key = "#result.id", cacheManager = "etcdCacheManager")
    fun createUser(@RequestBody request: CreateUserRequest): User {
        log.info("Creating new user: $request")
        val user = User(
            id = UUID.randomUUID().toString(),
            username = request.username,
            email = request.email,
            firstName = request.firstName,
            lastName = request.lastName
        )
        userStorage[user.id] = user
        return user
    }

    @DeleteMapping(value = ["/{userId}"])
    @CacheEvict(value = ["users"], key = "#userId", cacheManager = "etcdCacheManager")
    fun deleteUser(@PathVariable userId: String): Boolean {
        log.info("Deleting user: $userId")
        return userStorage.remove(userId) != null
    }

    @GetMapping(value = ["/cache/stats"])
    fun getCacheStats(): Map<String, Any> =
        mapOf(
            "totalUsers" to userStorage.size,
            "userIds" to userStorage.keys.toList(),
            "usernames" to userStorage.values.map { it.username }
        )

    @DeleteMapping(value = ["/cache"])
    fun clearAllCaches() {
        when (cacheManager) {
            is EtcdCacheManager -> cacheManager.clearAll()
            else -> cacheManager.cacheNames.forEach { cacheName: String ->
                cacheManager.getCache(cacheName)?.clear()
            }
        }
    }

    @EventListener(value = [CacheEvictedEvent::class])
    fun cacheEvictedEvent(event: CacheEvictedEvent<String, Any>) {
        log.info("Cache evicted received: $event")
    }

    @EventListener(value = [CacheInsertedEvent::class])
    fun cacheInsertedEvent(event: CacheInsertedEvent<String, Any>) {
        log.info("Cache inserted received: $event")
    }
}

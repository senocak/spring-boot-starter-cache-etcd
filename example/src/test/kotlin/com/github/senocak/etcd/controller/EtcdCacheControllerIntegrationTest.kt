package com.github.senocak.etcd.controller

import com.github.senocak.etcd.EtcdContainerIntegrationTestSupport
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils

@SpringBootTest(
    properties = [
        "spring.etcd.cache.key-prefix=/controller-test-cache-\${random.uuid}",
        "spring.etcd.cache.entry-ttl=0ms"
    ]
)
@AutoConfigureMockMvc
class EtcdCacheControllerIntegrationTest : EtcdContainerIntegrationTestSupport() {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var etcdCacheController: EtcdCacheController

    @Test
    fun `user endpoints support create read delete and stats workflows`() {
        val username: String = uniqueUsername(prefix = "ada")

        mockMvc.perform(get("/etcd/users"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)

        val createResult: MvcResult = mockMvc.perform(
            post("/etcd/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "username": "$username",
                      "email": "ada@example.com",
                      "firstName": "Ada",
                      "lastName": "Lovelace"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").isNotEmpty)
            .andExpect(jsonPath("$.username").value(username))
            .andExpect(jsonPath("$.email").value("ada@example.com"))
            .andExpect(jsonPath("$.firstName").value("Ada"))
            .andExpect(jsonPath("$.lastName").value("Lovelace"))
            .andExpect(jsonPath("$.fullName").value("Ada Lovelace"))
            .andReturn()

        val userId: String = createResult.response.contentAsString.jsonString("id")
        assertTrue(actual = userId.isNotBlank())

        mockMvc.perform(get("/etcd/users/username/{username}", username))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(userId))
            .andExpect(jsonPath("$.email").value("ada@example.com"))

        mockMvc.perform(get("/etcd/users/cache/stats"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalUsers").value(greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.userIds").isArray)
            .andExpect(jsonPath("$.usernames").isArray)

        mockMvc.perform(delete("/etcd/users/{userId}", userId))
            .andExpect(status().isOk)
            .andExpect(content().string("true"))

        mockMvc.perform(delete("/etcd/users/{userId}", userId))
            .andExpect(status().isOk)
            .andExpect(content().string("false"))
    }

    @Test
    fun `cache clear endpoint completes successfully`() {
        mockMvc.perform(delete("/etcd/users/cache"))
            .andExpect(status().isOk)
            .andExpect(content().string(""))
    }

    @Test
    fun `get user by username returns cached data after the first lookup`() {
        val username: String = uniqueUsername(prefix = "cached-username")
        val createResult: MvcResult = createUser(
            username = username,
            email = "cached-username@example.com",
            firstName = "Cached",
            lastName = "Username"
        )
        val userId: String = createResult.response.contentAsString.jsonString(fieldName = "id")

        mockMvc.perform(get("/etcd/users/username/{username}", username))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(userId))

        removeUserFromBackingStorage(userId = userId)

        mockMvc.perform(get("/etcd/users/username/{username}", username))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(userId))
            .andExpect(jsonPath("$.username").value(username))
            .andExpect(jsonPath("$.email").value("cached-username@example.com"))
            .andExpect(jsonPath("$.fullName").value("Cached Username"))
    }

    private fun createUser(username: String, email: String, firstName: String, lastName: String): MvcResult =
        mockMvc.perform(
            post("/etcd/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "username": "$username",
                      "email": "$email",
                      "firstName": "$firstName",
                      "lastName": "$lastName"
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").isNotEmpty)
            .andExpect(jsonPath("$.username").value(username))
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.firstName").value(firstName))
            .andExpect(jsonPath("$.lastName").value(lastName))
            .andExpect(jsonPath("$.fullName").value("$firstName $lastName"))
            .andReturn()

    @Suppress(names = ["UNCHECKED_CAST"])
    private fun removeUserFromBackingStorage(userId: String) {
        val target: EtcdCacheController = AopTestUtils.getTargetObject(etcdCacheController)
        val storage: ConcurrentHashMap<String, *> = ReflectionTestUtils.getField(target, "userStorage") as ConcurrentHashMap<String, *>
        storage.remove(key = userId)
    }

    private fun String.jsonString(fieldName: String): String =
        Regex(pattern = "\"${Regex.escape(literal = fieldName)}\"\\s*:\\s*\"([^\"]+)\"")
            .find(this)
            ?.groupValues
            ?.get(index = 1)
            ?: error("JSON string field not found: $fieldName")

    private fun uniqueUsername(prefix: String): String =
        "$prefix-${UUID.randomUUID()}"
}

package com.github.senocak.etcd.model

import kotlin.test.Test
import kotlin.test.assertEquals

class UserTest {
    @Test
    fun `full name joins first and last names`() {
        val user = User(
            id = "1",
            username = "ada",
            email = "ada@example.com",
            firstName = "Ada",
            lastName = "Lovelace"
        )

        assertEquals(expected = "Ada Lovelace", actual = user.fullName)
    }
}

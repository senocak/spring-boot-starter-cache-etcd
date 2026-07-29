package com.github.senocak.etcd.model

/**
 * User data model for demonstrating caching functionality.
 */
data class User(
    val id: String,
    val username: String,
    val email: String,
    val firstName: String,
    val lastName: String,
) {
    val fullName: String
        get() = "$firstName $lastName"
}

/**
 * Request DTO for creating a user.
 */
data class CreateUserRequest(
    val username: String,
    val email: String,
    val firstName: String,
    val lastName: String
)

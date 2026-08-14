package com.financetracker.repository

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

object Users : Table("users") {
    val id = uuid("id")
    val email = text("email").uniqueIndex()
    val password = text("password")
    override val primaryKey = PrimaryKey(id)
}

data class UserRow(val id: UUID, val email: String, val passwordHash: String)

interface UserRepository {
    fun findByEmail(email: String): UserRow?

    fun insert(
        email: String,
        passwordHash: String,
    ): UUID
}

class UserRepositoryImpl : UserRepository {
    override fun findByEmail(email: String): UserRow? =
        Users.selectAll()
            .where { Users.email eq email }
            .singleOrNull()
            ?.let { UserRow(it[Users.id], it[Users.email], it[Users.password]) }

    override fun insert(
        email: String,
        passwordHash: String,
    ): UUID {
        val newId = UUID.randomUUID()
        Users.insert {
            it[Users.id] = newId
            it[Users.email] = email
            it[Users.password] = passwordHash
        }
        return newId
    }
}

package com.financetracker.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

enum class CategoryType(val value: String) {
    Expense("expense"),
    Income("income"),
    ;

    companion object {
        fun fromString(s: String): CategoryType? = entries.find { it.value == s }
    }
}

object Categories : Table("categories") {
    val id = uuid("id")
    val userId = uuid("user_id").references(Users.id, onDelete = org.jetbrains.exposed.sql.ReferenceOption.CASCADE)
    val name = text("name")
    val type = text("type")
    val isFallback = bool("is_fallback")
    val idempotencyKey = text("idempotency_key").nullable()

    override val primaryKey = PrimaryKey(id)
}

data class CategoryRow(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val type: CategoryType,
    val isFallback: Boolean,
    val idempotencyKey: String?,
)

interface CategoryRepository {
    fun findAllByUserId(userId: UUID): List<CategoryRow>

    fun findByIdAndUserId(
        id: UUID,
        userId: UUID,
    ): CategoryRow?

    fun findFallbackByType(
        userId: UUID,
        type: CategoryType,
    ): CategoryRow?

    fun findByIdempotencyKey(
        key: String,
        userId: UUID,
    ): CategoryRow?

    fun insert(
        userId: UUID,
        name: String,
        type: CategoryType,
        isFallback: Boolean,
        idempotencyKey: String?,
    ): CategoryRow

    fun update(
        id: UUID,
        userId: UUID,
        name: String,
        type: CategoryType,
    ): CategoryRow?

    fun deleteAndReassignTransactions(
        id: UUID,
        userId: UUID,
        fallbackId: UUID,
    ): Boolean

    fun seedDefaults(userId: UUID)
}

class CategoryRepositoryImpl : CategoryRepository {
    override fun findAllByUserId(userId: UUID): List<CategoryRow> =
        transaction {
            Categories.selectAll()
                .where { Categories.userId eq userId }
                .map { it.toCategoryRow() }
        }

    override fun findByIdAndUserId(
        id: UUID,
        userId: UUID,
    ): CategoryRow? =
        transaction {
            Categories.selectAll()
                .where { (Categories.id eq id) and (Categories.userId eq userId) }
                .singleOrNull()
                ?.toCategoryRow()
        }

    override fun findFallbackByType(
        userId: UUID,
        type: CategoryType,
    ): CategoryRow? =
        transaction {
            Categories.selectAll()
                .where {
                    (Categories.userId eq userId) and
                        (Categories.type eq type.value) and
                        (Categories.isFallback eq true)
                }
                .singleOrNull()
                ?.toCategoryRow()
        }

    override fun findByIdempotencyKey(
        key: String,
        userId: UUID,
    ): CategoryRow? =
        transaction {
            Categories.selectAll()
                .where { (Categories.idempotencyKey eq key) and (Categories.userId eq userId) }
                .singleOrNull()
                ?.toCategoryRow()
        }

    override fun insert(
        userId: UUID,
        name: String,
        type: CategoryType,
        isFallback: Boolean,
        idempotencyKey: String?,
    ): CategoryRow =
        transaction {
            val newId = UUID.randomUUID()
            Categories.insert {
                it[Categories.id] = newId
                it[Categories.userId] = userId
                it[Categories.name] = name
                it[Categories.type] = type.value
                it[Categories.isFallback] = isFallback
                it[Categories.idempotencyKey] = idempotencyKey
            }
            CategoryRow(newId, userId, name, type, isFallback, idempotencyKey)
        }

    override fun update(
        id: UUID,
        userId: UUID,
        name: String,
        type: CategoryType,
    ): CategoryRow? =
        transaction {
            val count =
                Categories.update({ (Categories.id eq id) and (Categories.userId eq userId) }) {
                    it[Categories.name] = name
                    it[Categories.type] = type.value
                }
            if (count == 0) {
                null
            } else {
                Categories.selectAll()
                    .where { (Categories.id eq id) and (Categories.userId eq userId) }
                    .singleOrNull()
                    ?.toCategoryRow()
            }
        }

    override fun deleteAndReassignTransactions(
        id: UUID,
        userId: UUID,
        fallbackId: UUID,
    ): Boolean =
        transaction {
            Transactions.update({ (Transactions.categoryId eq id) and (Transactions.userId eq userId) }) {
                it[Transactions.categoryId] = fallbackId
            }
            val deleted = Categories.deleteWhere { (Categories.id eq id) and (Categories.userId eq userId) }
            deleted > 0
        }

    override fun seedDefaults(userId: UUID) {
        transaction {
            val seeds =
                listOf(
                    Triple("Food", CategoryType.Expense, false),
                    Triple("Transport", CategoryType.Expense, false),
                    Triple("Shopping", CategoryType.Expense, false),
                    Triple("Health", CategoryType.Expense, false),
                    Triple("Entertainment", CategoryType.Expense, false),
                    Triple("Housing", CategoryType.Expense, false),
                    Triple("Other", CategoryType.Expense, true),
                    Triple("Income", CategoryType.Income, false),
                    Triple("Other", CategoryType.Income, true),
                )
            for ((name, type, isFallback) in seeds) {
                Categories.insert {
                    it[Categories.id] = UUID.randomUUID()
                    it[Categories.userId] = userId
                    it[Categories.name] = name
                    it[Categories.type] = type.value
                    it[Categories.isFallback] = isFallback
                    it[Categories.idempotencyKey] = null
                }
            }
        }
    }
}

private fun ResultRow.toCategoryRow() =
    CategoryRow(
        id = this[Categories.id],
        userId = this[Categories.userId],
        name = this[Categories.name],
        type = CategoryType.fromString(this[Categories.type]) ?: error("Unknown category type: ${this[Categories.type]}"),
        isFallback = this[Categories.isFallback],
        idempotencyKey = this[Categories.idempotencyKey],
    )

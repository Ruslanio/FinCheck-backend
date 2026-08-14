package com.financetracker.repository

import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.util.UUID

object Transactions : Table("transactions") {
    val id = uuid("id")
    val userId = uuid("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val amount = decimal("amount", 12, 2)
    val category = text("category")
    val description = text("description").nullable()
    val idempotencyKey = text("idempotency_key").nullable()
    val occurredAt = timestamp("occurred_at")

    override val primaryKey = PrimaryKey(id, occurredAt)
}

data class TransactionRow(
    val id: UUID,
    val userId: UUID,
    val amount: BigDecimal,
    val category: String,
    val description: String?,
    val idempotencyKey: String?,
    val occurredAt: Instant,
)

interface TransactionRepository {
    fun findByUserId(
        userId: UUID,
        page: Int,
        size: Int,
        category: String?,
    ): Pair<List<TransactionRow>, Long>

    fun findByIdempotencyKey(
        key: String,
        userId: UUID,
    ): TransactionRow?

    fun insert(
        userId: UUID,
        amount: BigDecimal,
        category: String,
        description: String?,
        idempotencyKey: String?,
        occurredAt: Instant,
    ): TransactionRow
}

class TransactionRepositoryImpl : TransactionRepository {
    override fun findByUserId(
        userId: UUID,
        page: Int,
        size: Int,
        category: String?,
    ): Pair<List<TransactionRow>, Long> =
        transaction {
            fun buildQuery() =
                Transactions.selectAll()
                    .where { Transactions.userId eq userId }
                    .let { q ->
                        if (category != null) {
                            q.andWhere { Transactions.category.lowerCase() eq category.lowercase() }
                        } else {
                            q
                        }
                    }

            val total = buildQuery().count()
            val rows =
                buildQuery()
                    .orderBy(Transactions.occurredAt, SortOrder.DESC)
                    .limit(size)
                    .offset(page.toLong() * size)
                    .map { it.toTransactionRow() }
            Pair(rows, total)
        }

    override fun findByIdempotencyKey(
        key: String,
        userId: UUID,
    ): TransactionRow? =
        transaction {
            Transactions.selectAll()
                .where { (Transactions.idempotencyKey eq key) and (Transactions.userId eq userId) }
                .singleOrNull()
                ?.toTransactionRow()
        }

    override fun insert(
        userId: UUID,
        amount: BigDecimal,
        category: String,
        description: String?,
        idempotencyKey: String?,
        occurredAt: Instant,
    ): TransactionRow =
        transaction {
            val newId = UUID.randomUUID()
            Transactions.insert {
                it[Transactions.id] = newId
                it[Transactions.userId] = userId
                it[Transactions.amount] = amount
                it[Transactions.category] = category
                it[Transactions.description] = description
                it[Transactions.idempotencyKey] = idempotencyKey
                it[Transactions.occurredAt] = occurredAt
            }
            TransactionRow(newId, userId, amount, category, description, idempotencyKey, occurredAt)
        }
}

private fun ResultRow.toTransactionRow() =
    TransactionRow(
        id = this[Transactions.id],
        userId = this[Transactions.userId],
        amount = this[Transactions.amount],
        category = this[Transactions.category],
        description = this[Transactions.description],
        idempotencyKey = this[Transactions.idempotencyKey],
        occurredAt = this[Transactions.occurredAt],
    )

package com.financetracker.service

import com.financetracker.model.CreateTransactionRequest
import com.financetracker.model.PagedTransactionsResponse
import com.financetracker.model.toResponse
import com.financetracker.redis.RedisClient
import com.financetracker.repository.TransactionRepository
import com.financetracker.repository.TransactionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import java.util.UUID

sealed interface CreateTransactionResult {
    data class Created(val transaction: TransactionRow) : CreateTransactionResult

    data class Duplicate(val transaction: TransactionRow) : CreateTransactionResult

    data object InFlight : CreateTransactionResult

    data class ValidationError(val message: String) : CreateTransactionResult
}

interface TransactionService {
    suspend fun getTransactions(
        userId: UUID,
        page: Int,
        size: Int,
        category: String?,
    ): PagedTransactionsResponse

    suspend fun createTransaction(
        userId: UUID,
        request: CreateTransactionRequest,
    ): CreateTransactionResult
}

class TransactionServiceImpl(
    private val repository: TransactionRepository,
    private val redisClient: RedisClient,
) : TransactionService {
    override suspend fun getTransactions(
        userId: UUID,
        page: Int,
        size: Int,
        category: String?,
    ): PagedTransactionsResponse {
        require(page >= 0) { "page must be >= 0" }
        require(size in 1..100) { "size must be between 1 and 100" }

        val (rows, total) =
            withContext(Dispatchers.IO) {
                repository.findByUserId(userId, page, size, category)
            }

        return PagedTransactionsResponse(
            data = rows.map { it.toResponse() },
            page = page,
            size = size,
            total = total,
        )
    }

    override suspend fun createTransaction(
        userId: UUID,
        request: CreateTransactionRequest,
    ): CreateTransactionResult {
        if (request.amount == 0.0) return CreateTransactionResult.ValidationError("missing_required_fields")
        if (request.category.isBlank()) return CreateTransactionResult.ValidationError("missing_required_fields")

        val key = request.idempotencyKey

        if (key != null) {
            val lockAcquired = redisClient.acquireIdempotencyLock(key, userId.toString())

            if (!lockAcquired) {
                val existing =
                    withContext(Dispatchers.IO) {
                        repository.findByIdempotencyKey(key, userId)
                    }
                return if (existing != null) {
                    CreateTransactionResult.Duplicate(existing)
                } else {
                    CreateTransactionResult.InFlight
                }
            }

            val existing =
                withContext(Dispatchers.IO) {
                    repository.findByIdempotencyKey(key, userId)
                }
            if (existing != null) {
                redisClient.releaseIdempotencyLock(key, userId.toString())
                return CreateTransactionResult.Duplicate(existing)
            }
        }

        val occurredAt = request.occurredAt ?: Clock.System.now()

        val row =
            try {
                withContext(Dispatchers.IO) {
                    repository.insert(
                        userId = userId,
                        amount = request.amount.toBigDecimal(),
                        category = request.category,
                        description = request.description,
                        idempotencyKey = key,
                        occurredAt = occurredAt,
                    )
                }
            } catch (e: Exception) {
                if (key != null) redisClient.releaseIdempotencyLock(key, userId.toString())
                throw e
            }

        return CreateTransactionResult.Created(row)
    }
}

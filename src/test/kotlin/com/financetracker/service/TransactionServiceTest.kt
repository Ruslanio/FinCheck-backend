package com.financetracker.service

import com.financetracker.model.CreateTransactionRequest
import com.financetracker.redis.RedisClient
import com.financetracker.repository.TransactionRepository
import com.financetracker.repository.TransactionRow
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TransactionServiceTest {

    private val repository = mockk<TransactionRepository>()
    private val redisClient = mockk<RedisClient>()
    private val service: TransactionService = TransactionServiceImpl(repository, redisClient)
    private val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    private fun makeRow(idempotencyKey: String? = null) =
        TransactionRow(
            id = UUID.randomUUID(),
            userId = userId,
            amount = BigDecimal("10.00"),
            category = "food",
            description = null,
            idempotencyKey = idempotencyKey,
            occurredAt = Clock.System.now(),
        )

    @Test
    fun `getTransactions throws for negative page`() {
        assertThrows<IllegalArgumentException> {
            runBlocking { service.getTransactions(userId, -1, 20, null) }
        }
    }

    @Test
    fun `getTransactions throws for size over 100`() {
        assertThrows<IllegalArgumentException> {
            runBlocking { service.getTransactions(userId, 0, 101, null) }
        }
    }

    @Test
    fun `getTransactions delegates to repository with correct params`() =
        runBlocking {
            every { repository.findByUserId(userId, 0, 20, "food") } returns Pair(emptyList(), 0L)

            service.getTransactions(userId, 0, 20, "food")

            verify { repository.findByUserId(userId, 0, 20, "food") }
        }

    @Test
    fun `createTransaction returns ValidationError for blank category`() =
        runBlocking {
            val req = CreateTransactionRequest(amount = 10.0, category = "")
            val result = service.createTransaction(userId, req)
            assertIs<CreateTransactionResult.ValidationError>(result)
        }

    @Test
    fun `createTransaction returns ValidationError for zero amount`() =
        runBlocking {
            val req = CreateTransactionRequest(amount = 0.0, category = "food")
            val result = service.createTransaction(userId, req)
            assertIs<CreateTransactionResult.ValidationError>(result)
        }

    @Test
    fun `createTransaction returns Duplicate when idempotency key exists`() =
        runBlocking {
            val existingRow = makeRow(idempotencyKey = "key-1")
            every { redisClient.acquireIdempotencyLock("key-1", userId.toString()) } returns false
            every { repository.findByIdempotencyKey("key-1", userId) } returns existingRow

            val req = CreateTransactionRequest(amount = 10.0, category = "food", idempotencyKey = "key-1")
            val result = service.createTransaction(userId, req)

            assertIs<CreateTransactionResult.Duplicate>(result)
            assertEquals(existingRow, result.transaction)
        }

    @Test
    fun `createTransaction returns Created for new idempotency key`() =
        runBlocking {
            val newRow = makeRow(idempotencyKey = "key-2")
            every { redisClient.acquireIdempotencyLock("key-2", userId.toString()) } returns true
            every { repository.findByIdempotencyKey("key-2", userId) } returns null
            every { repository.insert(userId, any(), "food", null, "key-2", any()) } returns newRow

            val req = CreateTransactionRequest(amount = 10.0, category = "food", idempotencyKey = "key-2")
            val result = service.createTransaction(userId, req)

            assertIs<CreateTransactionResult.Created>(result)
            assertEquals(newRow, result.transaction)
        }

    @Test
    fun `createTransaction inserts without idempotency check when key is null`() =
        runBlocking {
            val newRow = makeRow()
            every { repository.insert(userId, any(), "food", null, null, any()) } returns newRow

            val req = CreateTransactionRequest(amount = 10.0, category = "food")
            val result = service.createTransaction(userId, req)

            assertIs<CreateTransactionResult.Created>(result)
            verify(exactly = 0) { repository.findByIdempotencyKey(any(), any()) }
        }
}

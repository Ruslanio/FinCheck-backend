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
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class IdempotencyTest {

    private val repository = mockk<TransactionRepository>()
    private val redisClient = mockk<RedisClient>()
    private val service: TransactionService = TransactionServiceImpl(repository, redisClient)
    private val userId = UUID.fromString("22222222-2222-2222-2222-222222222222")

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
    fun `null idempotency key bypasses lock and inserts directly`() =
        runBlocking {
            val row = makeRow()
            every { repository.insert(userId, any(), "food", null, null, any()) } returns row

            val req = CreateTransactionRequest(amount = 10.0, category = "food")
            val result = service.createTransaction(userId, req)

            assertIs<CreateTransactionResult.Created>(result)
            verify(exactly = 0) { redisClient.acquireIdempotencyLock(any(), any()) }
        }

    @Test
    fun `new key, lock acquired, no existing row - insert called and Created returned`() =
        runBlocking {
            val row = makeRow(idempotencyKey = "key-new")
            every { redisClient.acquireIdempotencyLock("key-new", userId.toString()) } returns true
            every { repository.findByIdempotencyKey("key-new", userId) } returns null
            every { repository.insert(userId, any(), "food", null, "key-new", any()) } returns row

            val req = CreateTransactionRequest(amount = 10.0, category = "food", idempotencyKey = "key-new")
            val result = service.createTransaction(userId, req)

            assertIs<CreateTransactionResult.Created>(result)
            assertSame(row, result.transaction)
            verify { repository.insert(userId, any(), "food", null, "key-new", any()) }
        }

    @Test
    fun `lock not acquired and row found in DB - returns Duplicate`() =
        runBlocking {
            val existingRow = makeRow(idempotencyKey = "key-dup")
            every { redisClient.acquireIdempotencyLock("key-dup", userId.toString()) } returns false
            every { repository.findByIdempotencyKey("key-dup", userId) } returns existingRow

            val req = CreateTransactionRequest(amount = 10.0, category = "food", idempotencyKey = "key-dup")
            val result = service.createTransaction(userId, req)

            assertIs<CreateTransactionResult.Duplicate>(result)
            assertSame(existingRow, result.transaction)
            verify(exactly = 0) { repository.insert(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `lock not acquired and no DB row - returns InFlight`() =
        runBlocking {
            every { redisClient.acquireIdempotencyLock("key-inflight", userId.toString()) } returns false
            every { repository.findByIdempotencyKey("key-inflight", userId) } returns null

            val req = CreateTransactionRequest(amount = 10.0, category = "food", idempotencyKey = "key-inflight")
            val result = service.createTransaction(userId, req)

            assertIs<CreateTransactionResult.InFlight>(result)
            verify(exactly = 0) { repository.insert(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `lock acquired but row found in DB (race condition) - releases lock and returns Duplicate`() =
        runBlocking {
            val existingRow = makeRow(idempotencyKey = "key-race")
            every { redisClient.acquireIdempotencyLock("key-race", userId.toString()) } returns true
            every { repository.findByIdempotencyKey("key-race", userId) } returns existingRow
            every { redisClient.releaseIdempotencyLock("key-race", userId.toString()) } returns Unit

            val req = CreateTransactionRequest(amount = 10.0, category = "food", idempotencyKey = "key-race")
            val result = service.createTransaction(userId, req)

            assertIs<CreateTransactionResult.Duplicate>(result)
            assertSame(existingRow, result.transaction)
            verify { redisClient.releaseIdempotencyLock("key-race", userId.toString()) }
            verify(exactly = 0) { repository.insert(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `insert exception releases lock and propagates`() =
        runBlocking<Unit> {
            every { redisClient.acquireIdempotencyLock("key-err", userId.toString()) } returns true
            every { repository.findByIdempotencyKey("key-err", userId) } returns null
            every {
                repository.insert(userId, any(), "food", null, "key-err", any())
            } throws RuntimeException("DB error")
            every { redisClient.releaseIdempotencyLock("key-err", userId.toString()) } returns Unit

            val req = CreateTransactionRequest(amount = 10.0, category = "food", idempotencyKey = "key-err")
            assertFailsWith<RuntimeException> {
                service.createTransaction(userId, req)
            }

            verify { redisClient.releaseIdempotencyLock("key-err", userId.toString()) }
        }

    @Test
    fun `Redis down (acquireIdempotencyLock returns true) - insert proceeds normally`() =
        runBlocking {
            val row = makeRow(idempotencyKey = "key-redis-down")
            every { redisClient.acquireIdempotencyLock("key-redis-down", userId.toString()) } returns true
            every { repository.findByIdempotencyKey("key-redis-down", userId) } returns null
            every { repository.insert(userId, any(), "food", null, "key-redis-down", any()) } returns row

            val req = CreateTransactionRequest(amount = 10.0, category = "food", idempotencyKey = "key-redis-down")
            val result = service.createTransaction(userId, req)

            assertIs<CreateTransactionResult.Created>(result)
            assertSame(row, result.transaction)
        }
}

package com.financetracker.service

import com.financetracker.model.CreateTransactionRequest
import com.financetracker.redis.RedisClient
import com.financetracker.repository.CategoryRepository
import com.financetracker.repository.CategoryRow
import com.financetracker.repository.CategoryType
import com.financetracker.repository.TransactionRepository
import com.financetracker.repository.TransactionRow
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

private val TEST_CATEGORY_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

class TransactionServiceTest {

    private val repository = mockk<TransactionRepository>()
    private val redisClient = mockk<RedisClient>()
    private val categoryRepository = mockk<CategoryRepository>()
    private val service: TransactionService = TransactionServiceImpl(repository, redisClient, categoryRepository)
    private val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    private fun makeRow(idempotencyKey: String? = null) =
        TransactionRow(
            id = UUID.randomUUID(),
            userId = userId,
            amount = BigDecimal("10.00"),
            categoryId = TEST_CATEGORY_ID,
            description = null,
            idempotencyKey = idempotencyKey,
            occurredAt = Clock.System.now(),
        )

    private fun makeCategoryRow() =
        CategoryRow(TEST_CATEGORY_ID, userId, "Food", CategoryType.Expense, false, null)

    @BeforeEach
    fun setUp() {
        every { categoryRepository.findByIdAndUserId(TEST_CATEGORY_ID, userId) } returns makeCategoryRow()
    }

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
            every { repository.findByUserId(userId, 0, 20, TEST_CATEGORY_ID) } returns Pair(emptyList(), 0L)

            service.getTransactions(userId, 0, 20, TEST_CATEGORY_ID)

            verify { repository.findByUserId(userId, 0, 20, TEST_CATEGORY_ID) }
        }

    @Test
    fun `createTransaction returns ValidationError for invalid categoryId format`() =
        runBlocking {
            val req = CreateTransactionRequest(amount = 10.0, categoryId = "not-a-uuid")
            val result = service.createTransaction(userId, req)
            assertIs<CreateTransactionResult.ValidationError>(result)
        }

    @Test
    fun `createTransaction returns ValidationError when category does not belong to user`() =
        runBlocking {
            val otherCategoryId = UUID.randomUUID()
            every { categoryRepository.findByIdAndUserId(otherCategoryId, userId) } returns null

            val req = CreateTransactionRequest(amount = 10.0, categoryId = otherCategoryId.toString())
            val result = service.createTransaction(userId, req)
            assertIs<CreateTransactionResult.ValidationError>(result)
            assertEquals("category_not_found", result.message)
        }

    @Test
    fun `createTransaction returns ValidationError for zero amount`() =
        runBlocking {
            val req = CreateTransactionRequest(amount = 0.0, categoryId = TEST_CATEGORY_ID.toString())
            val result = service.createTransaction(userId, req)
            assertIs<CreateTransactionResult.ValidationError>(result)
        }

    @Test
    fun `createTransaction returns Duplicate when idempotency key exists`() =
        runBlocking {
            val existingRow = makeRow(idempotencyKey = "key-1")
            every { redisClient.acquireIdempotencyLock("key-1", userId.toString()) } returns false
            every { repository.findByIdempotencyKey("key-1", userId) } returns existingRow

            val req = CreateTransactionRequest(amount = 10.0, categoryId = TEST_CATEGORY_ID.toString(), idempotencyKey = "key-1")
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
            every { repository.insert(userId, any(), TEST_CATEGORY_ID, null, "key-2", any()) } returns newRow

            val req = CreateTransactionRequest(amount = 10.0, categoryId = TEST_CATEGORY_ID.toString(), idempotencyKey = "key-2")
            val result = service.createTransaction(userId, req)

            assertIs<CreateTransactionResult.Created>(result)
            assertEquals(newRow, result.transaction)
        }

    @Test
    fun `createTransaction inserts without idempotency check when key is null`() =
        runBlocking {
            val newRow = makeRow()
            every { repository.insert(userId, any(), TEST_CATEGORY_ID, null, null, any()) } returns newRow

            val req = CreateTransactionRequest(amount = 10.0, categoryId = TEST_CATEGORY_ID.toString())
            val result = service.createTransaction(userId, req)

            assertIs<CreateTransactionResult.Created>(result)
            verify(exactly = 0) { repository.findByIdempotencyKey(any(), any()) }
        }
}

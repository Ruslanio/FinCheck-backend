package com.financetracker.service

import com.financetracker.model.CreateCategoryRequest
import com.financetracker.model.UpdateCategoryRequest
import com.financetracker.redis.RedisClient
import com.financetracker.repository.CategoryRepository
import com.financetracker.repository.CategoryRow
import com.financetracker.repository.CategoryType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CategoryServiceTest {

    private val repository = mockk<CategoryRepository>()
    private val redisClient = mockk<RedisClient>()
    private val service: CategoryService = CategoryServiceImpl(repository, redisClient)

    private val userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val otherUserId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val categoryId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")

    private fun makeCategory(
        id: UUID = categoryId,
        userId: UUID = this.userId,
        name: String = "Food",
        type: CategoryType = CategoryType.Expense,
        isFallback: Boolean = false,
    ) = CategoryRow(id, userId, name, type, isFallback, null)

    // ── getCategories ────────────────────────────────────────────────────────

    @Test
    fun `getCategories returns only caller's categories`() =
        runBlocking {
            val rows = listOf(makeCategory(), makeCategory(id = UUID.randomUUID(), name = "Transport"))
            every { repository.findAllByUserId(userId) } returns rows

            val result = service.getCategories(userId)

            assertEquals(2, result.size)
            verify { repository.findAllByUserId(userId) }
            verify(exactly = 0) { repository.findAllByUserId(otherUserId) }
        }

    // ── createCategory ───────────────────────────────────────────────────────

    @Test
    fun `createCategory returns Created for valid input`() =
        runBlocking {
            val row = makeCategory()
            every { repository.insert(userId, "Food", CategoryType.Expense, false, null) } returns row

            val req = CreateCategoryRequest(name = "Food", type = "expense")
            val result = service.createCategory(userId, req)

            assertIs<CreateCategoryResult.Created>(result)
            assertEquals(categoryId.toString(), result.category.id.toString())
        }

    @Test
    fun `createCategory returns ValidationError for blank name`() =
        runBlocking {
            val req = CreateCategoryRequest(name = "   ", type = "expense")
            val result = service.createCategory(userId, req)

            assertTrue(result is CreateCategoryResult.ValidationError)
        }

    @Test
    fun `createCategory returns ValidationError for invalid type`() =
        runBlocking {
            val req = CreateCategoryRequest(name = "Food", type = "invalid")
            val result = service.createCategory(userId, req)

            assertTrue(result is CreateCategoryResult.ValidationError)
        }

    @Test
    fun `createCategory returns Duplicate when idempotency key already exists`() =
        runBlocking {
            val existingRow = makeCategory()
            every { redisClient.acquireIdempotencyLock("key-1", userId.toString()) } returns false
            every { repository.findByIdempotencyKey("key-1", userId) } returns existingRow

            val req = CreateCategoryRequest(name = "Food", type = "expense", idempotencyKey = "key-1")
            val result = service.createCategory(userId, req)

            assertIs<CreateCategoryResult.Duplicate>(result)
            assertEquals(existingRow, result.category)
        }

    @Test
    fun `createCategory returns InFlight when lock not acquired and no DB row`() =
        runBlocking {
            every { redisClient.acquireIdempotencyLock("key-inflight", userId.toString()) } returns false
            every { repository.findByIdempotencyKey("key-inflight", userId) } returns null

            val req = CreateCategoryRequest(name = "Food", type = "expense", idempotencyKey = "key-inflight")
            val result = service.createCategory(userId, req)

            assertTrue(result is CreateCategoryResult.InFlight)
        }

    // ── updateCategory ───────────────────────────────────────────────────────

    @Test
    fun `updateCategory returns Updated for valid input`() =
        runBlocking {
            val updated = makeCategory(name = "Groceries")
            every { repository.update(categoryId, userId, "Groceries", CategoryType.Expense) } returns updated

            val req = UpdateCategoryRequest(name = "Groceries", type = "expense")
            val result = service.updateCategory(userId, categoryId, req)

            assertIs<UpdateCategoryResult.Updated>(result)
            assertEquals("Groceries", result.category.name)
        }

    @Test
    fun `updateCategory returns NotFound when category belongs to another user`() =
        runBlocking {
            every { repository.update(categoryId, otherUserId, "Groceries", CategoryType.Expense) } returns null

            val req = UpdateCategoryRequest(name = "Groceries", type = "expense")
            val result = service.updateCategory(otherUserId, categoryId, req)

            assertTrue(result is UpdateCategoryResult.NotFound)
        }

    @Test
    fun `updateCategory returns ValidationError for blank name`() =
        runBlocking {
            val req = UpdateCategoryRequest(name = "", type = "expense")
            val result = service.updateCategory(userId, categoryId, req)

            assertTrue(result is UpdateCategoryResult.ValidationError)
        }

    @Test
    fun `updateCategory returns ValidationError for invalid type`() =
        runBlocking {
            val req = UpdateCategoryRequest(name = "Food", type = "bad")
            val result = service.updateCategory(userId, categoryId, req)

            assertTrue(result is UpdateCategoryResult.ValidationError)
        }

    // ── deleteCategory ───────────────────────────────────────────────────────

    @Test
    fun `deleteCategory rejects fallback category with FallbackNotDeletable`() =
        runBlocking {
            val fallback = makeCategory(isFallback = true)
            every { repository.findByIdAndUserId(categoryId, userId) } returns fallback

            val result = service.deleteCategory(userId, categoryId)

            assertIs<DeleteCategoryResult.FallbackNotDeletable>(result)
            verify(exactly = 0) { repository.deleteAndReassignTransactions(any(), any(), any()) }
        }

    @Test
    fun `deleteCategory returns NotFound when category does not belong to user`() =
        runBlocking {
            every { repository.findByIdAndUserId(categoryId, otherUserId) } returns null

            val result = service.deleteCategory(otherUserId, categoryId)

            assertTrue(result is DeleteCategoryResult.NotFound)
        }

    @Test
    fun `deleteCategory reassigns expense transactions to expense fallback`() =
        runBlocking {
            val expenseFallbackId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
            val category = makeCategory(type = CategoryType.Expense, isFallback = false)
            val fallback = makeCategory(id = expenseFallbackId, type = CategoryType.Expense, isFallback = true)

            every { repository.findByIdAndUserId(categoryId, userId) } returns category
            every { repository.findFallbackByType(userId, CategoryType.Expense) } returns fallback
            every { repository.deleteAndReassignTransactions(categoryId, userId, expenseFallbackId) } returns true

            val result = service.deleteCategory(userId, categoryId)

            assertIs<DeleteCategoryResult.Success>(result)
            verify { repository.deleteAndReassignTransactions(categoryId, userId, expenseFallbackId) }
        }

    @Test
    fun `deleteCategory reassigns income transactions to income fallback`() =
        runBlocking {
            val incomeFallbackId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")
            val category = makeCategory(type = CategoryType.Income, isFallback = false)
            val fallback = makeCategory(id = incomeFallbackId, type = CategoryType.Income, isFallback = true)

            every { repository.findByIdAndUserId(categoryId, userId) } returns category
            every { repository.findFallbackByType(userId, CategoryType.Income) } returns fallback
            every { repository.deleteAndReassignTransactions(categoryId, userId, incomeFallbackId) } returns true

            val result = service.deleteCategory(userId, categoryId)

            assertIs<DeleteCategoryResult.Success>(result)
            verify { repository.deleteAndReassignTransactions(categoryId, userId, incomeFallbackId) }
        }
}

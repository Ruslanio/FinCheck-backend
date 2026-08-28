package com.financetracker.service

import com.financetracker.model.CategoryResponse
import com.financetracker.model.CreateCategoryRequest
import com.financetracker.model.UpdateCategoryRequest
import com.financetracker.model.toResponse
import com.financetracker.redis.RedisClient
import com.financetracker.repository.CategoryRepository
import com.financetracker.repository.CategoryRow
import com.financetracker.repository.CategoryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface CreateCategoryResult {
    data class Created(val category: CategoryRow) : CreateCategoryResult

    data class Duplicate(val category: CategoryRow) : CreateCategoryResult

    data object InFlight : CreateCategoryResult

    data class ValidationError(val message: String) : CreateCategoryResult
}

sealed interface UpdateCategoryResult {
    data class Updated(val category: CategoryRow) : UpdateCategoryResult

    data object NotFound : UpdateCategoryResult

    data class ValidationError(val message: String) : UpdateCategoryResult
}

sealed interface DeleteCategoryResult {
    data object Success : DeleteCategoryResult

    data object NotFound : DeleteCategoryResult

    data object FallbackNotDeletable : DeleteCategoryResult
}

interface CategoryService {
    suspend fun getCategories(userId: UUID): List<CategoryResponse>

    suspend fun createCategory(
        userId: UUID,
        request: CreateCategoryRequest,
    ): CreateCategoryResult

    suspend fun updateCategory(
        userId: UUID,
        id: UUID,
        request: UpdateCategoryRequest,
    ): UpdateCategoryResult

    suspend fun deleteCategory(
        userId: UUID,
        id: UUID,
    ): DeleteCategoryResult
}

class CategoryServiceImpl(
    private val repository: CategoryRepository,
    private val redisClient: RedisClient,
) : CategoryService {
    override suspend fun getCategories(userId: UUID): List<CategoryResponse> =
        withContext(Dispatchers.IO) {
            repository.findAllByUserId(userId).map { it.toResponse() }
        }

    override suspend fun createCategory(
        userId: UUID,
        request: CreateCategoryRequest,
    ): CreateCategoryResult {
        val name = request.name.trim()
        if (name.isBlank()) return CreateCategoryResult.ValidationError("missing_required_fields")

        val type =
            CategoryType.fromString(request.type)
                ?: return CreateCategoryResult.ValidationError("invalid_category_type")

        val key = request.idempotencyKey

        if (key != null) {
            val lockAcquired = redisClient.acquireIdempotencyLock(key, userId.toString())

            if (!lockAcquired) {
                val existing =
                    withContext(Dispatchers.IO) {
                        repository.findByIdempotencyKey(key, userId)
                    }
                return if (existing != null) {
                    CreateCategoryResult.Duplicate(existing)
                } else {
                    CreateCategoryResult.InFlight
                }
            }

            val existing =
                withContext(Dispatchers.IO) {
                    repository.findByIdempotencyKey(key, userId)
                }
            if (existing != null) {
                redisClient.releaseIdempotencyLock(key, userId.toString())
                return CreateCategoryResult.Duplicate(existing)
            }
        }

        val row =
            try {
                withContext(Dispatchers.IO) {
                    repository.insert(userId, name, type, isFallback = false, idempotencyKey = key)
                }
            } catch (e: Exception) {
                if (key != null) redisClient.releaseIdempotencyLock(key, userId.toString())
                throw e
            }

        return CreateCategoryResult.Created(row)
    }

    override suspend fun updateCategory(
        userId: UUID,
        id: UUID,
        request: UpdateCategoryRequest,
    ): UpdateCategoryResult {
        val name = request.name.trim()
        if (name.isBlank()) return UpdateCategoryResult.ValidationError("missing_required_fields")

        val type =
            CategoryType.fromString(request.type)
                ?: return UpdateCategoryResult.ValidationError("invalid_category_type")

        val updated =
            withContext(Dispatchers.IO) {
                repository.update(id, userId, name, type)
            } ?: return UpdateCategoryResult.NotFound

        return UpdateCategoryResult.Updated(updated)
    }

    override suspend fun deleteCategory(
        userId: UUID,
        id: UUID,
    ): DeleteCategoryResult {
        val category =
            withContext(Dispatchers.IO) {
                repository.findByIdAndUserId(id, userId)
            } ?: return DeleteCategoryResult.NotFound

        if (category.isFallback) return DeleteCategoryResult.FallbackNotDeletable

        val fallback =
            withContext(Dispatchers.IO) {
                repository.findFallbackByType(userId, category.type)
            } ?: error("Missing fallback category for type ${category.type.value} and user $userId")

        withContext(Dispatchers.IO) {
            repository.deleteAndReassignTransactions(id, userId, fallback.id)
        }

        return DeleteCategoryResult.Success
    }
}

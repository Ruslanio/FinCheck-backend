package com.financetracker.model

import com.financetracker.repository.CategoryRow
import kotlinx.serialization.Serializable

@Serializable
data class CategoryResponse(
    val id: String,
    val name: String,
    val type: String,
    val isFallback: Boolean,
)

@Serializable
data class CreateCategoryRequest(
    val name: String,
    val type: String,
    val idempotencyKey: String? = null,
)

@Serializable
data class UpdateCategoryRequest(
    val name: String,
    val type: String,
)

fun CategoryRow.toResponse() =
    CategoryResponse(
        id = id.toString(),
        name = name,
        type = type.value,
        isFallback = isFallback,
    )

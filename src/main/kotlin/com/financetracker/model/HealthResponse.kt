package com.financetracker.model

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val db: String,
    val version: String,
)

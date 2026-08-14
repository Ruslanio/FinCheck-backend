package com.financetracker.ratelimit

data class RateLimitConfig(
    val limit: Int,
    val windowSeconds: Long = 60L,
    val keyPrefix: String,
)

object RateLimits {
    val AUTH_LOGIN = RateLimitConfig(limit = 10, keyPrefix = "rate:ip")
    val AUTH_REGISTER = RateLimitConfig(limit = 5, keyPrefix = "rate:ip")
    val TRANSACTIONS = RateLimitConfig(limit = 100, keyPrefix = "rate")
}

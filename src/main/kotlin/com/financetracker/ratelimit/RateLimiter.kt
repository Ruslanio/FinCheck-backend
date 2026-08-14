package com.financetracker.ratelimit

import com.financetracker.redis.RedisClient

sealed interface RateLimitResult {
    val limit: Int
    val resetAtEpoch: Long

    data class Allowed(
        override val limit: Int,
        val remaining: Int,
        override val resetAtEpoch: Long,
    ) : RateLimitResult

    data class Exceeded(
        override val limit: Int,
        val remaining: Int,
        override val resetAtEpoch: Long,
        val retryAfterSeconds: Long,
    ) : RateLimitResult
}

class RateLimiter(private val redisClient: RedisClient) {
    fun check(
        key: String,
        config: RateLimitConfig,
    ): RateLimitResult {
        val bucket = System.currentTimeMillis() / 60_000
        val redisKey = "${config.keyPrefix}:$key:$bucket"
        // Fixed-window-per-minute: up to 2× the limit may be served across a minute boundary
        // (last second of one window + first second of next). Acceptable trade-off for simplicity.
        // Extra 5s TTL guards against premature expiry due to clock drift.
        val windowTtl = config.windowSeconds + 5

        val count = redisClient.incrementRateLimit(redisKey, windowTtl) ?: 0L
        val remaining = maxOf(0L, config.limit - count)
        val resetAt = (bucket + 1) * 60L

        return if (count > config.limit) {
            RateLimitResult.Exceeded(
                limit = config.limit,
                remaining = 0,
                resetAtEpoch = resetAt,
                retryAfterSeconds = (resetAt - System.currentTimeMillis() / 1000).coerceAtLeast(1),
            )
        } else {
            RateLimitResult.Allowed(
                limit = config.limit,
                remaining = remaining.toInt(),
                resetAtEpoch = resetAt,
            )
        }
    }
}

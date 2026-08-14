package com.financetracker.ratelimit

import com.financetracker.redis.RedisClient
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RateLimiterTest {
    private val redisClient = mockk<RedisClient>()
    private val rateLimiter = RateLimiter(redisClient)

    @Test
    fun `first request returns Allowed with remaining equal to limit minus 1`() {
        every { redisClient.incrementRateLimit(any(), any()) } returns 1L
        val result = rateLimiter.check("user1", RateLimits.AUTH_LOGIN)
        assertIs<RateLimitResult.Allowed>(result)
        assertEquals(RateLimits.AUTH_LOGIN.limit - 1, result.remaining)
    }

    @Test
    fun `request exactly at limit returns Allowed with remaining 0`() {
        every { redisClient.incrementRateLimit(any(), any()) } returns RateLimits.AUTH_LOGIN.limit.toLong()
        val result = rateLimiter.check("user1", RateLimits.AUTH_LOGIN)
        assertIs<RateLimitResult.Allowed>(result)
        assertEquals(0, result.remaining)
    }

    @Test
    fun `request over limit returns Exceeded with remaining 0`() {
        every { redisClient.incrementRateLimit(any(), any()) } returns (RateLimits.AUTH_LOGIN.limit + 1).toLong()
        val result = rateLimiter.check("user1", RateLimits.AUTH_LOGIN)
        assertIs<RateLimitResult.Exceeded>(result)
        assertEquals(0, result.remaining)
    }

    @Test
    fun `Exceeded result includes positive retryAfterSeconds`() {
        every { redisClient.incrementRateLimit(any(), any()) } returns 999L
        val result = rateLimiter.check("user1", RateLimits.AUTH_LOGIN)
        assertIs<RateLimitResult.Exceeded>(result)
        assertTrue(result.retryAfterSeconds > 0)
    }

    @Test
    fun `Redis down returns Allowed with remaining equal to limit`() {
        every { redisClient.incrementRateLimit(any(), any()) } returns null
        val result = rateLimiter.check("user1", RateLimits.AUTH_LOGIN)
        assertIs<RateLimitResult.Allowed>(result)
        assertEquals(RateLimits.AUTH_LOGIN.limit, result.remaining)
    }

    @Test
    fun `resetAtEpoch is the next minute boundary in unix seconds`() {
        every { redisClient.incrementRateLimit(any(), any()) } returns 1L
        val beforeBucket = System.currentTimeMillis() / 60_000
        val result = rateLimiter.check("user1", RateLimits.AUTH_LOGIN)
        val afterBucket = System.currentTimeMillis() / 60_000
        assertTrue(result.resetAtEpoch >= (beforeBucket + 1) * 60L)
        assertTrue(result.resetAtEpoch <= (afterBucket + 1) * 60L)
    }

    @Test
    fun `two different keys are independent`() {
        every { redisClient.incrementRateLimit(any(), any()) } answers {
            if ("user1" in firstArg<String>()) (RateLimits.AUTH_LOGIN.limit + 1).toLong() else 1L
        }

        val result1 = rateLimiter.check("user1", RateLimits.AUTH_LOGIN)
        val result2 = rateLimiter.check("user2", RateLimits.AUTH_LOGIN)

        assertIs<RateLimitResult.Exceeded>(result1)
        assertIs<RateLimitResult.Allowed>(result2)
    }

    @Test
    fun `AUTH_LOGIN limit is 10, AUTH_REGISTER is 5, TRANSACTIONS is 100`() {
        assertEquals(10, RateLimits.AUTH_LOGIN.limit)
        assertEquals(5, RateLimits.AUTH_REGISTER.limit)
        assertEquals(100, RateLimits.TRANSACTIONS.limit)
    }
}

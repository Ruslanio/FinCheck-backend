package com.financetracker.redis

import com.financetracker.model.SessionData
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.exceptions.JedisException
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionCacheTest {

    private val mockPool = mockk<JedisPool>()
    private val mockJedis = mockk<Jedis>(relaxed = true)

    @BeforeEach
    fun setUp() {
        clearAllMocks()
        RedisClient.pool = mockPool
        every { mockPool.resource } returns mockJedis
    }

    @AfterEach
    fun tearDown() {
        RedisClient.pool = null
    }

    @Test
    fun `setSession stores correct fields in Redis`() {
        val data = SessionData("user-1", "user@example.com", "2026-01-01T00:00:00Z")
        RedisClient.setSession("mytoken", data)
        verify {
            mockJedis.hset(
                "session:mytoken",
                mapOf(
                    "userId" to "user-1",
                    "email" to "user@example.com",
                    "createdAt" to "2026-01-01T00:00:00Z",
                ),
            )
        }
    }

    @Test
    fun `setSession sets TTL to 900`() {
        val data = SessionData("user-1", "user@example.com", "2026-01-01T00:00:00Z")
        RedisClient.setSession("mytoken", data)
        verify { mockJedis.expire("session:mytoken", 900L) }
    }

    @Test
    fun `getSession returns SessionData when key exists`() {
        every { mockJedis.hgetAll("session:mytoken") } returns
            mapOf(
                "userId" to "user-1",
                "email" to "user@example.com",
                "createdAt" to "2026-01-01T00:00:00Z",
            )

        val result = RedisClient.getSession("mytoken")

        assertEquals(SessionData("user-1", "user@example.com", "2026-01-01T00:00:00Z"), result)
    }

    @Test
    fun `getSession slides TTL on successful read`() {
        every { mockJedis.hgetAll("session:mytoken") } returns
            mapOf(
                "userId" to "user-1",
                "email" to "user@example.com",
                "createdAt" to "2026-01-01T00:00:00Z",
            )

        RedisClient.getSession("mytoken")

        verify { mockJedis.expire("session:mytoken", 900L) }
    }

    @Test
    fun `getSession returns null when key does not exist`() {
        every { mockJedis.hgetAll("session:mytoken") } returns emptyMap()

        val result = RedisClient.getSession("mytoken")

        assertNull(result)
    }

    @Test
    fun `deleteSession removes the key`() {
        RedisClient.deleteSession("mytoken")
        verify { mockJedis.del("session:mytoken") }
    }

    @Test
    fun `execute returns null when pool is not initialized`() {
        RedisClient.pool = null
        val result = RedisClient.execute { "value" }
        assertNull(result)
    }

    @Test
    fun `execute returns null when Jedis throws JedisException`() {
        every { mockPool.resource } throws JedisException("connection refused")
        val result = RedisClient.execute { "value" }
        assertNull(result)
    }
}

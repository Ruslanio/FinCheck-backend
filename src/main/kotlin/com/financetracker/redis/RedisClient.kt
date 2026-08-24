package com.financetracker.redis

import com.financetracker.model.SessionData
import org.slf4j.LoggerFactory
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.params.SetParams

private val logger = LoggerFactory.getLogger("RedisClient")

interface RedisClient {
    fun <T> execute(block: (Jedis) -> T): T?

    fun setSession(
        token: String,
        data: SessionData,
    )

    fun getSession(token: String): SessionData?

    fun deleteSession(token: String)

    fun incrementRateLimit(
        key: String,
        ttlSeconds: Long,
    ): Long?

    fun acquireIdempotencyLock(
        key: String,
        userId: String,
    ): Boolean

    fun releaseIdempotencyLock(
        key: String,
        userId: String,
    )
}

class RedisClientImpl(private val pool: JedisPool?) : RedisClient {
    override fun <T> execute(block: (Jedis) -> T): T? =
        runCatching {
            pool?.resource?.use { block(it) }
        }.onFailure { e ->
            logger.warn("Redis operation failed: ${e.message}")
        }.getOrNull()

    override fun setSession(
        token: String,
        data: SessionData,
    ) {
        execute { jedis ->
            val key = sessionKey(token)
            jedis.hset(
                key,
                mapOf(
                    "userId" to data.userId,
                    "email" to data.email,
                    "createdAt" to data.createdAt,
                ),
            )
            jedis.expire(key, 900L)
        }
    }

    override fun getSession(token: String): SessionData? =
        execute { jedis ->
            val key = sessionKey(token)
            val fields = jedis.hgetAll(key)
            if (fields.isNullOrEmpty()) return@execute null
            jedis.expire(key, 900L)
            SessionData(
                userId = fields["userId"] ?: return@execute null,
                email = fields["email"] ?: return@execute null,
                createdAt = fields["createdAt"] ?: return@execute null,
            )
        }

    override fun deleteSession(token: String) {
        execute { jedis -> jedis.del(sessionKey(token)) }
    }

    override fun incrementRateLimit(
        key: String,
        ttlSeconds: Long,
    ): Long? =
        execute { jedis ->
            val count = jedis.incr(key)
            if (count == 1L) jedis.expire(key, ttlSeconds)
            count
        }

    override fun acquireIdempotencyLock(
        key: String,
        userId: String,
    ): Boolean {
        val redisKey = "idem:$key:$userId"
        return execute { jedis ->
            val result = jedis.set(redisKey, "1", SetParams().nx().ex(86400L))
            result != null
        } ?: true
    }

    override fun releaseIdempotencyLock(
        key: String,
        userId: String,
    ) {
        execute { jedis -> jedis.del("idem:$key:$userId") }
    }

    private fun sessionKey(token: String) = "session:$token"
}

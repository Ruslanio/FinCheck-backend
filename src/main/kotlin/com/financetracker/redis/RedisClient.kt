package com.financetracker.redis

import com.financetracker.model.SessionData
import org.slf4j.LoggerFactory
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig

private val logger = LoggerFactory.getLogger("RedisClient")

object RedisClient {

    internal var pool: JedisPool? = null

    fun initialize(
        host: String,
        port: Int,
        poolSize: Int,
        timeoutMs: Int,
    ) {
        val config =
            JedisPoolConfig().apply {
                maxTotal = poolSize
                maxIdle = poolSize / 2
                minIdle = 1
                testOnBorrow = true
                testWhileIdle = true
            }
        pool = JedisPool(config, host, port, timeoutMs)
    }

    fun <T> execute(block: (Jedis) -> T): T? =
        runCatching {
            pool?.resource?.use { block(it) }
        }.onFailure { e ->
            logger.warn("Redis operation failed: ${e.message}")
        }.getOrNull()

    fun close() {
        pool?.close()
    }

    fun setSession(
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

    fun getSession(token: String): SessionData? =
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

    fun deleteSession(token: String) {
        execute { jedis -> jedis.del(sessionKey(token)) }
    }

    private fun sessionKey(token: String) = "session:$token"
}

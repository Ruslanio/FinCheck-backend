package com.financetracker.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig

private val logger = LoggerFactory.getLogger("RedisPlugin")

fun Application.createRedisPool(): JedisPool? {
    val config = environment.config.config("redis")
    val host = config.property("host").getString()
    val port = config.property("port").getString().toInt()
    val poolSize = config.property("poolSize").getString().toInt()
    val timeout = config.property("timeoutMs").getString().toInt()

    val pool =
        runCatching {
            val poolConfig =
                JedisPoolConfig().apply {
                    maxTotal = poolSize
                    maxIdle = poolSize / 2
                    minIdle = 1
                    testOnBorrow = true
                    testWhileIdle = true
                }
            JedisPool(poolConfig, host, port, timeout)
        }.onSuccess {
            logger.info("Redis connection pool initialized at $host:$port")
        }.onFailure {
            logger.warn("Redis pool initialization failed: ${it.message}")
        }.getOrNull()

    if (pool != null) {
        monitor.subscribe(ApplicationStopped) {
            pool.close()
            logger.info("Redis connection pool closed")
        }
    }

    return pool
}

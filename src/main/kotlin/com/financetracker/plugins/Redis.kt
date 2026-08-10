package com.financetracker.plugins

import com.financetracker.redis.RedisClient
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("RedisPlugin")

fun Application.configureRedis() {
    val config = environment.config.config("redis")
    val host = config.property("host").getString()
    val port = config.property("port").getString().toInt()
    val poolSize = config.property("poolSize").getString().toInt()
    val timeout = config.property("timeoutMs").getString().toInt()

    RedisClient.initialize(host, port, poolSize, timeout)
    logger.info("Redis connection pool initialized at $host:$port")

    monitor.subscribe(ApplicationStopped) {
        RedisClient.close()
        logger.info("Redis connection pool closed")
    }
}

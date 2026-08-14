package com.financetracker.plugins

import com.financetracker.model.ErrorResponse
import com.financetracker.ratelimit.RateLimitConfig
import com.financetracker.ratelimit.RateLimitResult
import com.financetracker.ratelimit.RateLimiter
import com.financetracker.redis.RedisClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import org.koin.ktor.ext.get

val RateLimiterKey = AttributeKey<RateLimiter>("RateLimiter")

fun Application.configureRateLimit() {
    val rateLimiter = RateLimiter(get<RedisClient>())
    attributes.put(RateLimiterKey, rateLimiter)
}

// Returns false and sends 429 if the rate limit is exceeded; true if the request is allowed.
// No-ops and returns true when configureRateLimit() has not been installed (e.g. in unit tests).
// TODO: Routes use call.request.local.remoteHost (direct TCP IP). Behind a reverse proxy,
//  install ForwardedHeaders plugin and use call.request.origin.remoteHost instead.
suspend fun ApplicationCall.checkRateLimit(
    key: String,
    config: RateLimitConfig,
): Boolean {
    val rateLimiter = application.attributes.getOrNull(RateLimiterKey) ?: return true

    val result = rateLimiter.check(key, config)

    response.headers.append("X-RateLimit-Limit", result.limit.toString())
    response.headers.append(
        "X-RateLimit-Remaining",
        when (result) {
            is RateLimitResult.Allowed -> result.remaining.toString()
            is RateLimitResult.Exceeded -> "0"
        },
    )
    response.headers.append("X-RateLimit-Reset", result.resetAtEpoch.toString())

    if (result is RateLimitResult.Exceeded) {
        response.headers.append("Retry-After", result.retryAfterSeconds.toString())
        respond(
            HttpStatusCode.TooManyRequests,
            ErrorResponse(error = "rate_limit_exceeded", retryAfterSeconds = result.retryAfterSeconds),
        )
        return false
    }
    return true
}

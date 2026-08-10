package com.financetracker.util

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.util.Date
import javax.crypto.SecretKey

object JwtUtil {
    private const val EXPIRY_MS = 900_000L

    private fun secret(): SecretKey {
        val raw =
            System.getenv("JWT_SECRET")
                ?: System.getProperty("JWT_SECRET")
                ?: throw IllegalStateException("JWT_SECRET environment variable is not set")
        return Keys.hmacShaKeyFor(raw.toByteArray())
    }

    fun issueAccessToken(userId: String): String {
        val now = System.currentTimeMillis()
        return Jwts.builder()
            .subject(userId)
            .issuedAt(Date(now))
            .expiration(Date(now + EXPIRY_MS))
            .signWith(secret())
            .compact()
    }

    fun verifyAccessToken(token: String): String? =
        runCatching {
            Jwts.parser()
                .verifyWith(secret())
                .build()
                .parseSignedClaims(token)
                .payload
                .subject
        }.getOrNull()
}

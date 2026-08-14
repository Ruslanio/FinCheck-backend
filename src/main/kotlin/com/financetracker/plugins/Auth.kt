package com.financetracker.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.financetracker.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond

fun Application.configureAuth() {
    val secret =
        System.getenv("JWT_SECRET")
            ?: throw IllegalStateException("JWT_SECRET not set")

    install(Authentication) {
        jwt("auth-jwt") {
            verifier(
                JWT.require(Algorithm.HMAC256(secret))
                    .build(),
            )
            validate { credential ->
                val userId = credential.payload.subject
                if (userId != null) JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_or_expired_token"))
            }
        }
    }
}

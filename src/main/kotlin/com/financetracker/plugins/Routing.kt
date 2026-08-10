package com.financetracker.plugins

import com.financetracker.routing.configureAuthRouting
import com.financetracker.routing.healthRoutes
import com.financetracker.service.AuthService
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

fun Application.configureRouting(
    authService: AuthService = AuthService(),
    dbPing: suspend () -> Boolean = ::pingDatabase,
) {
    configureAuthRouting(authService)
    routing {
        healthRoutes(dbPing)
    }
}

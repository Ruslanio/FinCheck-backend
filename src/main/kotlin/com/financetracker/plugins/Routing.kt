package com.financetracker.plugins

import com.financetracker.routing.healthRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

fun Application.configureRouting(
    dbPing: suspend () -> Boolean = ::pingDatabase,
) {
    routing {
        healthRoutes(dbPing)
    }
}

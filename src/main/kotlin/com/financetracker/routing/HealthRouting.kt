package com.financetracker.routing

import com.financetracker.model.HealthResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.healthRoutes(dbPing: suspend () -> Boolean) {
    get("/health") {
        val version =
            call.application.environment.config
                .propertyOrNull("app.version")?.getString() ?: "dev"
        if (dbPing()) {
            call.respond(HttpStatusCode.OK, HealthResponse("ok", "ok", version))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse("degraded", "error", version))
        }
    }
}

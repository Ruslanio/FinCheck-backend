package com.financetracker.routing

import com.financetracker.model.ErrorResponse
import com.financetracker.model.LoginRequest
import com.financetracker.model.LoginResult
import com.financetracker.model.RegisterRequest
import com.financetracker.model.RegisterResult
import com.financetracker.service.AuthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AuthRouting")

fun Application.configureAuthRouting(authService: AuthService = AuthService()) {
    routing {
        route("/auth") {
            post("/register") {
                val req =
                    runCatching { call.receive<RegisterRequest>() }.getOrElse {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request"))
                        return@post
                    }
                try {
                    when (val result = authService.register(req.email, req.password)) {
                        is RegisterResult.Success ->
                            call.respond(HttpStatusCode.Created, result.response)
                        RegisterResult.InvalidEmail ->
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_email"))
                        RegisterResult.PasswordTooShort ->
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("password_too_short"))
                        RegisterResult.EmailAlreadyRegistered ->
                            call.respond(HttpStatusCode.Conflict, ErrorResponse("email_already_registered"))
                    }
                } catch (e: Exception) {
                    logger.error("Unhandled error in POST /auth/register", e)
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error"))
                }
            }

            post("/login") {
                val req =
                    runCatching { call.receive<LoginRequest>() }.getOrElse {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request"))
                        return@post
                    }
                try {
                    when (val result = authService.login(req.email, req.password)) {
                        is LoginResult.Success ->
                            call.respond(HttpStatusCode.OK, result.response)
                        LoginResult.InvalidCredentials ->
                            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_credentials"))
                    }
                } catch (e: Exception) {
                    logger.error("Unhandled error in POST /auth/login", e)
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error"))
                }
            }
        }
    }
}

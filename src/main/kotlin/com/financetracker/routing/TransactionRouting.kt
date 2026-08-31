package com.financetracker.routing

import com.financetracker.model.CreateTransactionRequest
import com.financetracker.model.ErrorResponse
import com.financetracker.model.toResponse
import com.financetracker.plugins.checkRateLimit
import com.financetracker.ratelimit.RateLimits
import com.financetracker.service.CreateTransactionResult
import com.financetracker.service.TransactionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("TransactionRouting")

fun Route.transactionRoutes(transactionService: TransactionService) {
    authenticate("auth-jwt") {
        route("/transactions") {
            get {
                val userId =
                    UUID.fromString(call.principal<JWTPrincipal>()!!.payload.subject)
                if (!call.checkRateLimit(userId.toString(), RateLimits.TRANSACTIONS)) return@get

                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
                val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20

                val categoryIdStr = call.request.queryParameters["categoryId"]
                val categoryId: UUID? =
                    if (categoryIdStr != null) {
                        runCatching { UUID.fromString(categoryIdStr) }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_category_id"))
                            return@get
                        }
                    } else {
                        null
                    }

                try {
                    val result = transactionService.getTransactions(userId, page, size, categoryId)
                    call.respond(HttpStatusCode.OK, result)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "invalid_request"))
                } catch (e: Exception) {
                    logger.error("Unhandled error in GET /transactions", e)
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error"))
                }
            }

            post {
                val userId =
                    UUID.fromString(call.principal<JWTPrincipal>()!!.payload.subject)
                if (!call.checkRateLimit(userId.toString(), RateLimits.TRANSACTIONS)) return@post

                val req =
                    runCatching { call.receive<CreateTransactionRequest>() }.getOrElse {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request"))
                        return@post
                    }

                try {
                    when (val result = transactionService.createTransaction(userId, req)) {
                        is CreateTransactionResult.Created ->
                            call.respond(HttpStatusCode.Created, result.transaction.toResponse())
                        is CreateTransactionResult.Duplicate ->
                            call.respond(HttpStatusCode.OK, result.transaction.toResponse())
                        is CreateTransactionResult.InFlight ->
                            call.respond(HttpStatusCode.Conflict, ErrorResponse("request_in_flight"))
                        is CreateTransactionResult.ValidationError ->
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                    }
                } catch (e: Exception) {
                    logger.error("Unhandled error in POST /transactions", e)
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error"))
                }
            }
        }
    }
}

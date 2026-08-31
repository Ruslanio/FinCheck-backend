package com.financetracker.routing

import com.financetracker.model.CreateCategoryRequest
import com.financetracker.model.ErrorResponse
import com.financetracker.model.UpdateCategoryRequest
import com.financetracker.model.toResponse
import com.financetracker.plugins.checkRateLimit
import com.financetracker.ratelimit.RateLimits
import com.financetracker.service.CategoryService
import com.financetracker.service.CreateCategoryResult
import com.financetracker.service.DeleteCategoryResult
import com.financetracker.service.UpdateCategoryResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("CategoryRouting")

fun Route.categoryRoutes(categoryService: CategoryService) {
    authenticate("auth-jwt") {
        route("/categories") {
            get {
                val userId = UUID.fromString(call.principal<JWTPrincipal>()!!.payload.subject)
                if (!call.checkRateLimit(userId.toString(), RateLimits.CATEGORIES)) return@get

                try {
                    val categories = categoryService.getCategories(userId)
                    call.respond(HttpStatusCode.OK, categories)
                } catch (e: Exception) {
                    logger.error("Unhandled error in GET /categories", e)
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error"))
                }
            }

            post {
                val userId = UUID.fromString(call.principal<JWTPrincipal>()!!.payload.subject)
                if (!call.checkRateLimit(userId.toString(), RateLimits.CATEGORIES)) return@post

                val req =
                    runCatching { call.receive<CreateCategoryRequest>() }.getOrElse {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request"))
                        return@post
                    }

                try {
                    when (val result = categoryService.createCategory(userId, req)) {
                        is CreateCategoryResult.Created ->
                            call.respond(HttpStatusCode.Created, result.category.toResponse())
                        is CreateCategoryResult.Duplicate ->
                            call.respond(HttpStatusCode.OK, result.category.toResponse())
                        is CreateCategoryResult.InFlight ->
                            call.respond(HttpStatusCode.Conflict, ErrorResponse("request_in_flight"))
                        is CreateCategoryResult.ValidationError ->
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                    }
                } catch (e: Exception) {
                    logger.error("Unhandled error in POST /categories", e)
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error"))
                }
            }

            route("/{id}") {
                put {
                    val userId = UUID.fromString(call.principal<JWTPrincipal>()!!.payload.subject)
                    if (!call.checkRateLimit(userId.toString(), RateLimits.CATEGORIES)) return@put

                    val id =
                        runCatching { UUID.fromString(call.parameters["id"] ?: "") }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_id"))
                            return@put
                        }

                    val req =
                        runCatching { call.receive<UpdateCategoryRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request"))
                            return@put
                        }

                    try {
                        when (val result = categoryService.updateCategory(userId, id, req)) {
                            is UpdateCategoryResult.Updated ->
                                call.respond(HttpStatusCode.OK, result.category.toResponse())
                            is UpdateCategoryResult.NotFound ->
                                call.respond(HttpStatusCode.NotFound, ErrorResponse("category_not_found"))
                            is UpdateCategoryResult.ValidationError ->
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                        }
                    } catch (e: Exception) {
                        logger.error("Unhandled error in PUT /categories/{id}", e)
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error"))
                    }
                }

                delete {
                    val userId = UUID.fromString(call.principal<JWTPrincipal>()!!.payload.subject)
                    if (!call.checkRateLimit(userId.toString(), RateLimits.CATEGORIES)) return@delete

                    val id =
                        runCatching { UUID.fromString(call.parameters["id"] ?: "") }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_id"))
                            return@delete
                        }

                    try {
                        when (categoryService.deleteCategory(userId, id)) {
                            DeleteCategoryResult.Success ->
                                call.respond(HttpStatusCode.NoContent)
                            DeleteCategoryResult.NotFound ->
                                call.respond(HttpStatusCode.NotFound, ErrorResponse("category_not_found"))
                            DeleteCategoryResult.FallbackNotDeletable ->
                                call.respond(HttpStatusCode.Conflict, ErrorResponse("fallback_category_not_deletable"))
                        }
                    } catch (e: Exception) {
                        logger.error("Unhandled error in DELETE /categories/{id}", e)
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error"))
                    }
                }
            }
        }
    }
}

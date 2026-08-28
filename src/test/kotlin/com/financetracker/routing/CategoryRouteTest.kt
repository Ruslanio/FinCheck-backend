package com.financetracker.routing

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.financetracker.model.CategoryResponse
import com.financetracker.model.ErrorResponse
import com.financetracker.model.toResponse
import com.financetracker.plugins.configureSerialization
import com.financetracker.repository.CategoryRow
import com.financetracker.repository.CategoryType
import com.financetracker.service.CategoryService
import com.financetracker.service.CreateCategoryResult
import com.financetracker.service.DeleteCategoryResult
import com.financetracker.service.UpdateCategoryResult
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Date
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val TEST_JWT_SECRET = "test-jwt-secret-for-category-route-tests-long-enough"
private const val TEST_USER_ID = "11111111-1111-1111-1111-111111111111"

private fun Application.installTestAuth() {
    install(Authentication) {
        jwt("auth-jwt") {
            verifier(JWT.require(Algorithm.HMAC256(TEST_JWT_SECRET)).build())
            validate { JWTPrincipal(it.payload) }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_or_expired_token"))
            }
        }
    }
}

private fun makeToken(userId: String = TEST_USER_ID): String {
    val now = Date()
    return JWT
        .create()
        .withSubject(userId)
        .withIssuedAt(now)
        .withExpiresAt(Date(now.time + 900_000L))
        .sign(Algorithm.HMAC256(TEST_JWT_SECRET))
}

private fun makeCategoryRow(
    id: UUID = UUID.randomUUID(),
    userId: UUID = UUID.fromString(TEST_USER_ID),
    name: String = "Food",
    type: CategoryType = CategoryType.Expense,
    isFallback: Boolean = false,
) = CategoryRow(id, userId, name, type, isFallback, null)

class CategoryRouteTest {

    private val categoryService = mockk<CategoryService>()

    @BeforeEach
    fun setUp() {
        clearAllMocks()
    }

    // ── GET /categories ──────────────────────────────────────────────────────

    @Test
    fun `GET categories without auth returns 401`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                routing { categoryRoutes(categoryService) }
            }
            val response = client.get("/categories")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET categories with auth returns 200 and list`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                routing { categoryRoutes(categoryService) }
            }
            val userId = UUID.fromString(TEST_USER_ID)
            val rows = listOf(makeCategoryRow(), makeCategoryRow(name = "Transport"))
            coEvery { categoryService.getCategories(userId) } returns rows.map { it.toResponse() }

            val response =
                client.get("/categories") {
                    header(HttpHeaders.Authorization, "Bearer ${makeToken()}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.decodeFromString<List<CategoryResponse>>(response.bodyAsText())
            assertEquals(2, body.size)
        }

    // ── POST /categories ─────────────────────────────────────────────────────

    @Test
    fun `POST categories without auth returns 401`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                routing { categoryRoutes(categoryService) }
            }
            val response =
                client.post("/categories") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"name":"Food","type":"expense"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `POST categories with valid body returns 201`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                routing { categoryRoutes(categoryService) }
            }
            val userId = UUID.fromString(TEST_USER_ID)
            val row = makeCategoryRow()
            coEvery { categoryService.createCategory(userId, any()) } returns CreateCategoryResult.Created(row)

            val response =
                client.post("/categories") {
                    header(HttpHeaders.Authorization, "Bearer ${makeToken()}")
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"name":"Food","type":"expense"}""")
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = Json.decodeFromString<CategoryResponse>(response.bodyAsText())
            assertEquals("Food", body.name)
            assertEquals("expense", body.type)
            assertFalse(body.isFallback)
        }

    @Test
    fun `POST categories with duplicate idempotency key returns 200`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                routing { categoryRoutes(categoryService) }
            }
            val userId = UUID.fromString(TEST_USER_ID)
            val row = makeCategoryRow()
            coEvery { categoryService.createCategory(userId, any()) } returns CreateCategoryResult.Duplicate(row)

            val response =
                client.post("/categories") {
                    header(HttpHeaders.Authorization, "Bearer ${makeToken()}")
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"name":"Food","type":"expense","idempotencyKey":"key-dup"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `POST categories with validation error returns 400`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                routing { categoryRoutes(categoryService) }
            }
            val userId = UUID.fromString(TEST_USER_ID)
            coEvery { categoryService.createCategory(userId, any()) } returns
                CreateCategoryResult.ValidationError("invalid_category_type")

            val response =
                client.post("/categories") {
                    header(HttpHeaders.Authorization, "Bearer ${makeToken()}")
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"name":"Food","type":"bad"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ── PUT /categories/{id} ─────────────────────────────────────────────────

    @Test
    fun `PUT categories id with valid body returns 200`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                routing { categoryRoutes(categoryService) }
            }
            val userId = UUID.fromString(TEST_USER_ID)
            val categoryId = UUID.randomUUID()
            val updated = makeCategoryRow(id = categoryId, name = "Groceries")
            coEvery { categoryService.updateCategory(userId, categoryId, any()) } returns
                UpdateCategoryResult.Updated(updated)

            val response =
                client.put("/categories/$categoryId") {
                    header(HttpHeaders.Authorization, "Bearer ${makeToken()}")
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"name":"Groceries","type":"expense"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.decodeFromString<CategoryResponse>(response.bodyAsText())
            assertEquals("Groceries", body.name)
        }

    @Test
    fun `PUT categories id returns 404 when not owned by caller`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                routing { categoryRoutes(categoryService) }
            }
            val userId = UUID.fromString(TEST_USER_ID)
            val categoryId = UUID.randomUUID()
            coEvery { categoryService.updateCategory(userId, categoryId, any()) } returns
                UpdateCategoryResult.NotFound

            val response =
                client.put("/categories/$categoryId") {
                    header(HttpHeaders.Authorization, "Bearer ${makeToken()}")
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"name":"Groceries","type":"expense"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `PUT categories id without auth returns 401`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                routing { categoryRoutes(categoryService) }
            }
            val response =
                client.put("/categories/${UUID.randomUUID()}") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"name":"Groceries","type":"expense"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // ── DELETE /categories/{id} ──────────────────────────────────────────────

    @Test
    fun `DELETE categories id returns 204 on success`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                routing { categoryRoutes(categoryService) }
            }
            val userId = UUID.fromString(TEST_USER_ID)
            val categoryId = UUID.randomUUID()
            coEvery { categoryService.deleteCategory(userId, categoryId) } returns DeleteCategoryResult.Success

            val response =
                client.delete("/categories/$categoryId") {
                    header(HttpHeaders.Authorization, "Bearer ${makeToken()}")
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `DELETE categories id returns 409 when deleting fallback`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                routing { categoryRoutes(categoryService) }
            }
            val userId = UUID.fromString(TEST_USER_ID)
            val categoryId = UUID.randomUUID()
            coEvery { categoryService.deleteCategory(userId, categoryId) } returns
                DeleteCategoryResult.FallbackNotDeletable

            val response =
                client.delete("/categories/$categoryId") {
                    header(HttpHeaders.Authorization, "Bearer ${makeToken()}")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("fallback_category_not_deletable", body.error)
        }

    @Test
    fun `DELETE categories id returns 404 when not found`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                routing { categoryRoutes(categoryService) }
            }
            val userId = UUID.fromString(TEST_USER_ID)
            val categoryId = UUID.randomUUID()
            coEvery { categoryService.deleteCategory(userId, categoryId) } returns DeleteCategoryResult.NotFound

            val response =
                client.delete("/categories/$categoryId") {
                    header(HttpHeaders.Authorization, "Bearer ${makeToken()}")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `DELETE categories id without auth returns 401`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                routing { categoryRoutes(categoryService) }
            }
            val response = client.delete("/categories/${UUID.randomUUID()}")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `DELETE categories id with invalid UUID returns 400`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                routing { categoryRoutes(categoryService) }
            }
            val response =
                client.delete("/categories/not-a-uuid") {
                    header(HttpHeaders.Authorization, "Bearer ${makeToken()}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}

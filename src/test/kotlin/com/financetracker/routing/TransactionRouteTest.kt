package com.financetracker.routing

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.financetracker.model.ErrorResponse
import com.financetracker.model.PagedTransactionsResponse
import com.financetracker.model.TransactionResponse
import com.financetracker.plugins.configureSerialization
import com.financetracker.repository.TransactionRow
import com.financetracker.service.CreateTransactionResult
import com.financetracker.service.TransactionService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import io.ktor.server.testing.testApplication
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Date
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TEST_JWT_SECRET = "test-jwt-secret-for-transaction-route-tests-long-enough"
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

private fun makeTransactionRow(userId: UUID = UUID.fromString(TEST_USER_ID)) =
    TransactionRow(
        id = UUID.randomUUID(),
        userId = userId,
        amount = BigDecimal("42.50"),
        category = "food",
        description = "Lunch",
        idempotencyKey = "key-001",
        occurredAt = Clock.System.now(),
    )

class TransactionRouteTest {

    private val transactionService = mockk<TransactionService>()

    @BeforeEach
    fun setUp() {
        clearAllMocks()
    }

    @Test
    fun `GET transactions without auth returns 401`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                configureTransactionRouting(transactionService)
            }
            val response = client.get("/transactions")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET transactions with valid auth returns 200 and paged response`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                configureTransactionRouting(transactionService)
            }
            val userId = UUID.fromString(TEST_USER_ID)
            coEvery {
                transactionService.getTransactions(userId, 0, 20, null)
            } returns PagedTransactionsResponse(data = emptyList(), page = 0, size = 20, total = 1L)

            val response =
                client.get("/transactions") {
                    header(HttpHeaders.Authorization, "Bearer ${makeToken()}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.decodeFromString<PagedTransactionsResponse>(response.bodyAsText())
            assertEquals(0, body.page)
            assertEquals(20, body.size)
            assertEquals(1L, body.total)
        }

    @Test
    fun `GET transactions returns 200 with empty data list`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                configureTransactionRouting(transactionService)
            }
            val userId = UUID.fromString(TEST_USER_ID)
            coEvery {
                transactionService.getTransactions(userId, 0, 20, null)
            } returns PagedTransactionsResponse(data = emptyList(), page = 0, size = 20, total = 0L)

            val response =
                client.get("/transactions") {
                    header(HttpHeaders.Authorization, "Bearer ${makeToken()}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.decodeFromString<PagedTransactionsResponse>(response.bodyAsText())
            assertTrue(body.data.isEmpty())
        }

    @Test
    fun `GET transactions respects page and size query params`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                configureTransactionRouting(transactionService)
            }
            val userId = UUID.fromString(TEST_USER_ID)
            coEvery {
                transactionService.getTransactions(userId, 2, 5, null)
            } returns PagedTransactionsResponse(data = emptyList(), page = 2, size = 5, total = 0L)

            val response =
                client.get("/transactions?page=2&size=5") {
                    header(HttpHeaders.Authorization, "Bearer ${makeToken()}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.decodeFromString<PagedTransactionsResponse>(response.bodyAsText())
            assertEquals(2, body.page)
            assertEquals(5, body.size)
        }

    @Test
    fun `GET transactions passes category filter to service`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                configureTransactionRouting(transactionService)
            }
            val userId = UUID.fromString(TEST_USER_ID)
            coEvery {
                transactionService.getTransactions(userId, 0, 20, "food")
            } returns PagedTransactionsResponse(data = emptyList(), page = 0, size = 20, total = 0L)

            val response =
                client.get("/transactions?category=food") {
                    header(HttpHeaders.Authorization, "Bearer ${makeToken()}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `POST transactions with valid body returns 201`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                configureTransactionRouting(transactionService)
            }
            val userId = UUID.fromString(TEST_USER_ID)
            val row = makeTransactionRow(userId)
            coEvery {
                transactionService.createTransaction(userId, any())
            } returns CreateTransactionResult.Created(row)

            val response =
                client.post("/transactions") {
                    header(HttpHeaders.Authorization, "Bearer ${makeToken()}")
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"amount":42.50,"category":"food","description":"Lunch","idempotencyKey":"key-001"}""")
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = Json.decodeFromString<TransactionResponse>(response.bodyAsText())
            assertEquals("food", body.category)
        }

    @Test
    fun `POST transactions with duplicate idempotency key returns 200`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                configureTransactionRouting(transactionService)
            }
            val userId = UUID.fromString(TEST_USER_ID)
            val row = makeTransactionRow(userId)
            coEvery {
                transactionService.createTransaction(userId, any())
            } returns CreateTransactionResult.Duplicate(row)

            val response =
                client.post("/transactions") {
                    header(HttpHeaders.Authorization, "Bearer ${makeToken()}")
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"amount":42.50,"category":"food","idempotencyKey":"key-001"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.decodeFromString<TransactionResponse>(response.bodyAsText())
            assertEquals("food", body.category)
        }

    @Test
    fun `POST transactions missing required fields returns 400`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                configureTransactionRouting(transactionService)
            }
            val response =
                client.post("/transactions") {
                    header(HttpHeaders.Authorization, "Bearer ${makeToken()}")
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"amount":42.50}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST transactions without auth returns 401`() =
        testApplication {
            application {
                installTestAuth()
                configureSerialization()
                configureTransactionRouting(transactionService)
            }
            val response =
                client.post("/transactions") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"amount":42.50,"category":"food"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}

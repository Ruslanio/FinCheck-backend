package com.financetracker.routing

import com.financetracker.model.ErrorResponse
import com.financetracker.model.LoginResponse
import com.financetracker.model.LoginResult
import com.financetracker.model.RegisterResponse
import com.financetracker.model.RegisterResult
import com.financetracker.plugins.configureSerialization
import com.financetracker.service.AuthService
import com.financetracker.util.JwtUtil
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthRouteTest {

    companion object {
        private const val TEST_JWT_SECRET = "test-jwt-secret-key-must-be-at-least-32-chars-for-hs256-x"
    }

    private val authService = mockk<AuthService>()

    @BeforeEach
    fun setUp() {
        clearAllMocks()
        System.setProperty("JWT_SECRET", TEST_JWT_SECRET)
    }

    @Test
    fun `register with valid input returns 201 with userId and email`() =
        testApplication {
            application {
                configureSerialization()
                configureAuthRouting(authService)
            }
            val userId = UUID.randomUUID().toString()
            coEvery { authService.register("new@example.com", "password123") } returns
                RegisterResult.Success(RegisterResponse(userId, "new@example.com"))

            val response =
                client.post("/auth/register") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"email":"new@example.com","password":"password123"}""")
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = Json.decodeFromString<RegisterResponse>(response.bodyAsText())
            assertEquals(userId, body.userId)
            assertEquals("new@example.com", body.email)
        }

    @Test
    fun `register with duplicate email returns 409`() =
        testApplication {
            application {
                configureSerialization()
                configureAuthRouting(authService)
            }
            coEvery { authService.register("dup@example.com", "password123") } returns
                RegisterResult.EmailAlreadyRegistered

            val response =
                client.post("/auth/register") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"email":"dup@example.com","password":"password123"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("email_already_registered", body.error)
        }

    @Test
    fun `register with invalid email returns 400`() =
        testApplication {
            application {
                configureSerialization()
                configureAuthRouting(authService)
            }
            coEvery { authService.register("not-an-email", "password123") } returns
                RegisterResult.InvalidEmail

            val response =
                client.post("/auth/register") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"email":"not-an-email","password":"password123"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("invalid_email", body.error)
        }

    @Test
    fun `register with short password returns 400`() =
        testApplication {
            application {
                configureSerialization()
                configureAuthRouting(authService)
            }
            coEvery { authService.register("user@example.com", "short") } returns
                RegisterResult.PasswordTooShort

            val response =
                client.post("/auth/register") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"email":"user@example.com","password":"short"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("password_too_short", body.error)
        }

    @Test
    fun `login with valid credentials returns 200 and a verifiable JWT`() =
        testApplication {
            application {
                configureSerialization()
                configureAuthRouting(authService)
            }
            val userId = UUID.randomUUID().toString()
            val realToken = JwtUtil.issueAccessToken(userId)
            val refreshToken = UUID.randomUUID().toString()

            coEvery { authService.login("user@example.com", "password123") } returns
                LoginResult.Success(LoginResponse(realToken, refreshToken, 900))

            val response =
                client.post("/auth/login") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"email":"user@example.com","password":"password123"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.decodeFromString<LoginResponse>(response.bodyAsText())
            assertEquals(refreshToken, body.refreshToken)
            assertEquals(900, body.expiresIn)

            val key = Keys.hmacShaKeyFor(TEST_JWT_SECRET.toByteArray())
            val claims =
                Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(body.accessToken)
                    .payload
            assertEquals(userId, claims.subject)
            val nowMs = System.currentTimeMillis()
            assertTrue(claims.expiration.time > nowMs)
            assertTrue(claims.expiration.time <= nowMs + 900_000L + 5_000L)
        }

    @Test
    fun `login with wrong password returns 401`() =
        testApplication {
            application {
                configureSerialization()
                configureAuthRouting(authService)
            }
            coEvery { authService.login("user@example.com", "wrongpass") } returns
                LoginResult.InvalidCredentials

            val response =
                client.post("/auth/login") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"email":"user@example.com","password":"wrongpass"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("invalid_credentials", body.error)
        }

    @Test
    fun `login with unknown email returns 401 with same error as wrong password`() =
        testApplication {
            application {
                configureSerialization()
                configureAuthRouting(authService)
            }
            coEvery { authService.login("nobody@example.com", "password123") } returns
                LoginResult.InvalidCredentials

            val response =
                client.post("/auth/login") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"email":"nobody@example.com","password":"password123"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("invalid_credentials", body.error)
        }
}

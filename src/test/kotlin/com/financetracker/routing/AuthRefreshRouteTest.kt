package com.financetracker.routing

import com.financetracker.model.ErrorResponse
import com.financetracker.model.LoginResponse
import com.financetracker.plugins.configureSerialization
import com.financetracker.service.AuthService
import com.financetracker.service.LogoutResult
import com.financetracker.service.RefreshResult
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthRefreshRouteTest {

    private val authService = mockk<AuthService>()

    @BeforeEach
    fun setUp() {
        clearAllMocks()
    }

    @Test
    fun `refresh with valid token returns 200 with new tokens`() =
        testApplication {
            application {
                configureSerialization()
                routing { authRoutes(authService) }
            }
            coEvery { authService.refresh("valid-refresh-token") } returns
                RefreshResult.Success("new-access-token", "new-refresh-uuid")

            val response =
                client.post("/auth/refresh") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"refreshToken":"valid-refresh-token"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.decodeFromString<LoginResponse>(response.bodyAsText())
            assertEquals("new-access-token", body.accessToken)
            assertEquals("new-refresh-uuid", body.refreshToken)
            assertEquals(900, body.expiresIn)
        }

    @Test
    fun `refresh with revoked token returns 401 token_revoked`() =
        testApplication {
            application {
                configureSerialization()
                routing { authRoutes(authService) }
            }
            coEvery { authService.refresh("revoked-token") } returns RefreshResult.Revoked

            val response =
                client.post("/auth/refresh") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"refreshToken":"revoked-token"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("token_revoked", body.error)
        }

    @Test
    fun `refresh with expired token returns 401 token_expired`() =
        testApplication {
            application {
                configureSerialization()
                routing { authRoutes(authService) }
            }
            coEvery { authService.refresh("expired-token") } returns RefreshResult.Expired

            val response =
                client.post("/auth/refresh") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"refreshToken":"expired-token"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("token_expired", body.error)
        }

    @Test
    fun `refresh with unknown token returns 401 invalid_refresh_token`() =
        testApplication {
            application {
                configureSerialization()
                routing { authRoutes(authService) }
            }
            coEvery { authService.refresh("unknown-token") } returns RefreshResult.Invalid

            val response =
                client.post("/auth/refresh") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"refreshToken":"unknown-token"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("invalid_refresh_token", body.error)
        }

    @Test
    fun `refresh same token twice returns 401 on second use`() =
        testApplication {
            application {
                configureSerialization()
                routing { authRoutes(authService) }
            }
            coEvery { authService.refresh("reused-token") } returnsMany
                listOf(
                    RefreshResult.Success("access-token", "next-refresh-token"),
                    RefreshResult.Revoked,
                )

            val first =
                client.post("/auth/refresh") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"refreshToken":"reused-token"}""")
                }
            assertEquals(HttpStatusCode.OK, first.status)

            val second =
                client.post("/auth/refresh") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"refreshToken":"reused-token"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, second.status)
            val body = Json.decodeFromString<ErrorResponse>(second.bodyAsText())
            assertEquals("token_revoked", body.error)
        }

    @Test
    fun `logout with valid token returns 204`() =
        testApplication {
            application {
                configureSerialization()
                routing { authRoutes(authService) }
            }
            coEvery { authService.logout("active-token", any()) } returns LogoutResult.Success

            val response =
                client.post("/auth/logout") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"refreshToken":"active-token","accessToken":"some-access-token"}""")
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertTrue(response.bodyAsText().isEmpty())
        }

    @Test
    fun `logout with already revoked token returns 401 token_revoked`() =
        testApplication {
            application {
                configureSerialization()
                routing { authRoutes(authService) }
            }
            coEvery { authService.logout("revoked-token", any()) } returns LogoutResult.AlreadyRevoked

            val response =
                client.post("/auth/logout") {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"refreshToken":"revoked-token","accessToken":"some-access-token"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("token_revoked", body.error)
        }
}

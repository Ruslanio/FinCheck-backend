package com.financetracker

import com.financetracker.plugins.configureRouting
import com.financetracker.plugins.configureSerialization
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class HealthRouteTest {

    @Test
    fun `returns 200 and ok status when DB is reachable`() = testApplication {
        application {
            configureSerialization()
            configureRouting(dbPing = { true })
        }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "\"ok\"")
    }

    @Test
    fun `returns 503 and degraded status when DB is unreachable`() = testApplication {
        application {
            configureSerialization()
            configureRouting(dbPing = { false })
        }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertContains(response.bodyAsText(), "\"degraded\"")
    }
}

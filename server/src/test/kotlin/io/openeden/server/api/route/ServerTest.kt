package io.openeden.server.api.route

import io.openeden.server.api.dto.DevMessageResponseDto
import io.openeden.server.api.plugin.configureSerialization
import io.openeden.server.api.plugin.configureStatusPages
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.*

class ServerTest {

    @Test
    fun `test root endpoint`() = testApplication {
        application {
            configureSerialization()
            configureWebsockets()
            configureStatusPages()
            configureRouting()
        }

        assertEquals(HttpStatusCode.OK, client.get("/").status)
    }

    @Test
    fun `development message endpoint runs one turn`() = testApplication {
        application {
            configureSerialization()
            configureWebsockets()
            configureStatusPages()
            configureRouting()
        }

        val response = client.post("/dev/message") {
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"DEV","scopeId":"scope","userId":"user","text":"hello","emotionConfidence":0.49}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<DevMessageResponseDto>(response.body<String>())
        assertEquals("DEV:scope", body.sessionId)
        assertEquals(1, body.evolutionIndex)
        assertContains(body.promptPreview, "\"bio_core_state\"")
        assertEquals("not_triggered", body.diaryOutcome)
    }

}

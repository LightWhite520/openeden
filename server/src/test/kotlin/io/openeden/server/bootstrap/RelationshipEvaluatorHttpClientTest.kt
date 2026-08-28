package io.openeden.server.bootstrap

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RelationshipEvaluatorHttpClientTest {
    @Test
    fun `relationship evaluator requests have a bounded timeout`() = runTest {
        val client = relationshipEvaluatorHttpClient(
            engine = MockEngine {
                delay(1_000L)
                respond("{}")
            },
            requestTimeoutMillis = 10L,
        )

        try {
            assertFailsWith<HttpRequestTimeoutException> {
                client.get("https://relay.example.test/v1/responses")
            }
        } finally {
            client.close()
        }
    }
}

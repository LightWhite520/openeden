package io.openeden.server.auth

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.Date
import kotlin.test.*

class ChatGptOAuthTest {
    @Test
    fun `catalog filters hidden models and failed remote logout still clears local tokens`() = runTest {
        val store = ChatGptAccountStore(Files.createTempDirectory("oauth-catalog-"))
        store.locked { state, save -> save(state.copy(activeClientId = "oaiapp_test", accounts = listOf(
            ChatGptAccount("oaiapp_test", "subject", null, "access", "refresh", expiresAtMs = now + 3_600_000,
                scopes = setOf(ChatGptOAuth.PLAN_SCOPE, "resource.invoke")),
        ))) }
        ChatGptOAuth(store, HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/models" -> {
                    assertEquals("Bearer access", request.headers[HttpHeaders.Authorization])
                    respond("""{"models":[{"slug":"b","display_name":"B","visibility":"list"},{"slug":"hidden","display_name":"H","visibility":"hidden"},{"slug":"a","display_name":"A","visibility":"list"}]}""")
                }
                else -> respond("unavailable", HttpStatusCode.ServiceUnavailable)
            }
        }), { now }).use {
            assertEquals(listOf("b" to "B", "a" to "A"), it.models())
            assertFalse(it.logout())
            assertNull(store.read().activeClientId)
            assertNull(store.read().accounts.single().refreshToken)
            assertFailsWith<IllegalStateException> { it.accessToken() }
        }
    }

    @Test
    fun `returning registration rejects a different subject without replacing credentials`() = runTest {
        val store = ChatGptAccountStore(Files.createTempDirectory("oauth-identity-"))
        store.locked { state, save -> save(state.copy(activeClientId = "oaiapp_test",
            accounts = listOf(ChatGptAccount("oaiapp_test", "original", null, "original-access")))) }
        lateinit var attempt: ChatGptLoginAttempt
        ChatGptOAuth(store, HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/.well-known/jwks.json") respond(JWKSet(key.toPublicJWK()).toString())
            else respond(tokens(id(attempt.nonce, subject = "different")))
        }), { now }).use {
            attempt = it.begin("http://127.0.0.1:14551/auth/callback", "oaiapp_test")
            assertFailsWith<IllegalArgumentException> { it.complete(attempt, callback(attempt)) }
            assertEquals("original-access", store.read().accounts.single().accessToken)
        }
    }
    private val key = RSAKeyGenerator(2048).keyID("test-key").generate()
    private val now = 1_800_000_000_000L
    private fun id(nonce: String, subject: String = "subject", audience: String = "oaiapp_test", expiry: Long = now + 3_600_000) =
        SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), JWTClaimsSet.Builder()
            .issuer(ChatGptOAuth.ISSUER).audience(audience).subject(subject).expirationTime(Date(expiry))
            .claim("nonce", nonce).claim("email", "account@example.test").build()).also { it.sign(RSASSASigner(key)) }.serialize()

    @Test
    fun `dynamic registration validates identity and stores isolated protected credentials`() = runTest {
        val directory = Files.createTempDirectory("openeden-oauth-")
        val store = ChatGptAccountStore(directory)
        lateinit var attempt: ChatGptLoginAttempt
        var form: Parameters? = null
        val oauth = ChatGptOAuth(store, HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/.well-known/jwks.json" -> respond(JWKSet(key.toPublicJWK()).toString())
                else -> {
                    form = parseQueryString(request.body.toByteArray().decodeToString())
                    respond(tokens(id(attempt.nonce)))
                }
            }
        }), { now })
        oauth.use {
            attempt = oauth.begin("http://127.0.0.1:14551/auth/callback")
            val query = Url(attempt.authorizationUrl).parameters
            assertEquals("dynamic_agent_client", query["client_id"])
            assertEquals("OpenEden", query["agent_name_hint"])
            assertEquals("S256", query["code_challenge_method"])
            assertEquals(ChatGptOAuth.RESOURCE, query["resource"])
            val account = oauth.complete(attempt, callback(attempt))
            assertEquals("oaiapp_test", form!!["client_id"])
            assertEquals(attempt.verifier, form!!["code_verifier"])
            assertEquals(attempt.redirectUri, form!!["redirect_uri"])
            assertEquals("subject", account.subject)
            assertEquals("access-secret", oauth.accessToken())
            val saved = ChatGptAccountStore(directory).read()
            assertEquals(account, saved.accounts.single())
            assertEquals(attempt.hostId, saved.hostId)
            val returning = oauth.begin("http://127.0.0.1:14552/auth/callback", account.clientId)
            assertEquals("oaiapp_test", Url(returning.authorizationUrl).parameters["client_id"])
            assertNull(Url(returning.authorizationUrl).parameters["agent_name_hint"])
            assertFalse(returning.authorizationUrl.contains("access-secret"))
            assertFalse(account.toString().contains("access-secret"))
            if (System.getProperty("os.name").startsWith("Windows")) {
                assertFalse(Files.readAllBytes(directory.resolve("accounts.dpapi")).decodeToString().contains("access-secret"))
            } else {
                assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("accounts.json"))))
            }
        }
    }

    @Test
    fun `bad state and denied consent never exchange a code`() = runTest {
        var requests = 0
        val oauth = ChatGptOAuth(ChatGptAccountStore(Files.createTempDirectory("oauth-state-")), HttpClient(MockEngine {
            requests++; error("No request expected")
        }), { now })
        oauth.use {
            val attempt = oauth.begin("http://127.0.0.1:14551/auth/callback")
            assertFailsWith<IllegalArgumentException> { oauth.complete(attempt, parametersOf("state", "bad")) }
            assertFailsWith<IllegalStateException> { oauth.complete(attempt, parameters { append("state", attempt.state); append("error", "access_denied") }) }
            assertEquals(0, requests)
        }
    }

    @Test
    fun `identity validator rejects nonce audience expiry and invalid signature`() {
        val jwks = JWKSet(key.toPublicJWK()).toString()
        assertFailsWith<IllegalArgumentException> { ChatGptIdTokenValidator.validate(id("wrong"), jwks, "oaiapp_test", "expected", now) }
        assertFailsWith<IllegalArgumentException> { ChatGptIdTokenValidator.validate(id("expected", audience = "other"), jwks, "oaiapp_test", "expected", now) }
        assertFailsWith<IllegalArgumentException> { ChatGptIdTokenValidator.validate(id("expected", expiry = now - 60_000), jwks, "oaiapp_test", "expected", now) }
        val unrelated = RSAKeyGenerator(2048).keyID("test-key").generate()
        assertFailsWith<IllegalArgumentException> { ChatGptIdTokenValidator.validate(id("expected"), JWKSet(unrelated.toPublicJWK()).toString(), "oaiapp_test", "expected", now) }
    }

    @Test
    fun `two clients serialize rotating refresh and reload the latest token`() = runTest {
        val directory = Files.createTempDirectory("oauth-refresh-")
        val store = ChatGptAccountStore(directory)
        store.locked { state, save -> save(state.copy(activeClientId = "oaiapp_test", accounts = listOf(
            ChatGptAccount("oaiapp_test", "subject", null, "expired", "refresh-secret", expiresAtMs = 0,
                scopes = setOf(ChatGptOAuth.PLAN_SCOPE, "resource.invoke")),
        ))) }
        var refreshes = 0
        val engine = MockEngine { request ->
            refreshes++
            val form = parseQueryString(request.body.toByteArray().decodeToString())
            assertEquals("refresh_token", form["grant_type"])
            assertEquals("oaiapp_test", form["client_id"])
            assertEquals("refresh-secret", form["refresh_token"])
            assertNull(form["scope"])
            respond(tokens(null))
        }
        ChatGptOAuth(store, HttpClient(engine), { now }).use { first ->
            ChatGptOAuth(ChatGptAccountStore(directory), HttpClient(engine), { now }).use { second ->
                val a = async { first.accessToken() }
                val b = async { second.accessToken() }
                assertEquals("access-secret", a.await())
                assertEquals("access-secret", b.await())
                assertEquals(1, refreshes)
                assertEquals("rotated-refresh", store.read().accounts.single().refreshToken)
            }
        }
    }

    @Test
    fun `invalid grant clears tokens but retains host and registration`() = runTest {
        val store = ChatGptAccountStore(Files.createTempDirectory("oauth-revoked-"))
        val host = store.read().hostId
        store.locked { state, save -> save(state.copy(activeClientId = "oaiapp_test", accounts = listOf(
            ChatGptAccount("oaiapp_test", "subject", null, "expired", "refresh-secret", scopes = setOf(ChatGptOAuth.PLAN_SCOPE)),
        ))) }
        ChatGptOAuth(store, HttpClient(MockEngine { respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest) }), { now }).use {
            assertEquals(ChatGptAuthException.Reason.REAUTHORIZATION_REQUIRED,
                assertFailsWith<ChatGptAuthException> { it.accessToken() }.reason)
            val state = store.read()
            assertEquals(host, state.hostId)
            assertEquals("oaiapp_test", state.accounts.single().clientId)
            assertNull(state.accounts.single().accessToken)
            assertNull(state.accounts.single().refreshToken)
            // A second client must not misreport a cleared grant as missing permission.
            ChatGptOAuth(store, HttpClient(MockEngine { error("Must not refresh cleared credentials") }), { now }).use { next ->
                assertEquals(ChatGptAuthException.Reason.REAUTHORIZATION_REQUIRED,
                    assertFailsWith<ChatGptAuthException> { next.accessToken() }.reason)
            }
        }
    }

    @Test
    fun `rotated credentials survive unavailable identity keys and restart without another refresh`() = runTest {
        val directory = Files.createTempDirectory("oauth-checkpoint-")
        val store = expiredStore(directory)
        var refreshes = 0
        ChatGptOAuth(store, HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/.well-known/jwks.json") respond("unavailable", HttpStatusCode.ServiceUnavailable)
            else { refreshes++; respond(tokens(id("refresh"))) }
        }), { now }).use { oauth ->
            assertEquals(ChatGptAuthException.Reason.TEMPORARILY_UNAVAILABLE,
                assertFailsWith<ChatGptAuthException> { oauth.accessToken() }.reason)
        }
        val pending = store.read().accounts.single()
        assertNull(pending.accessToken)
        assertEquals("rotated-refresh", pending.pendingRefresh!!.tokens["refresh_token"]!!.jsonPrimitive.content)
        assertFalse(pending.pendingRefresh.toString().contains("rotated-refresh"))
        ChatGptOAuth(ChatGptAccountStore(directory), HttpClient(MockEngine { request ->
            assertEquals("/.well-known/jwks.json", request.url.encodedPath)
            respond(JWKSet(key.toPublicJWK()).toString())
        }), { now + 120_000 }).use { recovered ->
            assertEquals("access-secret", recovered.accessToken())
        }
        val recovered = store.read().accounts.single()
        assertNull(recovered.pendingRefresh)
        assertEquals("rotated-refresh", recovered.refreshToken)
        assertEquals(now + 3_600_000, recovered.expiresAtMs)
        assertEquals(1, refreshes)
    }

    @Test
    fun `cancellation during identity validation preserves rotated credentials`() = runTest {
        val store = expiredStore(Files.createTempDirectory("oauth-cancel-"))
        ChatGptOAuth(store, HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/.well-known/jwks.json") throw CancellationException("cancelled")
            respond(tokens(id("refresh")))
        }), { now }).use { oauth ->
            assertFailsWith<CancellationException> { oauth.accessToken() }
        }
        assertNull(store.read().accounts.single().accessToken)
        assertEquals("rotated-refresh", store.read().accounts.single().pendingRefresh!!.tokens["refresh_token"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Windows publication failure retains checkpoint for another client`() = runTest {
        if (!System.getProperty("os.name").startsWith("Windows")) return@runTest
        val directory = Files.createTempDirectory("oauth-publication-")
        val store = expiredStore(directory)
        var handle: com.sun.jna.platform.win32.WinNT.HANDLE? = null
        var refreshes = 0
        try {
            ChatGptOAuth(store, HttpClient(MockEngine { request ->
                if (request.url.encodedPath == "/.well-known/jwks.json") {
                    // Deny delete sharing after the checkpoint exists: atomic publication fails.
                    handle = com.sun.jna.platform.win32.Kernel32.INSTANCE.CreateFile(
                        directory.resolve("accounts.dpapi").toString(),
                        com.sun.jna.platform.win32.WinNT.GENERIC_READ,
                        com.sun.jna.platform.win32.WinNT.FILE_SHARE_READ,
                        null, com.sun.jna.platform.win32.WinNT.OPEN_EXISTING,
                        com.sun.jna.platform.win32.WinNT.FILE_ATTRIBUTE_NORMAL, null,
                    )
                    assertNotEquals(com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE, handle)
                    respond(JWKSet(key.toPublicJWK()).toString())
                } else { refreshes++; respond(tokens(id("refresh"))) }
            }), { now }).use { oauth ->
                assertEquals(ChatGptAuthException.Reason.CREDENTIAL_STORAGE_FAILED,
                    assertFailsWith<ChatGptAuthException> { oauth.accessToken() }.reason)
            }
        } finally {
            handle?.let { com.sun.jna.platform.win32.Kernel32.INSTANCE.CloseHandle(it) }
        }
        assertNotNull(store.read().accounts.single().pendingRefresh)
        ChatGptOAuth(ChatGptAccountStore(directory), HttpClient(MockEngine { request ->
            assertEquals("/.well-known/jwks.json", request.url.encodedPath)
            respond(JWKSet(key.toPublicJWK()).toString())
        }), { now }).use { oauth -> assertEquals("access-secret", oauth.accessToken()) }
        assertEquals(1, refreshes)
        assertNull(store.read().accounts.single().pendingRefresh)
        assertEquals("rotated-refresh", store.read().accounts.single().refreshToken)
    }

    @Test
    fun `refreshed wrong identity cannot activate or repeat rotation`() = runTest {
        val store = expiredStore(Files.createTempDirectory("oauth-wrong-refresh-"))
        var refreshes = 0
        ChatGptOAuth(store, HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/.well-known/jwks.json") respond(JWKSet(key.toPublicJWK()).toString())
            else { refreshes++; respond(tokens(id("refresh", subject = "other"))) }
        }), { now }).use { oauth ->
            repeat(2) {
                assertEquals(ChatGptAuthException.Reason.IDENTITY_VALIDATION_FAILED,
                    assertFailsWith<ChatGptAuthException> { oauth.accessToken() }.reason)
            }
        }
        assertEquals(1, refreshes)
        assertNull(store.read().accounts.single().accessToken)
        assertNotNull(store.read().accounts.single().pendingRefresh)
    }

    @Test
    fun `logout revokes the pending replacement and clears the checkpoint`() = runTest {
        val store = expiredStore(Files.createTempDirectory("oauth-pending-logout-"))
        store.locked { state, save -> save(state.copy(accounts = state.accounts.map {
            it.copy(accessToken = null, refreshToken = null,
                pendingRefresh = PendingChatGptRefresh(Json.parseToJsonElement(tokens(null)).jsonObject, now))
        })) }
        var revoked = false
        ChatGptOAuth(store, HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/.well-known/openid-configuration") {
                respond("""{"revocation_endpoint":"https://auth.openai.com/revoke"}""")
            } else {
                assertEquals("rotated-refresh", parseQueryString(request.body.toByteArray().decodeToString())["token"])
                revoked = true
                respond("")
            }
        }), { now }).use { assertTrue(it.logout()) }
        assertTrue(revoked)
        assertNull(store.read().accounts.single().pendingRefresh)
        assertNull(store.read().accounts.single().refreshToken)
    }

    @Test
    fun `permission or malformed expiry after rotation retains protected response`() = runTest {
        for ((field, value, reason) in listOf(
            Triple("scope", JsonPrimitive("openid"), ChatGptAuthException.Reason.PERMISSION_REQUIRED),
            Triple("expires_in", JsonPrimitive("bad"), ChatGptAuthException.Reason.INVALID_TOKEN_RESPONSE),
        )) {
            val store = expiredStore(Files.createTempDirectory("oauth-bad-response-"))
            var requests = 0
            ChatGptOAuth(store, HttpClient(MockEngine {
                requests++
                respond(JsonObject(Json.parseToJsonElement(tokens(null)).jsonObject + (field to value)).toString())
            }), { now }).use { oauth ->
                repeat(2) {
                    assertEquals(reason, assertFailsWith<ChatGptAuthException> { oauth.accessToken() }.reason)
                }
            }
            assertEquals(1, requests)
            assertNull(store.read().accounts.single().accessToken)
            assertEquals("rotated-refresh", store.read().accounts.single().pendingRefresh!!.tokens["refresh_token"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `transport and server failures preserve existing credentials`() = runTest {
        for (transport in listOf(true, false)) {
            val store = expiredStore(Files.createTempDirectory("oauth-unavailable-"))
            val before = store.read()
            ChatGptOAuth(store, HttpClient(MockEngine {
                if (transport) throw java.io.IOException("private provider detail")
                respond("private provider detail", HttpStatusCode.ServiceUnavailable)
            }), { now }).use { oauth ->
                val error = assertFailsWith<ChatGptAuthException> { oauth.accessToken() }
                assertEquals(ChatGptAuthException.Reason.TEMPORARILY_UNAVAILABLE, error.reason)
                assertFalse(error.toString().contains("private provider detail"))
            }
            assertEquals(before, store.read())
        }
    }

    @Test
    fun `documented unusable refresh codes clear credentials without leaking provider details`() = runTest {
        for (code in listOf("invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")) {
            val store = expiredStore(Files.createTempDirectory("oauth-error-code-"))
            ChatGptOAuth(store, HttpClient(MockEngine {
                respond("""{"error":{"code":"$code","message":"private provider detail"}}""", HttpStatusCode.BadRequest)
            }), { now }).use { oauth ->
                val error = assertFailsWith<ChatGptAuthException> { oauth.accessToken() }
                assertEquals(ChatGptAuthException.Reason.REAUTHORIZATION_REQUIRED, error.reason)
                assertFalse(error.toString().contains("private provider detail"))
            }
            assertNull(store.read().accounts.single().refreshToken)
        }
    }

    private suspend fun expiredStore(directory: java.nio.file.Path): ChatGptAccountStore = ChatGptAccountStore(directory).also { store ->
        store.locked { state, save -> save(state.copy(activeClientId = "oaiapp_test", accounts = listOf(
            ChatGptAccount("oaiapp_test", "subject", null, "expired", "refresh-secret", expiresAtMs = 0,
                scopes = setOf(ChatGptOAuth.PLAN_SCOPE, "resource.invoke")),
        ))) }
    }

    private fun callback(attempt: ChatGptLoginAttempt) = parameters {
        append("state", attempt.state); append("code", "code-secret"); append("client_id", "oaiapp_test")
    }
    private fun tokens(id: String?) = buildJsonObject {
        put("access_token", "access-secret"); put("refresh_token", "rotated-refresh"); put("token_type", "Bearer")
        put("expires_in", 3600); put("scope", "${ChatGptOAuth.PLAN_SCOPE} resource.invoke openid")
        id?.let { put("id_token", it) }
    }.toString()
}

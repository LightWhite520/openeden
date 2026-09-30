package io.openeden.server.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class ChatGptOAuth(
    private val store: ChatGptAccountStore = ChatGptAccountStore(),
    private val client: HttpClient = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000 }
    },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onProgress: (String) -> Unit = {},
) : AutoCloseable {
    suspend fun checkConnection() {
        val response = client.get("$ISSUER/.well-known/jwks.json")
        check(response.status.isSuccess()) { "ChatGPT identity endpoint is unavailable" }
        onProgress("ChatGPT identity endpoint: HTTP ${response.status.value}")
    }

    /** Minimal operator probe: never returns token material or a provider response body. */
    suspend fun probeModel(model: String): Int {
        val response = client.post("$RESOURCE/responses") {
            bearerAuth(accessToken())
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model); put("store", false); put("stream", true)
                putJsonArray("input") { add(buildJsonObject { put("role", "user"); put("content", "Reply with exactly OK.") }) }
            }.toString())
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) {
            val events = body.split(Regex("\\r?\\n\\r?\\n")).mapNotNull { frame ->
                val data = frame.lineSequence().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trimStart() }
                if (data.isBlank() || data == "[DONE]") null else Json.parseToJsonElement(data).jsonObject
            }
            check(events.none { it["type"]?.jsonPrimitive?.content in setOf("response.failed", "response.incomplete", "error") }) { "Model probe did not complete" }
            check(events.count { it["type"]?.jsonPrimitive?.content == "response.completed" } == 1) { "Model probe omitted completion" }
        }
        onProgress("ChatGPT model probe HTTP ${response.status.value}; completed=${response.status.isSuccess()}")
        return response.status.value
    }
    suspend fun begin(redirectUri: String, clientId: String? = null): ChatGptLoginAttempt {
        val uri = java.net.URI(redirectUri)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port > 0 && uri.path == "/auth/callback")
        val state = store.read()
        val account = clientId?.let { id -> state.accounts.single { it.clientId == id } }
        val verifier = randomSecret()
        val csrf = randomSecret()
        val nonce = randomSecret()
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
        val url = URLBuilder("$ISSUER/api/accounts/authorize").apply {
            parameters.append("client_id", account?.clientId ?: "dynamic_agent_client")
            if (account == null) parameters.append("agent_name_hint", "OpenEden")
            parameters.append("ext_agent_host_id", state.hostId)
            // No id_token_hint in the URL: the CLI can safely display the authorization link.
            account?.email?.let { parameters.append("login_hint", it) }
            parameters.append("response_type", "code")
            parameters.append("redirect_uri", redirectUri)
            parameters.append("scope", "openid profile email offline_access resource.invoke $PLAN_SCOPE")
            parameters.append("resource", RESOURCE)
            parameters.append("state", csrf)
            parameters.append("nonce", nonce)
            parameters.append("code_challenge_method", "S256")
            parameters.append("code_challenge", challenge)
        }.buildString()
        return ChatGptLoginAttempt(url, csrf, nonce, verifier, redirectUri, state.hostId, account)
    }

    suspend fun complete(attempt: ChatGptLoginAttempt, callback: Parameters): ChatGptAccount {
        onProgress("ChatGPT: validating authorization callback")
        require(callback.getAll("state")?.size == 1 && callback["state"] == attempt.state) { "Login state mismatch" }
        check(callback["error"] == null) { "ChatGPT authorization was declined or failed" }
        val issued = callback["client_id"] ?: attempt.account?.clientId ?: error("Registration omitted its issued client ID")
        require(issued.isNotBlank() && issued != "dynamic_agent_client") { "Invalid issued client ID" }
        require(attempt.account == null || issued == attempt.account.clientId) { "Registration changed during sign-in" }
        val code = callback["code"]?.takeIf(String::isNotBlank) ?: error("Authorization code is missing")
        val tokens = token(parameters {
            append("grant_type", "authorization_code"); append("client_id", issued); append("code", code)
            append("code_verifier", attempt.verifier); append("redirect_uri", attempt.redirectUri); append("resource", RESOURCE)
        })
        val idToken = tokens.required("id_token")
        onProgress("ChatGPT: validating identity")
        val claims = validate(idToken, issued, attempt.nonce)
        require(attempt.account == null || attempt.account.subject == claims.subject) { "Selected ChatGPT account does not match sign-in" }
        val account = refreshed(
            ChatGptAccount(issued, claims.subject, claims.getStringClaim("email")), tokens,
        )
        onProgress("ChatGPT: saving protected credentials")
        store.locked { current, save ->
            require(current.hostId == attempt.hostId) { "Host registration changed during sign-in" }
            val existing = current.accounts.firstOrNull { it.clientId == issued }
            require(existing == null || existing.subject == account.subject) { "Account registration identity mismatch" }
            save(current.copy(activeClientId = issued, accounts = current.accounts.filterNot { it.clientId == issued } + account))
        }
        return account
    }

    suspend fun accessToken(): String = store.locked { state, save ->
        var account = state.accounts.singleOrNull { it.clientId == state.activeClientId }
            ?: throw ChatGptAuthException(ChatGptAuthException.Reason.REAUTHORIZATION_REQUIRED)
        suspend fun persist(updated: ChatGptAccount) = withContext(NonCancellable) {
            save(state.copy(accounts = state.accounts.map { if (it.clientId == updated.clientId) updated else it }))
        }
        // Complete an interrupted validation using the protected response, never rotate twice.
        account.pendingRefresh?.let { pending ->
            logger.info("chatgpt_auth=REFRESH_RECOVERY pid={}", ProcessHandle.current().pid())
            account = validateRefresh(account, pending)
            persist(account)
        }
        if (account.accessToken == null && account.refreshToken == null) {
            throw ChatGptAuthException(ChatGptAuthException.Reason.REAUTHORIZATION_REQUIRED)
        }
        if (PLAN_SCOPE !in account.scopes) throw ChatGptAuthException(ChatGptAuthException.Reason.PERMISSION_REQUIRED)
        val cachedAccess = account.accessToken
        if (cachedAccess != null && account.expiresAtMs > nowMs() + 60_000) return@locked cachedAccess
        val refresh = account.refreshToken ?: throw ChatGptAuthException(ChatGptAuthException.Reason.REAUTHORIZATION_REQUIRED)
        logger.info("chatgpt_auth=REFRESH_REQUEST pid={}", ProcessHandle.current().pid())
        val tokens = try {
            token(parameters {
                append("grant_type", "refresh_token"); append("client_id", account.clientId)
                append("refresh_token", refresh); append("resource", RESOURCE)
            })
        } catch (failure: ChatGptAuthException) {
            if (failure.reason == ChatGptAuthException.Reason.REAUTHORIZATION_REQUIRED) persist(signedOut(account))
            throw failure
        }
        val pendingTokens = if ("refresh_token" !in tokens)
            JsonObject(tokens + ("refresh_token" to JsonPrimitive(refresh))) else tokens
        val pending = PendingChatGptRefresh(pendingTokens, nowMs())
        logger.info("chatgpt_auth=REFRESH_RECEIVED pid={}", ProcessHandle.current().pid())
        // The provider may already have invalidated the previous refresh token. Save before
        // any further network request or validation, but do not activate these credentials.
        persist(account.copy(accessToken = null, refreshToken = null, pendingRefresh = pending))
        logger.info("chatgpt_auth=REFRESH_CHECKPOINT_SAVED pid={}", ProcessHandle.current().pid())
        val updated = validateRefresh(account, pending)
        persist(updated)
        logger.info("chatgpt_auth=REFRESH_ACTIVATED pid={}", ProcessHandle.current().pid())
        updated.accessToken!!
    }

    private suspend fun validateRefresh(account: ChatGptAccount, pending: PendingChatGptRefresh): ChatGptAccount {
        return try {
            pending.tokens["id_token"]?.jsonPrimitive?.contentOrNull?.let { id ->
                if (validate(id, account.clientId, null).subject != account.subject) {
                    throw ChatGptAuthException(ChatGptAuthException.Reason.IDENTITY_VALIDATION_FAILED)
                }
            }
            refreshed(account, pending.tokens, pending.receivedAtMs).copy(pendingRefresh = null)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: ChatGptAuthException) { throw failure
        } catch (_: Exception) { throw ChatGptAuthException(ChatGptAuthException.Reason.INVALID_TOKEN_RESPONSE) }
    }

    suspend fun models(includeHidden: Boolean = false): List<Pair<String, String>> {
        val response = client.get("$RESOURCE/models") { bearerAuth(accessToken()) }
        check(response.status.isSuccess()) { "ChatGPT model catalog failed: HTTP ${response.status.value}" }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("models").jsonArray
            .map { it.jsonObject }.filter { includeHidden || it["visibility"]?.jsonPrimitive?.content == "list" }
            .map { it.required("slug") to it.required("display_name") }
    }

    suspend fun select(clientId: String) = store.locked { state, save ->
        require(state.accounts.any { it.clientId == clientId && it.accessToken != null }) { "Sign in to this account before selecting it" }
        save(state.copy(activeClientId = clientId))
    }

    suspend fun logout(): Boolean = store.locked { state, save ->
        val account = state.accounts.singleOrNull { it.clientId == state.activeClientId } ?: return@locked true
        val refresh = (account.pendingRefresh?.tokens?.get("refresh_token") as? JsonPrimitive)?.contentOrNull
            ?: account.refreshToken
        var revoked = refresh == null
        try {
            if (refresh != null) {
                val discovery = client.get("$ISSUER/.well-known/openid-configuration")
                check(discovery.status.isSuccess())
                val endpoint = Json.parseToJsonElement(discovery.bodyAsText()).jsonObject.required("revocation_endpoint")
                requireTrustedEndpoint(endpoint)
                revoked = client.submitForm(endpoint, parameters {
                    append("token", refresh); append("token_type_hint", "refresh_token"); append("client_id", account.clientId)
                }).status == HttpStatusCode.OK
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { revoked = false }
        save(state.copy(activeClientId = null, accounts = state.accounts.map { if (it.clientId == account.clientId) signedOut(it) else it }))
        revoked
    }

    private suspend fun token(form: Parameters): JsonObject {
        onProgress("ChatGPT: exchanging authorization at the token endpoint")
        val response = try { client.submitForm("$ISSUER/api/accounts/oauth/token", form) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            onProgress("ChatGPT: token transport failed (${failure.javaClass.simpleName})")
            throw ChatGptAuthException(ChatGptAuthException.Reason.TEMPORARILY_UNAVAILABLE)
        }
        onProgress("ChatGPT: token endpoint returned HTTP ${response.status.value}")
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val code = runCatching {
                when (val error = Json.parseToJsonElement(body).jsonObject["error"]) {
                    is JsonPrimitive -> error.contentOrNull
                    is JsonObject -> error["code"]?.jsonPrimitive?.contentOrNull
                    else -> null
                }
            }.getOrNull()
            val reason = when (code) {
                in unusableRefreshCodes -> ChatGptAuthException.Reason.REAUTHORIZATION_REQUIRED
                "invalid_client" -> ChatGptAuthException.Reason.INVALID_CLIENT
                else -> if (response.status.value >= 500 || response.status.value == 429)
                    ChatGptAuthException.Reason.TEMPORARILY_UNAVAILABLE else ChatGptAuthException.Reason.INVALID_TOKEN_RESPONSE
            }
            // Provider descriptions and unknown codes can contain private material.
            val safeCode = code?.takeIf { it in unusableRefreshCodes || it == "invalid_client" } ?: "OTHER"
            logger.warn("chatgpt_auth=TOKEN_REJECTED reason={} provider_code={} http_status={} pid={}",
                reason.name, safeCode, response.status.value, ProcessHandle.current().pid())
            throw ChatGptAuthException(reason)
        }
        return try {
            JsonObject(Json.parseToJsonElement(body).jsonObject.filterKeys {
                it in setOf("access_token", "refresh_token", "id_token", "token_type", "scope", "expires_in")
            })
        }
        catch (_: Exception) { throw ChatGptAuthException(ChatGptAuthException.Reason.INVALID_TOKEN_RESPONSE) }
    }

    private suspend fun validate(token: String, clientId: String, nonce: String?): com.nimbusds.jwt.JWTClaimsSet {
        val keys = try {
            val response = client.get("$ISSUER/.well-known/jwks.json")
            if (!response.status.isSuccess()) throw ChatGptAuthException(ChatGptAuthException.Reason.TEMPORARILY_UNAVAILABLE)
            response.bodyAsText()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { throw ChatGptAuthException(ChatGptAuthException.Reason.TEMPORARILY_UNAVAILABLE) }
        return withContext(Dispatchers.Default) {
            try { ChatGptIdTokenValidator.validate(token, keys, clientId, nonce, nowMs()) }
            catch (_: Exception) { throw ChatGptAuthException(ChatGptAuthException.Reason.IDENTITY_VALIDATION_FAILED) }
        }
    }

    private fun refreshed(account: ChatGptAccount, tokens: JsonObject, receivedAtMs: Long = nowMs()): ChatGptAccount {
        require(tokens.required("token_type").equals("Bearer", ignoreCase = true)) { "Unexpected token type" }
        val scopes = tokens["scope"]?.jsonPrimitive?.content?.split(' ')?.filter(String::isNotBlank)?.toSet() ?: account.scopes
        if (PLAN_SCOPE !in scopes || "resource.invoke" !in scopes) throw ChatGptAuthException(ChatGptAuthException.Reason.PERMISSION_REQUIRED)
        val lifetime = tokens.getValue("expires_in").jsonPrimitive.long
        require(lifetime in 1..31_536_000) { "Invalid token expiry" }
        return account.copy(
            accessToken = tokens.required("access_token"),
            refreshToken = tokens["refresh_token"]?.jsonPrimitive?.contentOrNull ?: account.refreshToken,
            idToken = tokens["id_token"]?.jsonPrimitive?.contentOrNull ?: account.idToken,
            scopes = scopes, expiresAtMs = receivedAtMs + lifetime * 1_000,
        )
    }

    private fun signedOut(account: ChatGptAccount) = account.copy(accessToken = null, refreshToken = null, idToken = null, expiresAtMs = 0, scopes = emptySet(), pendingRefresh = null)
    private fun JsonObject.required(key: String): String = getValue(key).jsonPrimitive.content.also { require(it.isNotBlank()) }
    private fun randomSecret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    private fun requireTrustedEndpoint(endpoint: String) {
        val uri = java.net.URI(endpoint)
        require(uri.scheme == "https" && uri.host == "auth.openai.com" && uri.userInfo == null && uri.port in listOf(-1, 443))
    }
    override fun close() = client.close()

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(ChatGptOAuth::class.java)
        private val unusableRefreshCodes = setOf("invalid_grant", "invalid_refresh_token", "token_expired",
            "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")
        const val ISSUER = "https://auth.openai.com"
        const val RESOURCE = "https://api.openai.com/v1"
        const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    }
}

# Relay-Compatible Prompt Caching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Match Codex's custom-provider cache request shape by retaining cache key/options while omitting `prompt_cache_breakpoint` unless the provider is official OpenAI or explicitly opted in.

**Architecture:** Keep model-family detection responsible for whether explicit cache options are meaningful, and add exact parsed-host detection for whether `AUTO` may serialize a breakpoint. Resolve options and breakpoint independently in the OpenAI client so custom providers retain verified cache reads without triggering relay incompatibility.

**Tech Stack:** Kotlin/JVM, Ktor client MockEngine, kotlinx.serialization JSON, kotlin.test, Gradle, systemd

---

### Task 1: Capture the provider capability matrix in request tests

**Files:**
- Modify: `core/src/jvmTest/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt`

- [ ] **Step 1: Add a reusable request-capture helper**

Add this helper inside `OpenAiResponsesLlmClientTest`:

```kotlin
private suspend fun captureCachingRequest(
    baseUrl: String,
    mode: OpenAiPromptCachingMode,
): JsonObject {
    var requestBody = ""
    val engine = MockEngine { request ->
        requestBody = request.body.toByteArray().decodeToString()
        respond(
            content = """
                {"output_text":"{\"internal_logic\":\"logic\",\"vector_delta\":{\"L\":0.0,\"P\":0.0,\"E\":0.0,\"S\":0.0,\"tau\":0.0,\"V\":0.0,\"M\":0.0,\"F\":0.0},\"response\":\"ok\"}"}
            """.trimIndent(),
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }
    val client = OpenAiResponsesLlmClient(
        apiKey = "sk-test",
        model = "gpt-5.6-luna",
        reasoningEffort = ReasoningEffort.MEDIUM,
        baseUrl = baseUrl,
        httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
        json = Json { ignoreUnknownKeys = true },
        promptCachingMode = mode,
    )

    client.complete(BuiltPrompt("stable system", "stable persona", "current user", "dynamic state"))
    return Json.parseToJsonElement(requestBody).jsonObject
}
```

Add `import kotlinx.serialization.json.JsonObject`.

- [ ] **Step 2: Add the failing custom-provider AUTO regression test**

```kotlin
@Test
fun `auto keeps cache controls but omits breakpoint for custom providers`() = runTest {
    val body = captureCachingRequest(
        baseUrl = "http://38.175.222.29:8080/v1",
        mode = OpenAiPromptCachingMode.AUTO,
    )

    assertTrue(body.getValue("prompt_cache_key").jsonPrimitive.content.isNotBlank())
    assertEquals(
        "explicit",
        body.getValue("prompt_cache_options").jsonObject.getValue("mode").jsonPrimitive.content,
    )
    val personaContent = body.getValue("input").jsonArray[1].jsonObject.getValue("content")
    assertEquals("stable persona", personaContent.jsonPrimitive.content)
}
```

- [ ] **Step 3: Add official, explicit override, disabled, and lookalike-host coverage**

```kotlin
@Test
fun `auto emits breakpoint only for the exact official provider host`() = runTest {
    val official = captureCachingRequest("https://api.openai.com/v1", OpenAiPromptCachingMode.AUTO)
    val lookalike = captureCachingRequest("https://api.openai.com.example.org/v1", OpenAiPromptCachingMode.AUTO)

    val officialPersona = official.getValue("input").jsonArray[1].jsonObject
        .getValue("content").jsonArray[0].jsonObject
    assertEquals(
        "explicit",
        officialPersona.getValue("prompt_cache_breakpoint").jsonObject.getValue("mode").jsonPrimitive.content,
    )
    assertEquals(
        "stable persona",
        lookalike.getValue("input").jsonArray[1].jsonObject.getValue("content").jsonPrimitive.content,
    )
}

@Test
fun `explicit mode permits breakpoint on a custom provider`() = runTest {
    val body = captureCachingRequest("https://relay.example.com/v1", OpenAiPromptCachingMode.EXPLICIT)
    val persona = body.getValue("input").jsonArray[1].jsonObject
        .getValue("content").jsonArray[0].jsonObject

    assertEquals(
        "explicit",
        persona.getValue("prompt_cache_breakpoint").jsonObject.getValue("mode").jsonPrimitive.content,
    )
}

@Test
fun `disabled mode omits every cache control`() = runTest {
    val body = captureCachingRequest("https://api.openai.com/v1", OpenAiPromptCachingMode.DISABLED)

    assertEquals(null, body["prompt_cache_key"])
    assertEquals(null, body["prompt_cache_options"])
    assertEquals(
        "stable persona",
        body.getValue("input").jsonArray[1].jsonObject.getValue("content").jsonPrimitive.content,
    )
}
```

- [ ] **Step 4: Run the focused test and verify RED**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests io.openeden.llm.OpenAiResponsesLlmClientTest --console=plain
```

Expected: the custom-provider AUTO test fails because persona content is currently a `JsonArray` containing `prompt_cache_breakpoint`, while the test expects a plain string.

### Task 2: Separate cache options from breakpoint capability

**Files:**
- Modify: `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiPromptCachingMode.kt`
- Modify: `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt`
- Test: `core/src/jvmTest/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt`

- [ ] **Step 1: Implement provider-aware policy helpers**

Replace the existing breakpoint helper in `OpenAiPromptCachingMode.kt` with:

```kotlin
import java.net.URI

fun OpenAiPromptCachingMode.usesCache(): Boolean = this != OpenAiPromptCachingMode.DISABLED

fun OpenAiPromptCachingMode.usesExplicitCacheOptions(model: String): Boolean = when (this) {
    OpenAiPromptCachingMode.DISABLED -> false
    OpenAiPromptCachingMode.EXPLICIT -> true
    OpenAiPromptCachingMode.AUTO -> supportsExplicitPromptCaching(model)
}

fun OpenAiPromptCachingMode.usesExplicitBreakpoint(model: String, baseUrl: String): Boolean = when (this) {
    OpenAiPromptCachingMode.DISABLED -> false
    OpenAiPromptCachingMode.EXPLICIT -> true
    OpenAiPromptCachingMode.AUTO ->
        supportsExplicitPromptCaching(model) && isOfficialOpenAiBaseUrl(baseUrl)
}

private fun isOfficialOpenAiBaseUrl(baseUrl: String): Boolean = runCatching {
    URI(baseUrl.trim()).host?.equals("api.openai.com", ignoreCase = true) == true
}.getOrDefault(false)
```

Keep `supportsExplicitPromptCaching` and enum parsing unchanged.

- [ ] **Step 2: Resolve options and breakpoint independently in the client**

Change request construction in `OpenAiResponsesLlmClient.execute`:

```kotlin
val cacheOptions = promptCacheOptions()
val cacheBreakpoint = if (promptCachingMode.usesExplicitBreakpoint(model, baseUrl)) {
    ResponsesPromptCacheBreakpoint("explicit")
} else {
    null
}
```

Pass `cacheBreakpoint` to the persona message:

```kotlin
textMessage(
    role = "developer",
    text = prompt.personaText,
    breakpoint = cacheBreakpoint,
)
```

Update `promptCacheOptions`:

```kotlin
private fun promptCacheOptions(): ResponsesPromptCacheOptions? =
    if (promptCachingMode.usesExplicitCacheOptions(model)) {
        ResponsesPromptCacheOptions(mode = "explicit")
    } else {
        null
    }
```

- [ ] **Step 3: Run the focused test and verify GREEN**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests io.openeden.llm.OpenAiResponsesLlmClientTest --console=plain
```

Expected: all `OpenAiResponsesLlmClientTest` tests pass, including the custom relay, official host, explicit override, and disabled matrix.

- [ ] **Step 4: Commit the regression fix**

```powershell
git add -- 'core/src/jvmMain/kotlin/io/openeden/llm/OpenAiPromptCachingMode.kt' 'core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt' 'core/src/jvmTest/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt'
git commit -m "fix(llm): adapt prompt caching to provider capability"
git log -1 --format=%s
```

Expected commit title: `fix(llm): adapt prompt caching to provider capability`.

### Task 3: Verify the runtime and build the deployment artifact

**Files:**
- Verify: all affected modules
- Build: `server/build/libs/server-all.jar`

- [ ] **Step 1: Run focused and cross-module verification**

```powershell
.\gradlew.bat :core:jvmTest :server:test :core:compileKotlinIosSimulatorArm64 --console=plain
```

Expected: all three tasks complete successfully. Do not use the pre-existing `:core:allTests` native-test failure as evidence for or against this JVM-only fix.

- [ ] **Step 2: Check the committed patch and unrelated worktree state**

```powershell
git diff HEAD^ --check
git show --stat --oneline HEAD
git status --short
```

Expected: only the three LLM files are in the implementation commit. The existing untracked `docs/diagnostics/` directory remains untouched.

- [ ] **Step 3: Build and checksum the fat JAR**

```powershell
.\gradlew.bat :server:buildFatJar --console=plain
Get-FileHash -Algorithm SHA256 -LiteralPath 'server\build\libs\server-all.jar'
```

Expected: `server/build/libs/server-all.jar` exists and the build exits successfully.

### Task 4: Deploy atomically and verify production

**Files:**
- Deploy: `/opt/openeden/releases/$releaseName/server-all.jar`
- Switch: `/opt/openeden/current`

- [ ] **Step 1: Record the rollback target and create a new release directory**

Use PowerShell variables with the exact implementation commit and timestamp:

```powershell
$releaseCommit = git rev-parse --short HEAD
$releaseStamp = Get-Date -Format 'yyyyMMddHHmmss'
$releaseName = "$releaseCommit-live-$releaseStamp"
$sshArgs = @('-i', 'C:\Users\LightWhite\.ssh\id_ed25519', 'root@103.205.240.118')
$rollbackTarget = (& ssh @sshArgs 'readlink -f /opt/openeden/current').Trim()
$rollbackTarget
& ssh @sshArgs "install -d -m 0755 /opt/openeden/releases/$releaseName; cp -a /opt/openeden/current/. /opt/openeden/releases/$releaseName/"
```

Expected: the old absolute release path is printed and the new release contains the current runtime assets.

- [ ] **Step 2: Upload and verify the artifact before switching**

```powershell
& scp -i 'C:\Users\LightWhite\.ssh\id_ed25519' 'server\build\libs\server-all.jar' "root@103.205.240.118:/opt/openeden/releases/$releaseName/server-all.jar.new"
$localHash = (Get-FileHash -Algorithm SHA256 -LiteralPath 'server\build\libs\server-all.jar').Hash.ToLowerInvariant()
$remoteHash = (& ssh @sshArgs "sha256sum /opt/openeden/releases/$releaseName/server-all.jar.new").Split(' ')[0]
if ($localHash -ne $remoteHash) { throw "Deployment artifact hash mismatch" }
& ssh @sshArgs "mv /opt/openeden/releases/$releaseName/server-all.jar.new /opt/openeden/releases/$releaseName/server-all.jar; chown -R openeden:openeden /opt/openeden/releases/$releaseName"
```

Expected: local and remote SHA-256 values match before the JAR becomes deployable.

- [ ] **Step 3: Switch the symlink, restart, and check health**

```powershell
& ssh @sshArgs "ln -sfn /opt/openeden/releases/$releaseName /opt/openeden/current.next; mv -Tf /opt/openeden/current.next /opt/openeden/current; systemctl restart openeden.service"
& ssh @sshArgs "systemctl is-active openeden.service; curl -fsS http://127.0.0.1:18080/health"
```

Expected: systemd reports `active` and `/health` returns JSON containing `"status":"ready"`.

- [ ] **Step 4: Exercise the deployed OpenEden path with a synthetic session**

Send one non-streaming request and print only status/error metadata:

```powershell
$smokeId = "cache-relay-smoke-$releaseCommit"
$remote = "curl -fsS -X POST http://127.0.0.1:18080/api/v1/chat -H 'Content-Type: application/json' --data '{\"userId\":\"$smokeId\",\"text\":\"Return a brief acknowledgement.\"}' | python3 -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps({\"status\":d.get(\"status\"),\"error\":d.get(\"error\")}))'"
& ssh @sshArgs $remote
& ssh @sshArgs "journalctl -u openeden.service --since '5 minutes ago' --no-pager | grep -E '502|upstream_error|prompt_cache_breakpoint' || true"
```

Expected: the API response status is successful, `error` is null, and the recent service log contains no breakpoint-related 502. This creates one isolated diagnostic session named with the deployment commit.

- [ ] **Step 5: Roll back immediately if health or smoke verification fails**

Use the `$rollbackTarget` value captured in Step 1:

```powershell
& ssh @sshArgs "ln -sfn $rollbackTarget /opt/openeden/current.rollback; mv -Tf /opt/openeden/current.rollback /opt/openeden/current; systemctl restart openeden.service; systemctl is-active openeden.service"
```

Expected: the previous release is restored and reports `active`. Do not retry the failed LLM generation automatically.

# Authoritative Host Role Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure ATRI treats a user as the host only when an independent authoritative host configuration exactly matches that sender.

**Architecture:** Add a pure relationship-role resolver in core, inject its result through the existing pipeline and prompt structures, and load an optional host identity in the server bootstrap. Keep heartbeat delivery ownership independent and keep all persona expression in YAML.

**Tech Stack:** Kotlin Multiplatform, coroutines, Ktor configuration, kotlin.test, YAML persona data

---

### Task 1: Pure Relationship Role Resolution

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/relationship/RelationshipRole.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/relationship/HostIdentity.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/relationship/RelationshipRoleResolver.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/relationship/RelationshipRoleResolverTest.kt`

- [ ] **Step 1: Write failing resolver tests**

Test exact platform/user matching, platform mismatch, user mismatch, absent configuration, and synthetic heartbeat sender behavior. Expected API:

```kotlin
val resolver = RelationshipRoleResolver(HostIdentity("QQ", "owner"))
assertEquals(RelationshipRole.HOST, resolver.resolve("QQ", "owner"))
assertEquals(RelationshipRole.INTERLOCUTOR, resolver.resolve("QQ", "member"))
assertEquals(RelationshipRole.INTERLOCUTOR, RelationshipRoleResolver(null).resolve("QQ", "owner"))
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.relationship.RelationshipRoleResolverTest" --rerun-tasks
```

Expected: compilation failure because the three relationship-role types do not exist.

- [ ] **Step 3: Implement the minimal pure types**

```kotlin
enum class RelationshipRole { HOST, INTERLOCUTOR }

data class HostIdentity(val platform: String, val userId: String) {
    init {
        require(platform.isNotBlank())
        require(userId.isNotBlank())
    }
}

class RelationshipRoleResolver(private val host: HostIdentity?) {
    fun resolve(platform: String, userId: String): RelationshipRole =
        if (host?.platform == platform && host.userId == userId) RelationshipRole.HOST
        else RelationshipRole.INTERLOCUTOR
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Expected: all resolver tests pass without touching session, vector, or persona state.

### Task 2: Prompt And Pipeline Data Flow

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/PromptInput.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/OpenEdenPromptBuilder.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/prompt/DefaultPromptBuilderTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTest.kt`

- [ ] **Step 1: Write failing prompt and pipeline tests**

Assert the rendered system prompt contains:

```text
"relationship_role": "HOST"
Do not assume the current user is the host
```

Create one pipeline with a configured `QQ/owner` host and assert `owner` resolves to `HOST`; send another request from `member` and assert `INTERLOCUTOR`. Assert heartbeat `INTERNAL` is not host.

- [ ] **Step 2: Run focused tests and verify RED**

Run the two test classes. Expected: missing `relationshipRole`/resolver parameters or absent prompt fields.

- [ ] **Step 3: Thread role through existing data structures**

Add `relationshipRole: RelationshipRole = INTERLOCUTOR` to `PromptInput`. Add a `RelationshipRoleResolver` dependency to `DevelopmentMessagePipeline.create`, defaulting to an unconfigured resolver. Resolve from `request.platform` and `request.userId` and pass the value into `PromptInput`.

In the logical core, inject:

```kotlin
"relationship_role" to input.relationshipRole.name
```

and add this English hard rule:

```text
Do not assume the current user is the host. Apply host-specific address and relationship semantics only when relationship_role is HOST.
```

- [ ] **Step 4: Run focused tests and verify GREEN**

Expected: exact host, non-host, and heartbeat roles render correctly; existing prompt schema remains unchanged.

### Task 3: Explicit Server Host Configuration

**Files:**
- Create: `server/src/main/kotlin/io/openeden/server/bootstrap/HostIdentityConfig.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`
- Modify: `server/src/main/resources/application.yaml`
- Modify: `.env.example`
- Test: `server/src/test/kotlin/io/openeden/server/bootstrap/HostIdentityConfigTest.kt`

- [ ] **Step 1: Write failing configuration tests**

Using `MapApplicationConfig`, assert:

- neither property returns `null`;
- both properties return `HostIdentity(platform, userId)`;
- only one property throws `IllegalArgumentException`.

- [ ] **Step 2: Run the focused server test and verify RED**

Expected: `loadHostIdentity` does not exist.

- [ ] **Step 3: Implement focused configuration loading and runtime wiring**

Load `openeden.relationship.hostPlatform` and `openeden.relationship.hostUserId` as an all-or-none pair. Add optional environment mappings:

```yaml
relationship:
  hostPlatform: "$?OPENEDEN_HOST_PLATFORM:"
  hostUserId: "$?OPENEDEN_HOST_USER_ID:"
```

Construct `RelationshipRoleResolver(serverConfig.hostIdentity)` and pass it to the pipeline. Do not read or reuse `heartbeatOwner`.

- [ ] **Step 4: Run the focused server test and verify GREEN**

Expected: all three configuration cases pass.

### Task 4: Persona And Architecture Contract

**Files:**
- Modify: `persona/atri.yaml`
- Modify: `persona/default.yaml`
- Modify: `AGENTS.md`
- Modify: `README.md`
- Modify: `README.zh-CN.md`
- Test: `core/src/jvmTest/kotlin/io/openeden/persona/AtriPersonaGuardTest.kt`

- [ ] **Step 1: Write failing persona guards**

Assert `atri.yaml` does not contain `The current user is your host` and does contain the English conditional host rule. Assert heartbeat contexts do not unconditionally call their recipient `宿主`.

- [ ] **Step 2: Run the guard and verify RED**

Expected: failure on the current identity and heartbeat text.

- [ ] **Step 3: Update persona data and repository invariants**

Keep ATRI identity unconditional, but make host treatment conditional on injected `relationship_role`. Change Chinese present-user and heartbeat wording to `被确认的宿主`, `对方`, or `预定接收者` as context requires. Document that host identity is independent from session scope, relationship scores, and heartbeat delivery owner. Add the two host environment variables to both READMEs.

- [ ] **Step 4: Run persona and full verification**

Run:

```powershell
.\gradlew.bat :core:jvmTest test --rerun-tasks
git diff --check
```

Also scan `persona/atri.yaml` for Japanese kana, unconditional current-user host assertions, Chinese hard-constraint markers, and recognizable source dialogue.

- [ ] **Step 5: Review task-owned diff and commit**

Stage only host-role files and task-specific hunks in shared dirty files. Commit with:

```text
feat: make ATRI host identity explicit
```

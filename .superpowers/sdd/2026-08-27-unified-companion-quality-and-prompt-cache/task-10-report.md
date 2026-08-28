# Task 10 Report: Structured Relationship Evaluation

## Scope

Implemented only Task 10 in `feat/unified-companion-optimization`.

- Added the common `RelationshipEventEvaluator` boundary, `RelationshipTurn`, and confidence-gated `RelationshipEvaluation`.
- Added `DeterministicRelationshipEventEvaluator` with exact phrase matching. It explicitly excludes proposal and negation phrases and does not use broad `String.contains(Regex(...))` classification.
- Added optional JVM `OpenAiRelationshipEventEvaluator`. It submits only the validated user/ATRI turn to the Responses API and requests a strict JSON relationship-event schema with no response-text field.
- Removed `MessagePipeline.relationshipEvidence` and its substring classifier.
- The pipeline now evaluates relationships only after a validated user turn has atomically committed with `TurnCommitOutcome.INSERTED`. It supplies the active incarnation ID, `CanonicalSubjectResolver` output, request turn ID, validated assistant response, and `RuntimeClock` timestamp. High-confidence events use the existing `RelationshipStateStore.append` ledger API. Low-confidence evaluations do not write relationship state. A high-confidence empty evaluation retains the former familiarity increment.

## TDD Evidence

Tests were added before production implementation:

- `RelationshipEventEvaluatorTest`: exact proposal/negation exclusions, an explicit boundary request, and the `0.75f` commit threshold.
- `MessagePipelineTest`: a proposal cannot create a boundary result, a low-confidence result cannot mutate a committed relationship, and a committed turn reaches the evaluator with canonical context before its event is appended to the existing ledger.

The pre-implementation test attempt could not reach Kotlin compilation, so it could not display the intended assertion failure from the existing substring classifier. The exact host failure is recorded below.

## Verification

All commands were run from `D:\Project\openeden\.worktrees\unified-companion-optimization` using PowerShell.

1. Pre-implementation red attempt:

   ```powershell
   .\gradlew.bat :core:jvmTest --tests "*RelationshipEventEvaluatorTest" --tests "*MessagePipelineTest"
   ```

   Exit code: `1`.

   Result before compilation: `java.io.IOException: Unable to establish loopback connection`.

2. Post-implementation narrow regression suite:

   ```powershell
   .\gradlew.bat :core:jvmTest --tests "*RelationshipEventEvaluatorTest" --tests "*MessagePipelineTest"
   ```

   Exit code: `1`.

   Result before compilation: `java.io.IOException: Unable to establish loopback connection`.

3. Post-implementation required broad relationship/pipeline suite:

   ```powershell
   .\gradlew.bat :core:jvmTest --tests "*Relationship*Test" --tests "*MessagePipeline*Test"
   ```

   Exit code: `1`.

   Result before compilation: `java.io.IOException: Unable to establish loopback connection`.

4. Static whitespace check:

   ```powershell
   git diff --check
   ```

   Exit code: `0`; no whitespace errors. Git emitted only the repository's existing LF-to-CRLF conversion warnings for the two edited tracked files.

## Concern

No JVM test or compile result is available from this worktree because Gradle fails while establishing its local loopback connection. The new tests are present, but neither the requested substring-path red assertion nor the green suite can be claimed as executed on this host.

## Task 10 Fix Verification

The fix round was verified from WSL using the worktree path and the process-local system proxy `127.0.0.1:7890`. No global proxy configuration was changed.

1. Focused relationship/pipeline suite:

   ```bash
   ./gradlew :core:jvmTest --tests '*RelationshipEventEvaluatorTest' --tests '*MessagePipelineTest' --no-daemon
   ```

   Exit code: `1` after approximately 27 seconds. Gradle reached `:core:compileKotlinJvm`, then failed before test execution with existing unresolved references in `core/src/commonMain/kotlin/io/openeden/prompt/PromptManifest.kt` (`id`, `utf8Bytes`, `fingerprint`), `core/src/commonMain/kotlin/io/openeden/runtime/diary/DiaryTrigger.kt` (`MemoryMetadata` nullability), and `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiCapabilityProbe.kt` (`contentType`).

2. Required broad relationship/pipeline suite:

   ```bash
   ./gradlew :core:jvmTest --tests '*Relationship*Test' --tests '*MessagePipeline*Test' --no-daemon
   ```

   Exit code: `1` after approximately 25 seconds. It reached `:core:compileKotlinJvm` and stopped on the same unresolved references before test execution.

3. Static verification:

   ```bash
   git diff --check
   ```

   Exit code: `0`; no whitespace errors.

The fix keeps only the vector/transcript atomic commit in `NonCancellable`; relationship evaluation and relationship-store writes remain cancellable. REPAIR and REPEATED_CONSISTENCY now use exact positive corpora with separate proposal and negation exclusions. The focused common tests cover both signal families and the cancellation/retry path; the JVM tests cover strict JSON schema flags, response-field exclusion, and malformed provider output. The JVM tests could not execute because of the unrelated compilation blockers listed above.

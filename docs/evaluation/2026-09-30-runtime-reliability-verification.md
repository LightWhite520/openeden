# Runtime reliability verification — 2026-09-30

## Scope and implementation

- Heartbeat scheduling survives ordinary evaluation failures and observer failures. It waits for a newly drawn interval before the next attempt; cancellation still terminates the job. Server logs identify evaluation failures with `source=HEARTBEAT heartbeat=EVALUATION_FAILED`.
- Active low-intensity ShockState prevents MIXED retrieval. Shock intensity >= 0.6 and Omega >= 0.75 retain CONTRAST priority.
- The specification uses the existing uniform Vitality low threshold of 0.3. Tests cover both exclusive boundaries.
- Maintenance readiness probes secure directory handle support on the actual configured export filesystem using the exporter's I/O dispatcher. The export script requires positive evidence before submitting its POST. Unsupported export capabilities return a typed HTTP 503 without internal filesystem details.

## Automated verification

Command: `./gradlew.bat :core:jvmTest :server:test --console=plain`

- core: 464 tests passed.
- server: 294 tests passed, 2 skipped.
- Total: 758 passed, 2 skipped, 0 failures/errors.
- The filesystem test probes the actual host provider, checks readiness independently from database reset readiness, and verifies that unsupported export creates no payload.
- Existing unrelated CLI and application.yaml changes were left intact.

## Subscription and model-selection follow-up verification

The subsequent implementation adds official Sign in with ChatGPT (PKCE, verified identity, protected account storage, serialized token rotation), a local interactive model picker, and authenticated CLI model management. See [operator guide](../operations/chatgpt-and-model-selection.md).

Final command: `./gradlew.bat :core:jvmTest :server:test :client:jvmTest :cli:test --console=plain` (successful). The final added client transport test was then verified with `:client:jvmTest`.

- core: 468 passed.
- server: 305 passed, 2 skipped.
- client: 10 passed.
- CLI: 185 passed, including pseudo-terminal regression coverage.
- Total: 968 passed, 2 skipped, no failures/errors.

A separate process smoke test exercised `:server:models` against a loopback HTTP fixture: fetch, interactive refresh, numeric selection, then a fresh process reading the saved selection. Both commands exited 0. This validates the local command and persistence, not real provider access. Evidence: `build/model-selector-smoke.json` (local, ignored build artifact).

At that checkpoint, real ChatGPT authorization had not yet been performed. The later live follow-up below supersedes that connectivity limitation. No production database was changed.

## Initial real A/B attempt: blocked (historical checkpoint)

Selected baseline A: `a7fed2a7e782656c20df8e901abc5b913d92b193`. Candidate B: the working tree containing this repair. Both must use independent candidate databases and the same provider/model/persona/scenario when execution resumes.

A minimal Responses API connectivity request using the locally configured provider, model, and credential returned HTTP 401 on 2026-09-30. No credentials, full request headers, or production prompts are included here. No 120–200 turn run was started, and no production state was reset.

The existing `scripts/run-relationship-evaluation.ps1` explicitly produces synthetic fixtures only. It must not be used to claim production acceptance. Real acceptance still requires runtime-backed collection, the required repeated candidate runs when provider seed control is unavailable, authenticated evidence and pairwise decisions, and all existing release thresholds. A baseline choice and successful unit tests do not substitute for those results.

## Live subscription follow-up

Real browser authorization subsequently succeeded using the Windows certificate trust store with TLS verification retained. The actual completed Responses envelope identifies `gpt-6-luna`; its absence from the public model catalog does not imply inference is unavailable. The model picker now probes an explicitly supplied subscription model before saving a selection omitted from the catalog.

The live collector ran the same fixed 128-input scenario against isolated A/B SQLite databases and frozen source snapshots, with real DJL and subscription inference. The first pair completed; subsequent repetitions were stopped after confirming that the subscription relationship evaluator discarded streamed text when the completion envelope omitted output. Data, failed deliveries and protocol amendments are retained under `build/ab-20260930`. The defect was fixed and a separate 16-turn live lifecycle check passed. See the [live diagnostic report](2026-09-30-live-subscription-ab.md); the result remains NOT_ACCEPTED, not a production release gate pass.

The current workspace also fixes startup-failure cleanup attempting to close shared `Dispatchers.IO`. This follow-up is outside the frozen A/B snapshots. Verification: `:core:jvmTest :server:test :client:jvmTest` succeeded with 785 passed, 2 skipped, zero failures/errors (468 core; 307 server passed plus 2 skipped; 10 client).

## Windows limitation at the original checkpoint

Standard Windows JDK production export remains unsupported because its filesystem provider lacks SecureDirectoryStream. The readiness and HTTP changes expose this limitation early; they do not implement a native Windows exporter. Production export/reset workflows need a supported provider or a reviewed native handle-relative implementation. The existing fail-closed export checks and reset saga recovery are preserved.

This checkpoint is superseded by the later native implementation and filesystem/integration tests in [remaining-issue repair and verification](2026-09-30-remaining-fixes.md). The new exporter does not depend on Windows JDK SecureDirectoryStream support.

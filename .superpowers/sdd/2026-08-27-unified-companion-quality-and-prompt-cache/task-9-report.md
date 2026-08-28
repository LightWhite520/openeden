# Task 9 Implementation Report

## Scope

Implemented the Task 9 relationship persistence layer only. The implementation keeps personality and response behavior out of Kotlin: the reducer accepts only typed relationship events and persists mechanical signals, facts, and audit records.

## Implementation

- Added continuous `reciprocalInterest` alongside the existing bounded relationship signals.
- Re-keyed relationship state by `(incarnationId, canonicalSubjectId)` and retained deprecated `sessionId`/`userId` read aliases for existing read-only callers.
- Added durable `RelationshipFacts` with phase, confession, acceptance, mutual commitment, and preferred-address facts.
- Added append-only `RelationshipEvent` records with the required idempotency key `(source_turn_id, event_type, incarnation_id, canonical_subject_id)`.
- Added pure replay reducer behavior for mutual acceptance, correction via `supersedesEventId`, and explicit reset events. Ordinary acquaintance events can establish familiarity but cannot establish `COUPLE`.
- Migrated the pipeline relationship lookup to the active incarnation plus resolved canonical subject identity.
- Replaced relationship SQLite persistence with a state snapshot plus append-only `relationship_events` ledger. All open, read, write, append, and close work uses a dedicated single-thread SQLite dispatcher; close releases the JDBC connection on that dispatcher.
- Added schema migration `15.sqm` to convert legacy session/user rows to neutral `legacy-incarnation` state and create the event ledger.

## Tests Added First

- `RelationshipReducerTest`: mutual acceptance is idempotent, retry keys suppress duplicate events, ordinary turns cannot create `COUPLE`, corrections rebuild facts without removing audit events, and explicit reset clears reduced facts.
- `SqlDelightRelationshipStateStoreTest`: close/reopen persistence, subject isolation, ledger idempotency, correction/reset audit retention, and neutral legacy migration.

## Test Execution

TDD command executed before production implementation:

```powershell
.\gradlew.bat --stop
.\gradlew.bat :core:jvmTest :server:test --tests "*RelationshipReducerTest" --tests "*SqlDelightRelationshipStateStoreTest"
```

Result: Gradle failed during daemon/configuration startup before Kotlin compilation or test discovery. The exact error was:

```text
FAILURE: Build failed with an exception.

* What went wrong:
java.io.IOException: Unable to establish loopback connection
```

No test passed or failed at the test level because the known loopback failure prevented execution. Per instruction, Gradle was not retried after implementation.

## Static Verification

- `git diff --cached --check` completed without whitespace errors.
- Checked the SQLite store and SQL definition for old `selectByKey` / `deleteByKey` queries and `Dispatchers.IO`; none remain there.
- Checked relationship-state constructor call sites; production construction now uses the incarnation/canonical-subject identity.
- Reviewed staged diff for Task 9 scope only.

## Concern

The Task 9 brief allocated migration `14.sqm`, but Task 8 already owns that committed migration. Task 9 therefore uses `15.sqm` to preserve existing work and maintain monotonic SQLDelight schema versioning.

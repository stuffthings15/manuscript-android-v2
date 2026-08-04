# Task 3E1 report: durable download telemetry store

## Scope

Added the pure durable telemetry schema, atomic snapshot store, generation fence,
recovery policy, and focused tests. Manager, worker, notification, and UI wiring
remain outside this checkpoint.

## Files

- `app/src/main/java/com/echolink/mobile/DownloadTelemetryStore.kt`
- `app/src/test/java/com/echolink/mobile/DownloadTelemetryStoreTest.kt`

## RED proof

The first focused test compilation failed because `DownloadTargetKey`,
`DownloadWorkIdentity`, `DurableDownloadRecord`, `DownloadTelemetryCodec`, and
`DownloadTelemetryStore` did not exist. The missing contract was therefore
proved before production code was added.

## GREEN proof

- Store tests: 30 passed, 0 failed, 0 errors, 0 skipped.
- Related telemetry tests before final audit: 72 passed across calculator,
  presentation, and store suites.
- Independent audit round 1 found four blockers.
- All four blockers were fixed.
- Independent audit round 2 approved the checkpoint after a fresh 30-test run.

## Closed guarantees

- Every lifecycle phase round-trips without free-form text.
- Gemma and legacy targets use one schema without overwriting each other.
- Target, generation, and exact work identity form a durable callback fence.
- Missing or corrupt snapshots cannot be recreated by stale callbacks.
- A corrupt generation watermark fences read, write, and reservation.
- Retry generations remain monotonic across fresh store instances and failed
  atomic commits.
- Atomic replacement preserves the previous complete snapshot on commit failure.
- Corrupt, truncated, future, stale, impossible, or unknown-schema records never
  claim Ready.
- Ready is reconciled against an active self-tested model before exposure.
- A failed safe-recovery commit returns no unsafe state.
- Schema version 1 uses explicit stable wire codes and stable target filenames.
- Known total bytes cannot regress to unknown.
- Sample history and record size are bounded.

## Deferred

- Shared lifecycle coordinator and progress publisher.
- Gemma and legacy manager and worker integration.
- Notification actions and Task 3F presentation wiring.
- Instrumentation, emulator, and physical-device execution.

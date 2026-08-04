# Task 3E2 report: shared download lifecycle

## Scope

Added the pure shared lifecycle coordinator and bounded byte/time progress
publisher. Android WorkManager, manager, worker, and notification adapters remain
outside this checkpoint.

## Files

- `app/src/main/java/com/echolink/mobile/DownloadLifecycleCoordinator.kt`
- `app/src/main/java/com/echolink/mobile/DownloadProgressPublisher.kt`
- `app/src/test/java/com/echolink/mobile/DownloadLifecycleCoordinatorTest.kt`
- `app/src/test/java/com/echolink/mobile/DownloadProgressPublisherTest.kt`

## RED proof

- Coordinator tests first failed to compile because the typed attempt,
  coordinator, scheduler, and partial snapshot contracts did not exist.
- Publisher tests first failed to compile because the publication contract did
  not exist.
- The first publisher behavior run exposed a lost Preparing baseline sample.

## GREEN proof

- Coordinator and publisher suites: 29 passed, 0 failed, 0 errors, 0 skipped.
- Independent audit found two publisher boundedness defects and two coordinator
  restart and Retry defects.
- All four defects were fixed before the final independent run.
- Independent final audit approved both mechanisms.

## Closed guarantees

- Target, generation, and work identity are compared on every publication.
- Active and Waiting work is idempotently reconciled after restart.
- Cancel persists Paused with USER_PAUSED before exact work cancellation.
- Retry cancels the predecessor and reserves the stored original target with a
  fresh generation and work identity.
- Scheduling failure becomes a safe retryable terminal state.
- CancellationException is rethrown.
- First progress uses the actual partial byte offset and a fresh sample.
- Progress is accepted by byte delta, time delta, phase change, or first final
  completion. Integer percent is never a publication gate.
- Duplicate completion bursts cannot publish without bound.
- Progress state is isolated by full attempt identity and capped at 16 attempts.
- Public progress carriers contain no user text, path, URL, or exception field.

## Deferred

- Android WorkData encoding and exact WorkManager scheduling.
- Gemma and legacy manager and worker integration.
- Notification Cancel and Retry adapters.
- Task 3F presentation wiring.

# Task 3E3 report: exact work identity and resumable installer

## Scope

Added the strict WorkManager input contract, canonical target catalog, shared
network policy evaluator, and hardened resumable model installer. Android
manager and worker adapters remain outside this checkpoint.

## Files

- `app/src/main/java/com/echolink/mobile/DownloadWorkContract.kt`
- `app/src/main/java/com/echolink/mobile/DownloadNetworkPolicyEvaluator.kt`
- `app/src/main/java/com/echolink/mobile/DownloadTargetCatalog.kt`
- `app/src/main/java/com/echolink/mobile/AiModelInstaller.kt`
- Corresponding focused unit tests.

## RED proof

- Installer adversarial tests first exposed premature EOF cleanup, cancellation
  masking, stale ETag reuse, unsafe activation rollback, and cleanup failures.
- The final two tests failed before production changes because a zero-offset
  restart retained an orphan ETag and post-commit cleanup swallowed cancellation.
- Work contract and network policy tests first failed before their production
  types existed.

## GREEN proof

- Installer suites: 29 passed, 0 failed, 0 errors, 0 skipped.
- Work contract, network policy, and target catalog: 20 passed, 0 failed,
  0 errors, 0 skipped.
- Independent audits approved the pure contract group and the final installer
  behavior.

## Closed guarantees

- Worker input contains only a canonical target, positive generation, and exact
  worker UUID encoded with fixed v1 keys and literal wire codes.
- Forged artifact metadata and impossible target combinations fail closed.
- Wi-Fi-only and mobile-enabled network decisions cover every network type.
- A clean premature EOF remains resumable and never becomes integrity cleanup.
- Cancellation and interruption remain primary across read, progress, sync,
  response close, disconnect, and post-commit cleanup.
- Changed or orphaned ETags cannot be attached to new partial bytes.
- Every non-append response clears the prior marker before storing a new one.
- Candidate and activation failures preserve the working active model.
- Ordinary post-commit backup cleanup failure leaves a successful activation
  successful.

## Deferred

- Android WorkManager scheduler and process-wide service wiring.
- Gemma and legacy manager and worker migration.
- Notification Retry and Cancel adapters.
- Task 3F presentation wiring.

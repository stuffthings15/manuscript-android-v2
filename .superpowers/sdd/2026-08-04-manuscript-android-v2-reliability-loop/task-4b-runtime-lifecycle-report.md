# Task 4B report: across-app runtime lifecycle

## Result

Task 4B closes the reproduced stale-overlay, target-loss, package-switch, and
Close lifecycle failures in the Version 2 accessibility service. The product
mechanism is committed in `0c8f7c4`, the independent-review terminal
completion fix is committed in `55916f6`, and the retained-window creation
guard is committed in `8d15dde`.

The exact `8d15dde` debug APK passed all six dedicated external-fixture device
cases on `emulator-5554` with zero failures and zero skips. This is emulator
functional proof. It is not physical-device proof.

Independent re-review approved the final retained-window creation guard after
the repeat-focus/open-close tests, full local gate, and exact device rerun.

## Mechanism

- Verified application-window changes remove trigger and panel early without
  trusting package names alone.
- Retained targets use immutable structural identity, a one-shot target-loss
  deadline, and a fail-closed stale-node inspection boundary.
- Close and invalidation cancel callbacks, detach, scrub transient holders,
  and restore only the exact eligible target.
- Panel invalidation removes the trigger. Ordinary Close parks an inert trigger
  only while exact-target restoration remains eligible.
- Terminal detach now separates logical lifecycle completion from physical
  `WindowManager` removal. A stalled surface is first hidden, disabled,
  non-touchable, and removed from accessibility. The controller reaches Hidden
  after a fixed 64 ms deadline even without later accessibility events.
- Physical ownership remains in a separate coordinator until Android confirms
  detachment. Bounded scheduled retries and sampled event retries continue.
  Destroy reaches Destroyed and retains stalled ownership for cleanup.
- While ownership remains, the shared executor rejects every event that could
  create or restore a trigger or panel. The service also guards both window
  creation sites. Physical release removes ownership exactly once before a
  safe reconnect, and Destroy never creates a replacement window.
- Raw event diagnostics are sampled and all lifecycle diagnostics are
  content-free and length-bounded.

Disproved return prewarming and post-layout refresh experiments were removed.
The final product does not contain the prewarm candidate, alpha, or return
package mechanisms.

## Strict TDD evidence

The original device RED reproduced both Settings disappearance failures, the
detached/target-loss stale overlay, and later-case blocking.

Focused product RED then covered the one-shot 100-event deadline, throwing
stale-node inspection, exact application switching, terminal ownership, and
host integration before the final implementation. Independent review found a
remaining liveness gap: after scheduled removal retries, logical completion
still depended on a later accessibility event.

The review-fix fail-first run reached unit-test compilation and failed on the
missing `TerminalDetachLifecycleCoordinator` contract. After its minimal
implementation, three coordinator tests passed for no-event completion, later
physical detach, and Destroy. A second fail-first test then failed because
Destroy's first stalled detach did not enter terminal ownership. The executor
fix made that path pass.

Independent re-review then found that logical Hidden state could accept later
focus/open events while a physically stalled window was still owned. A strict
100-cycle focus/open/attach/close test failed before the guard because it
requested new surfaces without releasing the old ownership. A companion
Destroy test locked no-new-window behavior. Both passed after the shared
creation guard and service creation-site checks.

Final focused lifecycle result:

- 55 tests passed.
- 0 failures, 0 errors, 0 skipped.

One full-suite attempt under JDK 17 produced an unrelated
`List.removeFirst()` `NoSuchMethodError`. It is not counted as proof. The
documented JDK 21 passed that test and the complete gate.

## Full local gate

The JDK 21 gate ran debug and release unit tests, debug and release lint,
product debug/release/instrumentation builds, and fixture
debug/instrumentation builds.

- App debug: 837 passed, 0 failed, 0 errors, 0 skipped.
- App release: 837 passed, 0 failed, 0 errors, 0 skipped.
- Fixture local: 29 passed, 0 failed, 0 errors, 0 skipped.
- Total: 1703 passed.
- Debug lint: 0 errors, 106 advisory warnings.
- Release lint: 0 errors, 106 advisory warnings.
- All requested product and fixture APK builds succeeded.
- `git diff --check` passed.

## Exact installed artifact

```text
Commit: 8d15dde41670
APK: app/build/outputs/apk/debug/app-debug.apk
Bytes: 212251025
SHA-256: BBCD93A0299A599D40F19FCB5E3894F83616C337FB7E02E559B0AA3C888F1F6B
```

`BuildConfig.MANUSCRIPT_GIT_COMMIT` is `8d15dde41670`. The emulator's installed
`base.apk` SHA-256 matched exactly after `adb install -r -t`.

Model bytes were identical before install, after install, and after the device
gate:

```text
Qwen3-0.6B-Q8_0.gguf             639446688
Qwen_Qwen3-1.7B-Q4_K_M.gguf    1282439584
Qwen3-4B-Q4_K_M.gguf           2497280256
```

No product data was cleared. No package was uninstalled.

## External fixture device gate

The exact class-filtered gate ran the five lifecycle cases plus the locked
product-contract case:

```text
OK (6 tests)
Instrumentation time: 73.892 seconds
Host-observed time: 74.334 seconds
Failures: 0
Skips: 0
```

All previously failing lifecycle paths reopened and passed. Exact rendered
timing evidence was:

| Path | Surface disappearance | Rendered transition to exact trigger |
|---|---:|---:|
| Trigger visible to Settings | 1 ms | 328 ms |
| Panel open to Settings | 0 ms | 287 ms |
| Panel Close return | n/a | 345 ms |

Every rendered return reported `exactIdentity=true`. The selected harness
contract was `verified-emulator-functional`, whose explicit ceilings are
1000 ms removal and 2500 ms return. The production physical limits remain
unchanged at 300 ms removal and 500 ms return; the observed emulator values
are below those numbers, but this run does not substitute for physical-device
acceptance.

After the gate, secure settings enabled only:

```text
com.remote.Manuscript.v2.debug/com.echolink.mobile.ManuscriptAccessibilityService
```

`dumpsys accessibility` showed exactly one bound Manuscript service,
`Binding services:{}`, and `Crashed services:{}`. `dumpsys activity services`
showed the exact component with `hasBound=true`.

## Safety and proof boundary

- Only `emulator-5554` received product commands.
- Version 1 and phone serial `RFCRA0A6CFH` were not modified.
- No clear, uninstall, Play upload, or `performClick` bypass was used.
- No editor text, correction text, vocabulary, prompt, or model output was
  logged or included in the evidence.
- This report proves local contracts, exact emulator installation, rendered
  emulator behavior, and service health. Physical phone timing remains a
  separate acceptance boundary.

# Adaptive On-Device Model Selection

## Goal

Manuscript must audit the user’s Android device and select the highest-quality
installed AI model that is expected to produce the first corrected paragraph
within 10 seconds in Auto mode.

The selection must remain local, reliable, and conservative. Every correction
must still use AI. Deterministic rules may validate output, restore formatting,
or prepare an AI retry, but they may not produce a correction by themselves.

## Approved Product Behavior

- Auto mode targets a first corrected paragraph within 10 seconds.
- Faster and Best Quality remain explicit manual overrides.
- Auto may download the selected model using the user’s existing Wi-Fi-only or
  mobile-data permission.
- Auto keeps the compact AI model installed as a recovery model.
- The correction card always terminates with nonblank text or a clear
  "No safe correction available" result. It may not spin forever or show an
  empty suggestion.
- Audit and benchmark data never leaves the device.

## Existing Foundation

The current app already records or derives:

- total and available RAM
- available storage
- processor count
- 64-bit support and supported ABIs
- Android API level
- Vulkan hardware support
- low-memory-device status
- thermal severity
- known phone or Chromebook family

The existing static policy safely excludes models that the device cannot
support. The missing capability is a measured benchmark for the primary Gemma
models that is tied to a response-time target.

## Components

### DeviceAudit

`DeviceAudit` creates an immutable local snapshot containing:

- total and available RAM
- usable model storage
- processor count
- supported ABIs
- Android API level and OS build fingerprint
- Vulkan availability
- low-memory-device status
- thermal status
- battery percentage
- charging state
- power-saving state
- recognized device family

The audit returns a conservative profile when a signal cannot be read. Missing
information must never cause selection of a larger model.

### ModelBenchmark

`ModelBenchmark` measures a real local first-paragraph correction for one model
and backend. The measurement includes:

- runtime initialization
- prompt preparation
- first complete correction
- grammar and meaning validation

A benchmark passes only when generation completes, returns nonblank text, and
passes the same production safety gates used by normal corrections.

Each record stores:

- model artifact and pinned revision
- backend
- app version
- Android API and OS fingerprint
- stable device-profile fingerprint
- duration in milliseconds
- completion and validation status
- measurement timestamp

Raw prompt text and generated text are not stored in the benchmark record.

### AdaptiveModelSelector

The selector first removes candidates that fail static hardware and storage
requirements. It then ranks remaining candidates by expected writing quality.

In Auto mode, the selector chooses the highest-ranked model whose validated
benchmark is at or below the 10-second target with a safety margin. An unknown
or expired benchmark cannot promote a larger model. It may trigger a benchmark
or select the compact model until qualification completes.

Faster uses the fastest safe model. Best Quality uses the highest-quality model
that passes hard device-safety requirements, even when its measured latency is
above the Auto target.

### BenchmarkStore

Benchmark records and slow-response strikes are stored in private app
preferences or the encrypted local database. Records expire when any of these
values change:

- model revision or integrity hash
- app version
- Android API or OS fingerprint
- relevant device-profile fingerprint
- benchmark policy version

The user can remove all models and benchmark data through the existing Remove
AI action.

### ModelDownloadCoordinator

Auto may enqueue the selected artifact only after the existing AI download
consent has been granted. It must retain the selected Wi-Fi-only or mobile-data
policy and the current storage checks.

Auto maintains the compact model as the recovery model. It must not download
the thorough model when the device fails its hard safety requirements.

## Selection and Correction Flow

1. Read the current device audit.
2. Load valid benchmark records.
3. Filter unsafe or unsupported candidates.
4. Rank the remaining candidates by quality.
5. Select the highest-ranked candidate expected to meet the 10-second Auto
   target.
6. If the model is not installed, enqueue it under existing download consent
   and network policy.
7. Run the correction with an 8-second primary working limit.
8. Validate the result through the production safety gates.
9. If the primary result is slow, failed, blank, or rejected, cancel it and run
   the compact AI fallback.
10. Record the duration and outcome without retaining user text.

The first clean correction after installation serves as the initial benchmark.
A dedicated "Run audit again" action can perform the same local qualification
using a fixed built-in paragraph.

## Reliability and Failure Handling

- A failed device audit selects the compact model.
- A failed or expired benchmark cannot promote a larger model.
- A primary model that exceeds the 8-second working limit is cancelled.
- A crashed runtime is restarted once before it receives a failure strike.
- Invalid or unsafe output triggers one strict AI retry when time remains.
- The compact AI handles primary failure.
- Two consecutive slow or failed corrections persistently downgrade Auto mode
  until a successful audit is completed.
- A fallback failure returns the original text with
  "No safe correction available."
- The result callback must complete exactly once.
- Rule-only output must never be passed to Accept or Replace.
- Manual modes report failures but do not silently change the user’s selected
  mode.

The 10-second value is a response-time target, not a promise against operating
system process suspension or failing hardware. The watchdog guarantees that
the app stops waiting and proceeds to recovery behavior.

## User Interface

The existing AI settings area will show:

- selected model
- selected backend
- most recent measured first-paragraph time
- last audit date
- compact fallback installation status
- reason for a conservative selection when applicable
- "Run audit again" action

Internal model names remain hidden where the existing product terminology
requires the user-facing label "AI."

## Testing

### Unit tests

- S20, S23, low-RAM phone, ARM64 Chromebook, and x86_64 Chromebook profiles
- low storage, low available memory, power saving, and severe thermal state
- quality ranking within the 10-second target
- conservative selection for missing or expired benchmarks
- benchmark invalidation after app, model, OS, or policy changes
- automatic download under Wi-Fi-only and mobile-data permission
- primary timeout, cancellation, restart, fallback, and persistent downgrade
- exactly-once callback behavior
- nonblank correction or explicit failure result
- no rule-only result reaching the live correction path

### Device tests

On the S23 test device:

- record cold first-paragraph duration for each installed candidate
- record warm first-paragraph duration
- verify Auto selects the highest-quality candidate within the target
- force a timeout and verify compact fallback
- force invalid output and verify strict retry or fallback
- verify audit details remain local

### Regression verification

- existing 305 release unit tests
- Android lint
- signed release build and security release gate
- physical S23 correction in the main editor
- physical S23 correction through the across-app accessibility panel

## Success Criteria

- Auto chooses the best validated model expected to finish the first corrected
  paragraph within 10 seconds.
- The compact recovery model is available after AI installation.
- Slow or failing models are downgraded automatically in Auto mode.
- Every correction attempt terminates with AI-generated nonblank text or an
  explicit safe failure result.
- No user writing, audit record, or benchmark content is transmitted.
- Faster and Best Quality remain user-controlled overrides.

# Task List: Fix Diagnostics Telemetry/Diagnosis on Vector

## Phase 1: Baseline and token-sync resilience
- [x] Task 1.1: Capture/encode failing scenarios in focused tests and identify exact token-sync gaps (App convergence retry for token-only failure, reporter token wait/retry behavior, remote prefs visibility assumptions).
- [x] Task 1.2: Implement minimal fix for token synchronization/resolution so hooked process can obtain valid token in supported environments (without weakening auth).
- [x] Task 1.3: Verify with phase-appropriate checks; independent review; fix/review loop until clean; commit.

## Checkpoint: Token path
- [x] Token unavailable symptom addressed in code/tests; no security regression.

## Phase 2: Provider-invisible fallback diagnosability (SDK 33 / Vector)
- [ ] Task 2.1: Trace why token-only fallback batches yield unverified run with no usable milestones/registrations in UI/store; define truthful-but-useful state transitions.
- [ ] Task 2.2: Implement minimal store/receiver/reporter/UI-policy adjustments so authenticated fallback telemetry can be diagnosed (clearly marked unverified/token-authenticated) when OS-verified path unavailable.
- [ ] Task 2.3: Verify with phase-appropriate checks; independent review; fix/review loop until clean; commit.

## Checkpoint: Fallback diagnosability
- [ ] Diagnostics can report meaningful state under provider-invisible + fallback conditions without false verification claims.

## Phase 3: Payload schema rejection and transport correctness
- [ ] Task 3.1: Reproduce/identify source of `invalid payload schema` from reporter/provider path; add regression coverage.
- [ ] Task 3.2: Implement fix and validate event sequencing/batch behavior.
- [ ] Task 3.3: Verify with phase-appropriate checks; independent review; fix/review loop until clean; commit.

## Checkpoint: Transport/schema
- [ ] No invalid-schema rejection for valid payloads; retries/batches behave correctly.

## Phase 4: Final integration, release-gate verification, and PR/acceptance
- [ ] Task 4.1: Run required verification commands (per AGENTS), final comprehensive multi-subagent review, fix/review loop until clean.
- [ ] Task 4.2: Final commit(s) as needed, push branch, open PR with evidence.
- [ ] Task 4.3: Arrange and document real-device acceptance (adb/Diagnostics UI/logcat on target device).

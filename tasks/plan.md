# Implementation Plan: Fix Diagnostics Telemetry/Diagnosis on Vector (Android 13)

## Overview
Address "hooks work but diagnosis cannot diagnose correctly" observed on Vector/SM-T870 (Android 13, SDK 33) with Pixelify Infinity v1.5.0 and Google Photos 8.0.0.1011730683. Evidence shows (a) intermittent/missing diagnostics token visibility in hooked process remote prefs causing reports not dispatched, (b) ContentProvider authority not visible to Photos (`Unknown authority`) with broadcast fallback batches accepted but telemetry remains unverified/unassociated in UI, and (c) at least one `PixelifyDiagStore: Rejecting diagnostics request with invalid payload schema`.

Goal: make diagnostics truthful *and* functional in this environment (token sync resilient, fallback telemetry attributable enough for diagnosis without falsely claiming OS-verified identity, and no spurious schema rejections for valid reporter payloads), with tests and real-device acceptance.

## Investigation Evidence (baseline)
- PR mapping: PR #17 = CI/JDK hardening (unrelated). No PR #16 merge; issue 16 diagnostics work merged as PR #18 (`e3a9896`, truthful hook diagnostics/token/run/origin hardening).
- Hook path OK: module loaded in Photos, Build/sysprops/feature hooks registered, `Device spoof VERIFY OK for Pixel XL`.
- Failures: `Diagnostics IPC token unavailable... not synchronized to token in remote prefs` / `report not dispatched`; provider lookup failed from Photos, fallback `recordDiagnosticsBatch` dispatched but UI shows unverified run with no milestones/registrations/VERIFY; `Rejecting diagnostics request with invalid payload schema` observed once.

## Architecture Decisions (initial)
- Preserve "truthful diagnostics" semantics: never label unverified/token-only evidence as OS-verified.
- Improve availability: token convergence must be retried/observable for telemetry even when settings are READY; hooked reporter must cope with delayed token availability where safe.
- Improve fallback usefulness on SDK < 34 / provider-invisible cases: allow token-authenticated fallback telemetry to populate diagnostics state with explicit "unverified/token-authenticated" provenance instead of leaving diagnosis empty, while keeping provenance gates.
- Fix schema/transport mismatches only when validated against current reporter/store contract and tests.

## Task List

### Phase 1: Baseline, reproduction harness, and token-sync resilience
- [ ] Task 1.1: Capture/encode failing scenarios in focused tests and identify exact token-sync gaps (App convergence retry for token-only failure, reporter token wait/retry behavior, remote prefs visibility assumptions).
- [ ] Task 1.2: Implement minimal fix for token synchronization/resolution so hooked process can obtain valid token in supported environments (without weakening auth).
- [ ] Task 1.3: Verify with unit tests and targeted checks; review/fix loop; commit.

### Checkpoint: Token path
- [ ] Token unavailable symptom addressed in code/tests; no security regression.

### Phase 2: Provider-invisible fallback diagnosability (SDK 33 / Vector)
- [ ] Task 2.1: Trace why token-only fallback batches yield unverified run with no usable milestones/registrations in UI/store; define truthful-but-useful state transitions.
- [ ] Task 2.2: Implement minimal store/receiver/reporter/UI-policy adjustments so authenticated fallback telemetry can be diagnosed (clearly marked unverified/token-authenticated) when OS-verified path unavailable.
- [ ] Task 2.3: Verify with unit tests and targeted checks; review/fix loop; commit.

### Checkpoint: Fallback diagnosability
- [ ] Diagnostics can report meaningful state under provider-invisible + fallback conditions without false verification claims.

### Phase 3: Payload schema rejection and transport correctness
- [ ] Task 3.1: Reproduce/identify source of `invalid payload schema` from reporter/provider path; add regression coverage.
- [ ] Task 3.2: Implement fix and validate event sequencing/batch behavior.
- [ ] Task 3.3: Verify with unit tests and targeted checks; review/fix loop; commit.

### Checkpoint: Transport/schema
- [ ] No invalid-schema rejection for valid payloads; retries/batches behave correctly.

### Phase 4: Final integration, release-gate verification, and PR/acceptance
- [ ] Task 4.1: Run required verification commands (per AGENTS), full multi-subagent review, fix/review loop until clean.
- [ ] Task 4.2: Final commit(s) as needed, push branch, open PR with evidence.
- [ ] Task 4.3: Arrange and document real-device acceptance (adb/Diagnostics UI/logcat on target device).

## Risks and Mitigations
| Risk | Impact | Mitigation |
|------|--------|------------|
| Weakening auth/provenance while fixing diagnosability | High | Keep token required; distinguish unverified vs OS-verified in store/UI/tests |
| Behavior changes across API levels/frameworks | High | Add/adjust unit tests for API 30-33/34+ and fallback/provider paths; preserve existing semantics where valid |
| Large blast radius in diagnostics core | Med | Small phased commits, independent reviews each phase |
| Real-device-only issues (Vector/AppsFilter timing) | Med | Include adb acceptance phase and log-based evidence |

## Open Questions
- Exact trigger for observed `invalid payload schema` (which method/payload/state) on this device/build.
- Whether token invisibility is timing-only, store propagation, or process-context issue in Vector/libxposed on this ROM.
- Acceptable UI wording/state for "token-authenticated fallback (unverified)" to satisfy "truthful" diagnostics.

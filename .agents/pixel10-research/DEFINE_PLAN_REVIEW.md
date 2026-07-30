# DEFINE_PLAN Review — v3 re-review (after R1/R2)

**Reviewer role:** Independent PLAN REVIEWER (did not author the plan)  
**Date:** 2026-07-30  
**Branch:** `feat/pixel10-multi-scope`  
**Primary under review:** `.agents/pixel10-research/DEFINE_PLAN.md` (**v3 / REVISED after R1/R2**)  
**Prior reviews:** this file — v1 **FAIL** (B1–B5); v2 **FAIL** (residuals **R1**, **R2**)  
**Status of plan when reviewed:** REVISED after DEFINE_PLAN_REVIEW FAIL (v2 residuals R1/R2) — ready for re-review  

---

## Verdict: **PASS**

**BUILD may start Phase 1.**

v3 closes the two residual blockers from the v2 re-review (**R1**, **R2**) without reopening product scope. Prior **B1–B5** remain addressed. No remaining plan blockers for Phase 1 implementation under the frozen hard policies.

---

## Must-confirm residuals — resolution check

| ID | Required fix | v3 evidence | Status |
|----|--------------|-------------|--------|
| **R1** | Freeze experimental carve-out for Fold/10a; name existing universal FP test; positive FP-key absences; identity props still required | **TEST-1** defines `EXPERIMENTAL_IDENTITY_ONLY = setOf("Pixel 10 Pro Fold", "Pixel 10a")`; names `all device fingerprints contain brand slash device slash device pattern` → apply only to non-None devices **not** in that set; experimental path asserts FP key **absent/null** (no invent); any universal FP/ID completeness checks share the same carve-out; non-empty identity / BRAND / MANUFACTURER / MODEL / androidVersion **still apply** to experimental devices; positive Fold=`rango` / 10a=`stallion` + FP/ID/INCREMENTAL/SECURITY_PATCH **absent**; Phase 1 reviewer gate includes “TEST-1 carve-out applied to universal FP test” | **Fixed** |
| **R2** | 9a `SECURITY_PATCH` pinned OMIT **or** cited; ban tehua-era patch/ID/incremental residues | **FP-4**: `SECURITY_PATCH` = **OMIT** (no cited patch for exact `BP4A.260105.004.E1`); do **not** keep `2025-04-05`; never leave `tehua` / `BP1A.250405.002` / `13115780` on 9a entry; source notes omit under FP-1 rather than guess. **TEST-1** residue row: no `tehua`; 9a must not contain `BP1A.250405.002` or `13115780`. Phase 1 gate: “9a has no SECURITY_PATCH residue from tehua era” | **Fixed** |

### R1 code collision still real (plan now resolves it)

Spot-check of current suite confirms the v2 failure mode still exists in code and therefore still **must** be updated in Phase 1 exactly as TEST-1 freezes:

```text
DevicePropsTest.kt
`all device fingerprints contain brand slash device slash device pattern`
→ every non-None device: assertNotNull(FINGERPRINT) + google/... pattern
```

v3 no longer leaves that rewrite to builder invention: carve-out set, named test, positive absences, and identity-still-required rules are plan-frozen. Phase 1 can go green without violating FP-1.

### R2 code residue still real (plan now bans it)

Current `DeviceProps.kt` 9a entry still has `tehua` + `BP1A.250405.002` + `13115780` + `SECURITY_PATCH=2025-04-05`. That is expected pre-BUILD debt. v3 pins the replacement FP/ID/INCREMENTAL and **OMIT** for patch with explicit residue ban — builders cannot keep a mixed train.

---

## Prior FAIL items B1–B5 — still hold

| ID | Prior blocker | Still frozen in v3? | Status |
|----|---------------|---------------------|--------|
| **B1** | Fold/10a FP invent pressure | **FP-1** no-invent; **FP-3** identity-only OMIT FP keys; CHANGELOG experimental; acceptance “no fake FP”; **R1** closes suite collision | **Holds (Addressed)** |
| **B2** | 10a featureLevel / mid-year thrash | **FP-3** + **FEAT-1**: 10a → **Pixel 2025** max-spoof; no mid-year rung this PR | **Holds (Addressed)** |
| **B3** | Non-Photos feature policy + Option B scope | **SCOPE-1** B0 + denylist; policy **(a)** full Device+Feature; explicit In/Out; Photos-only `scope.list` + mirror SCOPE | **Holds (Addressed)** |
| **B4** | Unpinned A16 maps + soft 9a | **FP-2** frankel/blazer/mustang full A16 table; **FP-4** cited tegu A16 FP/ID/INCREMENTAL; **R2** patch OMIT + residue ban | **Holds (Addressed; residual closed)** |
| **B5** | Missing concrete test contracts | **TEST-1**: features **13**, devices **26**, default **Pixel XL**, `getFeaturesUpTo("Pixel 2016").size == 1`, tegu/no-tehua, frankel exact, Fold/10a absences, ScopePolicyTest; **R1** carve-out | **Holds (Addressed; residual closed)** |

---

## Nits from v2 — status

| ID | Nit | v3 | Status |
|----|-----|----|--------|
| **N1** | No migration of saved `PREF_DEVICE_TO_SPOOF` | **DEFAULT-1**: no migration; only first-run / missing-pref / reset-settings → Pixel XL | **Addressed** |
| **N2** | Shared helper on both module entry points | **DENY-1**: both `onPackageLoaded` and `onPackageReady` call the **same** ScopePolicy helper | **Addressed** |
| **N3** | Duplicate `com.android.systemui` in denylist | DENY-1 unique set; systemui once | **Addressed** |
| **N4** | BRAND/MANUFACTURER on FP-3 rows | FP-3 table includes `google` / `Google` | **Addressed** |
| **N5** | Optional oriole → 27 | Still clear if added | **OK** |
| **N6** | PRELOAD MED/LOW honesty | FEAT-1 + risks preserved | **OK** |
| **N7** | App id / signing unchanged | Locked decision #5; acceptance | **OK** |
| **N8** | Phase order + Phase 2 in-app warning | Phases 1→4; SCOPE-1 UI/docs warnings in Phase 2 scope | **OK** |

No nit rises to a blocker.

---

## Criteria checklist

| # | Criterion | Result |
|---|-----------|--------|
| 1 | Matches locked decisions (full P10 series, A16, multi-scope B, default Pixel XL) | **Yes** |
| 2 | Blocking research fixes included (9a tegu + no invent Fold/10a) | **Yes** |
| 3 | Phases ordered with review gates | **Yes** |
| 4 | Acceptance criteria testable | **Yes** — counts, string pins, carve-out, residue bans, ScopePolicy cases |
| 5 | Safety (denylist, no signing, no PRELOAD overclaim, feature policy explicit) | **Yes** |
| 6 | Prior B1–B5 closed | **Yes** |
| 7 | R1 + R2 closed | **Yes** |
| 8 | Feasibility in this codebase | **High** — edits localized to DeviceProps + tests (P1), module gates + ScopePolicy (P2), docs (P3) |

---

## Feasibility re-spot-check (evidence, not BUILD)

| Area | Observation | Plan fit |
|------|-------------|----------|
| `DeviceProps.kt` | 12 feature levels; default **Pixel 5** / features → 2020; **9a = tehua** + BP1A/13115780/2025-04-05; 21 devices; no Pixel 10 / 2025 | v3 pins exact replacement maps |
| `DevicePropsTest.kt` | Hardcoded 12 / 21 / Pixel 5; **universal FINGERPRINT required** on all non-None | v3 TEST-1 freezes carve-out + named test update |
| Research A16 BD3A | frankel/blazer/mustang `BD3A.250721.001/13808258`, patch `2025-08-05` | **FP-2 matches** |
| Research Fold/10a | FP UNKNOWN; no invent | **FP-1/3 match** |
| Research 10a feature | mid-year unresolved | Plan freezes **Pixel 2025** (B2 intentional) |
| `PixelifyModule.kt` | Hard Photos gate on load + ready | SCOPE-1 + DENY-1 shared helper |
| `module.prop` / `scope.list` / mirror SCOPE | Photos-only recommended path | Explicit B0 |

No architectural plan defect remains.

---

## What v3 does well (delta from v2)

1. **R1:** Explicit `EXPERIMENTAL_IDENTITY_ONLY`, named existing FP test rewrite, positive key-absence asserts, identity still required, ban on new universal FP checks that re-break Fold/10a, feature-ladder 13 for P10* devices.  
2. **R2:** 9a `SECURITY_PATCH` **OMIT** with cited rationale + hard ban on tehua-era strings.  
3. **N1–N4** landed in the same pass (pref non-migration, shared ScopePolicy both hooks, unique denylist, BRAND/MANUFACTURER on FP-3).  
4. Phase 1 reviewer checklist now includes carve-out + 9a patch residue — BUILD review can enforce without re-litigating policy.  
5. Status line correctly marks REVISED after R1/R2 (not overclaiming shipped code).

---

## Remaining blockers

**None.**

Optional non-blocking note for Phase 1 builder/reviewer (does **not** hold PASS):

- TEST-1 residue row bans old 9a ID/INCREMENTAL/tehua; FP-4 already requires SECURITY_PATCH OMIT. A single explicit assert that 9a props lack `SECURITY_PATCH` would mirror Fold/10a positive absences and make Phase 1 review mechanical — nice-to-have, not a plan defect.

---

## Authorization to proceed

- **Verdict: PASS**  
- **BUILD may start Phase 1** (DeviceProps + DevicePropsTest under FP-2..5, FEAT-1, DEFAULT-1, TEST-1).  
- Subsequent phases still require independent review gates per locked decision #6.  
- **NO code, NO commit** from this reviewer.

---

## Checks performed

1. Read `.agents/pixel10-research/DEFINE_PLAN.md` **v3** (full).  
2. Read prior `.agents/pixel10-research/DEFINE_PLAN_REVIEW.md` (v2 FAIL: R1/R2 + B1–B5 table).  
3. Confirmed R1 text: carve-out set name, named universal FP test, positive absences, identity required.  
4. Confirmed R2 text: 9a SECURITY_PATCH OMIT + tehua/BP1A/13115780 ban.  
5. Confirmed B1–B5 policy sections still present and consistent (FP-1..5, FEAT-1, DEFAULT-1, SCOPE-1, DENY-1, TEST-1).  
6. Confirmed nits N1–N4 present in v3.  
7. Spot-checked `DevicePropsTest.kt` — universal FP assert still present (R1 still necessary at BUILD).  
8. Spot-checked `DeviceProps.kt` — 9a still tehua-era (R2 still necessary at BUILD).  
9. Spot-checked research pins (BD3A train; rango/stallion FP unknown) vs plan tables.  
10. Spot-checked `PixelifyModule.kt` Photos hard gates (Phase 2 work remains correctly deferred).  
11. No code changes; no commit (review only).

---

## Summary for orchestrator

- **Verdict: PASS**  
- **BUILD may start Phase 1**  
- **R1 fixed · R2 fixed · B1–B5 hold · nits addressed**  
- Remaining blockers: **none**  
- **NO code, NO commit** from this reviewer  

---

*End of plan re-review (v3).*

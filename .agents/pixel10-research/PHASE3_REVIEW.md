# PHASE3_REVIEW — Docs + CHANGELOG (r2 re-review)

**Verdict: PASS**

**Branch:** `feat/pixel10-multi-scope`  
**Date:** 2026-07-30  
**Reviewer role:** Independent Phase 3 docs re-reviewer (strict)  
**Model:** cpa/grok-4.5  
**No commits made.**

**Round:** r2 after prior **FAIL** (B1 PRELOAD docs, B2 translated README leads)  
**Builder input:** `.agents/pixel10-research/PHASE3_BUILDER_REPORT.md` (r2 READY FOR REVIEW)

**Inputs re-reviewed:**
- `.agents/pixel10-research/DEFINE_PLAN.md` (Phase 3 + PR docs acceptance)
- `.agents/pixel10-research/PHASE3_REVIEW.md` (prior FAIL — overwritten by this file)
- `.agents/pixel10-research/PHASE3_BUILDER_REPORT.md` (r2)
- `README.md`
- `docs/README.zh-TW.md`, `docs/README.zh-CN.md`, `docs/README.ja.md`
- `SUPPORT.md`, `CONTRIBUTING.md`, `AGENTS.md`
- `CHANGELOG.md` `[Unreleased]`
- `docs/PUBLICATION_CHECKLIST.md`
- `site/index.html`, `site/zh-TW/index.html`, `site/zh-CN/index.html`, `site/ja/index.html`
- `app/src/main/res/values/strings.xml`, `values-zh-rTW/strings.xml` (relevant keys)
- Diff hygiene for secrets / signing / app id (no Phase 3 docs surface changes expected)

---

## Prior FAIL re-check

| ID | Prior finding | r2 status | Evidence |
|----|---------------|-----------|----------|
| **B1** | `PIXEL_2025_PRELOAD` MED/LOW confidence only in code comment; absent from docs/CHANGELOG | **FIXED** | `CHANGELOG.md` Unreleased **Added**: PRELOAD at **MED/LOW** (historical pairing; not factory-confirmed; may be no-op). EN README feature bullet same honesty. Mirrored in zh-TW / zh-CN / ja feature bullets. Code comment in `DeviceProps.kt` remains consistent. |
| **B2** | zh-TW / zh-CN / ja README leads still Photos-only product framing; omitted recommended scope + multi-app unsupported | **FIXED** | All three translated leads now match EN meaning: spoof props/flags; **Photos = recommended LSPosed scope**; extra scoped apps = advanced/unsupported. Install + troubleshooting remain correctly recommended/risk-aware. |

---

## Checklist (required)

| # | Requirement | Result | Evidence |
|---|-------------|--------|----------|
| 1 | No hard exclusive “Photos only” product rule in primary docs / install / troubleshooting | **PASS** | EN README install #4–5 + troubleshooting use recommended + multi-app risk; SUPPORT / CONTRIBUTING / AGENTS / site EN+locales use recommended wording; no residual hard “scope only to Photos” install rules |
| 2 | Recommended Photos scope + multi-app risk + denylist guidance present | **PASS** | README install recommended + advanced/unsupported extras + soft-denylist; SUPPORT never-scope list + extra apps advanced/unsupported; CONTRIBUTING recommended + multi-app honesty for `site/`; AGENTS `staticScope=false` + soft-denylist; strings EN/zh-TW risk text |
| 3 | Pixel 10 series / experimental Fold+10a honesty | **PASS** | README + all three translated READMEs feature bullets; CHANGELOG Unreleased experimental identity-only Fold (`rango`) / 10a (`stallion`), FP omitted |
| 4 | Default Pixel XL documented | **PASS** | README + zh-TW/zh-CN/ja feature bullets; CHANGELOG “First-open default … **Pixel XL** (was Pixel 5)” |
| 5 | 9a tegu + multi-app `staticScope` in CHANGELOG Unreleased | **PASS** | `tehua` → `tegu` + cited A16 FP; `staticScope=false` + recommended `scope.list` + soft denylist; hooks not hard-gated to Photos |
| 6 | Translations link/align with English meaning (no new product claims) | **PASS** | Language links + “English authoritative” disclaimers; **leads** now recommended-scope + advanced/unsupported; install/troubleshoot/features (incl. PRELOAD MED/LOW, XL default, experimental Fold/10a) aligned |
| 7 | `site/` landing no longer hard-exclusive | **PASS** | EN requirements: recommended + “Extra apps are advanced/unsupported.” Locale pages: 建議/推荐/推奨 + 其他 App 進階且不支援 / 高级且不支持 / 高度／非サポート. Install steps use recommended, not exclusive-only |
| 8 | No secrets / signing material changes | **PASS** | Working tree: no diffs to `certificates/`, `docs/RELEASE_SIGNING.md`, `app/build.gradle.kts`, keystores, or private key material; app id claims unchanged in docs |
| 9 | Phase 3 plan: PRELOAD confidence documented | **PASS** | Documented user-facing in README + three translations + CHANGELOG Unreleased (not code-only). Matches DEFINE_PLAN FEAT-1 / Phase 3 / PR acceptance “PRELOAD documented MED/LOW” |

---

## Non-blocking nits (do not reverse PASS)

### N1 — Site hero still Photos-primary (thinner than README leads)
- EN `site/index.html` tagline: spoofs props/flags **for Google Photos** (no recommended-scope / extra-apps sentence in hero).
- zh-TW / zh-CN / ja heroes: 「針對／为／に対して Google 相簿／相册／フォト」— same marketing framing.
- Install/requirements on all four landings **do** carry recommended + advanced/unsupported. Acceptable for thin landing if README is source of truth; optional later align heroes with README leads.

### N2 — Site features omit Pixel 10 / experimental Fold+10a / default XL / PRELOAD
Still generic “newer Pixel generations.” Optional tighten to match README honesty; not required for Phase 3 docs PASS when README + CHANGELOG carry full honesty.

### N3 — SUPPORT denylist vs module soft-denylist
SUPPORT says never scope Play Services / Play Store / system UI / banking (good) but does not explicitly say the module soft-denylists some packages even if selected (README does). Minor consistency gap only.

### N4 — PUBLICATION_CHECKLIST “Google Photos only”
`scope.list` still listing Google Photos only is **correct** (recommended default list metadata, not product exclusivity). No change required.

### N5 — Site EN title/meta still “for Google Photos”
Marketing packaging; not a hard exclusive install rule. Optional soft-align with recommended-scope framing.

### N6 — Prior nits from r1 that builder addressed (verified)
- zh-TW `spoofs_build_and_features`: 「建議僅勾選」→「建議勾選」— **done**
- site locale multi-app risk clause — **done**

---

## What looks good (r2)

1. **B1 closed:** PRELOAD MED/LOW is honest in CHANGELOG Unreleased, EN README, and all three translated feature lists; matches code comment intent without overselling.
2. **B2 closed:** Translated README **opening paragraphs** track EN product framing (recommended scope + advanced/unsupported extras), not residual Photos-exclusive pitch.
3. **Hard exclusive install/troubleshooting language remains removed** across primary docs and site landings.
4. **Multi-app Option B honesty** intact: recommended Photos, advanced/unsupported extras, soft-denylist / do-not-scope; AGENTS records `staticScope=false`.
5. **Pixel 10 series + experimental Fold/10a**, **default Pixel XL**, **9a tegu**, **staticScope** covered in README family + CHANGELOG.
6. **No signing / app id / secret** doc or tree changes on the Phase 3 docs surface.
7. **In-app strings** EN + zh-TW consistent with recommended (not exclusive-only) scope messaging.
8. Builder r2 self-check matches reviewer findings on B1/B2 (no overclaim residual on those items).

---

## Verdict rationale

Under a strict reading of DEFINE_PLAN Phase 3 + prior FAIL fix list:

- Prior **B1** and **B2** are **fixed** with direct evidence in CHANGELOG, EN README, and zh-TW/zh-CN/ja leads + feature bullets.
- Full required checklist items 1–9 **PASS**.
- Remaining gaps are **non-blocking** marketing-thinness on `site/` heroes/features (N1/N2/N5) and minor SUPPORT wording consistency (N3).

**Phase 3 docs + CHANGELOG: PASS.** Proceed to Phase 4 full verify / final acceptance when ready. Still **no commits** from this review.

---

## Out of scope for this review

- Commits, PR open
- Phase 4 `./gradlew` full gate
- Code/runtime re-audit of Phase 1–2 (DeviceProps / ScopePolicy) beyond docs surface claims
- Enabling GitHub Pages in repository settings

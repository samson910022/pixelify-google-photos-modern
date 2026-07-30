# Phase 2 Builder Report — Full i18n landings

**Date:** 2026-07-30  
**Role:** Builder (Phase 2)  
**Repo:** `pixelify-google-photos-modern`  
**Scope:** Content parity + Phase 1 nits for i18n/content under `site/**` only  
**No commit / push / Pages enable / PR**

---

## Summary

Phase 2 brings EN / zh-TW / zh-CN / ja landing pages to structural and content parity:

1. All four pages use banner intrinsic size **1280×714**.
2. Locale pages now include **FORK_NOTICE** link and **BaltiApps** attribution (matching EN).
3. Locale copy realigned to project docs (`docs/README.zh-TW.md`, `docs/README.zh-CN.md`, `docs/README.ja.md`) for hero, features, requirements, install/signing note, legacy, privacy, and footer.
4. Language switchers, `lang` / `hreflang` / `aria-current`, and relative `../assets/...` paths remain correct.
5. No CSS change required: existing flex-wrap handles long CJK/JA nav labels.
6. No Android code, workflows other than existing pages scaffold, APKs, analytics, or out-of-scope mini-sites touched.

---

## Files changed

| File | Changes |
|------|---------|
| `site/index.html` | Banner `height` 640 → **714** |
| `site/zh-TW/index.html` | Full i18n parity polish vs EN + docs; banner 714; FORK_NOTICE; BaltiApps footer; fuller signing + privacy notes; 模擬 wording from docs |
| `site/zh-CN/index.html` | Same parity polish for Simplified Chinese |
| `site/ja/index.html` | Same parity polish for Japanese; banner 714; FORK_NOTICE; BaltiApps; signing/privacy parity; docs-aligned disclaimer |

**Unchanged (intentionally):**

- `site/assets/**`, `site/404.html` (404 nested-path hardening is Phase 3)
- `.github/workflows/*` (including `pages.yml` SHA-pin nit deferred)
- Android app / signing / secrets / README commit

---

## Section parity checklist (all locales)

| Section | EN | zh-TW | zh-CN | ja |
|---------|----|-------|-------|----|
| Hero + CTAs | ✓ | ✓ | ✓ | ✓ |
| Important disclaimer | ✓ | ✓ | ✓ | ✓ |
| Features (7 items) | ✓ | ✓ | ✓ | ✓ |
| Requirements + modern-API note | ✓ | ✓ | ✓ | ✓ |
| Install steps + RELEASE_SIGNING note | ✓ | ✓ | ✓ | ✓ |
| Official download cards (4) | ✓ | ✓ | ✓ | ✓ |
| Legacy package coexistence | ✓ | ✓ | ✓ | ✓ |
| FORK_NOTICE outbound link | ✓ | ✓ | ✓ | ✓ |
| Privacy + PRIVACY/SECURITY links | ✓ | ✓ | ✓ | ✓ |
| Footer links | ✓ | ✓ | ✓ | ✓ |
| MIT + BaltiApps attribution | ✓ | ✓ | ✓ | ✓ |
| Non-affiliation tiny print | ✓ | ✓ | ✓ | ✓ |

### Technical i18n

| Check | Result |
|-------|--------|
| `lang`: `en` / `zh-Hant` / `zh-Hans` / `ja` | Pass |
| Switcher `hreflang` + single `aria-current="page"` | Pass |
| Locale assets `../assets/...` | Pass |
| EN assets `assets/...` | Pass |
| Banner `width="1280" height="714"` all pages | Pass |
| Official Releases / Xposed / LSPosed URLs only | Pass |
| No APK hosting, no analytics scripts | Pass |

---

## Translation grounding notes

- Prefer **project docs** phrasing over Phase 1 “偽造/伪造” wording → **模擬/模拟** (zh) and docs-aligned **偽装** (ja).
- Install signing notes now state stable release cert + private keys never in repo (docs/EN parity).
- Legacy text matches docs: separate app ID, coexistence, no automatic settings migration.
- **Not** translated onto the landing: long Android 17 ART/VERIFY technical paragraph from README (EN landing also omits it; would overclaim/overcrowd marketing page). English remains authoritative; landing still does not guarantee Android 17 success.

---

## Phase 1 nits status

| Nit | Status |
|-----|--------|
| Banner height 714 | **Fixed** (all 4 pages) |
| Locale FORK_NOTICE + BaltiApps | **Fixed** |
| 404 absolute project paths | **Deferred to Phase 3** (as planned) |
| Workflow action SHA pins | **Deferred** (not Phase 2) |
| Line-by-line translation audit | **Addressed for landing sections** against docs; full native speaker QA still recommended |

---

## Remaining risks

1. **Native QA residual** — Copy is grounded in official docs, but a native speaker (esp. ja) may still prefer stylistic tweaks on nav labels or card titles.
2. **EN ↔ locale card link text** — EN download cards show full GitHub path strings; locales use shorter labels (GitHub Releases / 官方鏡像). Meaning and targets match; visual string parity is not identical.
3. **404 relative URL fragility** on nested missing paths remains (Phase 3).
4. **No visual browser pass in this phase** — structural checks done via file inspection; Phase 4 owns screenshots/desktop+mobile.
5. **Android 17 detail intentionally omitted** on all landings (same as EN). Do not expand landing with that technical block unless product owner asks.
6. **Uncommitted tree** — deliverables remain unstaged/uncommitted per hard rules.

---

## Verification performed

- Confirmed section IDs present on all four pages.
- Confirmed FORK_NOTICE + BaltiApps + RELEASE_SIGNING + private-key notes on all locales.
- Confirmed banner dimensions 1280×714 on EN + three locales.
- Confirmed lang switcher `aria-current` and relative asset roots.
- Confirmed official download URLs only; no APK/analytics additions.
- Did **not** run git commit/push or modify CI workflows.

---

## Ready for

**Phase 2 Reviewer** (strict i18n + content accuracy gate).  
On PASS → Phase 3 harden (404 absolute paths, link audit, a11y).

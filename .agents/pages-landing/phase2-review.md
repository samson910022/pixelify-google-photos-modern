# Phase 2 Review — Full i18n landings (Pixelify Infinity)

**Date:** 2026-07-30  
**Reviewer role:** Independent strict reviewer (did not implement Phase 2)  
**Repo:** `pixelify-google-photos-modern`  
**Scope reviewed:** `site/index.html`, `site/zh-TW/index.html`, `site/zh-CN/index.html`, `site/ja/index.html` vs EN authority, `docs/README.{zh-TW,zh-CN,ja}.md`, root `README.md`, Phase 1 nits, Phase 2 builder report, `PHASE_PLAN.md` Phase 2  
**Out of scope this gate:** browser visual QA (Phase 4), 404 absolute-path hardening (Phase 3)

---

## Verdict: **PASS**

Phase 2 meets acceptance: all locales mirror EN section structure, copy is grounded in project docs without overclaims, Phase 1 i18n nits (banner 714, FORK_NOTICE, BaltiApps) are fixed, language switchers and nested relative assets are correct, and safety/Phase 1 scaffold integrity hold.

---

## Blocking issues (must fix)

*None.*

---

## Non-blocking nits

1. **Download card link label string parity (cosmetic)**  
   - EN cards show long path-style anchor text (e.g. `github.com/…/pixelify-google-photos-modern/releases`).  
   - Locales use shorter labels (`GitHub Releases` / `官方鏡像 Releases` / etc.).  
   - **URLs and meaning match.** Optional polish only if product wants visual string parity.

2. **zh-TW feature bullet synonym vs docs**  
   - Docs: 「模擬**特定** Google Pixel 裝置設定檔」  
   - Landing: 「模擬**選定的** Google Pixel 裝置設定檔」  
   - Same meaning; not an overclaim. Optional align to docs wording.

3. **404 nested-path relative fragility**  
   - Still present in `site/404.html`. Explicitly Phase 3; not a Phase 2 fail.

4. **Workflow action tags not SHA-pinned**  
   - `pages.yml` still uses `@v4` / `@v5` / `@v3` tags (same as Phase 1 nit). Deferred; not Phase 2.

5. **No native-speaker linguistic QA**  
   - Content is docs-aligned and structurally correct. Residual stylistic preference (esp. Japanese nav/card titles) may remain; not a gate blocker.

6. **No `<link rel="alternate" hreflang>` in `<head>`**  
   - In-page switcher is correct and required. Head alternate tags were not required by Phase 2 plan.

---

## Review checklist results

| # | Criterion | Result | Evidence |
|---|-----------|--------|----------|
| 1 | Section parity (all locales = EN) | **PASS** | Shared section IDs on all 4 pages: hero (banner + CTAs + important callout), `#features`, `#requirements`, `#install` (+ signing note), `#download` (4 cards), `#legacy` (+ FORK_NOTICE), `#privacy`, footer |
| 2 | Translations grounded in docs / no overclaims | **PASS** | zh-TW/zh-CN/ja hero, disclaimer, features (7), requirements, install, legacy, privacy, footer align with `docs/README.*.md` + EN; uses 模擬/模拟/偽装 (not 偽造-style overclaim); Android 17 ART technical block correctly **omitted** on all landings (same as EN; no success guarantee) |
| 3 | Language switcher (hrefs, hreflang, aria-current, lang) | **PASS** | `html lang`: `en` / `zh-Hant` / `zh-Hans` / `ja`; each page has exactly one `aria-current="page"`; `hreflang` + per-link `lang` present; relatives correct from each depth |
| 4 | Nested locale asset paths | **PASS** | Locales: `../assets/css/site.css`, `../assets/img/*`; EN: `assets/...` |
| 5 | Banner height fixed to 714 | **PASS** | All four pages: `width="1280" height="714"`; actual PNG is 1280×714 |
| 6 | FORK_NOTICE + BaltiApps attribution | **PASS** | Present on EN + zh-TW + zh-CN + ja (legacy note + footer MIT/derived-from) |
| 7 | No APK hosting / analytics / secrets / Android / commits | **PASS** | No `.apk` under `site/`; no tracker scripts; privacy text only mentions analytics as *absent*; no secrets; `git status` shows `site/` + `pages.yml` untracked (no commit); Android tree not part of Phase 2 deliverables |
| 8 | No breakage of `pages.yml` / Phase 1 safety | **PASS** | `pages.yml` intact (least privilege, `path: site`, path filters, Pages-not-enabled comments); `ci.yml` / `ai-review.yml` untouched |

---

## Section parity matrix

| Section / element | EN | zh-TW | zh-CN | ja |
|-------------------|----|-------|-------|----|
| Hero + primary CTAs (Releases / Xposed / LSPosed) | ✓ | ✓ | ✓ | ✓ |
| Important non-affiliation disclaimer | ✓ | ✓ | ✓ | ✓ |
| Features (7 items) | ✓ | ✓ | ✓ | ✓ |
| Requirements + modern-API / Photos-only note | ✓ | ✓ | ✓ | ✓ |
| Install steps (5) + RELEASE_SIGNING + private-key note | ✓ | ✓ | ✓ | ✓ |
| Official download cards (4) + “no APK hosted here” | ✓ | ✓ | ✓ | ✓ |
| Legacy coexistence (new id + `balti…` + no auto migrate) | ✓ | ✓ | ✓ | ✓ |
| FORK_NOTICE outbound | ✓ | ✓ | ✓ | ✓ |
| Privacy note + PRIVACY/SECURITY links | ✓ | ✓ | ✓ | ✓ |
| Footer links (Source/Releases/Signing/Privacy/Security/MIT) | ✓ | ✓ | ✓ | ✓ |
| MIT + BaltiApps attribution | ✓ | ✓ | ✓ | ✓ |
| Non-affiliation tiny print / trademarks | ✓ | ✓ | ✓ | ✓ |

---

## Meaning / claim audit (vs README + docs)

| Claim area | Status | Notes |
|------------|--------|-------|
| Product name Pixelify Infinity | PASS | All pages |
| Independently maintained; modern libxposed; own package/signing | PASS | Hero on all locales |
| Google Photos–only scope | PASS | Requirements + install steps |
| Package id `io.github.samson910022.pixelifyphotos` | PASS | Legacy section all locales |
| Legacy id `balti.xposed.pixelifygooglephotos`; coexistence; no auto settings migrate | PASS | |
| Official channels only (source Releases, Xposed mirror, LSPosed listing, source repo) | PASS | Same absolute URLs on all 4 pages |
| Site does not host APKs | PASS | Explicit lead on `#download` all locales |
| Signing: stable cert; verify via `docs/RELEASE_SIGNING.md`; private keys never in repo | PASS | Install note all locales |
| No analytics/ad SDKs; network for release metadata + links | PASS | Privacy section |
| Not affiliated with Google / Photos / Pixel / LSPosed / upstream | PASS | Callout + footer |
| Android 17 success not guaranteed / no technical overclaim dump | PASS | Omitted consistently with EN landing |
| BaltiApps derivation | PASS | Footer all locales |

Minor wording deltas (e.g. zh-TW 選定的 vs docs 特定) do **not** change claims.

---

## Language switcher detail

| Page | `html lang` | Active (`aria-current`) | EN href | zh-TW | zh-CN | ja |
|------|-------------|-------------------------|---------|-------|-------|-----|
| `site/index.html` | `en` | EN | `./` | `zh-TW/` | `zh-CN/` | `ja/` |
| `site/zh-TW/index.html` | `zh-Hant` | 繁中 | `../` | `./` | `../zh-CN/` | `../ja/` |
| `site/zh-CN/index.html` | `zh-Hans` | 简中 | `../` | `../zh-TW/` | `./` | `../ja/` |
| `site/ja/index.html` | `ja` | 日本語 | `../` | `../zh-TW/` | `../zh-CN/` | `./` |

All switcher anchors carry matching `hreflang` and `lang`.

---

## Safety / Phase 1 integrity

| Check | Result |
|-------|--------|
| `pages.yml` least privilege + artifact `path: site` | PASS (unchanged, correct) |
| Other workflows not modified by Phase 2 | PASS |
| No APK under `site/` | PASS |
| No analytics/third-party script tags | PASS |
| No secrets / signing material in site | PASS |
| Deliverables uncommitted (hard rule) | PASS — `?? site/`, `?? .github/workflows/pages.yml` |
| Unrelated untracked junk still not under `site/` | PASS (root banner copies / `opencode_model.json` remain outside scope) |

---

## Explicit list of checks performed

1. Read `PHASE_PLAN.md` Phase 2 acceptance and hard rules.  
2. Read Phase 1 review nits and Phase 2 builder report claims.  
3. Full read of EN `site/index.html` as structural/content authority.  
4. Full read of `site/zh-TW/index.html`, `site/zh-CN/index.html`, `site/ja/index.html`.  
5. Compared landing sections to root `README.md` and `docs/README.zh-TW.md` / `zh-CN` / `ja` for meaning parity and overclaim risk.  
6. Verified section ID set identity across all four pages.  
7. Verified banner `height="714"` on all four pages and measured `site/assets/img/banner.png` = 1280×714.  
8. Verified FORK_NOTICE links and BaltiApps footer attribution on all locales.  
9. Verified language switcher hrefs, `hreflang`, `lang`, and single `aria-current` per page.  
10. Verified EN `assets/...` vs locale `../assets/...` for CSS, favicon, icon, banner.  
11. Verified official download URLs only (source Releases, Xposed mirror, LSPosed, source repo) on all pages.  
12. Grep/scan for analytics scripts, APK hosting, tracker patterns under `site/`.  
13. Re-read `.github/workflows/pages.yml`; confirmed `ci.yml` / `ai-review.yml` present and untouched.  
14. `git status` for commit/scope discipline (no Phase 2 commit; Android not implicated).  
15. Confirmed intentional omission of Android 17 long technical paragraph on all landings (parity with EN; avoids overclaim).

**Not performed (by design):** live browser render, mobile/desktop screenshots, full a11y tooling pass, 404 absolute-path fix — owned by later phases.

---

## Gate decision

**Phase 2 PASS.** Proceed to **Phase 3 — Harden** (404 project-absolute paths, link audit, a11y, workflow path-filter review). Do **not** commit/push or enable Pages until Phase 4/5 gates.

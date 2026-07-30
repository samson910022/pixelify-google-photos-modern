# Phase 1 Review — GitHub Pages Landing (Pixelify Infinity)

**Date:** 2026-07-30  
**Reviewer role:** Independent strict reviewer (did not implement)  
**Repo:** `pixelify-google-photos-modern`  
**Scope reviewed:** `site/**`, `.github/workflows/pages.yml`, vs `README.md` + `PHASE_PLAN.md` Phase 1  
**Builder report:** `.agents/pages-landing/phase1-builder-report.md`

---

## Verdict: **PASS**

Phase 1 scaffold meets acceptance criteria. No blocking defects found in correctness, base-path/locale relative URLs, workflow least privilege, safety, or required structure.

---

## Blocking issues (must fix)

*None.*

---

## Non-blocking nits

1. **Banner intrinsic size mismatch (CLS hint only)**  
   - Files: `site/index.html`, `site/zh-TW/index.html`, `site/zh-CN/index.html`, `site/ja/index.html`  
   - Declared: `width="1280" height="640"`  
   - Actual `site/assets/img/banner.png`: **1280×714**  
   - CSS uses `height: auto`, so layout recovers; reserved aspect is slightly wrong before paint.  
   - Fix (optional): set `height="714"` (or matching real aspect) on all banner `<img>` tags.

2. **GitHub Pages 404 relative-URL fragility on nested missing paths**  
   - File: `site/404.html`  
   - `href="./"`, `assets/...`, and locale links are correct when the missing URL is one segment under the project root (e.g. `/pixelify-google-photos-modern/missing`).  
   - For deeper missing URLs (e.g. `/pixelify-google-photos-modern/zh-TW/nope`), the browser resolves relatives against the *requested* path, so CSS/lang links can break. Classic GH Pages behavior.  
   - Home link does **not** incorrectly point at user-site `/` (good).  
   - Hardening belongs in Phase 3; optional now: use root-absolute project paths  
     `/pixelify-google-photos-modern/` and `/pixelify-google-photos-modern/assets/...` **only in `404.html`**.

3. **Locale structural parity gaps vs EN (content polish, not scaffold break)**  
   - EN `#legacy` links to `FORK_NOTICE.md`; `zh-TW` / `zh-CN` / `ja` omit that outbound link.  
   - EN footer credits BaltiApps upstream derivation; locale footers only state MIT + non-affiliation.  
   - Phase 2 can align these without blocking Phase 1 (shells already exceed minimum).

4. **Workflow action pins**  
   - File: `.github/workflows/pages.yml`  
   - Uses movable tags (`actions/checkout@v4`, `configure-pages@v5`, `upload-pages-artifact@v3`, `deploy-pages@v4`) while `ci.yml` pins SHAs.  
   - Acceptable for Phase 1; consider SHA-pinning later for supply-chain consistency.

5. **Translation fidelity not audited**  
   - Locales are full mirrored landings (beyond bare shells). Wording was not re-checked line-by-line against `docs/README.zh-TW.md` / `zh-CN` / `ja` — that is Phase 2 scope. No English-only critical legal omission: disclaimers are present and localized on all three.

---

## Review axes

| # | Axis | Result | Notes |
|---|------|--------|-------|
| 1 | Correctness | **PASS** | EN matches README product claims; Google Photos–only scope; package id `io.github.samson910022.pixelifyphotos`; legacy id `balti.xposed.pixelifygooglephotos`; official Releases / Xposed repo / LSPosed listing only; no overclaims (no guaranteed Android 17 success, etc.) |
| 2 | Paths / base-path | **PASS** | EN uses `assets/...`; locales correctly use `../assets/...`; switcher relatives correct from each depth; safe under project base `/pixelify-google-photos-modern/` |
| 3 | i18n shells | **PASS** | `lang`: `en`, `zh-Hant`, `zh-Hans`, `ja`; switcher EN/繁中/简中/日本語 on all pages; `aria-current="page"` on active lang only |
| 4 | Workflow | **PASS** | `contents: read`, `pages: write`, `id-token: write`; artifact `path: site`; `workflow_dispatch` + push path filters; comments that Pages is not enabled; no secrets; does not alter `ci.yml` / `ai-review.yml` |
| 5 | Safety | **PASS** | No APKs under `site/`; no analytics/trackers/CDN scripts; no secrets; Android app tree untouched |
| 6 | A11y / HTML quality | **PASS** | Skip link, semantic landmarks, section labels, focus-visible styles, banner/decorative alts, contrast-oriented dark theme, `prefers-reduced-motion` |
| 7 | Scope discipline | **PASS** | Deliverables limited to `site/**` + `.github/workflows/pages.yml` (+ agent notes under `.agents/`); no unrelated required edits |
| 8 | 404 | **PASS** (with nit #2) | Simple back-home via `./` (project-relative, not site-root `/`); states no APK hosting; nested-path relative resolution is a known GH Pages limitation |

---

## Phase 1 acceptance checklist

| Criterion | Status |
|-----------|--------|
| Only intended files under `site/` + `pages.yml` | **PASS** — tree is exactly: `404.html`, `index.html`, `zh-TW/index.html`, `zh-CN/index.html`, `ja/index.html`, `assets/css/site.css`, `assets/img/{banner,favicon,icon,icon-64}.png` + workflow |
| EN landing complete and accurate vs README (no overclaim) | **PASS** — name, disclaimer, features, requirements, install (Photos-only), CTAs, legacy coexistence, signing doc link, privacy, footer MIT/disclaimer |
| Relative paths / base-path safe | **PASS** — verified EN + all locale asset/switcher relatives |
| Workflow least-privilege and correct artifact root | **PASS** — minimal perms; `path: site` |
| No APK hosting, no analytics, no signing material | **PASS** |
| Mobile-first CSS present | **PASS** — dark system-font CSS, single-column first, `@media` enhancements |
| Lang shells exist with correct `lang` and switcher | **PASS** — full shells with working switchers |
| No unrelated repo modifications | **PASS** — `git status` shows new `site/` + `pages.yml` only among deliverables; no app/code workflow regressions |

### Content sub-checklist (EN)

| Item | Status |
|------|--------|
| Product name Pixelify Infinity | PASS |
| Strong non-affiliation disclaimer near top | PASS (`callout-important`) |
| Features / Requirements / Install (Google Photos only) | PASS |
| CTAs: GitHub Releases, Xposed Modules Repo, LSPosed, source | PASS |
| Legacy coexistence note (new vs `balti.xposed.pixelifygooglephotos`) | PASS |
| Link to `docs/RELEASE_SIGNING.md` on GitHub | PASS |
| Language switcher all 4 langs | PASS |
| Footer MIT + not affiliated + Privacy/Security outbound | PASS |
| Relative asset paths | PASS |
| Semantic HTML5 / skip-link / focus / alt | PASS |

### Common-bug hunt

| Bug | Found? |
|-----|--------|
| Locale CSS as `assets/...` instead of `../assets/...` | **No** |
| Broken locale switcher relatives | **No** |
| 404 home → wrong root (`/`) | **No** (uses `./`) |
| Missing disclaimer prominence | **No** |
| Workflow deploy without proper permissions/environment | **No** |
| Banner height mismatch harmful | **Minor only** (nit #1) |
| English-only critical legal text missing on locales | **No** (localized disclaimers present) |

---

## Surgical fix instructions (only if builder chooses to clear nits)

Not required for Phase 1 gate. Optional:

1. **Banner dimensions** — in all four `index.html` files, change banner img to `height="714"` (keep `width="1280"`).  
2. **404 robustness (optional Phase 1 / else Phase 3)** — in `site/404.html` only, replace relative home/assets/lang hrefs with project-absolute:
   - Home: `/pixelify-google-photos-modern/`
   - CSS/icon: `/pixelify-google-photos-modern/assets/...`
   - Langs: `/pixelify-google-photos-modern/`, `.../zh-TW/`, `.../zh-CN/`, `.../ja/`  
3. **Parity** — add FORK_NOTICE + BaltiApps attribution lines to locale pages when doing Phase 2 i18n polish.

**Do not** modify Android sources, enable Pages settings, commit/push, or host APKs.

---

## Evidence summary

- Compared EN copy to root `README.md` (features, requirements, install, package ids, official URLs, disclaimer, privacy).  
- Manually resolved all relative `href`/`src` on EN, zh-TW, zh-CN, ja, 404.  
- Confirmed `site/` file set; no `.apk` / tracker scripts.  
- Read `.github/workflows/pages.yml` end-to-end; confirmed other workflows untouched.  
- `git status`: deliverables untracked as expected; no dirty Android tree from this phase.

**Gate decision:** Phase 1 may proceed to Phase 2.

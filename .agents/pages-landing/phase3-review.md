# Phase 3 Review — Harden (Pixelify Infinity GitHub Pages landing)

**Date:** 2026-07-30  
**Reviewer role:** Independent Phase 3 reviewer (no implementation, no commit, no Phase 4)  
**Repo:** `/home/samson1357924/projects/pixelify-google-photos-modern`  
**Scope:** Link audit, a11y harden, 404 project-base absolute paths, workflow path filters + action pins, safety (no APK / no analytics)

---

## Verdict: PASS

Phase 3 harden criteria are met. Language switchers resolve correctly in both directions, `404.html` uses project-root absolute paths under `/pixelify-google-photos-modern/`, workflow least-privilege + path filters + 40-hex action SHAs look correct, a11y basics (skip-link, banner alts, focus styles) are present, and there is no APK hosting or analytics scripting under `site/`.

---

## Blocking issues

None.

---

## Non-blocking nits

1. **404 is English-only**  
   Locale switcher on 404 points to translated homes, but the 404 body copy itself is EN-only. Acceptable for Phase 3; optional i18n later if desired.

2. **404 paths are production-base oriented**  
   Absolute `/pixelify-google-photos-modern/...` links will not resolve when serving `site/` at bare `/` locally. This is intentional for GitHub project Pages nested 404 behavior. Phase 4 should preview under the project base mount.

3. **Action SHAs format-validated, not re-resolved live**  
   All four `actions/*@<sha>` pins are 40-char lowercase hex with version comments. This review did not re-run `git ls-remote` against upstream action repos to reconfirm tag→SHA mapping on this pass (builder report claims 2026-07-30 resolution). Re-resolve only when intentionally upgrading.

4. **No `<link rel="alternate" hreflang>` head tags**  
   In-page language switchers are correct and sufficient for the plan. Head alternates remain optional SEO polish, not a Phase 3 gate.

5. **External URL liveness not re-HEADed by reviewer**  
   Internal filesystem resolution of all `href`/`src` under `site/**/*.html` was re-checked (0 misses). Outbound GitHub/LSPosed URLs were inspected as present and correct against prior phase authority; live HTTP HEAD was not re-run in this review pass.

6. **Brand icons use empty `alt=""`**  
   Correct decorative treatment next to visible text brand. Not an issue; listed only for completeness.

---

## Checks performed

- Read `.agents/pages-landing/PHASE_PLAN.md` (Phase 3 Harden section) and `.agents/pages-landing/phase3-builder-report.md`
- Inspected HTML:
  - `site/index.html`
  - `site/zh-TW/index.html`
  - `site/zh-CN/index.html`
  - `site/ja/index.html`
  - `site/404.html`
- Inspected CSS: `site/assets/css/site.css` (skip-link, `:focus-visible`, footer/card focus rings)
- Inspected workflow: `.github/workflows/pages.yml`
- Language switcher matrix (relative hrefs + `aria-current` + `hreflang`/`lang`):
  - EN → `./`, `zh-TW/`, `zh-CN/`, `ja/` (current EN)
  - zh-TW → `../`, `./`, `../zh-CN/`, `../ja/` (current 繁中)
  - zh-CN → `../`, `../zh-TW/`, `./`, `../ja/` (current 简中)
  - ja → `../`, `../zh-TW/`, `../zh-CN/`, `./` (current 日本語)
  - 404 → absolute `/pixelify-google-photos-modern/`, `/…/zh-TW/`, `/…/zh-CN/`, `/…/ja/`
- 404 absolute path audit: CSS, favicon, icon, brand home, back-home CTA, lang links all under `/pixelify-google-photos-modern/`; comment documents nested-404 rationale; `noindex` present
- Workflow checks:
  - `permissions`: `contents: read`, `pages: write`, `id-token: write`
  - path filters on `push` to `master`: `site/**`, `.github/workflows/pages.yml` + `workflow_dispatch`
  - artifact `path: site`
  - no secrets / no `${{ secrets.* }}`
  - actions pinned to 40-hex SHAs:
    - `actions/checkout@11d5960a326750d5838078e36cf38b85af677262`
    - `actions/configure-pages@983d7736d9b0ae728b81ab479565c72886d7745b`
    - `actions/upload-pages-artifact@56afc609e74202658d3ffba0e8f6dda462b719fa`
    - `actions/deploy-pages@d6db90164ac5ed86f2b6aed7e0febac5b3c0c03e`
  - Pages-not-enabled comments retained
- Safety:
  - `find site -iname '*.apk'` → none
  - no `<script>` tags on any of the 5 HTML pages
  - no analytics/tracker script markers (`gtag`, GTM, plausible, etc.)
  - download CTAs point only to GitHub Releases / Xposed mirror / LSPosed listing / source repo
  - explicit “does not host APK” copy on EN download lead + 404 note
- A11y:
  - `.skip-link` present on all 5 pages; CSS shows on `:focus` / `:focus-visible`
  - `main id="main"` present on all 5 pages
  - hero banner descriptive alts on EN/zh-TW/zh-CN/ja
  - global + component `:focus-visible` outline styles in CSS
- Cheap Phase 2 nit follow-ups (from builder report) spot-checked:
  - EN download card labels shortened (parity polish)
  - zh-TW uses 「特定」 in meta / tagline / first feature bullet
- Internal link/asset filesystem resolve across all HTML `href`/`src` (relative + project-absolute) → **0 missing targets**
- Confirmed assets exist: `site/assets/css/site.css`, `banner.png`, `favicon.png`, `icon-64.png`, `icon.png`
- Did **not** implement fixes, commit, push, enable Pages, or start Phase 4 visual acceptance

---

## Gate decision

**Phase 3: PASS.** Ready for Phase 4 (local static server under project base path + visual screenshots desktop/mobile for all 4 langs + 404 nested-path check). Still no commit/push/enable-Pages until later gates.

# GitHub Pages Landing — Phased Delivery Plan

**Repo:** `~/projects/pixelify-google-photos-modern`  
**Scope:** Landing pages ONLY (no privacy/security/verify sub-sites, no docs portal)  
**Languages v1:** EN, zh-TW, zh-CN, ja  
**Stack:** Static HTML/CSS under `site/` + GitHub Actions workflow  
**Base URL (future):** `https://samson910022.github.io/pixelify-google-photos-modern/`  
**Hard rules:**
- Do NOT `git push`, do NOT enable GitHub Pages in repo settings
- Do NOT commit until Phase 5 final acceptance passes
- Do NOT touch Android app code, signing, secrets, `.env`, private keys
- Do NOT commit unrelated untracked junk (root banner copies, opencode_model.json, etc.)
- Do NOT host APK files on Pages; link to official Releases / Xposed only
- Do NOT use existing `docs/` as Pages root
- English content authoritative; translations are translations
- No analytics / trackers
- Preserve unrelated working tree changes

## Models
- **Reviewer:** always `cpa/grok-4.5`
- **Builder:** prefer `cpa/grok-4.5` for HTML/CSS quality; `opencode/deepseek-v4-flash-free` OK for pure-text content drafts

## Gate protocol
Each phase: Builder implements → Reviewer strict review → if FAIL, Builder fixes → Review again until PASS → only then next phase.  
Final phase includes visual acceptance (browser screenshots).  
Only after final PASS: create branch, commit site/workflow only, open PR. Still no push of enable-Pages settings if not needed (PR may include workflow only).

---

## Phase 1 — Scaffold (current)
**Builder deliverables:**
1. `site/` tree:
   - `index.html` — complete EN landing
   - `zh-TW/index.html`, `zh-CN/index.html`, `ja/index.html` — language shells with working nav to EN (full i18n content can be Phase 2 if timeboxed; prefer at least placeholder structure + correct lang attrs)
   - `assets/css/site.css` — dark, mobile-first, product landing
   - `assets/img/` — copy needed branding assets from `branding/` (banner, icon) into site assets (do not rely on `../branding` after deploy)
   - `404.html` — simple back-home
2. `.github/workflows/pages.yml`:
   - trigger on push to master paths `site/**` and workflow file, plus `workflow_dispatch`
   - permissions minimal: `contents: read`, `pages: write`, `id-token: write`
   - upload `site/` as Pages artifact (or configure base path correctly)
   - MUST document that repo Settings → Pages is NOT enabled yet; workflow is prepared only
3. Content requirements for EN landing:
   - Product name: Pixelify Infinity
   - Banner/hero, clear non-affiliation disclaimer (IMPORTANT)
   - Features, Requirements, Install steps (scope = Google Photos only)
   - CTAs: GitHub Releases, Xposed Modules Repo / lsposed listing, source repo
   - Brief legacy package coexistence note (`io.github.samson910022.pixelifyphotos` vs legacy)
   - Link to README signing verification / RELEASE_SIGNING on GitHub (external repo links OK; no full verify mini-site)
   - Language switcher links for all 4 langs
   - Footer: MIT, not affiliated, links to Privacy/Security on GitHub raw/blob (OK as outbound links)
4. Technical:
   - All asset URLs work with base path `/pixelify-google-photos-modern/` (use relative URLs preferred)
   - No build framework
   - Valid semantic HTML, reasonable a11y (contrast, focus, alt text)
   - No secrets

**Phase 1 acceptance (Reviewer):**
- [ ] Only intended files under `site/` + `pages.yml`
- [ ] EN landing complete and accurate vs README (no overclaim)
- [ ] Relative paths / base-path safe
- [ ] Workflow least-privilege and correct artifact root
- [ ] No APK hosting, no analytics, no signing material
- [ ] Mobile-first CSS present
- [ ] Lang shells exist with correct `lang` and switcher
- [ ] No unrelated repo modifications

---

## Phase 2 — Full i18n landings
Complete zh-TW / zh-CN / ja landings aligned to EN structure; switcher active on all pages; translation quality check against `docs/README.zh-TW.md`, `docs/README.zh-CN.md`, `docs/README.ja.md`.

## Phase 3 — Harden
Link audit, a11y pass, 404, workflow path filters, optional README website note as unstaged doc tweak only if approved in phase (prefer leave README for PR phase).

## Phase 4 — Final acceptance + visual review
Orchestrator + reviewer: local static server, browser screenshots all 4 langs desktop+mobile widths, content accuracy, gate all previous criteria.

## Phase 5 — Branch + commit + PR
Only after Phase 4 PASS. Branch name e.g. `feat/github-pages-landing`. Commit only `site/**` and `.github/workflows/pages.yml`. Open PR. Do not enable Pages setting unless user asks.

## Out of scope
Privacy/Security/Verify dedicated site pages, docs portal, Jekyll/Docusaurus, custom domain, hosting APKs, enabling Pages in GitHub UI.

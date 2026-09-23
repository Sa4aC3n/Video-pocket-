# Login Required Analysis & False-Positive Elimination — Video Pocket

This document analyzes the distinction between true authentication requirements and false `LOGIN_REQUIRED` classifications across the Video Pocket media engine.

---

## 1. Definition of True LOGIN_REQUIRED

`LOGIN_REQUIRED` is an evidence-based terminal error classification indicating that the target media is genuinely gated behind an active user session or authentication barrier:
- The server responds with HTTP 401 Unauthorized or an explicit redirect to an authentication portal (`/login`, `/signin`, `accounts.google.com`, etc.).
- The platform's payload explicitly indicates `login_required: true`, `require_auth: true`, or `member_only: true`.
- The media is posted on a private account where visibility is strictly restricted to approved followers.

---

## 2. Fundamental Distinctions

### A. HTTP 403 Forbidden vs. Authentication Requirement
- **HTTP 403 Forbidden** does NOT imply authentication is required.
- In modern web infrastructure, HTTP 403 is frequently emitted by CDNs (Cloudflare, Fastly, Akamai, CloudFront) due to:
  - Missing or mismatched User-Agent headers.
  - Geo-blocking / territorial licensing restrictions.
  - Expired signed media URLs or token query parameters.
  - Automated bot traffic scoring.
  - TLS fingerprinting or IP reputation thresholds.
- **Rule:** A bare HTTP 403 response without authentication headers or login redirection MUST NOT be classified as `LOGIN_REQUIRED`. It must be classified as `RATE_LIMITED`, `GEO_RESTRICTED`, `ANTI_BOT_CHALLENGE`, or `TEMPORARILY_UNAVAILABLE`.

### B. Anti-Bot Challenge vs. Authentication
- Anti-bot challenges (Cloudflare Turnstile, CAPTCHA, PerimeterX, Datadome, Kasada) test whether the client is an automated agent or a browser.
- Solving an anti-bot challenge does not require a user account or user credentials; it requires human interactive proof or browser fingerprinting.
- Confusing anti-bot protection with `LOGIN_REQUIRED` misleads users into believing their personal login would grant access.
- **Rule:** When challenge HTML, captcha tokens, or anti-scraping block signatures are detected, the error must be classified as `ANTI_BOT_CHALLENGE` (or `RATE_LIMITED`), NOT `LOGIN_REQUIRED`.

### C. Private Media vs. Removed / Public Media
- **Private Media (`PRIVATE_MEDIA`):** Content uploaded by an account configured as "Private" on platforms such as Instagram or TikTok. The media exists but access is restricted to approved followers.
- **Removed Media (`MEDIA_NOT_FOUND`):** The media has been deleted by the author, removed due to policy violations, or the URL identifier is invalid.
- **Public Media:** Openly viewable by any unauthenticated visitor in an incognito browser window.

---

## 3. Fallback Sequence Before Final Classification

To prevent false-positive failures, Video Pocket executes a deterministic, multi-strategy resolution pipeline before determining any terminal error:

```
Target URL
   │
   ▼
1. Dedicated Extractor (Provider-Specific Parser)
   ├─► Success: Return Normalized Metadata
   └─► Failure: Record Attempt (duration, error type), continue
   │
   ▼
2. Canonical URL Expansion (Headless Redirect Resolution)
   ├─► Success (Destination Changed): Retry Dedicated Extractor
   └─► No Change / Fails: continue
   │
   ▼
3. Safe Public Metadata Fallback (OpenGraph, HTML5 <video>, JSON-LD)
   ├─► Success: Construct Minimal Normalized Metadata with verified streams
   └─► Failure: continue
   │
   ▼
4. Generic Web Extractor (yt-dlp Universal Fallback)
   ├─► Success: Return Normalized Metadata
   └─► Failure: Terminal Evaluation
   │
   ▼
5. Evidence-Based Terminal Classification
   Evaluate all failed attempts:
   - Was there explicit auth evidence? ──► LOGIN_REQUIRED
   - Was there private account evidence? ──► PRIVATE_MEDIA
   - Was there anti-bot/challenge? ─────► ANTI_BOT_CHALLENGE
   - Was it 404/expired? ───────────────► MEDIA_NOT_FOUND
   - Did extractors fail parsing? ──────► EXTRACTOR_OUTDATED / PARSER_FAILURE
```

---

## 4. Final Classification Rules

| Evidence Observed Across Attempts | Final Error Type | Classification | User Guidance (En / Ar) |
|---|---|---|---|
| HTTP 401 or redirect to `/login` | `LOGIN_REQUIRED` | `LOGIN_REQUIRED` | "This media requires signing in to the source service." / "هذا المحتوى يتطلب تسجيل الدخول إلى الخدمة المصدر." |
| Private profile / followers-only | `PRIVATE_MEDIA` | `PRIVATE_MEDIA` | "This content is from a private account." / "هذا المحتوى خاص بحساب مقيد." |
| CAPTCHA / Cloudflare Challenge / 429 | `ANTI_BOT_CHALLENGE` | `RATE_LIMITED` | "The source temporarily blocked automated access. Try again later." / "المصدر أوقف الوصول التلقائي مؤقتاً. حاول مرة أخرى لاحقاً." |
| HTTP 404, 410, or "Video unavailable" | `MEDIA_NOT_FOUND` | `PUBLIC_MEDIA_UNAVAILABLE` | "The requested media was not found or was removed." / "الوسائط المطلوبة غير موجودة أو تم حذفها." |
| Regex / JSON structural mismatch | `PARSER_FAILURE` | `EXTRACTOR_OUTDATED` | "The platform parser requires an update to handle changes on this page." / "مستخرج المنصة بحاجة إلى تحديث لمواكبة تغييرات الصفحة." |
| DRM protection (Widevine, FairPlay) | `DRM_PROTECTED` | `DRM_PROTECTED` | "This media is protected by DRM and cannot be downloaded." / "هذا المحتوى محمي بنظام إدارة الحقوق الرقمية (DRM) ولا يمكن تنزيله." |
| Network drop / Socket timeout | `NETWORK_ERROR` / `TIMEOUT` | `TEMPORARILY_UNAVAILABLE` | "Network connection failed or timed out." / "تعذر الاتصال بالشبكة أو انتهت مهلة الاتصال." |

---

## 5. Provider-Specific Observations

1. **Instagram (`instagram`):**
   - Unauthenticated web requests to `instagram.com/p/...` or `/reel/...` often receive a login modal in HTML, but the OpenGraph `<meta property="og:video">` tag or JSON embedded payload `<script type="application/ld+json">` frequently contains the direct public CDN video URL for public reels.
   - Falling back to safe public OpenGraph inspection rescues public Reels that yt-dlp marked as "Login Required".

2. **Reddit (`reddit`):**
   - NSFW posts or subreddits gate content behind a prompt. Unauthenticated scraping returns HTTP 403 or an age-gate interstitial.
   - Public non-NSFW Reddit posts resolve directly via JSON API endpoint (`.json`) or OpenGraph without requiring user accounts.

3. **Vimeo (`vimeo`):**
   - Unlisted or password-protected videos return HTTP 403 or require a password token in the URL.
   - Public videos are resolved without login. If a password is required, it is classified as `PRIVATE_MEDIA` rather than `LOGIN_REQUIRED`.

4. **TikTok (`tiktok`):**
   - Desktop and mobile links frequently trigger anti-bot slide verifications if accessed repeatedly from data center IP blocks.
   - Classified as `ANTI_BOT_CHALLENGE` or `RATE_LIMITED`, never `LOGIN_REQUIRED`.

---

## 6. Regression Test Suite

Deterministic unit tests implemented in `SmartPublicResolutionRegressionTest.kt`:
- **Case A (`testCaseA_DedicatedStrategyFailsWithParserError_FallbackSucceeds`):** Proves parser errors on primary strategy fall back to OpenGraph and succeed without returning `LOGIN_REQUIRED`.
- **Case B (`testCaseB_DedicatedStrategyReceivesHttp403_FallbackSucceeds`):** Proves an HTTP 403 on the dedicated strategy falls back safely and recovers public media.
- **Case C (`testCaseC_DedicatedStrategyFailsBecauseUrlIsExpired_YieldsNotFound`):** Proves expired links return `MEDIA_NOT_FOUND`, not `LOGIN_REQUIRED`.
- **Case D (`testCaseD_AntiBotChallengeDetected_YieldsRateLimitedNotLoginRequired`):** Proves anti-bot challenges return `ANTI_BOT_CHALLENGE` / `RATE_LIMITED`.
- **Case E (`testCaseE_ActualAuthenticationRequired_YieldsLoginRequired`):** Proves genuine 401/login-required states return `LOGIN_REQUIRED`.
- **Case F (`testCaseF_PrivateMediaAccount_YieldsPrivateMediaClassification`):** Proves private profiles return `PRIVATE_MEDIA`.
- **Case G (`testCaseG_AllStrategiesFailParser_YieldsExtractorFailureNotLoginRequired`):** Proves complete parser failure across all strategies yields `PARSER_FAILURE` / `EXTRACTOR_OUTDATED`, not `LOGIN_REQUIRED`.

---

## 7. Unresolved Limitations & Boundary Safeguards

- **No Authentication Bypass:** Video Pocket strictly honors copyright, platform terms of service, and access controls. It does NOT bypass DRM, does NOT crack passwords, does NOT harvest credentials, and does NOT utilize session hijacking or browser cookie theft.
- **Purely Public Media:** If media cannot be accessed by an anonymous public visitor, Video Pocket reports the truthful restriction to the user.

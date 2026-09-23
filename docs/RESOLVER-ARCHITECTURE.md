# Resolver Architecture & Operational Pipeline — Video Pocket

This document details the deterministic, multi-strategy resolution architecture, lifecycle, and data flow of Video Pocket.

---

## 1. High-Level Resolution Pipeline

```
                               Target URL
                                   │
                                   ▼
                       ┌────────────────────────┐
                       │      LinkPolicy        │  (Validate scheme, reject credentials,
                       │   & URL Sanitizer      │   strip ad/tracking parameters)
                       └───────────┬────────────┘
                                   │
                                   ▼
                       ┌────────────────────────┐
                       │    ProviderRegistry    │  (Route to dedicated provider by domain;
                       │   Provider Selection   │   GenericProvider is strictly LAST)
                       └───────────┬────────────┘
                                   │
                                   ▼
                    ┌──────────────────────────────┐
                    │ Multi-Strategy Orchestration │
                    │     (Isolated Execution)     │
                    └──────────────┬───────────────┘
                                   │
             ┌─────────────────────┼─────────────────────┐
             │                     │                     │
             ▼                     ▼                     ▼
     Strategy 1:            Strategy 2:           Strategy 3:
 ┌──────────────────────┐ ┌──────────────────┐ ┌──────────────────────┐
 │ Dedicated Extractor  │ │ Canonical URL    │ │ Safe Public HTML     │
 │ (Provider native/    │ │ Expansion        │ │ Fallback (OpenGraph, │
 │  yt-dlp core)        │ │ (Headless short  │ │  HTML5 <video>,      │
 └───────────┬──────────┘ │  link follow)    │ │  JSON-LD)            │
             │            └────────┬─────────┘ └──────────┬───────────┘
             │                     │                      │
             └─────────────────────┼──────────────────────┘
                                   │ (if all fail)
                                   ▼
                            Strategy 4:
                       ┌──────────────────────┐
                       │ Generic Provider     │
                       │ Fallback             │
                       └───────────┬──────────┘
                                   │
             ┌─────────────────────┴─────────────────────┐
             │                                           │
             ▼                                           ▼
      [ All Succeeded ]                           [ All Failed ]
┌───────────────────────────┐               ┌───────────────────────────┐
│    NormalizedMetadata     │               │       ResolverError       │
│  - Real variants only     │               │  - Evidence-based type    │
│  - Heights: e.g. 1080/720 │               │  - Multi-attempt trace    │
│  - True durations & sizes │               │  - Localized messages     │
│  - ResolutionTrace        │               └─────────────┬─────────────┘
└─────────────┬─────────────┘                             │
              │                                           ▼
              │                                   ProviderHealthManager
              ▼                                  (Mark DEGRADED / BROKEN
    Download Engine & Processing                  only on verified defects;
  - MediaStore / Pocket Library                   isolated per provider)
```

---

## 2. Multi-Strategy Ordering & Responsibilities

1. **Strategy 1: DEDICATED_EXTRACTOR**
   - The registered provider executes its specialized extractor (using native public parsers and bundled `yt-dlp`).
   - If successful, execution immediately stops and returns `NormalizedMetadata`.
   - If it encounters an error, the attempt is logged in `ResolutionTrace` and control passes to the next strategy.

2. **Strategy 2: CANONICAL_EXPANSION**
   - Resolves redirects for shortened URLs (e.g., `youtu.be`, `vm.tiktok.com`, `t.co`, `pin.it`, `fb.watch`).
   - Headless HTTP HEAD/GET request with strict redirect limit (max 5), enforcing no cookie propagation.
   - If the canonical URL changes, Strategy 1 is re-attempted with the target URL.

3. **Strategy 3: PUBLIC_HTML_OPENGRAPH**
   - Lightweight public page inspection without heavy binaries.
   - Extracts standard OpenGraph tags (`og:video`, `og:video:secure_url`, `og:image`, `og:title`), HTML5 `<video src="...">`, and Schema.org JSON-LD microdata.
   - Rescues public reels and video posts blocked by extractor format churn.

4. **Strategy 4: GENERIC_FALLBACK**
   - Invokes `GenericProvider` as an absolute terminal fallback.
   - `GenericProvider` is guaranteed to execute ONLY after dedicated providers have exhausted specialized strategies.

---

## 3. Strict Provider Priority & Invariants

- **GenericProvider Priority:** Under no circumstances may `GenericProvider` claim or preempt a URL from any registered dedicated provider (`tiktok`, `instagram`, `facebook`, `twitter`, `reddit`, `vimeo`, `dailymotion`, `bilibili`, `soundcloud`, `tumblr`, `snapchat`, `pinterest`, `ted`, `twitch`).
- **Priority Verification:** Unit tests in `ProviderContractTest.kt` enforce that each dedicated provider domain routes exclusively to its designated provider.

---

## 4. Failure Isolation & Structured Concurrency

- **No App Crashes:** Every provider call is encapsulated in `try-catch` blocks within Kotlin Coroutines (`Dispatchers.IO`).
- **Cancellation Propagation:** Coroutine `CancellationException` is explicitly rethrown, allowing users to cancel link inspection or downloading immediately without lingering background tasks.
- **Queue Protection:** A failure in one URL inspection or download job never affects or pauses other concurrent jobs in `DownloadQueueManager`.

---

## 5. Lazy Engine Initialization & Cold-Start Protection

To eliminate startup lag and system log warnings (`E/audit: rate limit exceeded in kauditd`):
1. **Zero Heavy Work on App Launch:** Launching `MainActivity` does NOT invoke `yt-dlp`, does NOT execute `FFmpeg`, and does NOT perform binary checks.
2. **On-Demand Initialization:** Engine initialization (`ensureEngineReady()`) occurs lazily only when the user submits a URL for inspection or starts a download.
3. **Thread-Safe Caching:** `ProbeEngine.cachedRuntime` caches the initialized environment in memory. Concurrent requests are serialized using a Mutex to avoid redundant initializations.
4. **Idempotent Binary Management:** Binaries in `app/src/main/res/raw/` are copied only when missing or corrupt, avoiding filesystem churn on every launch.

---

## 6. Truthful Media Formats & Presets

- **No Static/Fake Qualities:** `ProbeEngine` and providers derive video qualities dynamically from actual resolved streams. If a video only provides 360p and 720p, the UI only presents 360p and 720p (never fabricating 480p, 1080p, 1440p, or 4K).
- **Dynamic Quality Presets:**
  - `Best Available`: Chooses the variant with the maximum height present in the stream.
  - `Balanced`: Selects the highest variant between 720p and 1080p, falling back to Best Available.
  - `Smallest File`: Chooses the lowest non-zero resolution variant.
  - `Best Audio`: Chooses the audio stream with the highest bitrate.
- **Truthful Playlist Handling:** Playlist entries are parsed individually; entries without specific variants report actual capabilities rather than assuming uniform availability.
- **No Fabricated Filesizes:** If the stream does not declare `Content-Length` or `filesize`, the UI displays "Unknown size" rather than guessing.

---

## 7. Diagnostics & Privacy Boundaries

- **ResolutionTrace:** Records `strategy`, `providerId`, `success`, `errorType`, and `durationMs` for internal diagnostic logging and health computation.
- **Privacy Wall:** Diagnostics NEVER log or display:
  - Authorization tokens
  - Cookies or session data
  - Full signed CDN tokens
  - Private filesystem paths
  - Raw exception stack traces
- **Security Boundaries:** Zero DRM circumvention, zero credential storage, zero paid API scraping.

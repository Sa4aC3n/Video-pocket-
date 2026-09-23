# Provider Capability Matrix — Video Pocket

This document specifies the capabilities, validation status, engine dependencies, and fallback behaviors for all supported media providers in Video Pocket.

> **Environment Notice:** Testing conducted in Linux x86_64 host container environment with local JVM / Robolectric.  
> **Physical Device Status:** PHYSICAL DEVICE VALIDATION: NOT EXECUTED.

| Provider Name | Provider ID | Registered | Contract Tested | Live Resolution | E2E Download | Clip | Last Test | Final Classification | Fallback Behavior |
|---|---|---|---|---|---|---|---|---|---|
| **TikTok** | `tiktok` | Yes | Yes | BLOCKED BY SOURCE | NOT TESTED | NOT TESTED | 2026-09-23 | **BLOCKED BY SOURCE** | Fallback to `generic` on failure |
| **Instagram** | `instagram` | Yes | Yes | PARTIAL (OpenGraph) | NOT TESTED | NOT TESTED | 2026-09-23 | **DEGRADED** | Fallback to `generic` on failure |
| **Facebook** | `facebook` | Yes | Yes | BLOCKED BY SOURCE | NOT TESTED | NOT TESTED | 2026-09-23 | **DEGRADED** | Fallback to `generic` on failure |
| **X / Twitter** | `twitter` | Yes | Yes | PASS | PASS | PASS | 2026-09-23 | **LIVE VERIFIED** | Fallback to `generic` on failure |
| **Reddit** | `reddit` | Yes | Yes | PASS (Public/JSON) | NOT TESTED | NOT TESTED | 2026-09-23 | **PARTIALLY VERIFIED** | Fallback to `generic` on failure |
| **Vimeo** | `vimeo` | Yes | Yes | PASS (Public) | NOT TESTED | NOT TESTED | 2026-09-23 | **PARTIALLY VERIFIED** | Fallback to `generic` on failure |
| **Dailymotion** | `dailymotion` | Yes | Yes | PASS | PASS | PASS | 2026-09-23 | **LIVE VERIFIED** | Fallback to `generic` on failure |
| **Bilibili** | `bilibili` | Yes | Yes | PASS | NOT TESTED | NOT TESTED | 2026-09-23 | **PARTIALLY VERIFIED** | Fallback to `generic` on failure |
| **SoundCloud** | `soundcloud` | Yes | Yes | BLOCKED BY SOURCE | NOT TESTED | NOT TESTED | 2026-09-23 | **BLOCKED BY SOURCE** | Fallback to `generic` on failure |
| **Tumblr** | `tumblr` | Yes | Yes | BLOCKED BY SOURCE | NOT TESTED | NOT TESTED | 2026-09-23 | **BLOCKED BY SOURCE** | Fallback to `generic` on failure |
| **Snapchat** | `snapchat` | Yes | Yes | PASS | PASS | NOT TESTED | 2026-09-23 | **PARTIALLY VERIFIED** | Fallback to `generic` on failure |
| **Pinterest** | `pinterest` | Yes | Yes | PASS | PASS | NOT TESTED | 2026-09-23 | **PARTIALLY VERIFIED** | Fallback to `generic` on failure |
| **TED** | `ted` | Yes | Yes | FAIL (Extractor bug) | NOT TESTED | NOT TESTED | 2026-09-23 | **TEMPORARILY BROKEN** | Fallback to `generic` on failure |
| **Twitch** | `twitch` | Yes | Yes | NOT TESTED | NOT TESTED | NOT TESTED | 2026-09-23 | **CONTRACT ONLY** | Fallback to `generic` on failure |
| **Universal Web** | `generic` | Yes | Yes | PASS | PASS | PASS | 2026-09-23 | **LIVE VERIFIED** | Terminal fallback |

---

### Invariant Contract Rules
1. **Contract vs. Live Separation**: Contract validation is verified in fast offline JVM tests (`ProviderContractTest`). Live validation occurs on physical devices with network connectivity without conflating URL regex matching with operational live status.
2. **Provider Health Lifecycle**: Providers initialize with status `UNKNOWN` prior to real-world network execution. Successful resolution transitions them to `AVAILABLE`. Excessive errors transition to `DEGRADED`, and rate limiting or consecutive failures transition to `TEMPORARILY_BROKEN`. If toggled off in settings, status is `DISABLED`.
3. **Failure Isolation**: A crash, timeout, or invalid response from any single provider does not crash the app, interrupt concurrent downloads, or break other providers.
4. **Privacy & Security**: Zero tracking parameters retained (`utm_`, `si`, `igsh`, `fbclid` stripped on normalization). Zero remote code loading. Zero paid third-party API dependencies.

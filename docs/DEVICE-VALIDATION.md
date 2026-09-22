# Real-Device Validation Checklist — Video Pocket

This document outlines the systematic verification procedure for validating Video Pocket builds on physical Android hardware and production network conditions.

---

### Pre-requisites
- **Test Devices**:
  - Android 8.0 - 10.0 (API 26-29, Legacy storage model check)
  - Android 11.0 - 13.0 (API 30-33, Scoped Storage & MediaStore enforcement)
  - Android 14.0 - 15.0+ (API 34+, Selective media permissions, predictive back)
- **Architecture**: ARM64-v8a / armeabi-v7a / x86_64
- **Network Profiles**:
  - Wi-Fi (High-bandwidth, low latency)
  - Cellular 4G/5G (Variable latency, metered connection)
  - Throttled/Captive portal (Simulating network drops and timeouts)

---

### Phase 1: Cold Startup & Platform Health
- [ ] **Instant Launch (No Hang)**: Launch app from completely killed state (`am force-stop`). Ensure first frame renders within 300ms without freezing the main thread.
- [ ] **Kernel Log Cleanliness**: Check `adb logcat | grep -E "audit|avc|rate limit exceeded"`. Verify zero rate-limiting bursts during startup.
- [ ] **Background Initialization**: Verify `ProbeEngine.initialize()` completes silently in `Dispatchers.IO` without invoking blocking subprocesses.

---

### Phase 2: Provider Resolution & Contract Matching
- [ ] **Clipboard Auto-Detection**: Copy a supported URL (e.g. TikTok, Reddit, Pinterest) and open app. Verify automatic paste suggestion chip appears.
- [ ] **Tracking Parameter Stripping**: Verify queries containing `utm_source`, `fbclid`, `si`, `igsh` have those parameters stripped before resolution.
- [ ] **Metadata Display**:
  - Title, author, thumbnail image, duration properly populated.
  - Platform pill displays correct icon and name.
  - Quality badges (e.g. 1080p, 720p, 360p, Audio) accurately list format size and bitrate.
- [ ] **Error Taxonomies**:
  - Test 404 URL: Returns `MEDIA_NOT_FOUND` with localized Arabic/English string.
  - Test unsupported domain: Returns `UNSUPPORTED_SOURCE` or gracefully delegates to `generic`.
  - Airplane mode test: Returns `NETWORK_ERROR` without app crash.

---

### Phase 3: Download & Post-Processing Execution
- [ ] **Standard Video Download**: Download a direct MP4 stream (e.g. TikTok/Vimeo).
  - State progresses: `QUEUED` -> `DOWNLOADING` -> `SAVING` -> `COMPLETED`.
  - Notification updates progress percentages smoothly.
- [ ] **Separate Audio & Video Merge (FFmpeg)**: Download a 1080p Reddit or Facebook video requiring stream multiplexing.
  - State reflects: `DOWNLOADING` -> `MERGING` -> `SAVING` -> `COMPLETED`.
  - Resulting file plays synchronized video and audio in system media player.
- [ ] **Audio-Only Extraction**: Select "Best Audio" (MP3/M4A) for a music track or talk.
  - Proper ID3/metadata tagging retained if available.
- [ ] **Pause & Resume**: Pause active download midway through transfer. Resume and confirm completion without corrupted data.

---

### Phase 4: Storage & Android Policy Compliance
- [ ] **Scoped Storage / MediaStore**:
  - Target downloads saved to standard `Movies/VideoPocket` and `Music/VideoPocket` public directories.
  - Media immediately shows in Gallery / Photos via `MediaScannerConnection`.
  - Zero deprecated `READ_EXTERNAL_STORAGE` or broad `MANAGE_EXTERNAL_STORAGE` requests.
- [ ] **App Notification Status**:
  - Android 13+ requests `POST_NOTIFICATIONS` at appropriate non-intrusive moment.
  - Notification channel correctly configured with cancel/pause actions.

---

### Phase 5: Provider Feature Flags & Circuit Breakers
- [ ] **Disable Provider in Settings**: Toggle off e.g. "Tumblr".
  - Attempting to resolve a Tumblr URL immediately informs user with `PROVIDER_DISABLED`.
- [ ] **Re-enable Provider**: Toggle "Tumblr" back on. URL resolution functions normally.
- [ ] **Health State Transition**: Verify health badges in "Supported Sources" dialog accurately update from `UNKNOWN` to `AVAILABLE` or `DEGRADED`.

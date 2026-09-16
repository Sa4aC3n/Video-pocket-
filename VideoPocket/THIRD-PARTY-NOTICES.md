# Third-party notices

VideoPocket source: GPL-3.0-only (see LICENSE).

Pinned direct components:

| Component | Version | Upstream / license |
|---|---|---|
| youtubedl-android library and FFmpeg wrapper | 0.18.1 | https://github.com/yausername/youtubedl-android/tree/0.18.1 — GPL-3.0 |
| yt-dlp resource overridden by this app | 2026.08.19 | https://github.com/yt-dlp/yt-dlp/tree/2026.08.19 — Unlicense for yt-dlp's own source; bundled dependencies have their own licenses |
| Python, QuickJS, FFmpeg native payloads | supplied by wrapper 0.18.1 | Native binaries and archives are supplied by the wrapper's AARs; preserve their notices and corresponding source requirements |
| Kotlin / kotlinx.coroutines | 2.1.20 / 1.10.1 | https://github.com/JetBrains/kotlin — Apache-2.0 |
| AndroidX / Compose | pinned in app/build.gradle.kts | https://android.googlesource.com/platform/frameworks/support/ — Apache-2.0 |
| Jackson | 2.18.3 | https://github.com/FasterXML/jackson — Apache-2.0 |
| Commons IO | 2.18.0 | https://commons.apache.org/proper/commons-io/ — Apache-2.0 |
| JUnit (test only) | 4.13.2 | https://github.com/junit-team/junit4 — EPL-1.0 |
| Gradle wrapper | 8.11.1 | https://github.com/gradle/gradle — Apache-2.0 |

This is a development probe and this table is not an exhaustive native-source distribution bundle. Before public binary distribution, audit every packaged/transitive native component, include the exact corresponding source and build instructions needed for its license, and preserve upstream notices. Do not infer the FFmpeg binary's license solely from the Kotlin wrapper's license.

No upstream logos or trademarks are included as app branding.

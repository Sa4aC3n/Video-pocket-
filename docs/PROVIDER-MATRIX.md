# Provider Capability Matrix — Video Pocket

This document specifies the capabilities, validation status, engine dependencies, and fallback behaviors for all supported media providers in Video Pocket.

| Provider Name | Provider ID | Supported URL Types / Domains | Supported Media Types | Contract Validated | Live Validated (Device) | Engine Dependency | Fallback Behavior | Feature Flag Key |
|---|---|---|---|---|---|---|---|---|
| **TikTok** | `tiktok` | `tiktok.com`, `vm.tiktok.com`, `vt.tiktok.com` (videos, user shares) | Video, Short Video, Audio, Gallery | Yes | Pending Live Run | `yt-dlp` (`tiktok.py`) | Fallback to `generic` on failure | `provider_tiktok_enabled` |
| **Instagram** | `instagram` | `instagram.com`, `instagr.am` (`/reel/`, `/p/`, `/stories/`) | Reel, Post, Story, Video, Gallery | Yes | Pending Live Run | `yt-dlp` (`instagram.py`) | Fallback to `generic` on failure | `provider_instagram_enabled` |
| **Facebook** | `facebook` | `facebook.com`, `fb.watch`, `fb.com`, `m.facebook.com` | Video, Reel, Story | Yes | Pending Live Run | `yt-dlp` (`facebook.py`) + FFmpeg | Fallback to `generic` on failure | `provider_facebook_enabled` |
| **X / Twitter** | `twitter` | `twitter.com`, `x.com`, `t.co` (posts with media) | Video, GIF, Short Video | Yes | Pending Live Run | `yt-dlp` (`twitter.py`) | Fallback to `generic` on failure | `provider_twitter_enabled` |
| **Reddit** | `reddit` | `reddit.com`, `redd.it`, `v.redd.it` (video posts) | Video, GIF, Post | Yes | Pending Live Run | `yt-dlp` (`reddit.py`) + FFmpeg DASH | Fallback to `generic` on failure | `provider_reddit_enabled` |
| **Vimeo** | `vimeo` | `vimeo.com`, `player.vimeo.com` (public videos) | Video | Yes | Pending Live Run | `yt-dlp` (`vimeo.py`) + FFmpeg | Fallback to `generic` on failure | `provider_vimeo_enabled` |
| **Dailymotion** | `dailymotion` | `dailymotion.com`, `dai.ly` (videos, playlists) | Video, Playlist | Yes | Pending Live Run | `yt-dlp` (`dailymotion.py`) | Fallback to `generic` on failure | `provider_dailymotion_enabled` |
| **Bilibili** | `bilibili` | `bilibili.com`, `b23.tv` (public videos) | Video, Audio | Yes | Pending Live Run | `yt-dlp` (`bilibili.py`) + FFmpeg | Fallback to `generic` on failure | `provider_bilibili_enabled` |
| **SoundCloud** | `soundcloud` | `soundcloud.com`, `on.soundcloud.com` (tracks, sets) | Audio, Playlist | Yes | Pending Live Run | `yt-dlp` (`soundcloud.py`) | Fallback to `generic` on failure | `provider_soundcloud_enabled` |
| **Tumblr** | `tumblr` | `tumblr.com`, `tmblr.co` (public media posts) | Video, Audio, Image, GIF | Yes | Pending Live Run | `yt-dlp` (`tumblr.py`) | Fallback to `generic` on failure | `provider_tumblr_enabled` |
| **Snapchat** | `snapchat` | `snapchat.com`, `story.snapchat.com` (public stories) | Story, Video | Yes | Pending Live Run | `yt-dlp` (`snapchat.py`) | Fallback to `generic` on failure | `provider_snapchat_enabled` |
| **Pinterest** | `pinterest` | `pinterest.com`, `pin.it` (pins, video pins, images) | Video, Image | Yes | Pending Live Run | `yt-dlp` (`pinterest.py`) | Fallback to `generic` on failure | `provider_pinterest_enabled` |
| **TED** | `ted` | `ted.com` (talks, playlists) | Video, Audio, Subtitle, Playlist | Yes | Pending Live Run | `yt-dlp` (`ted.py`) + FFmpeg | Fallback to `generic` on failure | `provider_ted_enabled` |
| **Twitch** | `twitch` | `twitch.tv`, `clips.twitch.tv` (public clips, VODs) | Clip, Video | Yes | Pending Live Run | `yt-dlp` (`twitch.py`) + FFmpeg | Fallback to `generic` on failure | `provider_twitch_enabled` |
| **Universal Web** | `generic` | `http://*`, `https://*` (any yt-dlp supported source) | Video, Audio, Playlist, Subtitle | Yes | Pending Live Run | Bundled `yt-dlp` extractors | Terminal fallback | Always enabled |

---

### Invariant Contract Rules
1. **Contract vs. Live Separation**: Contract validation is verified in fast offline JVM tests (`ProviderContractTest`). Live validation occurs on physical devices with network connectivity without conflating URL regex matching with operational live status.
2. **Provider Health Lifecycle**: Providers initialize with status `UNKNOWN` prior to real-world network execution. Successful resolution transitions them to `AVAILABLE`. Excessive errors transition to `DEGRADED`, and rate limiting or consecutive failures transition to `TEMPORARILY_BROKEN`. If toggled off in settings, status is `DISABLED`.
3. **Failure Isolation**: A crash, timeout, or invalid response from any single provider does not crash the app, interrupt concurrent downloads, or break other providers.
4. **Privacy & Security**: Zero tracking parameters retained (`utm_`, `si`, `igsh`, `fbclid` stripped on normalization). Zero remote code loading. Zero paid third-party API dependencies.

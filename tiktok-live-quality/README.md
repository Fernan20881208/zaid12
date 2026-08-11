# TikTok LIVE Quality (LSPosed)

Experimental LSPosed module for `com.zhiliaoapp.musically` based on the decompiled ByteDance `live_cast` pipeline from TikTok 46.4.3.

## Current behavior

- Scope: TikTok only (`com.zhiliaoapp.musically`).
- Forces screen profile FPS toward **60 FPS**.
- Forces target bitrate toward **16,000 kbps** with **19,200 kbps max**.
- Forces the VirtualDisplay toward a **20:9** high-resolution target:
  - landscape: `1920x864`
  - portrait: `864x1920`
- Keeps TikTok/ByteDance adaptive bitrate callbacks intact.
- Does **not** override `ScreenRecorder.tryEncoder(...)` fallback attempts.
- Logs actual encoder attempts from `ScreenRecorder.tryEncoder(...)` and `VideoEncoder.prepareVideoEncoder(...)` to the Xposed log.

## Hooked ByteDance classes

- `com.byted.cast.sdk.RTCScreenProfile`
- `com.byted.cast.sdk.core.RTCEngineImpl`
- `com.byted.cast.capture.ByteMediaRecorder`
- `com.byted.cast.capture.video.VideoRecorderManager`
- `com.byted.cast.capture.video.screen.ScreenRecorder`
- `com.byted.cast.capture.encoder.VideoEncoder`

The module also hooks class loading inside the TikTok process so it can attach when the dynamic `live_cast` DEX is loaded after app startup.

## Build

GitHub Actions builds an installable debug-signed APK. You can also build locally with Java 17 and Gradle 8.9:

```bash
gradle --no-daemon :app:assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install / enable

1. Install the generated APK.
2. Open LSPosed.
3. Enable **TikTok LIVE Quality**.
4. Scope it only to TikTok / `com.zhiliaoapp.musically`.
5. Force-stop TikTok and reopen it.
6. Start a LIVE screen share.

## Logs

Filter LSPosed/Xposed logs for:

```text
TikTokLiveQuality
```

Useful log entries include original -> forced FPS, bitrate, VirtualDisplay size, and every encoder attempt/fallback.

## Notes

This is intentionally a first-stage test build. It does not patch TikTok's APK, authentication, LIVE transport, or server-side limits. If the encoder rejects the requested profile, TikTok's own fallback chain remains available instead of being disabled.

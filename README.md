# NDI Viewer for Android

A minimal, standalone NDI® receiver for Android based on the NewTek/Vizrt NDI SDK
(`v6.3.2.0`). It detects NDI sources on the network (mDNS), displays video with audio,
and allows manual connection using `IP[:port]`.

## Architecture

```
app/
├── src/main/
│   ├── AndroidManifest.xml
│   ├── jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libndi.so   ← NDI SDK
│   ├── cpp/
│   │   ├── CMakeLists.txt
│   │   ├── ndi_jni.cpp                           ← native JNI bridge (+ I420/YV12/NV12)
│   │   └── ndi/Processing.NDI.*.h                ← NDI headers
│   └── java/com/lekozaur/ndiviewer/
│       ├── DecodeStatus.kt / SourceProbe.kt      ← classification + HX vs Full NDI probe
│       ├── NdiNative.kt   (JNI surface: NdiFinderJni, NdiReceiverJni, NdiNative)
│       ├── NdiFinder.kt   (discovery thread: wait_for_sources + get_current_sources)
│       ├── NdiStream.kt   (video + statistics threads + JNI orchestration)
│       ├── VideoRenderer.kt (FitSurfaceView, double-buffered bitmap, aspect-fit)
│       ├── SourceAdapter.kt (RecyclerView + green/yellow/red status dot)
│       ├── LocaleHelper.kt (PL/EN/DE/ES/IT/FR)
│       └── MainActivity.kt (UI: top sheet, left vertical bar, LIVE red chip)
```

### Data Flow

```
NDI Sender ──TCP/UDP (mDNS)──► NDI SDK (libndi.so)
                                   │
                                   ▼  NdiReceiverJni.capture (video thread)
                    copy/convert: RGBA/BGRX/BGRA/UYVY → RGBA  (BT.709 limited)
                                   │  (DirectByteBuffer, zero-copy within JNI boundaries)
                                   ▼
                            Renderer.onFrame (video thread)
                          Bitmap.copyPixelsFromBuffer (2x ping-pong)
                                   │
                                   ▼  Choreographer ~120 Hz (UI thread)
                            FitSurfaceView → drawBitmap aspect-fit
                                   ▲
                                   │
                          Audio (native audio thread)
                          capture_v2 audio → SPSC ring → AAudio stream (PCM float)
```

### Key Decisions

- **Forced color format**: we receive using `NDIlib_recv_color_format_RGBX_RGBA` — the SDK
  itself performs the down-conversion from UYVY/4:2:2 to RGBA. This maps directly to
  `Bitmap.Config.ARGB_8888` (RGBA in memory, BE→LE).
- **Fallback conversion**: if the source sends UYVY despite the preference (e.g. in
  `fastest` mode), the wrapper performs `UYVY→RGBA` (BT.709 limited, 4:2:2) in C++.
- **Zero allocations in the hot path**: the wrapper maintains two global `DirectByteBuffer`
  instances sized `stride*height` and switches the index for each frame. In Java, two
  `Bitmap` instances (ping-pong) receive `copyPixelsFromBuffer` — with no GC overhead.
- **Threads**: native video + audio run on two separate threads
  (`NDIlib_recv_capture_v2` supports simultaneous calls from multiple threads — this is
  guaranteed by the SDK). Statistics run on a third thread every 1 second.
- **Audio**: AAudio low-latency, PCM float format, downmixes more than 2 channels to stereo
  (NDI typically provides 2/8/16 channels). The AAudio stream is restarted when the format
  changes (rare). Muting is controlled using an atomic bool.
- **Power-saving mode**: `BANDWIDTH_LOWEST=0` reduces resolution and compression
  (useful over Wi-Fi), while `BANDWIDTH_HIGHEST=100` provides full quality.
- **Aspect ratio**: `FitSurfaceView` calculates `onMeasure` based on
  `picture_aspect_ratio` (or `xres/yres`) and maintains letterboxing both vertically
  and horizontally.
- **Discoverability**: mDNS requires a `MulticastLock` — acquired when the
  `BottomSheetDialog` is opened and released when it is closed.
- **Lifecycle**: the Activity's `configChanges` keeps the orientation without restarting.
  `singleTask` prevents multiple instances.

## Build Configuration

| Element        | Value                                  |
| -------------- | -------------------------------------- |
| Gradle         | 8.11.1 (cached)                        |
| AGP            | 8.7.3                                  |
| Kotlin         | 2.1.21                                 |
| NDK            | 27.1.12297006                          |
| CMake (Android)| 3.22.1                                 |
| compileSdk     | 35                                     |
| minSdk         | 26 (required by AAudio)                |
| targetSdk      | 35                                     |
| JDK            | 17                                     |
| ABIs           | arm64-v8a, armeabi-v7a, x86, x86_64    |
| NDI SDK        | v6.3.2.0                               |

## Building

```bat
cd D:\lekozaur\ndiviewer
gradlew.bat --no-daemon assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Replace JDK 17 in `gradle.properties` (`org.gradle.java.home`) with the correct
directory if yours is different.

## Decode Status Indicator (v0.5)

The source list displays a colored dot on the right-hand side of the name:

| Color | Meaning | FourCC |
|-------|---------|--------|
| 🟢 Green | **Will definitely decode** — full Full NDI, uncompressed (`UYVY`, `UYVA`, `RGBA/BGRA` + `I420/YV12/NV12`) is natively supported in `ndi_jni.cpp` (`uyvyToRgba`/`i420ToRgba`/… → `RGBA`). |
| 🟡 Yellow | **May work** — unknown format or no frame received during the 2.7 s probe (e.g. `P216/PA16` 16-bit, or timeout). Worth trying. |
| 🔴 Red | **Will definitely not work** — compressed `NDI|HX / H.264/H.265/SpeedHQ` (`H264/H265/AVC1/HEVC/SHQ0-7`). Requires a hardware decoder that does not exist on this device. Switch the sender to **Full NDI** (NDI Screen Capture → Full NDI, OBS NDI → Main profile). |

Pre-probe heuristic: a name containing `HX`, `H264`, `H265`, `SpeedHQ`, `HEVC`, or `AVC`
is immediately marked 🔴. Otherwise, every new entry is probed in the background by
`SourceProbe` (creates a temporary `NdiReceiverJni`, connects for ~900 ms ×3,
classifies `FourCC` via `DecodeClassifier`, and updates `SourceAdapter` through
`updateStatus`).

## Limitations / TODO

- H.264/NDI-HX in 🔴 mode requires an external decoder (MediaCodec `H264` or
  `libavcodec`) — currently it is detected and reported as
  `Video decoder not found (FourCC=H264 ...)` instead of showing a black screen.
  For `I420/YV12/NV12` (a common HX decoder fallback when available), `BT.709`
  conversion has already been added.
- `libndi.so` from SDK v6.3.2 does not have 16-KB page alignment — Google Play has
  required this for new apps since November 2025. This requires rebuilding the SDK
  from source or upgrading the NDI SDK.
- Audio may require minor tuning for a specific device (NDI typically uses 48 kHz,
  and the default AAudio path is also 48 kHz on Android).
- Video/audio synchronization in the first version is based on displaying frames as
  quickly as they arrive (as-fast-as-possible). For demanding broadcast applications,
  using `NDIlib_FrameSync` instead of raw `recv_capture` is recommended.

## NDI License

The application embeds `libndi.so` from the official NDI SDK package (EULA
`NDI SDK License Agreement.pdf`). Production use requires acceptance of the
Vizrt NDI license terms.


## Trademarks, Copyright and NDI SDK Notice

NDI® is a registered trademark of Vizrt NDI AB and is used under applicable license terms.

This application uses the NDI® SDK provided by Vizrt (formerly NewTek). The NDI SDK, including `libndi.so` and the associated header files, is proprietary software owned by Vizrt and/or its licensors and is subject to the **NDI SDK License Agreement**.

This project is not affiliated with, endorsed by, sponsored by, or otherwise officially associated with Vizrt or NewTek.

All trademarks, service marks, product names, logos, and company names mentioned in this project are the property of their respective owners.

Copyright © 2026 the authors of NDI Viewer for Android - and Opencode :).

The source code of this application is distributed under the license specified in this repository. This license does **not** grant any rights to redistribute, modify, or otherwise use the NDI SDK beyond the rights expressly granted by the applicable NDI SDK License Agreement.

For commercial or production distribution, ensure that your use and distribution of the NDI SDK comply with the current licensing terms provided by Vizrt.

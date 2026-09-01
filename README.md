# NDI Viewer for Android

Minimalny, samodzielny odbiornik NDI® dla Androida oparty na SDK NewTek/Vizrt NDI
(`v6.3.2.0`). Wykrywa źródła NDI w sieci (mDNS), wyświetla obraz z audio i pozwala
dołączyć ręcznie po adresie `IP[:port]`.

## Architektura

```
app/
├── src/main/
│   ├── AndroidManifest.xml
│   ├── jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libndi.so   ← NDI SDK
│   ├── cpp/
│   │   ├── CMakeLists.txt
│   │   ├── ndi_jni.cpp                           ← natywny most JNI (+ I420/YV12/NV12)
│   │   └── ndi/Processing.NDI.*.h                ← nagłówki NDI
│   └── java/com/lekozaur/ndiviewer/
│       ├── DecodeStatus.kt / SourceProbe.kt      ← klasyfikacja + probe HX vs Full NDI
│       ├── NdiNative.kt   (JNI surface: NdiFinderJni, NdiReceiverJni, NdiNative)
│       ├── NdiFinder.kt   (wątek discovery: wait_for_sources + get_current_sources)
│       ├── NdiStream.kt   (wątki wideo + statystyk + orkiestracja JNI)
│       ├── VideoRenderer.kt (FitSurfaceView, podwójny bufor bitmap, aspect-fit)
│       ├── SourceAdapter.kt (RecyclerView + dot zielony/żółty/czerwony)
│       ├── LocaleHelper.kt (PL/EN/DE/ES/IT/FR)
│       └── MainActivity.kt (UI: top sheet, lewy pionowy pasek, LIVE red chip)
```

### Przepływ danych

```
NDI Sender ──TCP/UDP (mDNS)──► NDI SDK (libndi.so)
                                   │
                                   ▼  NdiReceiverJni.capture (wątek wideo)
                    copy/convert: RGBA/BGRX/BGRA/UYVY → RGBA  (BT.709 limited)
                                   │  (DirectByteBuffer, zero-copy w granicach JNI)
                                   ▼
                            Renderer.onFrame (wątek wideo)
                          Bitmap.copyPixelsFromBuffer (2x ping-pong)
                                   │
                                   ▼  Choreographer ~120 Hz (wątek UI)
                            FitSurfaceView → drawBitmap aspect-fit
                                   ▲
                                   │
                          Audio (wątek audio natywny)
                          capture_v2 audio → SPSC ring → AAudio stream (PCM float)
```

### Kluczowe decyzje

- **Wymuszenie koloru**: odbieramy w `NDIlib_recv_color_format_RGBX_RGBA` — SDK sam
  robi down-convert z UYVY/4:2:2 do RGBA. To pasuje wprost do
  `Bitmap.Config.ARGB_8888` (RGBA w pamięci, BE→LE).
- **Back-up konwersja**: jeśli źródło wyśle UYVY pomimo preferencji (np. tryb
  `fastest`), wrapper robi `UYVY→RGBA` (BT.709 limited, 4:2:2) w C++.
- **Zerowa alokacja w hot path**: wrapper trzyma dwa globalne `DirectByteBuffer`
  rozmiaru `stride*height` i przełącza indeks co klatkę. W Javie dwa
  `Bitmap` (ping-pong) dostają `copyPixelsFromBuffer` — żadnych narzutów GC.
- **Wątki**: native wideo + audio na dwóch oddzielnych wątkach
  (`NDIlib_recv_capture_v2` wspiera jednoczesne wywołanie z wielu wątków — gwarantuje
  to SDK). Statystyki w trzecim wątku co 1 s.
- **Dźwięk**: AAudio low-latency, format PCM float, downmix kanałów >2 do stereo
  (NDI zazwyczaj daje 2/8/16ch). Wznowienie strumienia AAudio przy zmianie
  formatu (rzadkie). Mutowanie atomowym bool.
- **Tryb oszczędny**: `BANDWIDTH_LOWEST=0` obniża rozdzielczość i kompresję
  (przydatne na Wi-Fi), `BANDWIDTH_HIGHEST=100` daje pełną jakość.
- **Aspect**: `FitSurfaceView` liczy `onMeasure` na bazie `picture_aspect_ratio`
  (lub `xres/yres`) i utrzymuje letterbox w pionie i poziomie.
- **Discoverability**: mDNS wymaga `MulticastLock` — pobierany przy otwarciu
  `BottomSheetDialog`, zwalniany przy zamknięciu.
- **Lifecycle**: Activity `configChanges` trzyma orientację bez restartu.
  `singleTask` zapobiega wielu instancjom.

## Konfiguracja builda

| Element        | Wartość                                |
| -------------- | -------------------------------------- |
| Gradle         | 8.11.1 (cached)                        |
| AGP            | 8.7.3                                  |
| Kotlin         | 2.1.21                                 |
| NDK            | 27.1.12297006                          |
| CMake (Android)| 3.22.1                                 |
| compileSdk     | 35                                     |
| minSdk         | 26 (wymagane przez AAudio)             |
| targetSdk      | 35                                     |
| JDK            | 17                                     |
| ABIs           | arm64-v8a, armeabi-v7a, x86, x86_64    |
| NDI SDK        | v6.3.2.0                               |

## Budowanie

```bat
cd D:\lekozaur\ndiviewer
gradlew.bat --no-daemon assembleDebug
```

Wynik: `app/build/outputs/apk/debug/app-debug.apk`.

Podmień JDK 17 w `gradle.properties` (`org.gradle.java.home`) na właściwy
katalog, jeśli inny.

## Visual Helpers — BETA (branch `visual-helpers`, v0.6.0-beta)

> **BETA** — funkcje eksperymentalne, mogą obniżyć FPS na 4K. Branch `visual-helpers` nie jest mergowany do `main`.

- **False Color** (`VideoRenderer.falseColorEnabled`, `btnFalseColor` w lewym pasku): heatmap luma `0→64` niebieski, `64→128` cyan→zielony, `128→192` zielony→żółty, `192→255` żółty→czerwony (`applyFalseColor` per-pixel `getPixels/setPixels`). Toggle, alpha ikony `1.0`/`0.5`.
- **Histogram** (`HistogramView` + `histogramContainer`): overlay `160×100dp`, `R/G/B/L` log-scale, draggable (`onTouch` `ACTION_DOWN/MOVE` → `View.animate().x/y`), regulacja **wielkości** `SeekBar Size 0.6×–2.0×` i **przezroczystości** `SeekBar Alpha 0.2–1.0` (kontener `alpha`, controls `alpha`). Danych dostarcza `VideoRenderer.histogramCallback` (co 4. piksel). Toggle `btnHistogram` → `VISIBLE/GONE`.

Build z brancha:
```bat
git checkout visual-helpers
gradlew.bat --no-daemon assembleDebug
```

## Wskaźnik dekodowania (v0.5)

Lista źródeł pokazuje po prawej stronie nazwy kolorowe kółko:

| Kolor | Znaczenie | FourCC |
|-------|-----------|--------|
| 🟢 Zielony | **Na pewno zdekoduje** — pełne Full NDI, uncompressed (`UYVY`, `UYVA`, `RGBA/BGRA` + `I420/YV12/NV12`) obsługiwane natywnie w `ndi_jni.cpp` (`uyvyToRgba`/`i420ToRgba`/… → `RGBA`). |
| 🟡 Żółty | **Być może** — nieznany format lub brak klatki w próbie 2.7 s (np. `P216/PA16` 16-bit, lub timeout). Worth spróbować. |
| 🔴 Czerwony | **Na pewno nie** — kompresja `NDI|HX / H.264/H.265/SpeedHQ` (`H264/H265/AVC1/HEVC/SHQ0-7`). Wymaga dekodera sprzętowego, który na tym urządzeniu nie istnieje. Przełącz nadajnik na **Full NDI** (NDI Screen Capture → Full NDI, OBS NDI → Main profile). |

Heurystyka przed próbą: nazwa zawierająca `HX`, `H264`, `H265`, `SpeedHQ`, `HEVC`, `AVC` → od razu 🔴. W przeciwnym razie każdy nowy wpis jest probowany w tle przez `SourceProbe` (tworzy tymczasowy `NdiReceiverJni`, łączy się na ~900 ms ×3, klasyfikuje `FourCC` via `DecodeClassifier`, aktualizuje `SourceAdapter` przez `updateStatus`).

## Ograniczenia / TODO

- H.264/NDI-HX w trybie 🔴 wymaga zewnętrznego dekodera (MediaCodec `H264` lub `libavcodec`) — obecnie wykrywane i zgłaszane jako `Video decoder not found (FourCC=H264 ...)` zamiast czarnego ekranu. Dla `I420/YV12/NV12` (częsty fallback dekodera HX gdy dostępny) dodano już konwersję `BT.709`.
- Brak 16-KB page alignment w `libndi.so` z SDK v6.3.2 — Google Play wymaga tego
  od nowych aplikacji od XI 2025. Wymaga przebudowy SDK ze źródeł lub aktualizacji
  NDI SDK.
- Audio może wymagać drobnego dostrojenia pod konkretne urządzenie (NDI
  zazwyczaj 48 kHz, domyślna ścieżka AAudio to też 48 kHz na Androidzie).
- Synchronizacja wideo/audio w pierwszej wersji opiera się na wyświetlaniu
  klatek tak szybko jak przychodzą (as-fast-as-possible). Dla wymagających
  zastosowań broadcast zalecane użycie `NDIlib_FrameSync` zamiast surowego
  `recv_capture`.

## Licencja NDI

Aplikacja osadza `libndi.so` z oficjalnego pakietu NDI SDK (EULA
`NDI SDK License Agreement.pdf`). Użycie w produkcji wymaga akceptacji
warunków licencji Vizrt NDI.

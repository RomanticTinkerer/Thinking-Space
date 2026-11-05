# Third-party notices

## Dimowner/AudioRecorder — Apache License 2.0
Vendored in `recorder/` (the `MediaRecorder`-based AAC/.m4a recording engine only).

- Upstream: https://github.com/Dimowner/AudioRecorder
- Snapshot: commit `28ef142524f38f66a3793f9a4d2f796c981a5067` (2026-09-29)
- Copyright 2018–2026 Dmytro Ponomarenko
- License text: `recorder/LICENSE-Dimowner-AudioRecorder.txt`

Files are copied unmodified except for two local modifications, each marked in the file:

| File | Change |
| --- | --- |
| `v2/audio/AudioRecorderV2.kt` | Removed Hilt (`@Singleton`, `@Inject`, `@ApplicationContext`) so it can be constructed directly. |
| `AppConstants.java` | Reduced to the single constant the engine uses (upstream has several hundred). |

Not taken: the AAC `MediaCodec` pipeline, WAV/3GP recorders, Dimowner's own foreground recording service (it is Hilt-coupled; see `RecordingService` below),
broken-record recovery, and the Hilt/Room/Compose app layer.

## Material icons — Apache License 2.0
`design/icons/*.svg` and `app/src/main/res/drawable/ic_{edit,mic,stop,save,refresh,delete}.xml` use the geometry of
the Material Design icons (edit, mic, stop, save, refresh, delete), https://github.com/google/material-design-icons.

## ReFra (IacobIonut01/ReFra) — Apache License 2.0
Vendored in `editor/`, `cropper/` and `gesture/` (the markup editor UI, the image cropper and the 21 filter presets;
not the gallery, the edit pipeline or the ViewModel/Hilt layer).

- Upstream: https://github.com/IacobIonut01/ReFra
- Snapshot: commit `43facd9f5c9a44b21d07c116c23618f3e896097c` (2026-10-04, "ReFra 5.2.2")
- Copyright 2023–2026 IacobIacob01
- License text: `editor/LICENSE-ReFra.txt`

What was taken, and where it lives:

| Path in this repo | Upstream path | Notes |
| --- | --- | --- |
| `cropper/`, `gesture/` | `libs/cropper`, `libs/gesture` | Unmodified, except the cropper's unused demo image resource was removed. These are ReFra's copies of the SmartToolFactory Compose-Cropper libraries (Apache-2.0). |
| `editor/.../edit/components/markup/*` | `app/.../presentation/edit/components/markup/*` | `MarkupPainter.kt` modified: the Glide image composable was replaced by a plain Compose `Image` (marked in the file). Everything else unmodified. |
| `editor/.../edit/components/core/*`, `.../adjustment/SelectableItem.kt`, `.../edit/utils/*` | same paths | Unmodified. |
| `editor/.../domain/model/editor/*` (markup models) | same paths | `MarkupItems.kt` modified: Parcelize/Serializable annotations removed. |
| `editor/.../presentation/util/ModifierExt.kt`, `editor/.../ui/core/**` | same paths | Unmodified (Modifier helpers, ink/stylus icons). |
| `editor/.../presentation/util/FileUtils.kt`, `.../mediaview/RememberedDerivedState.kt`, `.../ui/theme/GalleryTheme.kt` | `FileUtils.kt`, `MediaViewScreen.kt`, theme | Minimal stand-ins containing only the pieces the vendored UI needs. |
| `editor/.../feature/editor/adjustments/filters/*`, `.../util/BitmapEditUtils.kt` | same paths | Filter presets (colour matrices) and bitmap helpers. `ImageFilterTypes.kt` has the `@Serializable` annotation removed. `model/ImageFilter.kt` is a minimal stand-in for ReFra's interface. |
| `editor/src/main/res/values/strings.xml` | `app/src/main/res/values/strings.xml` | English strings referenced by the vendored UI. |
| `editor/.../MarkupState.kt`, `CropContent` in `EditorScreen.kt` | `EditViewModel.kt` (markup state), `CropActivity.kt` | Re-expressed with the same logic and preset values; written for this project. |

`EditorActivity.kt`, `Look.kt` (the Adjust sliders: formulas follow ReFra's variable filters), `Ml.kt` (the AI tools follow the
steps in ReFra's `CutoutHelper` and `FaceHelper`) and the rest of `EditorScreen.kt` are original to this project.
The editor module keeps ReFra's `com.dot.gallery` Gradle namespace and package names on purpose, so the
vendored sources stay byte-comparable with upstream.

Toolchain versions in `gradle/libs.versions.toml` (AGP, Kotlin, Compose) match ReFra's for the same reason.

## Tesseract OCR — Apache License 2.0
Used to read text from screenshots, fully offline.

- **Tesseract4Android** 4.9.0 (https://github.com/adaptech-cz/Tesseract4Android), Apache-2.0. Pulled in as a Gradle
  dependency from JitPack, not vendored. It is a fork of tess-two and bundles Tesseract 5.5.1 (Apache-2.0),
  Leptonica 1.85.0 (BSD-2-Clause), libjpeg 9f and libpng 1.6.48 (both permissive).
- **tessdata_fast** `eng.traineddata` (https://github.com/tesseract-ocr/tessdata_fast), Apache-2.0. Unmodified, shipped in
  `app/src/main/assets/tessdata/`. License text: `licenses/LICENSE-tessdata_fast.txt`.

## Roboto Flex — SIL Open Font License 1.1
The app's typeface, shipped as `editor/src/main/res/font/roboto_flex.ttf` (the variable font, unmodified).

- Upstream: https://github.com/TypeNetwork/Roboto-Flex, copy taken from https://github.com/google/fonts (`ofl/robotoflex`)
- Copyright 2017 The Roboto Flex Project Authors
- License text: `licenses/OFL-RobotoFlex.txt`

## On-device AI models and runtime
Used by the editor's Smart tab. All three files are bundled in `editor/src/main/assets/models/`, so nothing is downloaded.

| Component | License | Notes |
| --- | --- | --- |
| **MobileSAM** (https://github.com/ChaoningZhang/MobileSAM) | Apache-2.0 | ONNX export by Acly (https://huggingface.co/Acly/MobileSAM), unmodified: `mobile_sam_image_encoder.onnx` (sha256 `580f5fb6…`) and `sam_mask_decoder_single.onnx` (sha256 `93915fc7…`). Tap-to-select cutout and background blur. |
| **Ultra-Light-Fast-Generic-Face-Detector-1MB** (https://github.com/Linzaer/Ultra-Light-Fast-Generic-Face-Detector-1MB) | MIT, Copyright (c) 2019 Linzaer | `version-RFB-320.onnx` (sha256 `34cd7e60…`), as published in the ONNX model zoo (`validated/vision/body_analysis/ultraface`). Face blur. |
| **ONNX Runtime** 1.29.0 (https://github.com/microsoft/onnxruntime) | MIT, Copyright (c) Microsoft Corporation | Gradle dependency (`onnxruntime-android`), not vendored. |

The Apache-2.0 text is the same as `editor/LICENSE-ReFra.txt`.

## Recording foreground service
`app/.../audio/RecordingService.kt` follows the approach used by Fossify Voice Recorder
(https://github.com/FossifyOrg/Voice-Recorder) and Dimowner/AudioRecorder: record inside a foreground
service of type `microphone`. No code was copied from Fossify (it is GPL-3.0, which would require this
whole app to be GPL-3.0); the service here is original code written against the Android API.

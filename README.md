# Thinking Space

An open, offline take on Nothing's **Essential Space** for any Android 11+ phone.

Essential Space turns a button press into a memory: a screenshot, a voice note, a task or a reminder, all searchable later. It only works on Nothing phones, and its smarter parts run in the cloud. Thinking Space does the capture-and-recall part on any phone, keeps everything on the device, and has no internet permission.

## How it works

Make it your default assistant, then hold the power button (or whatever gesture your phone uses for the assistant). The screen is captured and shown in a frame with three actions:

- **Edit**: built on [ReFra](https://github.com/IacobIonut01/ReFra)'s editor. Adjust sliders (brightness, contrast, warmth, highlights and more), 21 filters with an intensity slider, markup (pen, highlighter, blur, mosaic, text) and crop. The Smart tab runs AI models on your phone: tap a subject to cut it out or blur the background, or blur every face in one go.
- **Record**: attach a voice note. Stop, or reset to try again, with a live level meter.
- **Save**: store it. Nothing is written until you save, and the trash icon discards the capture.

Besides the assistant, you can share text, links, images or audio to Thinking Space from any app, write a note with the New note button on the home screen, or record a voice memo (mic button, launcher long-press shortcut, or the Voice memo Quick Settings tile). These land in the same album as small cards.

Everything lands in the shared `Pictures/OpenThinking Space Screenshots` album (the folder keeps its old name so earlier screenshots stay visible), named `Screenshot1`, `Screenshot2` and so on. Voice notes go to `Recordings/OTSVoiceNotes` as `Screenshot1_Note`, so any other gallery or recorder app can see them too.

## What carries over from Essential Space

| Essential Space | Thinking Space |
| --- | --- |
| Essential Key capture | Default-assistant gesture (power button hold) |
| Screenshot with a voice note | Yes, pinned to the screenshot |
| Notes on a memory | Text note on every screenshot, searchable |
| Text in screenshots | Offline OCR (Tesseract, English). Search finds words inside screenshots, and "Text in image" lets you copy them |
| Collections | Pinned and Voice notes filters |
| Reminders | "Remind me" on any screenshot (notification) |
| Voice transcription | On-device Parakeet. Voice notes are written out as text, searchable, with Copy in the viewer |
| Share to Essential Space | "Share to Thinking Space" saves text, links, images and audio (chat voice messages too) |
| Quick note, voice memo | "New note" and mic buttons on the home screen, plus launcher shortcuts and a Quick Settings tile (Voice memo) |
| Share, multi-select | Share, long-press to pin or delete several |

Not here yet: AI summaries and task extraction, transcription in languages other than English, OCR in languages other than English, widgets and backup. They need a model, and the aim is to add them on-device or not at all.

## Build

```
./gradlew :app:installDebug
```

The first build downloads the speech model (about 650 MB, NVIDIA Parakeet) and the sherpa-onnx library into `app/asr-model/` and `app/libs/`. Both are git-ignored, so the repo stays small, and the app itself never goes online. They are bundled in the APK, which is why it is large (about 840 MB debug). The model needs about 1 GB of RAM while it is writing voice notes out, and it is loaded only then.

Needs JDK 21 and the Android SDK (compileSdk 37). After installing, open the app, accept the "set as default assistant" prompt and allow the microphone. In the assistant settings, also switch on "Use screenshot".

The internal package is still `com.openthinking.blankassistant`. It was kept on purpose so existing installs keep their saved screenshots (scoped storage ties files to the package).

## Credits

Recording engine from [Dimowner/AudioRecorder](https://github.com/Dimowner/AudioRecorder) and the editor and cropper from [ReFra](https://github.com/IacobIonut01/ReFra), both Apache-2.0. See `THIRD_PARTY_NOTICES.md`.

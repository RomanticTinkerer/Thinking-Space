# OpenThinking Space

An open, offline take on Nothing's **Essential Space** for any Android 11+ phone.

Essential Space turns a button press into a memory: a screenshot, a voice note, a task or a reminder, all searchable later. It only works on Nothing phones, and its smarter parts run in the cloud. OpenThinking Space does the capture-and-recall part on any phone, keeps everything on the device, and has no internet permission.

## How it works

Make it your default assistant, then hold the power button (or whatever gesture your phone uses for the assistant). The screen is captured and shown in a frame with three actions:

- **Edit**: markup (pen, highlighter, blur, mosaic, text) and crop, using [ReFra](https://github.com/IacobIonut01/ReFra)'s editor.
- **Record**: attach a voice note. Stop, or reset to try again, with a live level meter.
- **Save**: store it. Nothing is written until you save, and the trash icon discards the capture.

Everything lands in the shared `Pictures/OpenThinking Space Screenshots` album, named `Screenshot1`, `Screenshot2` and so on. Voice notes go to `Recordings/OTSVoiceNotes` as `Screenshot1_Note`, so any other gallery or recorder app can see them too.

## What carries over from Essential Space

| Essential Space | OpenThinking Space |
| --- | --- |
| Essential Key capture | Default-assistant gesture (power button hold) |
| Screenshot with a voice note | Yes, pinned to the screenshot |
| Notes on a memory | Text note on every screenshot, searchable |
| Text in screenshots | Offline OCR (Tesseract, English). Search finds words inside screenshots, and "Text in image" lets you copy them |
| Collections | Pinned and Voice notes filters |
| Reminders | "Remind me" on any screenshot (notification) |
| Share, multi-select | Share, long-press to pin or delete several |

Not here yet: AI summaries and task extraction, voice transcription, OCR in languages other than English, standalone voice memos, widgets and backup. They need a model, and the aim is to add them on-device or not at all.

## Build

```
./gradlew :app:installDebug
```

Needs JDK 21 and the Android SDK (compileSdk 37). After installing, open the app, accept the "set as default assistant" prompt and allow the microphone. In the assistant settings, also switch on "Use screenshot".

The internal package is still `com.openthinking.blankassistant`. It was kept on purpose so existing installs keep their saved screenshots (scoped storage ties files to the package).

## Credits

Recording engine from [Dimowner/AudioRecorder](https://github.com/Dimowner/AudioRecorder) and the editor and cropper from [ReFra](https://github.com/IacobIonut01/ReFra), both Apache-2.0. See `THIRD_PARTY_NOTICES.md`.

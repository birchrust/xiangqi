# Xiangqi

An Android Xiangqi assistant built with Dioxus and Kotlin. It recognizes on-screen boards and displays analysis in a floating overlay.

## Engines

- **Chess:** [Pikafish 2026-09-06](https://github.com/official-pikafish/Pikafish/releases/tag/Pikafish-2026-09-06), bundled with its matching NNUE network. Uses UCI to provide up to four candidate moves, scores, and principal variations.
- **Recognition:** Models from [Chinese Chess Recognition](https://huggingface.co/spaces/yolo12138/Chinese_Chess_Recognition/tree/4bcaf91777effa645040ee76d008d45c2ffc03f3). Two ONNX models locate the board and classify pieces across all 90 squares. Inference runs on device through ONNX Runtime.

Model files and engine resources are in `assets/`. Sources and license details are listed in [Third-party notices](THIRD_PARTY_NOTICES.md).

## Build

Requires Linux, Rust, just, Dioxus CLI 0.7.10, JDK 17, and the Android SDK/NDK. Copy `.env.example` to `.env` and set your toolchain paths.

```sh
just doctor
just apk-debug  # Installable debug APK
just apk        # Unsigned release APK
just release    # Release APK signed with credentials from .env
```

APKs are written to `dist/`. Run `just` to list all commands.

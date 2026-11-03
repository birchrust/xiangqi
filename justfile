set shell := ["bash", "-euo", "pipefail", "-c"]
set dotenv-load
set positional-arguments

root := justfile_directory()
sdk := env("ANDROID_HOME", env("ANDROID_SDK_ROOT", home_directory() / "Android/Sdk"))
java_home := env("JAVA_HOME", "")
ndk := env("ANDROID_NDK_HOME", env("NDK_HOME", sdk / "ndk/30.0.16248370"))
build_tools := env("ANDROID_BUILD_TOOLS", "36.0.0")
dx_root := root / "target/dx/xiangqi"
library := "android/assistant"

export ANDROID_HOME := sdk
export JAVA_HOME := java_home
export ANDROID_NDK_HOME := ndk
export NDK_HOME := ndk
export PATH := java_home / "bin" + ":" + sdk / "platform-tools" + ":" + sdk / "emulator" + ":" + env("PATH")
export CARGO_BUILD_JOBS := env("CARGO_BUILD_JOBS", "1")
export GRADLE_OPTS := env("GRADLE_OPTS", "-Dorg.gradle.daemon=false -Dorg.gradle.workers.max=1 -Dorg.gradle.jvmargs=-Xmx768m -Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process")

# List project commands.
default:
    @just --list

# Check the Android toolchain.
doctor:
    @command -v cargo dx make just rg unzip sha256sum >/dev/null
    @test -x "$JAVA_HOME/bin/java" || { echo 'Set JAVA_HOME to JDK 17 in .env.' >&2; exit 1; }
    @"$JAVA_HOME/bin/java" -version 2>&1 | head -n 1 | rg 'version "17\.' >/dev/null || { echo 'JDK 17 is required.' >&2; exit 1; }
    @test -x "$ANDROID_HOME/platform-tools/adb" || { echo 'Android Platform-Tools is missing.' >&2; exit 1; }
    @test -x "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++" || { echo 'Set ANDROID_NDK_HOME to an installed Linux NDK.' >&2; exit 1; }
    @test -x "$ANDROID_HOME/build-tools/{{ build_tools }}/zipalign" || { echo 'Install Android Build-Tools {{ build_tools }}.' >&2; exit 1; }
    @printf 'JDK 17, SDK, NDK, and Build-Tools are ready.\n'

# Prepare pinned engine and model assets.
[arg("abi", pattern="arm64-v8a|x86_64")]
prepare-android abi="arm64-v8a": doctor (_engine abi)
    mkdir -p {{ library }}/src/main/assets/models {{ library }}/src/main/assets/pikafish
    cp assets/recognition/board-pose.onnx assets/recognition/board-layout.onnx {{ library }}/src/main/assets/models/
    cp assets/pikafish/pikafish.nnue {{ library }}/src/main/assets/pikafish/

[arg("abi", pattern="arm64-v8a|x86_64")]
_engine abi:
    mkdir -p "target/pikafish/$1" "assets/pikafish/bin/$1" "{{ library }}/src/main/jniLibs/$1"
    cp -a assets/pikafish/source/src/. "target/pikafish/$1/"
    make -C "target/pikafish/$1" -j1 all \
        ARCH={{ if abi == "arm64-v8a" { "armv8" } else { "x86-64" } }} COMP=clang OS=Android \
        COMPCXX="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/{{ if abi == "arm64-v8a" { "aarch64" } else { "x86_64" } }}-linux-android24-clang++" \
        EXTRALDFLAGS='-static-libstdc++ -Wl,-z,max-page-size=16384' \
        EXE="{{ root }}/assets/pikafish/bin/$1/libpikafish.so"
    "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" "assets/pikafish/bin/$1/libpikafish.so"
    cp "assets/pikafish/bin/$1/libpikafish.so" "{{ library }}/src/main/jniLibs/$1/libpikafish.so"

# Run Android development with native integration.
[arg("abi", pattern="arm64-v8a|x86_64")]
dev abi="arm64-v8a": (prepare-android abi)
    just _select-abi "$1" debug
    rm -rf "{{ dx_root }}/debug/android/app/plugins/xiangqiassistant/src/main/kotlin"
    dx serve --android --target {{ if abi == "arm64-v8a" { "aarch64" } else { "x86_64" } }}-linux-android --locked

# Build an optimized, unsigned APK.
[arg("abi", pattern="arm64-v8a|x86_64")]
apk abi="arm64-v8a": (_build abi "release")
    just _gradle release :app:assembleRelease
    mkdir -p dist
    cp "{{ dx_root }}/release/android/app/app/build/outputs/apk/release/app-release-unsigned.apk" "dist/xiangqi-$1-release-unsigned.apk"
    just verify-apk "dist/xiangqi-$1-release-unsigned.apk"
    sha256sum "dist/xiangqi-$1-release-unsigned.apk"

# Build a debug-signed APK for installation.
[arg("abi", pattern="arm64-v8a|x86_64")]
apk-debug abi="arm64-v8a": (_build abi "debug")
    just _gradle debug :app:assembleDebug
    mkdir -p dist
    cp "{{ dx_root }}/debug/android/app/app/build/outputs/apk/debug/app-debug.apk" "dist/xiangqi-$1-debug.apk"
    just verify-apk "dist/xiangqi-$1-debug.apk"
    "$JAVA_HOME/bin/java" -jar "{{ sdk }}/build-tools/{{ build_tools }}/lib/apksigner.jar" verify --verbose "dist/xiangqi-$1-debug.apk"
    sha256sum "dist/xiangqi-$1-debug.apk"

# Sign a release APK with environment-provided credentials.
[arg("abi", pattern="arm64-v8a|x86_64")]
release abi="arm64-v8a": (apk abi)
    @test -f "${ANDROID_KEYSTORE:-}" && test -n "${ANDROID_KEY_ALIAS:-}" && test -n "${ANDROID_KEYSTORE_PASSWORD:-}" && test -n "${ANDROID_KEY_PASSWORD:-}" || { echo 'Set ANDROID_KEYSTORE, ANDROID_KEY_ALIAS, ANDROID_KEYSTORE_PASSWORD, and ANDROID_KEY_PASSWORD in .env.' >&2; exit 1; }
    @"$JAVA_HOME/bin/java" -jar "{{ sdk }}/build-tools/{{ build_tools }}/lib/apksigner.jar" sign --ks "$ANDROID_KEYSTORE" --ks-key-alias "$ANDROID_KEY_ALIAS" --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD --out "dist/xiangqi-$1-release.apk" "dist/xiangqi-$1-release-unsigned.apk"
    "$JAVA_HOME/bin/java" -jar "{{ sdk }}/build-tools/{{ build_tools }}/lib/apksigner.jar" verify --verbose "dist/xiangqi-$1-release.apk"
    just verify-apk "dist/xiangqi-$1-release.apk"
    sha256sum "dist/xiangqi-$1-release.apk"

[arg("abi", pattern="arm64-v8a|x86_64")]
[arg("mode", pattern="debug|release")]
_build abi mode: (prepare-android abi)
    just _select-abi "$1" "$2"
    rm -rf "{{ dx_root }}/$2/android/app/plugins/xiangqiassistant/src/main/kotlin"
    just _dx-build "$1" "$2"
    just _sync-gradle "$1" "$2"

# Retry Dioxus's intermediate APK packaging failure once.
[arg("abi", pattern="arm64-v8a|x86_64")]
[arg("mode", pattern="debug|release")]
_dx-build abi mode:
    mkdir -p target/just
    for build_attempt in 1 2; do \
        if dx build --android --target {{ if abi == "arm64-v8a" { "aarch64" } else { "x86_64" } }}-linux-android {{ if mode == "release" { "--release" } else { "" } }} --locked > "target/just/dx-$2-$1.log" 2>&1; then \
            cat "target/just/dx-$2-$1.log"; exit 0; \
        fi; \
        cat "target/just/dx-$2-$1.log" >&2; \
        if [ "$build_attempt" = 2 ] || ! rg -q 'PackageAndroidArtifact[$]IncrementalSplitterRunnable' "target/just/dx-$2-$1.log"; then exit 1; fi; \
        printf 'Retrying intermediate APK packaging.\n' >&2; \
    done

[arg("abi", pattern="arm64-v8a|x86_64")]
[arg("mode", pattern="debug|release")]
_select-abi abi mode:
    mkdir -p "{{ dx_root }}/$2/android/app"
    if [ "$(cat "{{ dx_root }}/$2/android/app/.just-abi" 2>/dev/null || true)" != "$1" ]; then \
        rm -rf "{{ dx_root }}/$2/android/app/app/src/main/jniLibs" \
            "{{ dx_root }}/$2/android/app/app/build/intermediates/incremental/packageDebug" \
            "{{ dx_root }}/$2/android/app/app/build/intermediates/incremental/packageRelease"; \
    fi
    printf '%s\n' "$1" > "{{ dx_root }}/$2/android/app/.just-abi"

[arg("abi", pattern="arm64-v8a|x86_64")]
[arg("mode", pattern="debug|release")]
_sync-gradle abi mode:
    test -f "{{ dx_root }}/$2/android/app/gradlew"
    rm -rf "{{ dx_root }}/$2/android/app/plugins/xiangqiassistant/src"
    cp -a {{ library }}/src "{{ dx_root }}/$2/android/app/plugins/xiangqiassistant/src"
    cp {{ library }}/build.gradle.kts {{ library }}/consumer-rules.pro "{{ dx_root }}/$2/android/app/plugins/xiangqiassistant/"
    find "{{ dx_root }}/$2/android/app/plugins/xiangqiassistant/src/main/jniLibs" "{{ dx_root }}/$2/android/app/app/src/main/jniLibs" -mindepth 1 -maxdepth 1 -type d ! -name "$1" -exec rm -rf {} +
    sed -i '/^\/\/ just: packaging$/,$d' "{{ dx_root }}/$2/android/app/app/build.gradle.kts"
    printf '\n// just: packaging\nandroid {\n    defaultConfig.ndk.abiFilters.apply { clear(); add("%s") }\n    packaging.jniLibs.useLegacyPackaging = true\n}\n' "$1" >> "{{ dx_root }}/$2/android/app/app/build.gradle.kts"

[arg("mode", pattern="debug|release")]
_gradle mode +tasks: doctor
    bash "{{ dx_root }}/$1/android/app/gradlew" -p "{{ dx_root }}/$1/android/app" --no-daemon --max-workers=1 "${@:2}"

# Run architecture and JVM regression tests.
[arg("abi", pattern="arm64-v8a|x86_64")]
test abi="arm64-v8a": (_build abi "debug")
    just _gradle debug :plugins:xiangqiassistant:testDebugUnitTest

# Run engine, overlay, and frame tests on a connected device.
[arg("abi", pattern="arm64-v8a|x86_64")]
device-test abi="x86_64": (_build abi "debug")
    adb get-state
    just _gradle debug :plugins:xiangqiassistant:connectedDebugAndroidTest

# Check Rust formatting and the shared Web UI.
check:
    just --fmt --check
    cargo fmt --check
    cargo check --locked --no-default-features --features web --target wasm32-unknown-unknown

# Preview the shared Web UI.
web:
    dx serve --web --locked

# List installed emulators.
emulators: doctor
    "{{ sdk }}/emulator/emulator" -list-avds

# Start a headless emulator for device tests.
emulator avd: doctor
    "{{ sdk }}/emulator/emulator" -avd "$1" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -memory 1536 -cores 2 -no-snapshot

# Verify archive integrity and 16 KB ZIP alignment.
verify-apk apk: doctor
    unzip -t "$1" >/dev/null
    "{{ sdk }}/build-tools/{{ build_tools }}/zipalign" -c -P 16 -v 4 "$1" | tail -n 1

# Install a generated debug APK.
[arg("abi", pattern="arm64-v8a|x86_64")]
install abi="arm64-v8a": (apk-debug abi)
    adb install -r "dist/xiangqi-$1-debug.apk"

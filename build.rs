fn main() {
    for path in [
        "justfile",
        "android/assistant/build.gradle.kts",
        "android/assistant/consumer-rules.pro",
        "android/assistant/src/main/AndroidManifest.xml",
        "android/assistant/src/main/kotlin",
        "android/assistant/src/main/res",
        "assets/recognition",
        "assets/pikafish/source/src",
        "assets/pikafish/pikafish.nnue",
    ] {
        println!("cargo:rerun-if-changed={path}");
    }
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("android") {
        return;
    }
    let abi = match std::env::var("CARGO_CFG_TARGET_ARCH").as_deref() {
        Ok("x86_64") => "x86_64",
        Ok("aarch64") => "arm64-v8a",
        other => panic!("Unsupported Android architecture: {other:?}"),
    };
    let status = std::process::Command::new("just")
        .args(["--justfile", "justfile", "prepare-android", abi])
        .status()
        .expect("Install just to prepare Android assets");
    assert!(status.success(), "Android engine preparation failed");
}

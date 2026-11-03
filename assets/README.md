# Android 识别与推算资源

- `recognition/board-pose.onnx`：棋盘四角定位（原文件 `4_v6-0301.onnx`）。
- `recognition/board-layout.onnx`：90 个棋位的 16 类分类（原文件 `nano_v3-0319.onnx`）。
- `pikafish/pikafish.nnue`：Pikafish 2026-09-06 配套权重。
- `pikafish/bin/arm64-v8a/libpikafish.so`：ARM64 Android 引擎可执行文件。
- `pikafish/bin/x86_64/libpikafish.so`：x86_64 Android 模拟器引擎可执行文件。
- `pikafish/source/`：对应版本的完整引擎源码、作者和许可证。

`libpikafish.so` 是 PIE 可执行程序，用 `.so` 文件名是为了让 Android 将它安装到可执行的原生库目录；通过 UCI 标准输入／输出通信。

`just prepare-android` 准备资源，`build.rs` 在直接进行 Android 编译时调用同一个 just recipe：按目标 ABI 编译缺失或已过期的引擎，并把模型、权重和二进制同步到 Android 库的打包目录。该目录被 Git 忽略，以上 `assets/` 文件才是资源来源。构建时不下载浮动版本的模型或网络权重。

来源和校验值见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

Stockfish 19 不再放在 assets 中：Android 10+ 禁止执行应用私有目录里复制出来的二进制文件。
Release 工作流会把官方 Stockfish 19 Android ARM64 引擎放到
app/src/main/jniLibs/arm64-v8a/libstockfish.so，安装后由系统解压到 nativeLibraryDir 并可直接执行。

本地构建时也可手动下载官方 sf_19 的 stockfish-android-arm64-universal，重命名为 libstockfish.so 放到上述目录。
没有该文件时，应用会自动使用纯 Kotlin 的谱着内置引擎。

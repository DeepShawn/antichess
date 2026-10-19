将官方 Stockfish 19 的 Android arm64-v8a 可执行文件命名为 `stockfish` 放在此目录。
构建时会原样打包到 APK，运行时复制到 filesDir 并通过 UCI 在本机进程中运行。

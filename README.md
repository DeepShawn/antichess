# 谱着 · Chess Lab

仓库同时包含浏览器版本和原生 Android 版本。浏览器版本打开 `index.html` 即可使用；Android 版本位于 `app/`，使用 Kotlin + 原生 Android View，不使用 WebView。

## 功能

- 大师谱着开局库，按权重抽取一选、二选、三选及备选走法
- 低频错棋注入，让对局更接近真人
- 可点击棋盘、合法走法提示、悔棋、新局、走法记录
- 对局自动保存与中断恢复、PGN 导出、FEN 摆棋、分析模式
- Stockfish 19 UCI 状态位与评估面板

## Android 构建

Android 工程只声明 `arm64-v8a` ABI。Release 工作流会从官方 Stockfish 19 `sf_19` 发布包下载 Android ARM64 引擎并打包进 APK，应用首次启动时复制到应用私有目录，通过 UCI 本地进程运行。难度档位与等级分参考：入门 800、初级 1100、中级 1400、高级 1800、大师 2200、Stockfish 19 约 2850 Elo；实际强度会因设备、时限和开局阶段波动。

网页和 Android 按同一版本同步维护。每次 `main` 更新都会运行双端 CI，发布标签会同时对应网页版本和 Android APK。

## GitHub Pages

`.github/workflows/pages.yml` 会在 `main` 更新时发布仓库根目录的网页版本。首次使用时，在仓库 Settings → Pages → Source 选择 GitHub Actions。

## Android Release

推送 `v*` 标签会触发 `.github/workflows/android-release.yml`，在 GitHub runner 上构建并发布 `arm64-v8a` APK，同时上传 SHA-256 校验文件。当前版本为 `1.1.0`。

页面使用 `chess.js` CDN 生成合法走法。若需要接入本地 Stockfish 19 WASM，可将 UCI worker 放入 `stockfish/` 并在页面中替换引擎适配器。

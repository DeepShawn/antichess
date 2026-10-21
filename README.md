# 谱着 · Chess Lab 2.0

一个会"像人一样下棋"的国际象棋练习室：大师谱着开局库 + Stockfish 19 + 可调的真人感选着人格。
网页版与原生 Android 版共享同一套规则、开局库、棋子矢量与选着算法。

## 亮点

- **真引擎，不再是装饰**：网页内置 Stockfish 19 Lite（WASM，离线可用）；Android 以本地 UCI 进程运行官方 Stockfish 19 arm64。任一不可用时自动切换到自研的谱着内置引擎（PVS、置换表、空着裁剪、LMR、静态搜索）。
- **谱着人格**：引擎用 MultiPV 给出一选 / 二选 / 三选…，再按「一选主线 / 二选偏好 / 三选偏好 / 纯一选 / 均匀探索」与难度温度抽样；开局阶段从 65+ 条大师谱着中按热度抽取；可选低频错棋注入。面板实时展示每个候选的评估与抽样概率，走法记录里标注「谱 / 2 / 3 / 误」。
- **弃子打法**：新偏好。引擎在自己的主变里检查每个候选是否"立刻交出子力且不能马上赢回"，并且评估仍然成立；弃掉至少一个轻子且接近最佳的标为**妙棋 !!**，其余可靠的弃子（含兵的弃兵）标为**好棋 !**。只在存在可靠弃子时提高其概率，没有就正常下，不会为弃而弃。
- **六档难度**：入门 800 → 初级 1100 → 中级 1400 → 高级 1800 → 大师 2200 → Stockfish 19 全力（约 2850，参考值）。
- **复盘**：评估条、胜率曲线（点击跳转）、每步评级（?! / ? / ??）、双方准确率（Lichess 胜率模型），一键全盘复盘；导入 PGN 自动复盘。
- **分析模式**：双方自由走子，引擎持续分析，棋盘箭头显示前三候选；从任意历史局面分支。
- **体验**：拖动或点击走子、平滑动画、升变选择、右键画箭头/圈、合成音效与触感反馈、3 套界面主题 × 4 套棋盘、键盘快捷键、PGN/FEN 导入导出与分享链接、自动保存与恢复、PWA 可安装离线使用。

## 结构

```
web/                     静态网页（GitHub Pages 发布此目录）
  src/core/chess.js      0x88 规则内核：走法生成、SAN/FEN/PGN、和棋判定（perft 校验）
  src/core/book.js       开局库（按局面哈希识别转位）
  src/engine/            内置引擎、UCI Worker、Stockfish/内置统一客户端、谱着人格、复盘评级
  src/ui/                棋盘、评估曲线、图标、音效
  data/openings.txt      ← 开局库，网页与 Android 共用
  data/pieces.json       ← Cburnett 棋子矢量，网页与 Android 共用
  vendor/stockfish/      Stockfish 19 Lite 单线程 WASM（GPLv3）
core/                    纯 Kotlin 内核（规则 / 引擎 / 开局库 / 人格 / 复盘），JVM 单元测试
app/                     Android 原生应用（无 WebView、无 androidx 依赖，Canvas 自绘）
tests/                   网页内核测试（node --test）
tools/                   本地静态服务器、棋子矢量生成脚本
```

## 开发

```bash
npm test                  # JS 内核、引擎与人格测试（含 6 组标准 perft）
npm run serve             # http://localhost:4173
gradle :core:test         # Kotlin 内核测试（同一套 perft 与开局库校验）
gradle assembleRelease    # Android APK（需要 Android SDK）
```

ES 模块需要通过 HTTP 访问，直接双击 `index.html` 无法运行，请使用 `npm run serve`。

## Android

- 仅打包 `arm64-v8a`。Release 工作流从官方 `sf_19` 发布包下载 Stockfish 19 Android ARM64，放到 `app/src/main/jniLibs/arm64-v8a/libstockfish.so`。
  Android 10+ 禁止执行应用私有目录中的二进制，只有安装时解压到 `nativeLibraryDir` 的原生库可以执行——这修复了旧版本引擎无法启动的问题。详见 `docs/STOCKFISH.md`。
- 没有 Stockfish 时（例如本地调试构建）自动使用 Kotlin 内置引擎。
- 发布签名见 `docs/RELEASE_SIGNING.md`。推送 `v*` 标签会构建并发布签名 APK 与 SHA-256 校验文件。当前版本 `2.1.0`。

## CI / 发布

- `dual-platform-ci.yml`：每次推送 `main` / PR 运行 JS 测试与网页冒烟测试、Kotlin 内核测试、Android 构建。
- `pages.yml`：发布 `web/` 到 GitHub Pages（Settings → Pages → Source 选择 GitHub Actions）。
- `android-release.yml`：标签发布 Android APK。

## 许可与致谢

- Stockfish 19 © The Stockfish developers，GPLv3；WASM 构建来自 [stockfish.js](https://github.com/nmrugg/stockfish.js)（`web/vendor/stockfish/Copying.txt`）。
- 棋子 © Colin M.L. Burnett（Cburnett），CC BY-SA 3.0，经 [cm-chessboard](https://github.com/shaack/cm-chessboard) 整理。

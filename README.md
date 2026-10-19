# 谱着 · Chess Lab

一个无需构建的浏览器国际象棋练习软件。打开 `index.html` 即可使用。

## 功能

- 大师谱着开局库，按权重抽取一选、二选、三选及备选走法
- 低频错棋注入，让对局更接近真人
- 可点击棋盘、合法走法提示、悔棋、新局、走法记录
- Stockfish 19 UCI 状态位与评估面板

页面使用 `chess.js` CDN 生成合法走法。若需要接入本地 Stockfish 19 WASM，可将 UCI worker 放入 `stockfish/` 并在页面中替换引擎适配器。

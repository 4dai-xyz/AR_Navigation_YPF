# UI 参考资产清单 v0.1

更新时间：2026-05-08

本目录用于存放 Android 最终版 UI 交付参考图。实现时不要平均参考所有图片，而要按下列优先级理解。

## 1. 文件与用途

| 文件名 | 尺寸 | 用途 |
| --- | --- | --- |
| `01-final-product-board.png` | `1536 x 1024` | 全局气质主参考 |
| `02-near-term-board.png` | `1536 x 1024` | 当前工程约束下的过渡方案参考 |
| `03-search-flow-board.png` | `1672 x 941` | 搜索首页、目标确认页参考 |
| `04-indoor-debug-board.png` | `1536 x 1024` | 室内导航、低置信度、到达态参考 |
| `05-search-results-dense-board.png` | `1536 x 1024` | 搜索结果页修正版主参考 |
| `06-hidden-debug-controls-board.png` | `1672 x 941` | 隐藏 Debug 和十字方向键修正版主参考 |

## 2. 参考优先级

### 全局视觉

优先参考：

`01-final-product-board.png`

### 搜索相关

优先参考：

- `03-search-flow-board.png`
- `05-search-results-dense-board.png`

其中 `05` 覆盖 `03` 中的搜索结果页表现。

### 室内与调试相关

优先参考：

- `04-indoor-debug-board.png`
- `06-hidden-debug-controls-board.png`

其中 `06` 覆盖 `04` 中关于隐藏 Debug 入口和十字方向键面板的表现。

## 3. 使用规则

- 不要把这些图理解为“必须逐像素复刻的海报”。
- 这些图的作用是给出明确的结构、层次、密度和视觉语言。
- 真正的实现规范以 `android/docs/ui-final-handoff-spec-v0.1.md` 为准。

## 4. 开发时最容易误读的点

- 搜索结果页不是小抽屉，而是高密度主结果页。
- 十字方向键不是首屏常驻控件，而是隐藏二级层。
- 外部高德导航按钮不是调试能力，而是正式产品能力。

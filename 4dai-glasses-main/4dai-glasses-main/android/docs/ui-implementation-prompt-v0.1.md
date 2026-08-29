# Android App UI 实现提示词 v0.1

更新时间：2026-05-08

## 1. 用途

本文档给“负责落地 Android UI 的开发者 AI”使用。目标不是讨论设计，而是约束它如何在当前仓库上安全、准确地把最终版 UI 做出来。

## 2. 先读哪些文档

实现前必须先完整阅读：

1. `android/docs/ui-final-handoff-spec-v0.1.md`
2. `android/docs/ui-screen-state-matrix-v0.1.md`
3. `android/docs/ui-design-tokens-v0.1.json`
4. `android/docs/ui-optimization-plan-v0.1.md`
5. `android/docs/manual-indoor-demo-mode-v0.1.md`

实现时必须同时参考这些图片：

- `android/docs/assets/ui-final-handoff-v0.1/01-final-product-board.png`
- `android/docs/assets/ui-final-handoff-v0.1/05-search-results-dense-board.png`
- `android/docs/assets/ui-final-handoff-v0.1/06-hidden-debug-controls-board.png`

其中：

- 全局视觉以 `01` 为准
- 搜索结果页以 `05` 为准
- 隐藏 Debug 和十字方向键以 `06` 为准

## 3. 代码实现边界

当前工程边界如下，不得擅自越界：

- 继续使用 XML View 体系，不切 Compose
- 不重写整套导航状态机
- 不删除现有高德室外导航能力
- 不删除现有室内底图宿主
- 不删除 `manual_demo`
- 不删除 Debug 联调链路
- 不把当前未实现的自动入场/自动切室内伪装成已实现

## 4. 当前仓库中可直接复用的能力

实现时优先复用以下对象，而不是另起一套平行逻辑：

- `PocUiState.TopCardUiModel`
- `PocUiState.BottomActionBarUiModel`
- `PocUiState.MapChromeUiModel`
- `PocUiState.DebugPanelUiModel`
- `MainActivity`
- `MainViewModel`
- `AmapExternalNavigationLauncher`
- `ManualIndoorDemoController`

## 5. 这次实现要达成的核心目标

### 5.1 视觉收口

把主界面从“调试控制台”收口成“地图主界面 + 轻浮层”：

- 顶部大状态卡改为紧凑状态胶囊
- 底部大面积按钮堆改为单主动作 Dock
- 搜索结果页改为高密度结果主页面
- 默认不暴露十字方向键

### 5.2 交互收口

- `店铺 / 商场入口 / 写字楼 / 小区` 作为搜索筛选器
- `店铺` 的室外终点是入口，不是店铺门口
- 在目标确认页、路线就绪页加入“外部高德导航”按钮
- 通过长按顶部状态胶囊进入隐藏 Debug Drawer
- 通过 Debug Drawer 再进入十字方向键与楼层控制面板

## 6. 推荐改动文件

实际实现时大概率需要修改或新增以下文件：

- `android/ai-glasses-poc/app/src/main/res/layout/activity_main.xml`
- `android/ai-glasses-poc/app/src/main/java/com/aiglasses/poc/MainActivity.kt`
- `android/ai-glasses-poc/app/src/main/java/com/aiglasses/poc/PocUiState.kt`
- `android/ai-glasses-poc/app/src/main/java/com/aiglasses/poc/MainViewModel.kt`
- `android/ai-glasses-poc/app/src/main/res/values/strings.xml`
- 必要时新增若干 `drawable` 资源，承载胶囊、Dock、结果页面板背景

## 7. 推荐实现顺序

### 阶段 1：全局骨架

- 把现有页面骨架收成：
  - 全屏地图宿主
  - 顶部搜索胶囊/状态胶囊
  - 浮动地图按钮
  - 底部主 Dock

验收：

- 不展开 Debug 也看起来像正式产品

### 阶段 2：搜索流

- 增加类型筛选器
- 做高密度搜索结果页
- 做目标确认页
- 接入 `高德导航` 次按钮

验收：

- 搜索结果一屏能看到 6 到 8 条
- `店铺` 路径能明确“店铺 -> 最近入口”

### 阶段 3：室外到入口

- 完成路线就绪页
- 完成接近入口后的 `进入场馆`
- 将 `继续高德` 接到当前外部会话逻辑

验收：

- 内部导航与外部高德两条路径都可用

### 阶段 4：室内主界面

- 默认只显示 `上一步 / 继续 / 更多`
- 顶部胶囊显示当前指令与楼层
- 不在首屏暴露十字方向键

验收：

- 室内页面默认看起来像正式产品

### 阶段 5：隐藏 Debug

- 长按顶部状态胶囊进入 Debug Drawer
- 从 Drawer 进入十字方向键和楼层控件
- 保留联调参数与日志，但不首屏常驻

验收：

- 正常用户路径看不到调试台
- 内部演示仍然可完整推进 `manual_demo`

## 8. 关键实现注意事项

### 8.1 不要把页面做成“概念稿截图”

目标是把概念图翻译成真实可交互 Android UI，而不是把概念图当成静态拼图照搬。

### 8.2 不要重造现有逻辑

例如外部高德导航已经有：

- 文案状态
- 回流检测
- 距入口距离判断

实现 UI 时应直接复用，不要再发明一套新会话状态。

### 8.3 不要让 Debug 再度首层化

如果为了省事把参数表单留在首屏下方，即视为未达标。

### 8.4 不要让 `继续` 按钮撒谎

如果当前 `manual_demo` 期望动作不是直行，则必须通过二级控件完成，不要让主按钮假装能代表所有方向动作。

## 9. 必须完成的验收清单

- 首屏不再出现明显的工程调试表单
- 顶部显示为紧凑胶囊而非大块状态卡
- 搜索结果页一屏可见至少 6 条结果
- 目标确认页有 `前往入口` 和 `高德导航`
- 室外路线就绪页有 `开始导航` 和 `打开高德`
- 室内主界面默认只有 `上一步 / 继续 / 更多`
- 长按顶部状态胶囊可进入隐藏 Debug Drawer
- 可从 Debug Drawer 进入十字方向键与楼层控件
- 现有高德室外导航、室内底图、`manual_demo`、外部高德跳转均不回归

## 10. 一句话实现目标

把当前 App 从“能联调的 Demo 控制台”收口成“可展示、可跑主流程、调试能力藏在二级层的正式产品界面”，并且不牺牲现有真实能力。

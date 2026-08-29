# Android UI 与现有功能干涉点说明 v0.1

更新时间：2026-05-09

本文档记录当前最终版 UI 落地后，已经确认会和现有交互逻辑、状态机或演示链路发生摩擦的点，供后续 APP 开发继续完善。

## 1. 搜索结果页仍然依赖输入框焦点驱动

- 现象：
  - 搜索结果页是否展开，当前仍由 `EditText.hasFocus()`、关键词长度和结果列表是否为空共同决定。
  - 一旦后续接入更真实的输入法、语音回填或联想搜索，视觉层很容易和焦点时序互相影响，出现“结果页误收起”或“刚出结果又回到首页”的抖动。
- 现有代码入口：
  - `android/ai-glasses-poc/app/src/main/java/com/aiglasses/poc/MainActivity.kt`
  - 关键函数：`renderSearchSurface()`、`shouldShowExpandedSearchResults()`、`scheduleOutdoorPoiSearch()`
- 建议：
  - 后续 APP 开发把“输入中”“结果页展开”“已选中结果”拆成明确状态，不再只依赖焦点推导 UI。
  - 输入法收起、结果页展开、选点回填三件事建议分别建状态或事件。

## 2. 搜索结果页当前 7 条高密列表，属于模拟器演示补全逻辑

- 现象：
  - 为了匹配设计稿的一屏 7 条高密结果，模拟器降级链路下会用 demo seeds 补满结果数。
  - 这保证了视觉效果，但并不代表真实高德搜索接口在不同关键词下也一定返回相同数量、相同类别和相同排序。
- 现有代码入口：
  - `android/ai-glasses-poc/app/src/main/java/com/aiglasses/poc/MainActivity.kt`
  - 关键函数：`buildDemoOutdoorPoiResults()`、`filterOutdoorPoiOptions()`
- 建议：
  - 后续 APP 开发接真实搜索接口后，需要单独做一轮“真实数据密度校准”。
  - 若真实结果数不足，是否补推荐项、是否混排入口/店铺，需要产品和客户端共同定规则。

## 3. 隐藏 Debug 入口现在可从首页触发，但只在“空搜索态”存在

- 现象：
  - 隐藏 Debug 入口已经从“顶部状态胶囊长按”切到“搜索首页右上麦克风长按”。
  - 但该入口仅在搜索关键词为空、且右侧显示麦克风时可用；一旦输入关键词，右侧会切成清空按钮，隐藏入口就暂时消失。
- 现有代码入口：
  - `android/ai-glasses-poc/app/src/main/java/com/aiglasses/poc/MainActivity.kt`
  - 关键函数：`bindHiddenDebugReveal()`、`renderSearchSurface()`
- 建议：
  - 若后续要求“任何首页状态都能调出隐藏调试”，需要补一个更稳定但同样不显眼的触发热区。
  - 若保持当前方案，需要在交付说明里明确“仅空搜索首页可长按触发”。

## 4. 语音输入图标当前仅保留视觉入口

- 现象：
  - 搜索胶囊右侧麦克风图标已经按最终版 UI 保留。
  - 目前它承担隐藏调试触发热区，但没有真正的语音识别搜索能力。
- 现有代码入口：
  - `android/ai-glasses-poc/app/src/main/res/layout/activity_main.xml`
  - `android/ai-glasses-poc/app/src/main/java/com/aiglasses/poc/MainActivity.kt`
- 建议：
  - 后续 APP 开发接系统语音识别或业务语音检索后，需要重新分离“语音搜索功能”和“隐藏调试长按入口”的交互语义。
  - 若短期不接语音，建议在 PRD 中标记为“预留视觉入口，功能待补”。

## 5. 手动演示快捷入口会绕过正式室外 -> 室内交接链路

- 现象：
  - 当前在隐藏调试抽屉点击“手动演示”时，如果还不在室内态，会直接调用 `enterVenue()` 进入室内演示，再展开方向控制面板。
  - 这非常适合 UI 回归，但它跳过了正式产品链路里的入口确认、状态校验和真实室内初始化。
- 现有代码入口：
  - `android/ai-glasses-poc/app/src/main/java/com/aiglasses/poc/MainActivity.kt`
  - 关键函数：`bindHiddenDebugQuickActions()`
- 建议：
  - 后续 APP 开发应区分“调试快捷跳转”和“正式导航交接”两条链路。
  - 正式版不建议直接复用当前这条 shortcut 逻辑作为生产交接入口。

## 6. 模拟器地图底图与真机高德底图仍存在天然偏差

- 现象：
  - 当前室外与室内 UI 为了在模拟器里稳定展示，继续保留了 fallback 地图视图。
  - 因此顶部胶囊、路径线、楼层 pills、目标标记、右侧浮动按钮之间的相对位置，只能做到“接近设计稿”，不能视为真机最终精确位置。
- 现有代码入口：
  - `android/ai-glasses-poc/app/src/main/java/com/aiglasses/poc/OutdoorMapBackdropView.kt`
  - `android/ai-glasses-poc/app/src/main/java/com/aiglasses/poc/IndoorFallbackMapView.kt`
  - `android/ai-glasses-poc/app/src/main/java/com/aiglasses/poc/MainActivity.kt`
- 建议：
  - 后续 APP 开发在真机高德底图上再做一轮位置校准。
  - 重点核对顶部胶囊、目标点气泡、路径线、楼层切换 pill、右下浮动按钮之间是否互相遮挡。

## 7. 搜索结果点击与地图收起覆盖层仍需真机补验

- 现象：
  - 当前结果项点击逻辑本身已经稳定，但页面里仍存在 `mapCollapseTouchLayer` 这类用于收起面板的覆盖层。
  - 模拟器下已基本可用，真机上仍建议补一次触控命中与手势回放验证，避免边缘区域被上层拦截。
- 现有代码入口：
  - `android/ai-glasses-poc/app/src/main/java/com/aiglasses/poc/MainActivity.kt`
  - 关键函数：`buildOutdoorPoiResultRow()`、`applyOutdoorPoiToEntry()`、`renderMapCollapseTouchLayer()`
- 建议：
  - 后续 APP 开发在真机上检查结果列表区域、地图收起层和底部 sheet 的触摸分发。
  - 必要时把结果页改成独立容器，避免收起层参与命中竞争。

## 8. 手动演示里的“下”方向键目前仅保留视觉入口

- 现象：
  - 为了避免错误语义，手动演示十字方向键中的“下”按键不再复用“回退一步”逻辑。
  - 当前点击后只提示“该能力已预留”，不会真正推动室内演示状态机。
- 现有代码入口：
  - `android/ai-glasses-poc/app/src/main/java/com/aiglasses/poc/MainActivity.kt`
  - 关键函数：`bindManualIndoorActions()`
- 建议：
  - 后续 APP 开发需要明确“下”按键在正式产品里的语义，到底表示后退、下移视角还是楼层/路线相关动作。
  - 在语义未定前，维持当前 no-op 占位比错误绑定旧逻辑更安全。

# Android 室内语音提醒实现方案 V0.1

更新时间：2026-05-07

## 1. 文档目的

本文档定义 Android App 侧“室内导航语音提醒”的技术方案、实施拆分、验证口径和风险边界。

当前状态：

- 室外导航：真实高德 SDK / 外部高德 App 兜底。
- 室内导航：默认 `manual_demo` 手动演示模式。
- 室内地图：高德室内底图 + App 业务覆盖物。
- 语音提醒：尚未实现。

本方案只覆盖 Android App 侧，不修改云端、建图或 OpenAPI 契约。

## 2. 目标与非目标

### 2.1 目标

- 在室内 `manual_demo` 流程中播报关键导航提示。
- 在错误按键、跨楼层、到达目标等节点提供明确语音反馈。
- 不依赖网络，不依赖云端 TTS。
- 不影响当前高德室外导航主线。
- 提供 Debug 开关、测试播报和日志，便于现场联调。
- 为后续接入真实室内路径指令或眼镜端语音输出保留替换点。

### 2.2 非目标

- 不实现云端语音合成。
- 不实现后台持续语音导航服务。
- 不替代高德室外导航语音。
- 不接入眼镜端真实音频 SDK。
- 不做多语言复杂配置。
- 不做商业级无障碍语音体系。

## 3. 技术选型

首版采用 Android 系统 `TextToSpeech`：

- 离线可用，适合 Demo。
- 不增加重量级依赖。
- 能满足短语音提醒、纠错和到达播报。
- 和当前 XML / View + ViewModel 架构兼容。

音频焦点采用 Android `AudioFocusRequest`：

- 播报前申请短暂音频焦点。
- 播报完成或失败后释放焦点。
- 焦点失败不阻塞导航，只记录日志。

推荐焦点策略：

```text
AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
```

## 4. 总体架构

```text
PocUiState / ManualIndoorDemoState
        ↓
VoiceCueFactory
        ↓
VoiceGuidanceManager
  - 去重
  - 节流
  - 优先级
        ↓
AndroidTtsSpeaker
  - TextToSpeech
  - AudioFocusRequest
        ↓
系统语音播报 + Debug 日志
```

### 4.1 模块划分

新增轻量包：

```text
ai-glasses-poc/app/src/main/java/com/aiglasses/poc/voice/
```

计划文件：

```text
VoiceCue.kt
VoiceCueFactory.kt
VoiceGuidanceManager.kt
AndroidTtsSpeaker.kt
```

职责：

| 文件 | 职责 |
| --- | --- |
| `VoiceCue.kt` | 定义语音提醒数据模型 |
| `VoiceCueFactory.kt` | 从 `PocUiState` 生成候选语音 |
| `VoiceGuidanceManager.kt` | 管理开关、去重、节流和优先级 |
| `AndroidTtsSpeaker.kt` | 封装系统 TTS、音频焦点和生命周期释放 |

## 5. 数据模型

### 5.1 VoiceCue

```kotlin
data class VoiceCue(
    val id: String,
    val text: String,
    val source: VoiceCueSource,
    val priority: VoiceCuePriority,
    val dedupeKey: String,
)
```

### 5.2 VoiceCueSource

```kotlin
enum class VoiceCueSource {
    INDOOR_ENTERED,
    MANUAL_STEP,
    MANUAL_CORRECTION,
    FLOOR_CHANGED,
    ARRIVED,
    RESET,
    EXIT_INDOOR,
    TEST,
}
```

### 5.3 VoiceCuePriority

```kotlin
enum class VoiceCuePriority {
    NORMAL,
    IMPORTANT,
    INTERRUPT,
}
```

优先级规则：

- `NORMAL`：普通步骤提示，不打断上一条。
- `IMPORTANT`：跨楼层、进入室内、退出室内。
- `INTERRUPT`：纠错、到达、测试播报，可打断普通提示。

## 6. 状态触发规则

### 6.1 进入室内

触发条件：

```text
navState 从 OUTDOOR / HANDOFF 进入 INDOOR_READY 或 INDOOR_ROUTE_READY
indoorMode == MANUAL_DEMO
```

播报示例：

```text
已进入室内导航，目标是二楼 TATA 店铺门口。
```

### 6.2 手动演示步骤变化

触发条件：

```text
manualIndoorDemo.stepIndex 变化
manualIndoorDemo.arrived == false
manualIndoorDemo.correction == null
```

播报来源：

```text
manualIndoorDemo.instruction
```

当前五道口脚本示例：

| 步骤 | 播报 |
| --- | --- |
| F1 西门入口 | 从西门进入，沿一层通道直行至西侧通道转角 |
| F1 西侧通道转角 | 左转后沿主通道前往一层上行扶梯 |
| F1 上行扶梯口 | 乘扶梯上行至 F2 |
| F2 扶梯出口 | 到达 F2 后直行，沿店铺连廊前进 |
| F2 TATA 连廊转角 | 按提示右转或左转，前往 TATA 店铺门口 |

### 6.3 错误按键纠错

触发条件：

```text
manualIndoorDemo.correction 从 null 变为非空
```

播报示例：

```text
当前应执行：右转。
```

纠错提示使用 `INTERRUPT`，可打断普通步骤播报。

### 6.4 跨楼层

触发条件：

```text
manualIndoorDemo.currentFloorId 变化
```

播报示例：

```text
已切换到 F2，请继续前进。
```

跨楼层提示可和当前步骤提示合并，避免连续播报过长。

### 6.5 到达目标

触发条件：

```text
manualIndoorDemo.arrived 从 false 变为 true
```

播报示例：

```text
已到达二楼 TATA 店铺门口。
```

到达提示使用 `INTERRUPT`。

### 6.6 重置和退出室内

重置播报：

```text
室内演示已重置。
```

退出室内播报：

```text
已退出室内导航。
```

## 7. 去重与节流

语音提醒必须避免因 UI 重绘反复播报。

### 7.1 去重 Key

推荐：

```text
dedupeKey = navState + indoorMode + stepIndex + currentFloorId + correction + arrived
```

同一个 `dedupeKey` 只播一次。

### 7.2 节流

默认规则：

- 普通提示间隔不少于 `800ms`。
- 纠错和到达提示可立即打断普通提示。
- 同一纠错内容连续触发时只播第一次。

### 7.3 队列策略

首版不维护复杂队列：

- TTS 未初始化时，只保留最近一条 `IMPORTANT` 或 `INTERRUPT`。
- 普通提示在 TTS 未就绪时可丢弃。
- 新的 `INTERRUPT` 到来时停止当前普通播报。

## 8. TTS 与音频焦点

### 8.1 初始化

`AndroidTtsSpeaker` 在 `MainActivity.onCreate()` 后初始化。

初始化目标：

```text
language = Locale.CHINA
speechRate = 1.0f
pitch = 1.0f
```

初始化结果写入日志：

```text
voice_tts_init_success
voice_tts_init_failed reason=...
```

### 8.2 播报

播报流程：

```text
requestAudioFocus()
        ↓
TextToSpeech.speak()
        ↓
onDone / onError
        ↓
abandonAudioFocus()
```

日志：

```text
voice_cue_play id=... source=... text=...
voice_cue_done id=...
voice_cue_error id=... reason=...
voice_focus_failed id=...
```

### 8.3 生命周期

| 生命周期 | 处理 |
| --- | --- |
| `onPause()` | 不强制 shutdown；可停止当前播报 |
| `onDestroy()` | 调用 `TextToSpeech.shutdown()` |
| App 后台 | 不播报新的室内提示 |

首版只服务前台 Demo，不实现后台服务。

## 9. UI 与设置

语音控制放在 Debug 面板，不占用主界面。

新增控件：

| 控件 | 默认值 | 说明 |
| --- | --- | --- |
| `语音提醒` | 开启 | 控制室内语音提醒总开关 |
| `测试播报` | - | 播放固定测试语音 |
| `语速` | `1.0` | 可先不做滑杆，使用固定值 |
| `最近语音` | - | 显示最后一次播报文本 |

首版最小实现只需要：

- `CheckBox`：语音提醒开关。
- `Button`：测试播报。
- `TextView`：最近一次语音。

配置持久化：

```text
voice_enabled
```

语速和音调暂不要求持久化，可固定为默认值。

## 10. 与现有模块的关系

### 10.1 MainActivity

`MainActivity` 只做绑定和生命周期转发：

```kotlin
voiceGuidanceManager.onStateChanged(state)
voiceGuidanceManager.setEnabled(binding.checkVoiceGuidance.isChecked)
voiceGuidanceManager.shutdown()
```

不要在每个按钮点击里直接写 TTS 播报。

### 10.2 MainViewModel

首版不强制修改 `MainViewModel`。

如需记录日志，可采用回调：

```kotlin
onVoiceLog: (String) -> Unit
```

由 `MainActivity` 初始化 `VoiceGuidanceManager` 时注入：

```kotlin
voiceGuidanceManager = VoiceGuidanceManager(
    speaker = AndroidTtsSpeaker(...),
    onLog = viewModel::pushVoiceLog,
)
```

若 `pushVoiceLog` 不适合公开，可新增最小方法：

```kotlin
fun onVoiceGuidanceEvent(summary: String)
```

### 10.3 ManualIndoorDemoController

不直接依赖语音模块。

语音从 `ManualIndoorDemoState` 派生，避免控制器承担 UI / 音频职责。

### 10.4 室外高德导航

室外语音继续交给高德 SDK 或外部高德 App。

App 自有 TTS 只在以下情况播报：

```text
indoorMode == MANUAL_DEMO
navState in INDOOR_*
```

避免室外阶段出现双语音导航。

## 11. 实施拆分

### 阶段 A：最小可演示

目标：让室内手动演示有语音反馈。

改动范围：

```text
app/src/main/java/com/aiglasses/poc/voice/
app/src/main/java/com/aiglasses/poc/MainActivity.kt
app/src/main/res/layout/activity_main.xml
app/src/main/res/values/strings.xml
```

任务：

- 新增 `VoiceCue` 模型。
- 新增 `VoiceCueFactory`。
- 新增 `AndroidTtsSpeaker`。
- 新增 `VoiceGuidanceManager`。
- 在 `renderScreen(state)` 后调用 `onStateChanged(state)`。
- Debug 面板新增语音开关、测试播报和最近语音。
- 记录基础日志。

验收：

- 进入室内时播报目标。
- 每次正确推进步骤只播一次。
- 错误动作播报纠错。
- 到达 TATA 门口播报到达。
- 关闭语音后不再播报。

### 阶段 B：稳定性收口

目标：减少误播和重复播报。

任务：

- 增加去重单测。
- 增加节流单测。
- 增加 TTS 初始化失败降级逻辑。
- 增加音频焦点失败日志。
- 增加 App 前后台状态保护。

验收：

- UI 重绘不会重复播同一句。
- 连续快速按键不会造成语音堆积。
- TTS 不可用时 App 不崩溃。

### 阶段 C：现场增强

目标：支持真实场馆联调时更自然的播报。

任务：

- 支持基于路线节点类型生成更自然文案。
- 支持跨层提示合并。
- 支持“接近目标”类提示。
- 支持蓝牙耳机 / 眼镜端输出替换点。

验收：

- 五道口现场演示时，语音与 UI 步骤一致。
- 路线点位复核后，播报不需要改代码即可跟随脚本文案变化。

## 12. 测试方案

### 12.1 JVM 单测

新增测试：

```text
VoiceCueFactoryTest
VoiceGuidanceManagerTest
```

覆盖：

- 进入室内生成 `INDOOR_ENTERED`。
- stepIndex 变化生成 `MANUAL_STEP`。
- correction 生成 `MANUAL_CORRECTION`。
- arrived 生成 `ARRIVED`。
- 相同 dedupeKey 不重复播。
- 关闭开关时不播。

### 12.2 手工验证

流程：

```text
启动 App
进入场馆
开启语音提醒
按 上
按 错误方向
按 右
按 上楼
按 上
按 左
到达 2F TATA
关闭语音提醒
重置并再次推进
```

观察：

- 语音内容是否与顶部提示一致。
- 纠错是否打断普通提示。
- 到达提示是否只播一次。
- 关闭后是否静音。
- Debug 日志是否记录 `voice_cue_*`。

### 12.3 构建验证

必须执行：

```text
F:\Gradle\gradle-8.6\bin\gradle.bat :app:assembleDebug --no-daemon --console=plain
F:\Gradle\gradle-8.6\bin\gradle.bat :app:testDebugUnitTest --no-daemon --console=plain
```

## 13. 风险与处理

| 风险 | 处理 |
| --- | --- |
| 设备未安装中文 TTS | UI 显示不可用，日志记录，不阻塞导航 |
| 系统静音或媒体音量过低 | Debug 面板显示最近语音，辅助判断 |
| UI 重绘重复播报 | `dedupeKey` 去重 |
| 快速连续点击造成语音堆积 | 节流 + `INTERRUPT` 打断策略 |
| 与高德室外语音冲突 | 只在室内 `manual_demo` 播报 |
| 音频焦点申请失败 | 跳过本次播报并记录 `voice_focus_failed` |
| 眼镜端后续接入 | 保留 `Speaker` 接口，替换 `AndroidTtsSpeaker` |

## 14. 验收口径

语音提醒能力达到以下条件时，可视为首版可用：

- 室内 `manual_demo` 主流程中，关键步骤都有语音提醒。
- 语音内容与顶部状态卡提示一致。
- 错误动作、跨楼层、到达有明确语音反馈。
- 关闭语音开关后不再播报。
- TTS 初始化失败不影响 App 主流程。
- Debug 面板可查看最近一次语音和语音日志。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 通过。

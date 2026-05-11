---
name: ActionPilot 录制引擎整合到 Shizuku AI
description: 将 ActionPilot 的 MapBuilder 录制引擎整合进 Shizuku AI，新增 read_uimap 工具
type: project
originSessionId: f939919f-f8fe-4aba-9408-cbe4d7d55560
---
# ActionPilot 录制引擎整合（2026-05-11）

## 改动内容
- 新增 `UiMapRecorder.java` — 精简版 MapBuilder，含数据模型（UiNode/UiEdge/UiAction）、JSON 持久化（org.json，无 Gson 依赖）、AI 导出
- `ShizukuAccessibilityService.kt` — 加静态 `uiMapRecorder` 引用，窗口切换/点击/屏幕内容事件透传给录制器
- `FloatService.java` — 球模式（showBallOverlay）自动 startUiRecording，销毁时 stopUiRecording；加 `read_uimap` 工具调度；加 Shizuku dumpsys 轮询（1.5s 间隔）作为窗口切换 fallback
- `AIAgent.java` — 工具清单加第 13 个工具 `read_uimap`

## 数据流
```
SiriBall 显示 → UiMapRecorder.start()
   ├─ A11y 事件 → onWindowChanged / onAction("CLICK",...) / onScreenContent(...)
   └─ dumpsys 轮询 → onWindowChanged (fallback)

AI 调 read_uimap → exportForAI() → JSON (apps/screens/flows/timeline)

关闭/销毁 → stop() → 持久化到 uimap.json（累积更新）
```

## 其他修复
- `list_apps` — 改用 `pm list packages`（原为 `-3` 只列第三方），系统应用标记 `[系统]`，count 上限从 30→60
- `set_alarm` — 加 `SKIP_UI=true`（vivo 跳过确认直接保存）
- 原子笔记包名: `com.android.notes`（vivo 系统应用）
- 版本号 1.6.0（SiriBall 整合版基础上）

## create_note 工具
- 新增第 14 个工具 `create_note`
- 用 `am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "内容" -p com.android.notes` 打开原子笔记编辑界面并预填内容
- 支持 `title` 参数（`SUBJECT` extra）
- 原子笔记包名 `com.android.notes`（vivo 系统应用）

## 核心理念
所有功能必须工具化（工具说明书模式），AI 通过选工具填参数执行，不猜命令。这是确保 AI 行为准确的核心原则。

## 后续优化（2026-05-11）
- **浮窗高度限制** — `constrainWindowHeight()` 限制浮窗最大为屏幕 60%，对话再多也不会顶出屏幕
- **read_screen 强制刷新** — 服务运行时 lastScreenText 为空则主动调用 `requestScreenRefresh()` 捕获
- **ShizukuAccessibilityService** — 加 `serviceInstance` 静态引用 + `requestScreenRefresh()` 静态方法
- **list_apps** — `pm list packages`（全量）+ `[系统]` 前缀标记

---
name: IME 焦点浮窗问题
description: Shizuku AI 浮窗输入框和后台 App 之间无法切换焦点的踩坑记录
type: project
originSessionId: 22783284-edc4-4ff6-9f11-6060176d9f8c
---
# IME 焦点切换问题

## 症状
TYPE_APPLICATION_OVERLAY 浮窗的 EditText 和后台 App（微信）输入框之间无法自由切换焦点。Operit（Compose UI）丝滑流畅。

## 当前实现（已照抄 Operit 方案）

代码位置：`FloatService.java` lines 238-387

```
ensureFocusDismissView()     → 全屏透明 View 放在浮窗下层，捕获外部点击释放焦点
showKeyboard(et)             → 去掉 NOT_FOCUSABLE + 启用遮罩 → scheduleImeShow
scheduleImeShow(et, retry)   → 延迟 200ms + 最多 4 次重试（window token 就绪检查）
hideKeyboard()               → 取消 pending 任务 → clearFocus → 恢复 NOT_FOCUSABLE → 关遮罩
cancelFocusBeforeExit()      → 退出时清理
focusDismissView.onTouch     → ACTION_DOWN → hideKeyboard()
```

关键参数：
- `IME_FOCUS_DELAY_MS = 200`
- `MAX_IME_FOCUS_RETRIES = 4`
- `IME_FOCUS_RETRY_DELAY_MS = 50`
- Window flags: 显示键盘时去掉 `FLAG_NOT_FOCUSABLE`，加 `FLAG_NOT_TOUCH_MODAL` + `FLAG_WATCH_OUTSIDE_TOUCH`；隐藏键盘时恢复

## 已尝试但失败（含根因分析）

### 1. FloatRunner 方案 — 不加 FLAG_NOT_FOCUSABLE，始终可聚焦 → 微信无法聚焦
**根因**：TYPE_APPLICATION_OVERLAY 在 Z-order 上高于普通 App 窗口。当浮窗没有 NOT_FOCUSABLE 时，触摸事件永远优先被浮窗消费。后台 App 的 EditText 根本收不到触摸事件，不会触发 focus 请求。即使通过 clearFocus 释放焦点，系统焦点也会回到浮窗而非后台窗口——因为系统会将焦点还给"最近聚焦过的可聚焦窗口"。

### 2. 动态 toggle NOT_FOCUSABLE — 输入时去掉，隐藏时加回 → 微信无法聚焦
**根因**：`wm.updateViewLayout()` 切换 flag 只会修改浮窗自身的窗口属性，不会主动唤醒或通知后台窗口请求焦点。它只是"打开了门"，但不负责"请客人进去"。后台 App 的 EditText 需要自己的触摸事件才能请求焦点，但浮窗仍然覆盖在上层。此外 updateViewLayout 本身有几百毫秒的异步延迟，快速切换时焦点状态混乱。

### 3. NOT_FOCUSABLE + ALT_FOCUSABLE_IM → 浮窗自身无法聚焦
**根因**：`FLAG_ALT_FOCUSABLE_IM` 的语义是"当窗口 NOT_FOCUSABLE 时仍然允许 IME 交互"，不是"让窗口可聚焦"。它允许输入法和窗口交互（如输入法可以发送按键事件），但 EditText 本身拿不到输入焦点（focus）。浮窗的 EditText.requestFocus() 会静默返回 false。

### 4. 照抄 Operit 完整方案（当前代码，L238-L387） → 微信仍然无法聚焦
当前实现已经包含：focusDismissView 全屏遮罩 + toggle NOT_FOCUSABLE + scheduleImeShow（200ms延迟 + 最多4次重试 + window token 就绪检查）。

**最可能的根因**（待验证）：
- Compose 的 `FocusRequester` 和传统 View 的 `requestFocus()` 底层行为不同。Compose 通过 `Modifier.onFocusChanged` 更细粒度控制焦点链，而 View 体系的 requestFocus 会触发整个焦点链的重新分配，过程中可能被 Handler 队列中的其他 Runnable（AI 回调、无障碍事件）打断。
- Shizuku AI 同时运行 AccessibilityService 和 AI 网络请求线程。AccessibilityService 的 TYPE_WINDOW_STATE_CHANGED 事件回调可能发生在主线程，打断正在进行的焦点切换。Operit 是纯浮窗，没有这些干扰。

## 关键差异（与 Operit 对比）

| 维度 | Operit | Shizuku AI |
|------|--------|------------|
| UI 框架 | Jetpack Compose（ComposeView） | 传统 XML + EditText |
| 焦点机制 | `FocusRequester` + `Modifier.onFocusChanged` | `EditText.requestFocus()` + `View.clearFocus()` |
| IME 显示 | `scheduleImeShow()` 带重试 | 已照抄相同逻辑 |
| 遮罩 | `focusDismissOverlay` 全屏透明 View | 已实现相同逻辑 |
| 窗口层级 | TYPE_APPLICATION_OVERLAY | 相同 |
| 复杂度 | 纯浮窗，无其他服务干扰 | 同时运行无障碍服务 + Shizuku 回调 + AI 网络请求 |

## 怀疑点（未验证）

1. **`appendAIOutput` 里的 `scroll.fullScroll(FOCUS_DOWN)`** — 已检查并去掉，但问题依旧
2. **无障碍服务干扰** — ShizukuAccessibilityService 可能抢焦点（TYPE_WINDOW_STATE_CHANGED 等事件）
3. **AI 回调频繁 post Handler** — `appendAIOutput` 频繁 post 到主线程，可能打断焦点切换
4. **EditText vs Compose 差异** — 传统 View 体系的 requestFocus 行为可能和 Compose 的 FocusRequester 不同
5. **可能需要 `scheduleImeShow` 更长的延迟** — 甚至 500ms+，等所有 UI 更新完成后再请求焦点
6. **AccessibilityService `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`** — 可能与浮窗焦点冲突

## 调试建议（新对话用）

```bash
# 查看焦点相关日志
adb logcat -s "ShizukuAI" "ShizukuA11y" "WindowManager" "InputMethodManager" "InputMethod"
# 查看当前焦点窗口
adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp|InputMethod"
# 查看无障碍服务状态
adb shell dumpsys accessibility | grep -A 20 "com.shizuku.ai"
```

---
name: AI 工具调用架构套路（from Operit）
description: 让 AI 变聪明的核心不是改提示词，而是给工具说明书。AI 选工具填参数，app 执行验证过的代码。
type: feedback
originSessionId: f94680b6-a3b5-4b2e-ae80-f32511c7d14f
---
# AI 工具调用架构：核心套路（2026-05-09，从 Operit 学到）

## 三条铁律

1. **AI 不构造命令** — AI 只选工具名 + 填参数。命令在 app 代码里写死、验证过。
2. **工具说明书决定智商** — 每次请求把工具清单（name/description/parameters）塞进上下文，AI 看到"我有这些本事"才知道怎么干。
3. **不要改提示词台词** — 改 System Prompt 说"用 [CMD] 格式"没有用。给工具清单让 AI 做选择题，比让它做填空题可靠得多。

## 架构

```
用户说"定个12点闹钟"
  ↓
AI 看到工具清单里有：
  set_alarm(hour, minutes, message?) — "设置闹钟"
  search_web(query) — "联网搜索"
  start_app(package) — "打开应用"
  ...
  ↓
AI 输出：{"tool": "set_alarm", "params": {"hour": 12, "minutes": 0}}
  ↓
App 收到 → 执行写死的代码：
  am start -a android.intent.action.SET_ALARM --ei HOUR 12 --ei MINUTES 0
  ↓
结果喂回 AI → AI 告诉用户"闹钟定好了"
```

## 工具定义格式

每个工具需要：
- **name**: 工具名（AI 用这个调用）
- **description**: 一句话说清干什么（中英双语最佳）
- **parameters**: 参数列表
  - name / type / required / description

## so-ai 的工具设计原则

1. **粒度适中** — 不要太细（`move_left_10px` 这种不行），不要太粗（`do_everything` 也不行）
2. **每个工具一个明确职责** — `toggle_flashlight` 只管手电筒，不做别的
3. **参数尽量少** — AI 填参数越多越容易错，能省则省
4. **失败有回退** — 一个工具内部可以试多种方法（如 flashlight 先 Shizuku shell 再 CameraManager）
5. **工具名称用英文下划线** — AI 对英文名理解更稳定

## 验证过的好用工具实现

### set_alarm
```bash
am start -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR $hour --ei android.intent.extra.alarm.MINUTES $minutes
```
Android 标准 Intent，所有设备通用。

### toggle_flashlight (vivo)
```bash
settings put system FlashState 1 && settings put system back_flashlight_state 1
```
回退：CameraManager.setTorchMode()

### open_app
```bash
monkey -p $package_name 1
```
备选：am start -n $pkg/$activity

---
name: dumpsys window 格式变化（Android 14+）
description: Android 14+ dumpsys window 输出格式改变，mCurrentFocus/parseFocus 踩坑
type: reference
originSessionId: 9de5cf41-4298-4cb4-8a88-9b5feff3ab4c
---
# dumpsys window mCurrentFocus 格式变化（Android 14+）

## 旧格式（Android 13 及以下）
```
mCurrentFocus=Window{...} com.example.app/com.example.app.MainActivity
```
包名/Activity 在 `}` **后面**，用空格分隔。

## 新格式（Android 14+，vivo OriginOS 实测）
```
mCurrentFocus=Window{2d5d089 u0 com.example.app/com.example.app.MainActivity type=1}
```
包名/Activity 在 `{}` **内部**，位于 `u0 ` 和 ` type=` 之间。

## 影响
- ActionPilot RecordService 的 `parseFocus()` 原来找 `}` 后面的内容，新格式下永远返回空
- 修复方案：提取 `{...}` 内容，split 空格，找 `u0` 后直到 `type=` 之间的 token

## 在 ActionPilot 中的修复
- RecordService.kt `parseFocus()`: 改从 `{` `}` 间提取，定位 `u0` 后取 `type=` 前的部分
- 加 Log.e 日志便于下次调试

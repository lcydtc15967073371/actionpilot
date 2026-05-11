---
name: 万能APP源码分析教训
description: 分析万能APP源码后学到的教训，记录读写bug和项目文件管理
type: reference
originSessionId: fea740fd-bef3-417e-8b33-2ccc18b304f6
---
# 万能APP源码分析的教训

## 万能APP的核心问题

打开源码后发现一个致命bug和一个经验：

**致命bug：只有 save 没有 load**
- `saveAppMap()` 在每次 `recordAction()` 和 `disableRecording()` 时都会写 `app_map.json`
- 但 **没有任何 `loadAppMap()` 函数** 把 JSON 读回内存
- 所以 App 重启后 `appMap` 是空的（`mutableMapOf()`），UI 显示空白
- AI 能直接读文件所以"看得到"，App 自己读不到
- 教训：持久化必须成对——写了就要有对应的读

**经验：双通道写入**
- 主通道：`app_map.json`（结构化 JSON，带节点+边，用 tmp+rename）
- 备份通道：`click_log.txt`（纯文本 append 模式，每操作一行）
- 备份通道让 AI 在 JSON 损坏时也能恢复数据
- 教训：重要数据至少写两份

**其他问题：**
- `appMap` 是实例变量，AccessibilityService 重建时重置（不是 companion object）
- UI 状态没有持久化到 SharedPreferences
- JSON 用手拼 StringBuilder 而不是序列化库，容易出格式错误

## 项目文件管理教训
- 分析他人源码的 tar.gz/zip 必须在临时目录解压而不是项目目录
- 解压会覆盖同名文件（如 AndroidManifest.xml、资源文件等）
- 恢复方法：保持源码管理，万一覆盖了能 git restore 或重新生成

## AccessibilityService 录制的正确做法（从万能APP学到的）
- `TYPE_WINDOW_STATE_CHANGED` + `TYPE_VIEW_CLICKED` 足够记录操作，不需要 `TYPE_WINDOW_CONTENT_CHANGED`
- 用 debounce 机制（300ms）防止高频事件压垮 UI
- 异步树遍历用 `Dispatchers.Default`（不会阻塞主线程）
- 点击事件先读 `event.source` 再 `recycle()`，避免内存泄漏
- 每次写入都要 flush + close，不能用缓存

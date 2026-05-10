# 自动压缩机制 (Auto Compact)

## 一句话

对话文件 (`*.jsonl`) 超过 **500KB** 时自动触发 `/compact` 压缩上下文，防止 Claude 变慢。

## 触发逻辑（渐进式）

- **首次触发：** 文件 >= 500KB
- **后续触发：** 距上次压缩后文件再增长 **500KB+**
- 例：500K → 1M → 1.5M → 2M → ...
- 不受时间间隔限制，只看新增内容量

## 工作流程

```
你发消息 → UserPromptSubmit hook → inject_compact.py --hook
  → 检查当前对话 .jsonl 大小
  → < 500KB：不做任何事
  → ≥ 500KB 且比上次压缩时大了 500KB+：
    → 尝试 AttachConsole + CONIN$ 注入（直接终端有效）
    → 失败则写 .compact_needed 标记文件（SSH fallback）
  → REPL 读到 "/compact\r" → 执行压缩
```

## 注入原理

裸 `CreateFileW("CONIN$")` 在 hook 子进程中不可靠（子进程无控制台）。

改用 **AttachConsole(父进程PID)** 方案：
1. `FreeConsole()` — 断开当前（空）控制台
2. `NtQueryInformationProcess` 获取父进程 PID
3. `AttachConsole(父PID)` — 挂到 Claude Code 的控制台
4. `CreateFileW("CONIN$")` — 现在拿到的是父进程的控制台输入
5. `WriteConsoleInputW` — 注入按键
6. `FreeConsole()` — 释放

## 已知限制

| 环境 | 注入效果 | 原因 |
|------|---------|------|
| 直接 Windows 终端 | ✅ 正常 | AttachConsole + CONIN$ 直达 REPL |
| SSH (Cygwin bash) | ❌ 无效 | ConPTY 伪控制台下 `WriteConsoleInputW` 返回成功但数据不转发到 Node.js stdin |

SSH 下 fallback 为写 `.compact_needed` 标记文件，配合 CLAUDE.md 提示手动执行 `/compact`。

## 文件

| 文件 | 作用 |
|---|---|
| `C:/Users/ql/inject_compact.py` | Python 脚本，Hook 自动调用 |
| `C:/Users/ql/.claude/settings.json` | 全局设置，配置 UserPromptSubmit hook |
| `C:/Users/ql/.last_compact` | 标记文件，记录上次压缩时的文件大小 |
| `C:/Users/ql/.compact_log` | 日志文件，记录每次触发详情 |
| `C:/Users/ql/.compact_needed` | SSH fallback 标记 |
| `C:/Users/ql/.claude/projects/C--Users-ql/CLAUDE.md` | 项目指令文件 |

## 当前脚本

见 `C:/Users/ql/inject_compact.py`（~160行，ctypes + Windows API，零依赖）。

## 健壮性

- 🛡️ 全局异常保护：脚本出错不会阻塞发消息
- 🛡️ 渐进触发：不会每次发消息都压，只在增长足够时触发
- 🛡️ 零依赖：Python 内置库 + Windows API
- 🛡️ 静默失败：任何 IO 错误不影响对话流程
- 🛡️ 日志记录：每次触发写入 `.compact_log`
- 🛡️ 跨会话重置：新会话文件小于标记值时自动重置

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-05-10 | 增长阈值 200KB → 500KB（避免频繁触发） |
| 2026-05-10 | 修复跨会话失效 bug（新会话永不触发的逻辑错误） |
| 2026-05-10 | 添加 AttachConsole 方案（原 CONIN$ 在 hook 子进程中无效） |
| 2026-05-10 | 添加 SSH 限制说明 + fallback 标记文件 |

---
name: auto_compact
description: 自感知自动压缩方案 — 对话文件过大时自动注入 /compact（已验证通过）
type: reference
originSessionId: e8394871-271e-4da1-971b-57799b8b29be
---
## 自感知自动压缩方案（Hook 模式，全自动）

对话 .jsonl 文件超过 500KB 时自动尝试注入 `/compact` 命令。
**无需 Claude 参与，自动运行。**

### 原理
- `claude.exe` 是编译后的 PE32+ 原生二进制，不可改源码
- Hook 子进程 `FreeConsole()` + `AttachConsole(ClaudeCodePID)` 挂到 Claude Code 的控制台
- `CreateFileW("CONIN$")` + `WriteConsoleInputW` 注入按键序列
- Claude Code REPL 从输入队列读到 `/compact` 后自动执行

### 触发逻辑（渐进式）
- **首次触发：** 文件 >= 500KB
- **后续触发：** 距上次压缩后文件再增长 **500KB+**
- 例：500K → 1M → 1.5M → 2M → ...
- 不受时间间隔限制，只看新增内容量
- 跨会话自动重置（新会话文件小于标记值时重置为 0）

### 文件
- **脚本：** `C:/Users/ql/inject_compact.py`
  - 支持两种模式：`--hook`（自动检查+注入）和直接运行（仅注入）
  - 零依赖，仅用 Python 内置 `ctypes` + Windows 原生 API
  - 全局异常保护，任何错误不阻塞用户流程
- **配置：** `C:/Users/ql/.claude/settings.json`
  - `hooks.UserPromptSubmit` 配置为每次用户发消息自动调用脚本
  - `permissions.bash` 放行 `python C:/Users/ql/inject_compact.py`
- **标记文件：** `C:/Users/ql/.last_compact`（记录上次压缩时的文件大小）
- **日志文件：** `C:/Users/ql/.compact_log`（记录每次触发时间、文件大小）
- **备用标记：** `C:/Users/ql/.compact_needed`（SSH 等无法注入时的 fallback）
- **指令文件：** `C:/Users/ql/.claude/projects/C--Users-ql/CLAUDE.md`

### 修复记录
- **2026-05-11 逐字符延迟注入**：原版一次 `WriteConsoleInputW` 发 18 条记录（9 字符 × KEY_DOWN+KEY_UP），Node REPL 消费不过来导致丢字（如 `/compact` 变成 `/comac`）。改为逐字符写入 + 80ms sleep，确保 REPL 有足够时间处理每个字符。

### 已知限制
- **SSH 无效**：SSH 会话使用 ConPTY 伪控制台，`WriteConsoleInputW` API 返回成功但 ConPTY 不将控制台输入缓冲区的数据转发到 Node.js 的 `ReadFile` 管道。注入的按键序列 Node.js 收不到。
- **仅在直接 Windows 终端有效**：在 cmd/PowerShell/Windows Terminal 中直接运行 Claude Code 时可正常工作。

### 适用范围
全局设置，所有 Claude Code 会话（包括 Trae）都生效。

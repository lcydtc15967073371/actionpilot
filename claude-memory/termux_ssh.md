---
name: Termux SSH 连接
description: 用户通过 Android Termux SSH 连接到 Windows 机器，切 App 时 SSH 会断连
type: reference
originSessionId: 167f8588-7b7f-44be-be8e-c5b8272ddbb9
---
用户通过 Android Termux SSH 连接到 Windows（Git Bash MINGW64），切换 App 时网络被挂起导致 SSH 断连。

**已知问题**：输入光标脱离、对话断开、回到 shell 提示符。

**推荐的保持连接方案**：
1. SSH keepalive（Termux 端 `~/.ssh/config` 加 `ServerAliveInterval 15`）
2. Termux 下拉通知栏 "Acquire wakelock"（获取唤醒锁）
3. 系统设置关掉 Termux 电池优化

**更可靠的方案**（需 Windows 端操作）：安装 tmux 持久会话，断开 attach 回来。

---

## 双向 SSH 互通（通过 Tailscale）

已配置 Windows → 手机 SSH 直连（用 Tailscale 虚拟网络）：

- **手机 Termux sshd**: 端口 8022，用户名 `u0_a391`
- **Tailscale IP**: 手机 `100.79.166.26` / Windows `100.109.36.79`
- **密钥路径 (Windows 到手机)**: `~/.ssh/termux_key`

### 发送文件到手机

```bash
scp -i ~/.ssh/termux_key -P 8022 文件名 u0_a391@100.79.166.26:~/目标路径/
```

### 从手机拉取文件

```bash
scp -P 8022 ql@100.109.36.79:文件路径 ~/下载目录/
```

### 当前保活方案：电池优化（已验证可行）

**只改电池优化即可稳定保活，无需 SSH Keepalive：**

设置 → 应用 → Termux → 耗电管理 → **"不受限制"**。

**vivo OriginOS 实测结论**：系统挂起进程是断连的根本原因。电池优化设为不受限制后，进程不被挂起，黑屏/切应用均不会断连。SSH 双向心跳（`ClientAliveInterval` / `ServerAliveInterval`）不是必需的。

### 尝试过的其他方案

| 方案 | 结果 | 原因 |
|------|------|------|
| **Keepalive** | ✅ 成功 | 最简单，零依赖 |
| **mosh** | ❌ 失败 | Cygwin 兼容问题：`setsockopt(IP_MTU_DISCOVER)` 是 Linux 特有 API，Windows 不支持。防火墙规则已加但无用。WSL 环境下可行。 |
| **tmux 自动会话** | ❌ 失败 | 只能断后恢复，且 Windows SSH 默认 shell 是 cmd |
| **HTTP 传公钥** | ⚠️ 能用 | 需关闭防火墙或走 Tailscale |
| **scp 传公钥** | ❌ 失败 | 端口配置错误 |

Windows SSH 默认 shell 已改 Cygwin bash（`reg add HKCU\SOFTWARE\OpenSSH /v DefaultShell /d "C:\cygwin64\bin\bash.exe"`）。

## 配置过程踩坑记录

### 子网隔离
同 WiFi 但不同子网（手机 `192.168.31.x`，Windows `192.168.1.x`）无法直连。**必须通过 Tailscale 虚拟网络**。

### 公钥传送
手机屏幕窄，复制长 SSH 公钥会折行导致密钥损坏。推荐方案：
1. Windows 起临时 HTTP 服务器 → Termux 通过 Tailscale IP 用 `curl` 下载
2. 或在 Termux 设置临时密码，Windows 用密码 SSH 登录后手动追加公钥

### Windows 端限制
- Python `pty` 模块不可用（Windows 无 `termios`）
- 无 `sshpass` / `expect`
- 防火墙规则需管理员权限

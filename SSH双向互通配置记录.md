# Termux <-> Windows 双向 SSH 互通配置记录

## 背景

用户通过手机 Termux SSH 连接到 Windows（Claude Code 运行端），但只能手机→Windows 单向连接。手机切换 App 会断连且 IP 变化，需要实现 Windows→手机的双向互通，以便 Claude Code 能主动发送文件到手机。

## 环境

- **手机**: Android, Termux, 用户名 `u0_a391`
- **Windows**: Git Bash MINGW64, 用户名 `ql`
- **网络**: 手机在家 WiFi，但路由器分了不同子网（手机 `192.168.31.x`，Windows `192.168.1.x`），无法直连
- **跨网方案**: Tailscale（手机和 Windows 都已安装）

## 最终方案（成功的）

### 网络层 — Tailscale

手机和 Windows 都安装了 Tailscale，组建虚拟局域网：
- Windows Tailscale IP: `100.109.36.79`
- 手机 Tailscale IP: `100.79.166.26`

Tailscale 自动处理了跨子网和中继连接（relay）。

### 手机端 — Termux sshd

```bash
pkg install openssh     # 安装 OpenSSH（已预装）
sshd -p 8022            # 启动 SSH 服务端
```

sshd 用 debug 模式验证端口绑定：
```bash
sshd -d -p 8022
```

### 密钥配置

Windows 端生成专用密钥：
```bash
ssh-keygen -t ed25519 -f ~/.ssh/termux_key -N "" -C "claude-to-phone"
```

公钥内容：
```
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHdLa8IZLrcifc/1DOFzcw9fGd0MaJIC5dSyCjUFN1f2 claude-to-phone
```

#### 坑：公钥传送到手机

**失败尝试：**
1. ❌ 手机屏幕窄，聊天中公钥文本折行，复制粘贴到 `authorized_keys` 后密钥被截断
2. ❌ Termux 内 `scp ql@100.109.36.79:/path/key.txt ~/.ssh/authorized_keys` — 端口不对（用了 sshd 端口而非 Windows SSH 端口），Connection refused
3. ❌ Windows 启动 HTTP 服务器 `python -m http.server` — Windows 防火墙拦截入站

**成功方法：**

Windows 端启动 HTTP 服务（临时目录，只放公钥文件）：
```bash
mkdir -p /tmp/claude-phone
cp /c/Users/ql/key.txt /tmp/claude-phone/
cd /tmp/claude-phone && python -m http.server 8889
```

Termux 通过 Tailscale 下载（走虚拟网络，绕过了子网问题）：
```bash
curl -o ~/.ssh/authorized_keys http://100.109.36.79:8889/key.txt
```

### 验证

```bash
# Windows 端测试 SSH 连接
ssh -i ~/.ssh/termux_key -p 8022 u0_a391@100.79.166.26 "echo OK"

# Windows 端发送文件到手机
echo "test" | ssh -i ~/.ssh/termux_key -p 8022 u0_a391@100.79.166.26 "cat > ~/test.txt"

# 或者用 scp
scp -i ~/.ssh/termux_key -P 8022 文件名 u0_a391@100.79.166.26:~/目标路径/
```

### 清理

```bash
passwd -d                   # 删除临时密码
rm ~/hello_from_claude.txt  # 删除测试文件
```

## 命令速查

| 操作 | 命令 |
|------|------|
| 启动 Termux sshd | `sshd -p 8022` |
| Windows → 手机发文件 | `scp -i ~/.ssh/termux_key -P 8022 <file> u0_a391@100.79.166.26:~/` |
| Windows → 手机执行命令 | `ssh -i ~/.ssh/termux_key -p 8022 u0_a391@100.79.166.26 "<cmd>"` |
| 手机 → Windows 发文件 | `scp -P 8022 ql@100.109.36.79:<path> ~/` |

## 保活方案：电池优化（已验证可行）

**问题**：手机切 App / 黑屏 → 系统挂起 Termux 进程 → SSH 断 → 必须重连

**根因**：vivo OriginOS 省电策略会挂起第三方进程，SSH 心跳（Keepalive）在进程被挂起时也发不出去。

**解决**：只需改 Termux 电池优化即可，无需 SSH 心跳。

### 操作方法

设置 → 应用 → Termux → 耗电管理 → **"不受限制"**

### 效果

电池优化设为不受限制后，Termux 进程不被系统挂起。黑屏、切应用均不会断连。

**重要结论**：SSH 双向心跳（`ClientAliveInterval` / `ServerAliveInterval`）不是必需的。之前配置的心跳其实从未真正生效——进程被挂起时心跳根本发不出。只改电池优化就够了。

## 尝试过的其他保活方案对比

| 方案 | 状态 | 原因 |
|------|------|------|
| ✅ **电池优化（不受限制）** | **已启用** | 根本解决方案——防止系统挂起进程，零配置 |
| ❌ **双向 Keepalive** | 弃用 | 进程被挂起时心跳发不出，只是伪保活 |
| ❌ **mosh** | 弃用 | Cygwin 的 mosh-server 调用 `setsockopt(IP_MTU_DISCOVER)`，Windows 底层不支持此 Linux 特有 API，报错 `Invalid argument`。改用 WSL 也许可行。 |
| ❌ **tmux 自动会话** | 弃用 | 只能断后恢复，不能真保活，且 Windows SSH 默认 cmd 不兼容 tmux |
| ❌ **Tailscale 心跳/Funnel** | 未测试 | 复杂，超出需求范围 |

## Windows 环境备忘

- **Shell 环境**: Cygwin（`C:\cygwin64\bin\bash.exe`），非 MSYS2/Git Bash
- **包管理**: 通过 `C:\cygwin64\setup-x86_64.exe` 安装包（清华镜像: `https://mirrors.tuna.tsinghua.edu.cn/cygwin`）
- **已装包**: tmux 3.3a, mosh 1.4.0（UDP 端口被防火墙拦了暂不能用）
- **Git**: Git for Windows（Git Bash MINGW64，`C:\Program Files\Git\bin\bash.exe`）
- **Python**: 3.14.3（Windows 原生，`C:\Users\ql\AppData\Local\Programs\Python\Python314\`）

## 注意事项

- 手机和 Windows 都需连接 Tailscale
- 切 App 断连不影响 sshd 持续运行
- 密钥认证无需密码，断连重连无影响
- Termux 电池优化需关闭（否则切后台被杀）

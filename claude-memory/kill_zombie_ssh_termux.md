---
name: Termux SSH 僵尸连接清理脚本
description: 手机 Termux 端清理 SSH 僵尸进程（无 root 可行）
type: reference
originSessionId: ad5e0539-cae4-4788-af61-3a375fa5a09e
---
## 手机端（Termux）清理 SSH 连接

Android 14 非 Root 下不能读 `/proc/net/tcp`，所以走**进程级**清理。

```bash
# 1. 查看所有 SSH 连接进程
ps aux | grep -E "ssh\s+ql@" | grep -v grep
# 输出示例：
# u0_a391  29167  ...  ssh ql@100.109.36.79    ← 这是你的活跃 SSH 会话

# 2. 杀掉所有手机→电脑的 SSH 连接（不会关 sshd 服务本身）
killall ssh

# 3. 或者只杀旧的（保留最新 1 个）
ps aux | grep "ssh ql@" | grep -v grep | awk '{print $2}' | head -n -1 | xargs -r kill

# 4. 清理残留 sshd-session 子进程（处理连接用，杀光后会自动重生）
killall sshd-session
```

## 远程（从 Windows）一键清理手机端

```bash
ssh -i ~/.ssh/termux_key -p 8022 100.79.166.26 "killall ssh; killall sshd-session"
```

## 完整脚本（保存到手机 ~/kill_zombie_ssh.sh）

```bash
#!/data/data/com.termux/files/usr/bin/bash
# Termux SSH 僵尸连接清理
echo "=== 当前 SSH 进程 ==="
ps aux | grep -E "(ssh|sshd)" | grep -v grep

STALE=$(ps aux | grep "ssh ql@" | grep -v grep | awk '{print $2}' | head -n -1)
if [ -n "$STALE" ]; then
    echo "Killing stale SSH client(s): $STALE"
    kill $STALE
else
    echo "No stale SSH clients."
fi

# 清理僵尸 sshd-session（不死进程长时间累积）
SSHD_SESSION=$(pgrep sshd-session)
if [ -n "$SSHD_SESSION" ]; then
    echo "Running sshd-session PIDs: $(echo $SSHD_SESSION | tr '\n' ' ')"
fi
```

## 原理

手机端不需要像 Windows 那样担心 CLOSE_WAIT——客户端进程死掉后 TCP 连接自然释放。
问题在于 Termux 被 Android 杀掉时，`ssh` 进程来不及发 FIN，导致 Windows 侧残留 CLOSE_WAIT。

所以**手机端清理的核心是主动关掉不再用的 SSH 连接**，不等它超时。

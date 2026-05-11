---
name: 清理 SSH 僵尸连接脚本
description: Windows sshd CLOSE_WAIT 僵尸连接检测和清理工具
type: reference
originSessionId: ad5e0539-cae4-4788-af61-3a375fa5a09e
---
```powershell:kill_zombie_ssh.ps1
<#
.SYNOPSIS
    检测并清理 sshd 的 CLOSE_WAIT 僵尸 TCP 连接
.DESCRIPTION
    - 扫描 port 22 上的 CLOSE_WAIT 连接
    - 重启 sshd 服务
    - 强制杀残留的 sshd 子进程
    - 循环检测直到清理完毕
#>

$ErrorActionPreference = "SilentlyContinue"
$maxWait = 120  # 最多等 2 分钟
$checkInterval = 10

function Get-ZombieConnections {
    Get-NetTCPConnection -LocalPort 22 -State CloseWait
}

# Step 1: 扫描
$zombies = Get-ZombieConnections
if (-not $zombies) {
    Write-Host "✓ No zombie SSH connections found."
    exit 0
}

Write-Host ("Found " + @($zombies).Count + " zombie CLOSE_WAIT connections from:")
$zombies | ForEach-Object { Write-Host ("  " + $_.RemoteAddress + ":" + $_.RemotePort) }

# Step 2: 重启 sshd
Write-Host "`nRestarting sshd service..."
Restart-Service sshd -Force
Start-Sleep -Seconds 3

# Step 3: 杀残留的子进程（它们可能占着 socket）
Write-Host "Cleaning up orphaned sshd processes..."
$sshdProcs = Get-Process -Name sshd | Sort-Object StartTime
# 保留最新的那个（刚重启的 daemon），杀其他的旧子进程
if ($sshdProcs.Count -gt 1) {
    $sshdProcs | Select-Object -First ($sshdProcs.Count - 1) | ForEach-Object {
        Write-Host ("  Killing sshd PID " + $_.Id + " (started: " + $_.StartTime + ")")
        Stop-Process -Id $_.Id -Force
    }
}

# Step 4: 等待回收
$elapsed = 0
while ($elapsed -lt $maxWait) {
    Start-Sleep -Seconds $checkInterval
    $elapsed += $checkInterval
    $remaining = Get-ZombieConnections
    if (-not $remaining) {
        Write-Host "`n✓ All zombie connections cleaned up! (took ${elapsed}s)"
        exit 0
    }
    Write-Host ("  ...still " + @($remaining).Count + " zombie(s) after ${elapsed}s")
}

Write-Host "`n⚠ Some zombies persist after ${maxWait}s. They will clear on next network adapter reset."
```

---

### 用法

以管理员身份运行：

```powershell
powershell -ExecutionPolicy Bypass -File kill_zombie_ssh.ps1
```

### 原理

Windows 上 sshd 的 CLOSE_WAIT 僵尸连接：

- **成因**：手机端断开连接后，sshd 子进程没调用 `close()` 释放 socket
- **不能单杀进程**：所有连接挂在同一个 sshd 主进程上（包括 ESTABLISHED 和 CLOSE_WAIT）
- **回收时间**：Windows TCP 栈对僵尸 CLOSE_WAIT 的回收约 30-120 秒

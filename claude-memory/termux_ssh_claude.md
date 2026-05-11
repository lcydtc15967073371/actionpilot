---
name: 手机通过 Termux + SSH 和 Claude 聊天
description: 用户通过 Termux (Android) SSH 连接到 Windows 电脑，再运行 claude 命令进行对话
type: reference
originSessionId: f869cfea-0371-43b5-8ef2-43075c624303
---
## 手机通过 Termux 和 Claude 聊天

### 环境
- 电脑: Windows 10, Tailscale IP 100.109.36.79
- 手机: Android, Termux (从 F-Droid 下载)
- 网络: Tailscale 组网（同一账号）

### 步骤
1. 电脑端已开启 OpenSSH Server 服务（sshd）
2. 手机 Termux 运行：
   ```bash
   pkg install openssh -y
   ssh-keygen -t ed25519
   # 把 ~/.ssh/id_ed25519.pub 内容发给 Claude
   ```
3. Claude 把公钥添加到电脑 `%USERPROFILE%\.ssh\authorized_keys`
4. SSH 登录：
   ```bash
   ssh ql@100.109.36.79
   ```
5. 运行：
   ```bash
   claude
   ```

### 注意事项
- Windows 无登录密码，已配置 sshd 允许空密码登录（PermitEmptyPasswords yes）
- 公钥认证也已配置（PubkeyAuthentication yes）
- 密钥文件权限已设置为仅当前用户可访问
- 电脑和手机必须在同一 Tailscale 网络

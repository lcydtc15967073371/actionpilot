# Termux + Shizuku 终端提权配置

> 通过 Shizuku 授权 Termux 获取 ADB 级别权限，无需 Root。

## 前提

- 手机已安装 **Shizuku**（`moe.shizuku.privileged.api`）
- Shizuku 已启动（Root / 无线调试方式均可）
- Termux 已安装

## 配置步骤

### 1. 导出 rish 文件

打开 Shizuku App → **"在终端应用中使用 Shizuku"** → **"导出文件"**，保存到任意目录（如 `/sdcard/`）。导出后得到两个文件：

- `rish` — shell 脚本
- `rish_shizuku.dex` — Java 执行代码

### 2. 传输到 Termux

将这两个文件移到 Termux 家目录：

```bash
cp /sdcard/路径/rish* ~/
```

### 3. 修改包名

编辑 `~/rish`，将第 24 行的 `PKG` 改为 Termux 的包名：

```bash
[ -z "$RISH_APPLICATION_ID" ] && export RISH_APPLICATION_ID="com.termux"
```

### 4. 验证权限

执行以下命令测试 Shizuku 是否生效：

```bash
sh ~/rish -c "settings put system FlashState 1"
```

手电筒亮起即表示配置成功。

## 使用方式

### 本地（手机 Termux 内）

基本语法：

```bash
sh ~/rish -c "要执行的命令"
```

### 远程（Windows 端 SSH 调用）

前提：Windows 到手机 Termux 的 SSH 通道已打通（通过 Tailscale）。

单条命令：
```bash
ssh -i ~/.ssh/termux_key -p 8022 u0_a391@100.79.166.26 "sh ~/rish -c 'settings put system FlashState 1'"
```

交互式：
```bash
ssh -i ~/.ssh/termux_key -p 8022 u0_a391@100.79.166.26
# 登录后执行:
sh ~/rish -c "命令"
```

### AI（Claude Code）远程操作

Claude Code（Windows）可通过 SSH 直接操作手机，实现"AI → Windows → Termux → Shizuku" 全链路提权。例如：

1. Claude 在 Windows 上执行 SSH 命令连接到手机 Termux
2. 通过 `sh ~/rish -c "..."` 以 Shizuku 权限执行命令
3. 结果返回给 Claude，Claude 可据此做出下一步决策

这相当于 AI 获得了 Android 系统级 API 的调用能力。

## 完整配置流程（SSH 远程方式）

如果 SCP/SSH 到 Termux 的通道已通，可以在电脑端完成全部配置：

1. **电脑下载 Shizuku APK**，解压出 `assets/rish` 和 `assets/rish_shizuku.dex`
2. **修改包名**：将 rish 中 `PKG` 改为 `com.termux`
3. **SCP 传到手机**：
   ```bash
   scp -i ~/.ssh/termux_key -P 8022 rish rish_shizuku.dex u0_a391@100.79.166.26:/data/data/com.termux/files/home/
   ```
4. **远程验证**：
   ```bash
   ssh -i ~/.ssh/termux_key -p 8022 u0_a391@100.79.166.26 "sh ~/rish -c 'settings put system FlashState 1'"
   ```

注意：部分操作（如首次授权）仍需在手机上确认，但一旦配置好，日常使用完全可远程操作。

常用命令示例：

| 用途 | 命令 |
|------|------|
| 修改系统设置 | `sh ~/rish -c "settings put system 键 值"` |
| 列出第三方应用 | `sh ~/rish -c "pm list packages -3"` |
| 强制停止应用 | `sh ~/rish -c "am force-stop 包名"` |
| 修改全局设置 | `sh ~/rish -c "settings put global 键 值"` |
| 查看应用列表 | `sh ~/rish -c "pm list packages"` |

## 注意事项

- **Android 14+**：`rish_shizuku.dex` 不能可写，脚本会自动 `chmod 400`。如果文件在 sdcard 上可能会有权限问题，放在 Termux 私目录（`/data/data/com.termux/files/home/`）即可解决。
- **Shizuku 必须运行中**，否则 rish 会报错。
- 手机重启后，无线调试方式启动的 Shizuku 需要重新激活。
- 如果不想每次都输 `sh ~/rish`，可以把 rish 加到 PATH 并设可执行权限：

```bash
chmod +x ~/rish
mv ~/rish /data/data/com.termux/files/usr/bin/
mv ~/rish_shizuku.dex /data/data/com.termux/files/usr/bin/
```

之后可直接用 `rish -c "命令"`。

## 踩坑记录

| 问题 | 原因 | 解决 |
|------|------|------|
| `Permission denied` 访问 sdcard | Android 限制 | 直接用 Termux 内部路径 |
| `ClassNotFoundException` | 包名写错 | 确认 `RISH_APPLICATION_ID=com.termux` |
| `Cannot find rish_shizuku.dex` | 文件不在同目录 | 确保两个文件在同一目录 |
| `app_process cannot load writable dex` | Android 14+ 限制 | `chmod 400 rish_shizuku.dex`（脚本自动处理） |

---

## 远程控制命令示例（已验证）

通过 `ssh → termux → rish` 链路，从 Windows 远程执行：

### 设置闹钟（静默保存）
```bash
ssh -i ~/.ssh/termux_key -p 8022 u0_a391@100.79.166.26 \
  "sh ~/rish -c 'am start -a android.intent.action.SET_ALARM \
  --ei android.intent.extra.alarm.HOUR 7 \
  --ei android.intent.extra.alarm.MINUTES 30 \
  --es android.intent.extra.alarm.MESSAGE \"标签\" \
  --ez android.intent.extra.alarm.SKIP_UI true'"
```
支持参数：`HOUR`（时）、`MINUTES`（分）、`MESSAGE`（标签）、`SKIP_UI=true`（跳过确认直接保存，已验证）

### 向原子笔记发送内容
```bash
ssh -i ~/.ssh/termux_key -p 8022 u0_a391@100.79.166.26 \
  "sh ~/rish -c 'am start -a android.intent.action.SEND \
  -t text/plain \
  -e android.intent.extra.TEXT \"内容\" \
  -n com.android.notes/.EditWidget'"
```
注：会弹出编辑界面，无法静默保存（原子笔记未暴露写入 ContentProvider）。

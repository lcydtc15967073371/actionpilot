---
name: Termux + Shizuku 提权
description: 通过 Shizuku 的 rish 为 Termux 提供 ADB 级别权限（无需 Root）
type: reference
originSessionId: 30b54414-bc85-4d08-bc92-bb23e916d2f7
---
Windows（SSH）→ 手机 Termux → Shizuku（rish）的三层提权链路已打通。

## 配置

- 从 Shizuku App 导出 `rish` + `rish_shizuku.dex`，放在 Termux 家目录
- `~/rish` 中 `RISH_APPLICATION_ID` 设为 `com.termux`
- Android 14+ 需把 dex 放在 Termux 私目录（而非 sdcard），否则 writable dex 报错

## SSH 远程使用

```bash
ssh -i ~/.ssh/termux_key -p 8022 u0_a391@100.79.166.26 "sh ~/rish -c '命令'"
```

## 安装 APK 的正确姿势

**问题**：rish 以 shell uid 运行，SELinux 阻止从 Termux 私有目录（`/data/data/com.termux/files/home/`）读取文件；`pm install` 也读不到 Termux 目录。

**正确流程**：
```bash
# 1. SCP 传 APK 到 Termux 家目录
scp -i ~/.ssh/termux_key -P 8022 app-debug.apk u0_a391@100.79.166.26:~/app.apk

# 2. rish 用 run-as 管道方式复制到 /data/local/tmp/
ssh -i ~/.ssh/termux_key -p 8022 u0_a391@100.79.166.26 \
  "sh ~/rish -c 'run-as com.termux cat ~/app.apk > /data/local/tmp/app.apk'"

# 3. 安装（需要亮屏）
ssh -i ~/.ssh/termux_key -p 8022 u0_a391@100.79.166.26 \
  "sh ~/rish -c 'pm install -r --install-reason 0 /data/local/tmp/app.apk'"
```

⚠️ `pm install -r` 不是真正的静默安装——手机仍然会弹出安装确认界面。用户手机有触发器任务自动点击"安装"，所以看起来是静默的。**熄屏状态会安装失败**。

**关键**：`run-as com.termux cat` 以 Termux uid 读取文件，`> /data/local/tmp/` 在 rish 的 shell 上下文中写入（/data/local/tmp/ 世界可写）。两步组合绕过 SELinux。

**不要这样做**：
- 不要直接用 rish `cp` 从 Termux 目录（SELinux 阻止）
- 不要尝试写 `/sdcard/`（Android 分区存储阻止，即使 rish 也不行）

## 调查过程：从 su 到明确权限边界

排查 Termux 能做什么不能做什么时，"su 存在"是一个**关键调查节点**，流程如下：

```
发现 su → which su 返回路径 → 试着执行 → 确认是非 Root 占位脚本
                                    ↓
                            明确边界：非 Root 设备上这条路不通
                                    ↓
                            转向 Shizuku（rish）→ 成功提权
```

每一步都排除了一个方向，让后续排查更聚焦。

### su 的实际情况

Termux 下 `which su` 确实返回路径 `/data/data/com.termux/files/usr/bin/su`，但它是 Termux 的占位脚本，执行后打印"没有 Root 程序"退出——不是真正的 su，也不能提权。

### 实际权限边界（vivo Android 14 非 Root）

可直接操作：

| 命令 | 状态 |
|------|------|
| `which`、`ls`、`ps`（仅自身进程） | 正常 |
| SSH（`/data/data/com.termux/files/usr/bin/ssh`） | 正常 |
| Shizuku rish（通过 API 启动的 ADB 级别 shell） | 可用，需单独配置 |

不可用：

| 操作 | 原因 |
|------|------|
| `su -c "..."` | 非 Root 设备，su 只是占位脚本 |
| `cat /proc/net/tcp` | SELinux 阻止 |
| `ss` / `netstat` | 无权限打开 netlink socket |
| `dumpsys` | 需要 `DUMP` permission |

结论：Android 14 非 Root 的提权路径只有一条 `Termux → Shizuku (rish)`，没有捷径。

## 已验证可用的操作

- 修改 system/global 设置：`settings put system FlashState 1`（成功控制手电筒）
- 设置闹钟（vivo BBKClock 支持标准 `SET_ALARM` Intent）：
  ```bash
  sh ~/rish -c 'am start -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR 7 --ei android.intent.extra.alarm.MINUTES 30 --es android.intent.extra.alarm.MESSAGE "标签"'
  ```
  参数：`HOUR`（时）、`MINUTES`（分）、`MESSAGE`（标签）、`SKIP_UI`（设为 true 跳过确认直接保存闹钟，已验证可用）
- 向原子笔记追加内容（`com.android.notes` vivo 原子笔记）：
  ```bash
  sh ~/rish -c 'am start -a android.intent.action.SEND -t text/plain -e android.intent.extra.TEXT "内容" -n com.android.notes/.EditWidget'
  ```
  注：会弹出编辑界面，无法静默保存。`vivo.intent.action.INSERT_NEW_NOTE` 和 `vivo.intent.action.CREATE_NEW_NOTE` 广播已测试但不可靠。

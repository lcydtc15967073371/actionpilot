---
name: Termux ADB 控制方法
description: 通过 ADB shell run-as 控制 Termux 执行命令和传输文件
type: reference
originSessionId: cf99f906-2123-4e1f-9b8a-5793adcb1822
---

## 通过 ADB 控制 Termux

### 前提
- Termux 必须从 GitHub 下载的 debug 版本（F-Droid 版不行）
- 手机已通过 USB/ADB 连接电脑

### ADB 路径（Windows）
C:\Users\ql\AppData\Local\Android\Sdk\platform-tools\adb.exe
```bash
export ANDROID_HOME="C:/Users/ql/AppData/Local/Android/Sdk"
export ADB="$ANDROID_HOME/platform-tools/adb"
```

### 查看 Termux 目录
```bash
adb shell run-as com.termux ls files/home/
```

### 执行命令
```bash
adb shell run-as com.termux sh -c '命令'
```

### 拉取文件到电脑
```bash
# tar 管道（最可靠）
adb exec-out "run-as com.termux tar -czf - files/home/repo" | tar -xzf - -C /目标路径

# 或两步走
adb shell run-as com.termux cp files/home/文件 /data/local/tmp/
adb pull /data/local/tmp/文件
```

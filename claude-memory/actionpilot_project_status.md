---
name: ActionPilot 项目当前状态
description: ActionPilot（操作导航）App 的项目状态和待解决问题
type: project
originSessionId: cf99f906-2123-4e1f-9b8a-5793adcb1822
---
# ActionPilot 项目状态（2026-05-08）

## 已完成
- 项目骨架：Gradle 8.9 + AGP 8.7.3 + Kotlin 2.0.21 + Compose BOM 2024.12
- 数据模型：OpNode, OpEdge, OpMap, OpAction
- 录制引擎：MapBuilder（去重合并节点和边，内容子页面）
- 持久化：MapRepository（Gson）
- AI 导出：AiExporter v2.0（含 timeline + clicks）
- UI 全中文：录制/列表/导出（3 Tab，精简版）
- ShizukuShell.java（反射 newProcess）
- RecordService（Shizuku dumpsys 轮询）
- RecordAccessibilityService（完整实现，5级 label 提取）
- 启动时自动授权无障碍（settings put secure）
- appName 从 PackageManager 正确解析（非包名）
- 重复 TRANSITION 去重（A11y + Shizuku 防重复）
- Edge 标签消费机制（一次点击不会污染后续跳转）
- 页面内容捕获 captureVisibleText()（TYPE_WINDOW_CONTENT_CHANGED + 1s 限流）
- 内容子页面节点（同 Activity 内 H5 页面也能生成独立节点）
- 全量录制（已移除 AppSelection 应用过滤）
- 点击坐标捕获（bounds 存入 viewId 字段，区分同区域不同按钮）（2026-05-08）

## 已验证
- Shizuku 授权 OK
- 录制启动/停止/保存 OK
- 无障碍服务自动启用 OK
- 点击级事件捕获 OK
- 同花顺实战验证：完整录制 26 页面、总资产 382 万持仓数据、日K/周K/月K/分时 bounds 完美区分
- 支付宝录制验证：appName 中文、无重复 TRANSITION、完整捕获总资产(419.61元)和余额宝(417.61元)页面内容
- H5 内页（余额宝、总资产、账单明细）自动生成为地图独立节点

## 已知问题
- Icon 字体点击 label 乱码（如支付宝底部 Tab 的 ""），受限于源 App 的无障碍暴露
- "返回"等通用按钮文本会误生成为虚拟节点

## 版本
- 当前: 1.3.0 (versionCode 4)
- targetSdk: 32, compileSdk: 35

## ADB 调试流程

### ADB 路径
```bash
export ANDROID_HOME="C:/Users/ql/AppData/Local/Android/Sdk"
export ADB="$ANDROID_HOME/platform-tools/adb"
```

### 设备
- vivo V2313A (Android 14)
- 设备号: 10CE2S1FPD0027X

### 构建 + 安装
```bash
# 1. 构建
cd "f:/app/Claude/app"
export JAVA_HOME="C:/Users/ql/AppData/Local/Temp/jdk-17.0.14+7"
./gradlew assembleDebug

# 2. 推送到设备临时目录
adb -s 10CE2S1FPD0027X push ActionPilot/build/outputs/apk/debug/ActionPilot-debug.apk /data/local/tmp/actionpilot.apk

# 3. 安装（绕过 targetSdk 限制）
adb -s 10CE2S1FPD0027X shell pm install -r /data/local/tmp/actionpilot.apk

# 4. 查看日志
adb -s 10CE2S1FPD0027X logcat -s "ActionPilot" -d
```

### 读取录制数据
```bash
adb -s 10CE2S1FPD0027X shell run-as com.operit.actionpilot cat /data/data/com.operit.actionpilot/files/actionpilot_map.json
```

### 注意点
- 编译用 JDK 17（在 Temp 目录下）
- `adb push` 时 Git Bash 会转换路径，需加 `export MSYS_NO_PATHCONV=1`
- 不要用 `adb install`，用 `pm install -r` 绕过 targetSdk 限制
- `run-as` 只在 debug 版可用

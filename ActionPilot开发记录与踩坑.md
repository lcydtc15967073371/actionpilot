# ActionPilot 开发记录与踩坑

> 项目：操作导航 App — 录制用户操作路径 + 点击文字
> 技术栈：Kotlin + Jetpack Compose + Shizuku + AccessibilityService
> 周期：2026-05-07 ~ 2026-05-08

---

## 一、项目架构

```
┌─────────────────────────────────────────────┐
│                  UI (Compose)                │
│  录制Tab  │  应用Tab  │  列表Tab  │  导出Tab  │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│           RecordService (前台服务)            │
│  ┌─────────┐  ┌────────────┐  ┌──────────┐  │
│  │ Shizuku  │  │Accessibility│  │MapBuilder│  │
│  │ dumpsys  │  │  Service   │  │ 节点+边   │  │
│  │ 轮询     │  │ 点击事件    │  │ +操作列表 │  │
│  └─────────┘  └────────────┘  └─────┬────┘  │
└──────────────────────────────────────┼───────┘
                                       │
┌──────────────────────────────────────▼───────┐
│            MapRepository (Gson持久化)          │
└──────────────────────────────────────────────┘
```

### 双通道录制
| 通道 | 数据 | 频率 |
|------|------|------|
| Shizuku dumpsys window | Activity 切换 | 500~1500ms |
| AccessibilityService | 点击/长按/输入 + 文字 | 事件驱动 |

---

## 二、踩坑记录

### 坑1：无障碍服务不在系统设置列表中显示

**现象**：在 PermOpener 的无障碍服务能显示，ActionPilot 的完全不出现。

**根因**：AndroidManifest.xml 中无障碍服务声明缺少两个关键属性：
```xml
<!-- ❌ 错误 -->
<service android:name=".service.RecordAccessibilityService"
    android:exported="false">

<!-- ✅ 正确 -->
<service android:name=".service.RecordAccessibilityService"
    android:exported="true"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
```

- `BIND_ACCESSIBILITY_SERVICE`：系统绑定无障碍服务的**硬性要求**，缺了这个系统不会尝试绑定
- `exported="true"`：AccessibilityManagerService 运行在 system_server 进程，必须跨进程绑定

**参考来源**：PermOpener 项目的 AndroidManifest.xml。

---

### 坑2：Android 14 Restricted Settings 阻止启用无障碍

**现象**：即使服务声明正确，在设置中点无障碍开关时提示"受限设置"。

**根因**：Android 14 限制侧载（非 Play Store）安装的、targetSdk>=33 的 App 启用无障碍。

**解决**：targetSdk 降级到 32 — Restricted Settings 策略不适用于 targetSdk<33 的应用：
```kotlin
// app/build.gradle.kts
defaultConfig {
    targetSdk = 32  // 绕过 Restricted Settings
}
```

但问题是 targetSdk<33 的 APK 在 Android 14 上安装被拦截（"App not compatible"），需要用 `pm install` 或 `adb install --bypass-low-target-sdk-block`。

**安装流程**：
```bash
# 推送到设备临时目录（避免 SELinux 限制）
adb push app-debug.apk /data/local/tmp/
# 用 pm install 安装（绕过 targetSdk 检查）
adb shell pm install -r /data/local/tmp/app-debug.apk
```

---

### 坑3：settings put secure 在 ADB 和 Shizuku 中表现不同

**现象**：
- `adb shell settings put secure enabled_accessibility_services xxx` → 写入无效（vivo 拦截）
- 通过 ShizukuShell.exec(同样命令) → 成功

**根因**：vivo 设备上 ADB shell 和 Shizuku 进程的 SELinux 上下文不同。Shizuku (ADB mode) 的 `newProcess("sh")` 创建的进程可能有不同的安全策略上下文，允许写入 secure settings。

**正确做法**：不依赖 ADB 调试，直接在 App 内通过 ShizukuShell.exec() 执行：
```java
// ShizukuShell.java
exec("settings put secure enabled_accessibility_services \"" + component + "\"");
exec("settings put secure accessibility_enabled 1");
```

---

### 坑4：获取已安装应用列表

**现象**：
- `queryIntentActivities(CATEGORY_LAUNCHER)` → 只显示有桌面图标的 ~20 个应用
- `ShizukuShell.exec("pm list packages -3")` → 返回不完整（app 上下文下受限）
- `PackageManager.getInstalledApplications()` → 需要 QUERY_ALL_PACKAGES

**分析**：
Android 11+ 引入包可见性限制。`pm list packages` 命令在不同 UID 下表现不同：

| 执行上下文 | 返回数量 | 原因 |
|-----------|---------|------|
| `adb shell pm list packages -3` | 104 | shell UID，无限制 |
| `run-as app pm list packages -3` | 3 | app UID，包可见性限制 |
| Shizuku.newProcess ("sh") | 不确定 | 取决于 SELinux 上下文 |

**最终方案 — 三重兜底**：
1. **getInstalledApplications()** — 声明 QUERY_ALL_PACKAGES（targetSdk 32 自动授予）
2. **cmd package list packages -3** — Shizuku Shell 备用
3. **ls /data/app/** — 文件系统枚举终极备用

实际上方法1 在 targetSdk=32 + QUERY_ALL_PACKAGES 声明后就够用了。

```xml
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"
    tools:ignore="QueryAllPackagesPermission" />
```

---

### 坑5：无障碍事件捕获点击文字

**经验**：
- `TYPE_VIEW_CLICKED` 的 `event.source` 节点包含点击控件的文字信息
- 不要用 `getRootInActiveWindow()` 遍历整个树，直接从 event.source 取
- event.source 用完必须 `recycle()`

**Label 提取优先级**（5 级）：
```kotlin
// 1. contentDescription（TalkBack 主标签）
contentDescription → 
// 2. text（按钮可见文字）→ 
// 3. hintText（输入框提示）→ 
// 4. viewIdResourceName（资源ID最后一段）→ 
// 5. 子节点递归查找
```

---

### 坑6：uiautomator dump 在 vivo 上不可用

**现象**：通过 Shizuku 执行 `uiautomator dump`，进程被 SIGKILL。

**根因**：vivo ROM 限制了 uiautomator 命令或资源不足。

**结论**：不依赖 uiautomator，只靠 AccessibilityService + Shizuku dumpsys 双通道。

---

### 坑7：Shizuku.newProcess 反射调用的参数传递

```java
// ❌ 正确但容易写错
Process proc = (Process) m.invoke(null,
    new String[]{"sh"}, null, null);

// Method.invoke 是可变参数，不要多包一层 new Object[]{}，
// 否则参数结构错乱（虽然不报错但命令不执行）
```

注意：`new Object[]{new String[]{"sh"}, null, null}` 在 Java 中由于 invoke 的自动解包机制实际上**也能工作**，但容易混淆。

---

### 坑8：无障碍安全编程 — 避免死循环

**致命教训**（来自万能 App 变砖经历）：
```kotlin
// ❌ 危险：监听高频事件 + 自动启动功能
eventTypes = TYPE_WINDOW_CONTENT_CHANGED  // 频率极高
// onServiceConnected 中启动前台服务/悬浮窗

// ✅ 安全
eventTypes = TYPE_VIEW_CLICKED | TYPE_WINDOW_STATE_CHANGED  // 低频
// 所有功能由用户手动触发（点击按钮开始录制）
// START_NOT_STICKY（不要自动重启）
```

---

## 三、开发方法论

### 3.1 Shizuku 使用原则

1. **Shizuku 的本质是提权到 ADB shell 级别**。既然有了 shell 权限，直接执行 shell 命令就是最正确的方式
2. 不需要 Binder、不需要 AIDL、不需要 UserService
3. 反射调用放 Java 文件（Kotlin 拦截 private 方法）
4. 授权时序：`Service.onStartCommand` 比 `Activity.onCreate` 更可靠
5. 异常不要完全吞掉，最少输出到 logcat

### 3.2 无障碍服务安全原则

1. **所有功能由用户手动触发**，不在 `onServiceConnected` 中自动开启
2. **只监听低频事件**（`TYPE_WINDOW_STATE_CHANGED`、`TYPE_VIEW_CLICKED`）
3. **不要监听 `TYPE_WINDOW_CONTENT_CHANGED`**（频率极高，可能死循环）
4. **用 `START_NOT_STICKY`**，防止进程被杀后自动重启
5. 开发时连好 ADB，写好卸载脚本，备用机测试

### 3.3 调试方法

```bash
# 查看 Shizuku 授权状态
adb shell dumpsys package rikka.shizuku

# 查看无障碍服务配置
adb shell settings list secure | grep accessibility

# 查看当前焦点窗口
adb shell dumpsys window | grep mCurrentFocus

# 查看日志
adb logcat -s "ActionPilot"
adb logcat -s "ActionPilot-A11y"
```

### 3.4 APK 安装流程

```bash
# 1. 构建
./gradlew assembleDebug

# 2. 推送到设备临时目录
adb push app-debug.apk /data/local/tmp/

# 3. 安装（绕过 targetSdk 限制）
adb shell pm install -r /data/local/tmp/app-debug.apk
```

---

## 四、数据模型

```
OpMap
├── nodes: Map<String, OpNode>
│   ├── id: String (package#screen)
│   ├── appPackage / appName / screenName
│   ├── firstSeen / lastSeen / visitCount
│   └── (每个页面一个节点)
├── edges: List<OpEdge>
│   ├── fromId -> toId (页面跳转)
│   ├── actionType / elementLabel (触发动作)
│   ├── count / lastTime
│   └── (每次跳转一条边)
└── actions: List<OpAction>
    ├── nodeId (发生在哪个页面)
    ├── actionType: CLICK / LONG_CLICK / TEXT_INPUT / TRANSITION
    ├── elementLabel (点击的文字)
    ├── viewId (控件资源ID)
    └── timestamp

AppSelection (共享状态)
└── selectedPackages: Set<String>
    └── 空 = 录制全部，非空 = 只录选中的
```

---

## 五、版本历史

| 版本 | 日期 | 改动 |
|------|------|------|
| v1.0.0 | 05-07 | 初始版：Shizuku dumpsys 轮询录制 |
| v1.1.0 | 05-08 | 无障碍录制 + 点击文字捕获 + UI 中文 |
| v1.1.1 | 05-08 | 加开发经验宝典 |
| v1.2.0 | 05-08 | 应用选择列表 + 录制过滤 |

---

## 六、关键文件

```
app/src/main/java/com/operit/actionpilot/
├── model/
│   ├── OpNode.kt          — 页面节点
│   ├── OpEdge.kt          — 页面跳转边
│   ├── OpAction.kt        — 点击级操作
│   ├── OpMap.kt           — 有向图 + Mutable 版
│   └── AppSelection.kt    — 录制应用过滤
├── recorder/
│   └── MapBuilder.kt      — 录制引擎（去重+关联）
├── service/
│   ├── RecordService.kt           — 前台服务（Shizuku 轮询）
│   ├── RecordAccessibilityService.kt — 无障碍服务（点击文字）
│   └── ShizukuShell.java          — Shizuku 反射工具类
├── storage/
│   └── MapRepository.kt   — Gson 持久化
├── export/
│   └── AiExporter.kt      — AI 导出 JSON
└── ui/
    ├── MainScreen.kt      — 主界面（4 Tab）
    ├── AppSelectScreen.kt — 应用选择列表
    ├── RecordListScreen.kt— 操作列表
    ├── ExportScreen.kt    — 导出
    └── MapView.kt         — 有向图可视化（未使用）
```

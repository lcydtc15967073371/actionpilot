---
name: ADB 开发各种坑和小技巧
description: 用 ADB 开发 Android app 时遇到的坑和技巧
type: reference
originSessionId: fea740fd-bef3-417e-8b33-2ccc18b304f6
---
# ADB 开发避坑指南

## ADB 版本冲突
- 系统可能有多个 adb 版本（platform-tools 的 vs Git Bash 自带的）
- `adb -s` 在 bash 命令替换 `$(adb ...)` 中会调用不同版本的 adb
- 解决方法：始终使用完整路径 `E:/platform-tools/adb.exe -s <serial>`

## 安装无障碍服务
- `settings put secure enabled_accessibility_services` 在某些 ROM 上写不进去
- vivo/iQOO OriginOS 有额外限制：`persist.vivo.apps.restriction=yes`
- `install -i com.android.vending` 可以伪装安装来源
- 对于 vivo 设备，`settings put secure` 命令本身不报错但值被静默丢弃（写入 /dev/null）
- 通过 Shizuku 的 shell 进程执行可以绕过部分限制

## ADB 截图
- `adb exec-out screencap -p > file.png` 可以在 Windows 上正常保存 PNG
- 不同 ROM 的 screencap 参数不同（有的需要 -p 有的不需要）
- 查看截图分辨率和密度：`dumpsys window displays`

## 分析 APK
- `unzip -p app.apk AndroidManifest.xml` 提取 manifest（二进制格式）
- 用 `dexdump -d classes.dex` 反编译 DEX（Android SDK build-tools 自带）
- 搜索 APK 中的字符串：`dexdump -d classes.dex | grep "const-string"`，然后看字符串表

## Compose UI 自动化测试
- 难以直接用 ADB 找到 Compose 按钮坐标（没有 view id）
- `uiautomator dump` 在部分 ROM 上不可用（如 vivo）
- 对于 Compose UI，可以通过 intent 或 broadcast 绕过 UI 操作

## Gradle 构建
- Kotlin 2.0+ 需要 `org.jetbrains.kotlin.plugin.compose` 插件
- `composeOptions { kotlinCompilerExtensionVersion = "1.5.x" }` 不再适用于 Kotlin 2.0+
- AGP 8.7.3 + Gradle 8.9 + Kotlin 2.0.21 是稳定组合
- 国内镜像：华为云 `https://repo.huaweicloud.com/gradle/` 比官网快
- Gradle wrapper 生成：先用完整 Gradle 发行版跑 `gradle wrapper`，不要用 `--gradle-version` 参数（会验证 URL 可达性）
- `gradle-wrapper.jar` / `gradle-wrapper.properties` 缺失时，从缓存或别的项目复制
- 已缓存的 Gradle 在 `/c/Users/ql/.gradle/wrapper/dists/gradle-8.9-bin/<hash>/gradle-8.9/`

## TYPE_APPLICATION_OVERLAY 悬浮窗键盘问题（未完全解决）

Shizuku AI 的 IME 焦点切换问题（2026-05-09 仍未解决）

### 问题
浮窗输入框和后台 App（微信）输入框之间无法自由切换焦点。

### 当前方案（Operit 完整方案，FloatService.java L238-L387）
```java
// 显示键盘：去掉 NOT_FOCUSABLE + 全屏遮罩 + 延迟重试
p.flags &= ~FLAG_NOT_FOCUSABLE;
p.flags |= FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH;
ensureFocusDismissView();           // 全屏透明 View 捕获外部点击
setFocusDismissOverlayEnabled(true);
scheduleImeShow(et, 0, 200);        // 200ms 延迟 + 最多 4 次重试

// 隐藏键盘：clearFocus + 恢复 NOT_FOCUSABLE
View focused = v.findFocus();
if (focused != null) focused.clearFocus();
v.clearFocus();
p.flags |= FLAG_NOT_FOCUSABLE;
p.flags &= ~(FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH);
setFocusDismissOverlayEnabled(false);
```

### 关键发现
- `FLAG_ALT_FOCUSABLE_IM` 让浮窗弹不出键盘（不是可聚焦）
- `FLAG_LAYOUT_NO_LIMITS` 可以保留，不是卡键盘的原因
- Operit（Compose/完美切换）vs Shizuku AI（传统 View/失败）的唯一区别是 UI 框架 + 是否有额外服务干扰
- **vivo OriginOS Android 14 上的结论待验证**，前面的经验结论写错了——本问题至今未解决

### 已尝试失败方案及根因
详见记忆 `ime_focus_floating_window.md`

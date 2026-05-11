---
name: ActionPilot 开发踩坑记录
description: ActionPilot 开发中遇到的坑：无障碍服务不显示、Restricted Settings、包可见性、settings put secure
type: reference
originSessionId: cf99f906-2123-4e1f-9b8a-5793adcb1822
---
## 踩坑汇总

### 无障碍服务不显示在列表
- 必须加 `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`
- 必须 `exported="true"`（系统跨进程绑定）

### Android 14 Restricted Settings 阻止无障碍
- targetSdk >= 33 的侧载 App 被限制
- 降级到 32 可绕过，但安装时需 `pm install`

### settings put secure 在 vivo ADB 无效
- ADB shell 写的 secure settings 被 vivo 拦截
- 但 ShizukuShell.exec(同一命令) 成功
- 跟 SELinux 上下文有关

### 应用包可见性限制
- `queryIntentActivities(CATEGORY_LAUNCHER)` 只返回桌面应用
- `pm list packages -3` 在不同 UID 下返回不同结果
- 最终：targetSdk 32 + QUERY_ALL_PACKAGES 声明 = getInstalledApplications 可用

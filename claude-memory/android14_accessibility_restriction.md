---
name: Android 14 无障碍服务受限绕过
description: Sideloaded app 的 AccessibilityService 被 Android 14 阻止及绕过方法
type: reference
originSessionId: fea740fd-bef3-417e-8b33-2ccc18b304f6
---
Android 14 对 sideloaded app（非 Play Store安装）的 AccessibilityService 有额外限制：
- `settings put secure enabled_accessibility_services` 写值成功但 AccessibilityManagerService 会过滤掉受限 app 的服务
- `install -i com.android.vending` 伪装安装来源可能不够，因为系统还有额外检查
- vivo OriginOS 有 `persist.vivo.apps.restriction=yes`，更严格
- 唯一可靠方法：用户手动去 设置 → 应用 → App → 右上角⋮ → 允许受限设置
- PermOpener 通过 Shizuku 执行 shell 命令来绕过，核心命令：
  ```
  settings put secure enabled_accessibility_services "$(settings get secure):com.example/.Service"
  ```
- 开发时避坑：如果 service 注册了但 dumpsys 不显示，检查是否有 `<intent-filter>`（无 intent-filter 的 service 不在 Service Resolver Table 显示但仍然可用）

**Why:** Google 在 Android 14 引入了 Restricted Settings 机制，sideloaded 安装的 app 默认不能使用无障碍服务，防止恶意软件。
**How to apply:** 如果是 sideloaded app，要么引导用户手动开启"允许受限设置"，要么改用 Shizuku 轮询 `dumpsys window` 替代 AccessibilityService。

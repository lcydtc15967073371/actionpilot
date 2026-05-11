---
name: Shizuku 开发踩坑总结
description: Shizuku API 13.x 开发中遇到的问题和解决方案
type: reference
originSessionId: fea740fd-bef3-417e-8b33-2ccc18b304f6
---
# Shizuku 开发避坑

## 权限检查 vs 可用性检查
- ❌ 不要用 `Shizuku.ping()` 检查可用性（新版本可能不存在此方法）
- ❌ 不要直接调 `Shizuku.newProcess()` 来检查（需要权限，会报 Permission Denial）
- ✅ 用 `Shizuku.checkSelfPermission() == 0` 检查权限
- ✅ 权限请求用 `Shizuku.requestPermission(REQUEST_CODE)`
- ✅ 监听结果用 `Shizuku.addRequestPermissionResultListener()`

## ShizukuShell 实现要点
- `newProcess()` 是 private 方法，必须用 Java 反射（Kotlin 编译检查拦截 private）
- 反射调用 newProcess 的参数结构：
  ```java
  Method m = clz.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
  m.setAccessible(true);
  Process proc = (Process) m.invoke(null, new String[]{"sh"}, null, null);
  ```
- 通过 proc.getOutputStream() 写入命令，通过 proc.getInputStream() 读取输出

## ShizukuProvider 配置
- AndroidManifest 中必须设置 `android:exported="true"`，否则 crash：
  `IllegalStateException: android:exported must be true`
- 需要 `moe.shizuku.manager.permission.API_V23` 权限
- 需要 `<meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true" />`

## 进程信息
- Shizuku server 以 `shell` 用户运行，UID 2000
- ADB 连接不冲突 Shizuku（Shizuku 独立运行后不需要 ADB）
- 检查 Shizuku 进程：`ps | grep shizuku_server`

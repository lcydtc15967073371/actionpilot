---
name: Android 14 ForegroundServiceDidNotStartInTimeException
description: 前台服务必须在5秒内调用 startForeground()，否则系统会崩溃
type: reference
originSessionId: fea740fd-bef3-417e-8b33-2ccc18b304f6
---
## 错误信息
```
RemoteServiceException: Context.startForegroundService() did not then call Service.startForeground()
```

## 原因
Android 14 要求调用 `startForegroundService()` 后在 **5秒内** 必须调用 `Service.startForeground()`，否则系统会 throw 此异常并杀死服务。

## 触发场景
- `onStartCommand` 中执行耗时操作后再调用 `startForeground()`（如先检查权限）
- `onStartCommand` 返回前因条件不满足提前 `stopSelf()` 而没调 `startForeground()`
- 服务进程被系统杀死后重建但重建失败

## 解决方案
```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // 必须先调 startForeground！
    startForeground(NOTIF_ID, notification)
    
    // 然后再做耗时操作
    if (!checkCondition()) {
        stopSelf()  // 即使停服务，也必须先调 startForeground
    }
    return START_NOT_STICKY
}
```

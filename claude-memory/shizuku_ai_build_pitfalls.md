---
name: shizuku-ai 构建踩坑
description: shizuku-ai 打包构建和安装过程中的各类踩坑记录
type: reference
originSessionId: f94680b6-a3b5-4b2e-ae80-f32511c7d14f
---
# shizuku-ai 构建踩坑记录

## 1. Gradle 版本不匹配

AGP 9.0.0 要求 Gradle 9.1+，但开发机上只缓存了 Gradle 8.9。

**解法**：降 AGP 到 8.7.3，用已缓存的 Gradle 8.9 编译。
```
build.gradle:  classpath "com.android.tools.build:gradle:8.7.3"
gradle-wrapper.properties:  distributionUrl=.../gradle-8.9-bin.zip
```

## 2. gradle-wrapper.jar 缺失

shizuku-ai 独立项目，gradle/wrapper/ 下没有 wrapper jar 和 properties。

**解法**：
```bash
# 从 gradle 缓存复制
cp /c/Users/ql/.gradle/caches/<hash>/gradle-8.9/gradle/wrapper/gradle-wrapper.jar \
   shizuku-ai/gradle/wrapper/
# 手动创建 wrapper properties
```

## 3. Gradle JVM 被 kill（exit 137）

gradle.properties 里 `org.gradle.jvmargs=-Xmx2048m` 默认要求 2GB。但机器有 32GB 内存，实际原因是：
- 旧的 daemon 残留没杀掉
- 或者 ANDROID_HOME / local.properties 没配置

**解法**：先 `./gradlew --stop` 杀 daemon，或者 `./gradlew assembleDebug --no-daemon`。

## 4. local.properties SDK 路径错误

从 Proot Ubuntu 复制过来的项目，sdk.dir 指向 `/root/Android`。

**解法**：
```properties
sdk.dir=C\:\\Users\\ql\\AppData\\Local\\Android\\Sdk
```
或设置环境变量 `ANDROID_HOME=C:/Users/ql/AppData/Local/Android/Sdk`

## 5. 签名不一致安装失败

```
adb shell pm install -r ...apk
Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package signatures do not match]
```

**解法**：旧版签名不同，必须先卸载：
```bash
adb uninstall com.shizuku.ai
adb shell pm install -r /data/local/tmp/shizuku-ai.apk
```

## 6. ShizukuShell.exec() 返回 ShellResult 不是 String

shizuku-ai 的 ShizukuShell.exec() 返回 `ShellResult {int exitCode, String output}`，不是直接返回 String。

**解法**：用到返回值的地方加 `.output`：
```java
ShellResult sr = ShizukuShell.exec("命令");
String output = sr.output;
```

## 7. Kotlin companion object 字段从 Java 访问

ShizukuAccessibilityService 的 `private set` 字段在 Java 中访问报 private。

**解法**：加 `@JvmField` 注解：
```kotlin
@JvmField
var lastScreenText: String = ""
```

## 正确打包流程

```bash
export JAVA_HOME="C:/Users/ql/AppData/Local/Temp/jdk-17.0.14+7"
export ANDROID_HOME="C:/Users/ql/AppData/Local/Android/Sdk"
export MSYS_NO_PATHCONV=1
cd "F:/app/Claude/app/shizuku-ai"

# 编译
./gradlew assembleDebug

# 第一次安装（需先卸载旧版）
adb -s 10CE2S1FPD0027X uninstall com.shizuku.ai

# 推送+安装
adb -s 10CE2S1FPD0027X push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/shizuku-ai.apk
adb -s 10CE2S1FPD0027X shell pm install -r /data/local/tmp/shizuku-ai.apk
```

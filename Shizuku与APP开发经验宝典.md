# 🔧 Shizuku 与 Android APP 开发经验宝典

> 来源：Operit 记忆库 — 虚拟定位APP(v5.4) + 万能APP(v2.0) + FloatRunner + 财经新闻APP 实战血泪
> 最后更新：2026-05-07

---

## 目录

1. [一、Shizuku 核心经验](#一shizuku-核心经验)
2. [二、Android 安卓项目构建](#二android-安卓项目构建)
3. [三、模拟定位APP开发全攻略](#三模拟定位app开发全攻略)
4. [四、UI自动化与AutoJS6](#四ui自动化与autojs6)
5. [五、Playwright 网页数据采集](#五playwright-网页数据采集)
6. [六、金融数据接口](#六金融数据接口)
7. [七、GitHub 上传流程](#七github-上传流程)
8. [八、万能APP + 无障碍服务开发](#八万能app--无障碍服务开发)
9. [九、财经新闻APP避坑](#九财经新闻app避坑)
10. [十、Android 系统机制深度研究](#十android-系统机制深度研究)
11. [十一、通用经验教训汇总](#十一通用经验教训汇总)

---

# 一、Shizuku 核心经验

## 1.1 核心理念

> **Shizuku 的本质是给 APP 提权到 ADB shell 级别。既然有了 shell 权限，直接执行 shell 命令就是最正确的方式。**

不需要 Binder、不需要 AIDL、不需要反射调用 Shizuku 内部方法——直接通过 `newProcess("sh")` 获取 Shell 进程，写入命令即可。

## 1.2 ShizukuShell.java 最终实现（Java 反射）

```java
public class ShizukuShell {
    private static Method newProcessMethod;
    private static Class<?> shizukuClass;

    public static boolean isAvailable() {
        try {
            if (shizukuClass == null)
                shizukuClass = Class.forName("rikka.shizuku.Shizuku");
            Method ping = shizukuClass.getMethod("ping");
            return (boolean) ping.invoke(null);
        } catch (Exception e) {
            return false;
        }
    }

    public static Process exec(String cmd) {
        try {
            if (shizukuClass == null)
                shizukuClass = Class.forName("rikka.shizuku.Shizuku");
            if (newProcessMethod == null) {
                newProcessMethod = shizukuClass.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
                newProcessMethod.setAccessible(true);
            }
            return (Process) newProcessMethod.invoke(null,
                new String[]{"sh"}, null, null);  // ⚠️ 不要多包一层 new Object[]{}！
        } catch (Exception e) {
            return null;
        }
    }
}
```

### ⚠️ 致命坑：反射参数包装错误

```java
// ❌ 错误写法（Method.invoke 可变参数多包一层数组，参数结构错乱）
Process proc = (Process) m.invoke(null, new Object[]{new String[]{"sh"}, null, null});

// ✅ 正确写法（直接传参）
Process proc = (Process) m.invoke(null, new String[]{"sh"}, null, null);
```

`Method.invoke(Object obj, Object... args)` 是**可变参数**，多包一层 `new Object[]{}` 导致参数结构错乱，命令**永远不会执行成功**，但不会报错。这个 bug 极其隐蔽，是所有 Shizuku 自动授权失效的根因。

## 1.3 管道写入模式（最佳实践）

获取 Shell 后不要直接把参数传为数组，而是通过 shell 进程的 `getOutputStream()` 写入完整命令：

```java
Process proc = (Process) m.invoke(null, new String[]{"sh"}, null, null);
if (proc != null) {
    PrintWriter writer = new PrintWriter(proc.getOutputStream());
    writer.println(cmd);        // 写入完整命令，如 "appops set ..."
    writer.println("echo __EXIT__:$?");  // 标记退出码
    writer.flush();
    writer.close();
    // 读取 stdout ...
}
```

## 1.4 常用命令集

### 权限相关
```bash
# 设置模拟定位权限（核心一条）
appops set <pkg> android:mock_location allow

# 获取运行时危险权限（pm grant）
pm grant <pkg> android.permission.ACCESS_FINE_LOCATION
pm grant <pkg> android.permission.ACCESS_COARSE_LOCATION
pm grant <pkg> android.permission.ACCESS_BACKGROUND_LOCATION

# Android 13+ 通知权限
pm grant <pkg> android.permission.POST_NOTIFICATIONS
appops set <pkg> android:post_notifications allow

# 悬浮窗权限
appops set <pkg> android:system_alert_window allow
```

### APK 安装
```bash
# 方法1：从 sdcard 安装（SELinux 限制，需要管道绕过）
cat /sdcard/Download/app.apk | pm install -g -S $(stat -c%s /sdcard/Download/app.apk) -r

# 方法2：从 /data/local/tmp/ 安装（推荐！）
cp /sdcard/Download/app.apk /data/local/tmp/
pm install -r -g /data/local/tmp/app.apk
```

### 应用控制
```bash
# 启动 APP（透明 Activity 唤醒）
am start -n <pkg>/<pkg>.AdbCommandActivity --es action run

# 打开浏览器
am start -a android.intent.action.VIEW -d "https://www.baidu.com/s?wd=关键词"

# 查看当前焦点窗口
dumpsys window | grep mCurrentFocus
```

## 1.5 依赖配置

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
```

- minSdk **必须 >= 24**（Shizuku 13.1.5 要求）
- compileSdk / targetSdk: 35
- Shizuku API 13.1.5 兼容 AGP 9.0.0 + Gradle 9.1.0

## 1.6 自动授权的正确时机

```kotlin
// ❌ 错误：在 Activity.onCreate 中授权（APP 刚启动，Shizuku Binder 未就绪）
override fun onCreate() {
    super.onCreate()
    ShizukuShell.exec("appops set ...")  // 太早了！
}

// ✅ 正确：在 Service.onStartCommand 中授权（Service 连接时 Shizuku 已就绪）
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    try {
        ShizukuShell.exec("appops set $packageName android:mock_location allow")
    } catch (_: Exception) {}
    // ...
}
```

**关键**：不检查 `isAvailable`/`isGranted`，直接 try-catch 执行。检查反而可能因时序问题误判。

## 1.7 权限检查方式

```kotlin
// Shizuku 授权状态检查
Shizuku.checkSelfPermission()   // 返回 PERMISSION_GRANTED / PERMISSION_DENIED
Shizuku.requestPermission(1001) // 申请授权

// 授权结果监听（❗注意：不是 onActivityResult！）
Shizuku.addRequestPermissionResultListener(listener)
// 监听器：Shizuku.OnRequestPermissionResultListener
```

## 1.8 ShizukuShell 中 `exec()` 返回 null 需要兜底

```kotlin
val process = ShizukuShell.exec(cmd)
if (process == null) {
    // 走 fallback 逻辑
    return
}
// 正常使用 process
```

---

# 二、Android 安卓项目构建

## 2.1 环境版本

| 组件 | 版本 |
|------|------|
| Gradle | 9.1.0 |
| AGP | 9.0.0 |
| Kotlin | 2.3.10 |
| Java | 17 |
| Android SDK | 35 |
| UI 框架 | Jetpack Compose + Material3 |
| 构建环境 | Proot Ubuntu（移动端） |

## 2.2 gradle.properties 加速配置

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.dependency.optimization.enabled=true
kotlin.daemon.jvmargs=-Xmx2048m
kotlin.incremental=true
kotlin.compiler.execution.strategy=in-process
android.useAndroidX=true
android.nonTransitiveRClass=true
```

## 2.3 国内镜像源（settings.gradle.kts）

```kotlin
@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { setUrl("https://maven.aliyun.com/repository/public/") }
        maven { setUrl("https://maven.aliyun.com/repository/google/") }
        maven { setUrl("https://repo.huaweicloud.com/repository/maven/") }
        maven { setUrl("https://jitpack.io") }
        // Google 官方的要放最后，作为后备
        google()
        mavenCentral()
    }
}
```

## 2.4 AAPT2 必须强制替换（Proot 环境）

```kotlin
// app/build.gradle.kts
android {
    aapt2 {
        useTarget(":linux-aarch64")  // 强制使用 ARM64 版本
    }
}
// 额外属性防止 AAPT2 daemon 报错
android.aapt2.process.daemon=false  // gradle.properties 或命令行传
```

## 2.5 构建命令

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建（⚠️ 可能因 lint 报 ProtectedPermissions 失败）
./gradlew assembleRelease

# 带日志重定向（防止管道卡死）
./gradlew assembleDebug --no-daemon > /sdcard/Download/build_log.txt 2>&1

# 清理后重试
./gradlew clean
```

## 2.6 构建耗时参考（移动端）

| 场景 | 耗时 |
|------|------|
| 首次（全部下载依赖） | ~12分钟 |
| 第二次（缓存首次生成） | ~5分钟 |
| 配置缓存命中 | ~10秒 |
| 改代码后 | 30秒~2分钟 |

## 2.7 依赖精简建议

**最小可用集**（纯 Compose 项目）：
```
activity-compose, compose-bom, ui, material3
```

**常规保留**：
```
core-ktx, lifecycle-runtime-ktx, ui-graphics, ui-tooling-preview
```

**剔除**（非必要不打包）：
```
testImplementation, androidTestImplementation, debugImplementation
```

---

# 三、模拟定位APP开发全攻略

## 3.1 项目配置

- **包名**：`com.virtuallocation.app`
- **技术栈**：Kotlin + Jetpack Compose + Material3
- **AndroidManifest 关键配置**：
  - `ACCESS_MOCK_LOCATION` 权限（加 `tools:ignore="ProtectedPermissions"`）
  - **不能加** `testOnly="true"`（否则安装报 -15）
  - FOREGROUND_SERVICE + FOREGROUND_SERVICE_LOCATION
  - Service 声明 `foregroundServiceType="location"`（Android 14 强制要求）

## 3.2 核心原理

### 三源同时推送
模拟定位不是只改 GPS 就行，很多 APP（如高德、百度地图）会同时参考多个位置源。必须**同时覆盖 gps、network、passive 三个 provider**。

```kotlin
// MockLocationHelper 核心流程
lm.addTestProvider("gps", false, true, false, true, true, true, true, 0, 5)
lm.setTestProviderEnabled("gps", true)
lm.setTestProviderStatus("gps", LocationProvider.AVAILABLE, null, System.currentTimeMillis())

// 同理对 network 和 passive
lm.addTestProvider("network", ...)
lm.addTestProvider("passive", ...)
```

### 持续推送
定位不能只发一次就停，APP 会等待持续的位置更新。**每 100-200ms 推送一次**。

```kotlin
// 协程推送循环
while (isActive) {
    val location = Location("gps").apply {
        latitude = curLat + jitterLat
        longitude = curLng + jitterLng
        accuracy = 8f + jitterRand.nextFloat() * 12f  // 8~20m 真实精度
        speed = 0.5f + jitterRand.nextFloat() * 1.5f  // 0.5~2.0 随机速度
        time = System.currentTimeMillis()
    }
    lm.setTestProviderLocation("gps", location)
    delay(100)
}
```

## 3.3 推送参数优化（让 APP 采纳定位的关键）

> **模拟定位要"真实"而不是"精准"**

| 参数 | 错误值 | 正确值 | 原因 |
|------|--------|--------|------|
| accuracy | 1米 | 8~20米随机 | 真实 GPS 精度在 5~20 米之间，1米太假 |
| speed | 0（恒定） | 0.5~2.0 随机 | 即使静止也有微小波动 |
| 抖动 | 无 | ±10米随机 | 模拟 GPS 漂移 |
| 推送频率 | 仅1次 | 每100ms持续 | APP 需要持续更新的位置 |

## 3.4 Provider 注册的并发问题

### ⚠️ 致命坑：synchronized 锁

`addTestProvider()` / `removeTestProvider()` **不是线程安全的**。快速多次调用时会出现交叉执行：

```
线程A: remove("gps") → add("gps")
线程B: remove("gps") → add("gps")
// 可能变成：A remove → B remove → A add → 崩溃！
```

**修复**：用 `synchronized` 锁保护整个流程：

```kotlin
synchronized(mockLock) {
    stopMockInternal()           // 先停止旧的
    curLat = lat; curLng = lng  // 更新坐标
    if (!registerProviders(m)) {
        // 失败后延迟重试
        Handler(mainLooper).postDelayed({
            synchronized(mockLock) {
                registerProviders(m)
                startPushLoop()
            }
        }, 1000)
        return
    }
    startPushLoop()
}
```

### Provider 延迟注册

APP 刚启动时 LocationManager 未完全就绪，`addTestProvider()` 可能失败。**必须延迟重试**：

```kotlin
if (!registerProviders(m)) {
    // 1秒后自动重试
    Handler(mainLooper).postDelayed({ ... }, 1000)
    return
}
```

## 3.5 onStartCommand "S" 分支的正确处理

> **核心：服务已在运行时不要重新注册 provider，只更新坐标**

```kotlin
"S" -> {
    val lat = intent.getDoubleExtra("lat", 0.0)
    val lng = intent.getDoubleExtra("lng", 0.0)
    val name = intent.getStringExtra("name") ?: ""
    if (lat == 0.0 && lng == 0.0) return START_STICKY

    if (running) {
        // ✅ 服务已在运行：只更新坐标+通知，不碰 provider
        curLat = lat; curLng = lng; curName = name
        mockScope?.cancel()
        startPushLoop()
        try { startForeground(1001, notif(lat, lng, name)) } catch (_: Exception) {}
    } else {
        // 首次启动或已停止：走完整流程
        if (lm == null) {
            Handler(mainLooper).postDelayed({
                onStartCommand(Intent(...), 0, startId)
            }, 500)
            return START_STICKY
        }
        startMockInternal(lat, lng, name)
        try { startForeground(1001, notif(lat, lng, name)) } catch (_: Exception) {}
        running = true
    }
}
```

## 3.6 自愈推送循环

解决「第一次启动定位不生效，需要发两次命令」的问题：

```kotlin
private fun startPushLoop() {
    thread(start = true) {
        // 第一阶段：快速 burst 推送 3 次
        repeat(3) {
            pushOnce()
            Thread.sleep(150)
        }
        // 验证系统是否采纳
        if (!isMockActive()) {
            // 不采纳 → 强制重新授权 + 重新注册 provider
            ShizukuShell.exec("appops set $packageName android:mock_location allow")
            registerProviders(m)
            // 再推 5 次
            repeat(5) {
                pushOnce()
                Thread.sleep(150)
            }
        }
        // 第二阶段：进入正常轮询
        // 改为 Handler 异步轮询（不要阻塞主线程！）
        handler.post(object : Runnable {
            override fun run() {
                pushOnce()
                handler.postDelayed(this, 200)
            }
        })
    }
}
```

## 3.7 ADB 远程控制

### 应用正在运行时
```bash
am broadcast -a com.virtuallocation.app.ADB_COMMAND \
    --es action favorite --es name "收藏名"
```

### 应用已被杀死后（通过透明 Activity 唤醒）
```bash
am start -n com.virtuallocation.app/.AdbCommandActivity \
    --es action favorite --es name "收藏名"
```

### 直接传坐标
```bash
am start -n com.virtuallocation.app/.AdbCommandActivity \
    --es action start --ef lat 24.4667 --ef lng 118.1167
```

## 3.8 验证命令

```bash
# 查看 mock provider 状态
dumpsys location | grep -A5 "gps provider \[mock\]"

# 查看 last location
dumpsys location | grep -E "last location=Location\[gps" | head -3
```

## 3.9 已知无解问题

- **高德地图模拟定位不生效**：高德 SDK 主动调用 `isFromMockProvider()` 检测模拟定位，APP 层面无法绕过
- `network provider` 在某些手机上无法 mock，不影响 gps 推送

---

# 四、UI自动化与AutoJS6

## 4.1 核心规则

> **进入目标 APP 界面后，所有操作通过已写好的 AutoJS6 脚本执行，以节省 token**

## 4.2 脚本体系

| 文件 | 路径 | 功能 |
|------|------|------|
| 读取ui.js | `/sdcard/脚本/Operit/读取ui.js` | 抓取当前屏幕所有可见控件(text, clickable, x, y)→输出到 current_ui.json |
| 点击目标坐标.js | `/sdcard/脚本/Operit/点击目标坐标.js` | 读取目标坐标.txt → shizuku('input tap x y') 点击 → toast提示 |
| 目标坐标.txt | `/sdcard/脚本/Operit/目标坐标.txt` | 存储点击坐标，格式 `x,y` |

## 4.3 运行命令

```bash
am start -n org.autojs.autojs6/org.autojs.autojs.external.open.RunIntentActivity \
    -a android.intent.action.VIEW \
    -d /storage/emulated/0/脚本/Operit/<脚本名.js> \
    -f 0x10000000
```

## 4.4 标准操作流程

1. `am start` 打开目标 APP
2. 运行 `读取ui.js` → 查看 `current_ui.json` 找到目标坐标
3. `echo -n "x,y" > 目标坐标.txt`
4. 运行 `点击目标坐标.js`
5. 再次运行 `读取ui.js` 确认页面变化

## 4.5 踩坑记录

| 坑点 | 说明 |
|------|------|
| ❌ ForegroundService 启动 | 后台无 toast，点击无效 |
| ✅ 必须用 RunIntentActivity 启动 | 正确方式 |
| ❌ click() 或 className().text().click() | 支付宝等 APP 有触摸防护 |
| ✅ 必须用 `shizuku('input tap x y')` | 绕过触摸防护 |
| ❌ 首页"总资产"文字(≈y168) | 是搜索栏占位文本，不是功能入口 |
| ✅ "总资产"在"我的"页面中部(y≈938) | 正确入口 |
| ✅ 同一文字在不同页面含义不同 | 必须结合坐标范围判断页面模式 |

## 4.6 页面特征快速判断

| 页面 | 特征 |
|------|------|
| **首页** | 出现"扫一扫""收付款""出行"等快捷卡片，底部"首页"高亮 |
| **搜索页** | 出现"搜索历史"、搜索结果等 |
| **我的页** | 出现用户昵称、手机号、"总资产""账单"等列表项 |
| **总资产页** | 顶部"资产概览"，出现"活期资产""稳健理财""进阶理财"分区 |

## 4.7 6 条避坑经验

1. **每次操作前先确认当前页面状态**：先抓屏，从页面特征判断"我在哪个页面"
2. **坐标写入后验证合理性**：分辨率 1080×2400，y<200 为导航区，y>2300 为底部栏
3. **页面内容解读要关联上下文**：数字看它上下左右的文字标签；同一数据重复出现
4. **关键操作后必须抓屏确认**：每次点击后都运行读取ui.js
5. **路径规划要灵活应变**：不能死板套用历史坐标
6. **相同文字在不同位置含义不同**：先判断页面模式

---

# 五、Playwright 网页数据采集

## 5.1 技术栈

- **库**：Python + `playwright` (async API)
- **模式**：`headless=True`（无头模式）
- **浏览器**：Chromium（自动下载）

## 5.2 安装与导入

```bash
pip install playwright
playwright install chromium
```

## 5.3 关键策略（避免超时）

| 问题 | 解决方案 |
|------|---------|
| `networkidle` 超时（动态请求太多） | 改用 `domcontentloaded` + `wait_for_timeout(3000)` |
| 截图保存到 Android 不可访问 | 保存到 `/sdcard/Download/` 路径 |
| 元素定位不稳定 | 先用 snapshot 再映射 ref，或用 CSS 选择器 |

## 5.4 核心代码模板

```python
import asyncio
from playwright.async_api import async_playwright

async def fetch_news():
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        page = await browser.new_page(viewport={"width": 1280, "height": 720})

        await page.goto("https://target-url.com", timeout=60000)
        await page.wait_for_load_state("domcontentloaded", timeout=30000)
        await page.wait_for_timeout(3000)  # 等待动态渲染

        # 提取正文（inner_text 方式，最稳定）
        body_text = await page.inner_text('body')
        # ... 解析逻辑

        # 截图
        await page.screenshot(path="/sdcard/Download/screenshot.png", full_page=True)
        await browser.close()

asyncio.run(fetch_news())
```

## 5.5 正文提取的最简方案

> **不需要任何 DOM 选择器！一条 `inner_text('body')` 通吃**

```python
async def fetch_content(page, url, timeout=25000):
    await page.goto(url, timeout=timeout, wait_until='domcontentloaded')
    await page.wait_for_timeout(2000)
    raw = await page.inner_text('body')
    lines = [l.strip() for l in raw.split('\n') if l.strip()]

    # 找正文起点（以"全文共X字"标记为例）
    start = -1
    for idx, line in enumerate(lines):
        if '全文共' in line:
            start = idx + 1
            break
    if start == -1: start = 3  # 兜底

    # 收集正文，遇到广告/推荐标记停止
    collected = []
    for line in lines[start:]:
        if any(w in line for w in ['付费专享', 'App 内打开', '推荐']):
            break
        if len(line) < 4 and not re.search(r'\w', line):
            continue
        collected.append(line)

    return '\n\n'.join(collected)
```

## 5.6 懒加载处理

```python
# 循环滚动加载更多内容
for _ in range(5):
    await page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
    await page.wait_for_timeout(1500)
```

## 5.7 详情页正文兜底策略（8个选择器回退）

```python
# 在 page.evaluate() 中使用 JS 多选择器回退
const selectors = ['article', '.article-content', '.content', '.post-content',
                   '.article-body', '.detail-content', '#content', 'main'];
for (const sel of selectors) {
    const el = document.querySelector(sel);
    if (el && el.innerText.trim().length > 100) {
        return el.innerText.trim();
    }
}
// 最后兜底：取所有 p 标签文本
return Array.from(document.querySelectorAll('p')).map(p => p.innerText).join('\n');
```

## 5.8 展示方案（无需搭建服务器）

生成自包含 HTML（数据嵌入 JavaScript 数组），通过 Python `http.server` 本地服务 + `am start` 在 X 浏览器打开：

```bash
# 1. 启动本地 HTTP 服务
cd /sdcard/Download && python3 -m http.server 8899 --bind 127.0.0.1 &

# 2. 用 X 浏览器打开
am start -a android.intent.action.VIEW \
    -d "http://127.0.0.1:8899/your_page.html"
```

---

# 六、金融数据接口

## 6.1 akshare（推荐 ✅）

**安装**：`pip install akshare`
**版本**：1.18.60

### 可用新闻接口
```python
import akshare as ak

# 东方财富新闻（推荐）
df = ak.stock_news_em(symbol='财经要闻')
# 返回：关键词, 新闻标题, 新闻内容, 发布时间, 文章来源, 新闻链接

# 财新网新闻
df = ak.stock_news_main_cx()
# 返回：tag, summary, url

# 央视新闻（数据较旧）
df = ak.news_cctv()
```

## 6.2 tushare pro（❌ 受限）

- **Token**：免费 token（1794cf7d6...）只能调基本行情
- `pro.major_news()` 需要 800 积分（付费）

## 6.3 efinance（❌ 无新闻模块）

- 纯行情/基本面数据，无新闻 API
- 可用：get_quote_history, get_realtime_quotes, get_base_info

## 6.4 彭博社新闻（无公开 API）

所有 Python 库均不支持直接获取彭博社新闻（彭博终端年费 > $20K）。替代方案：
- 访问中文聚合站 **iBloomberg (https://m.bbwc.cn)**
- 或通过 `visit_web` / Playwright 直接访问 bloomberg.com

---

# 七、GitHub 上传流程

## 7.1 核心教训

> **永远不要从 zip 备份上传！直接从 Operit 工作区复制源码**

- ❌ 从 sdcard 的 zip 解压后上传：备份可能不是最新版，可能缺少新增文件
- ✅ 从工作区复制：`/data/user/0/com.ai.assistance.operit/files/workspace/{workspaceId}/`

## 7.2 上传步骤

```bash
# 1. 复制源码到 Linux 环境
cp -r "工作区路径" /home/project

# 2. 清理缓存文件
rm -rf .backup/ .operit/ app/build/

# 3. 添加 .gitignore
echo ".gradle/
.idea/
build/
.local/
.backup/
.operit/
*.iml" > .gitignore

# 4. 初始化仓库
git init
git config user.name "your_username"
git config user.email "your_email@users.noreply.github.com"
git add .
git commit -m "描述信息"
git branch -M main

# 5. 推送（URL 中嵌入经典 Token）
git remote add origin https://用户名:经典Token@github.com/用户名/仓库名.git
git push -u origin main

# 强制覆盖已上传的旧版
git push -f -u origin main
```

## 7.3 Token 注意事项

| Token 类型 | 说明 |
|-----------|------|
| **经典 Token（ghp_ 开头）** | ✅ 支持 git push |
| **细粒度 Token（github_pat_ 开头）** | ❌ 不支持 git push，仅用于 API |
| Token 权限 | 必须勾选 `repo` 全部权限 |
| 安全 | 保管好，不要传到公开仓库 |

---

# 八、万能APP + 无障碍服务开发

## 8.1 ⚠️ 血泪教训：手机变砖的完整链路

**致命组合**：无障碍服务(AccessibilityService) → 前台悬浮窗覆盖层(OVERLAY) → `TYPE_WINDOW_CONTENT_CHANGED` 事件触发扫描 UI 树 → 死循环 → 手机重启后再次进入死循环 → **只能通过 ADB 线连电脑解决**

### 完整变砖链路
```
手机开机
  ↓
系统自动启动无障碍服务（用户之前开启了）
  ↓
onAccessibilityEvent 被高频调用（开机过程系统UI变化极多）
  ↓
scanAndDraw() 不断递归遍历 UI 树
  ↓
OverlayDrawService 叠加全屏覆盖层
  ↓
用户触摸屏幕无响应
  ↓
重启 → 再次进入死循环
```

### ADB 急救命令
```bash
# 清空无障碍服务列表（最有效）
adb shell settings put secure enabled_accessibility_services ""

# 停用无障碍功能
adb shell settings put secure accessibility_enabled 0

# 强制卸载
adb shell settings put secure enabled_accessibility_services ""
adb uninstall com.wanneng.app

# 安全模式启动（第三方无障碍服务不启动）
adb reboot safe-mode
```

## 8.2 如何安全开发无障碍服务

### ✅ 安全代码实践

```kotlin
// ❌ 不要在 onServiceConnected 中自动开启任何功能！
override fun onServiceConnected() {
    // enableOverlay() // ← 千万不要！
}

// ✅ 所有功能由用户手动触发
override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null || !recording) return  // ← recording 必须用户手动开启
    // ...
}

// ❌ 不要监听高频事件做耗时操作
// AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED → 频率极高！

// ✅ 只用低频事件
// AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED → 页面切换时触发
// AccessibilityEvent.TYPE_VIEW_CLICKED → 用户点击时触发

// ❌ 不要用 START_STICKY
// return START_STICKY  // 进程被杀后自动重启，极其危险

// ✅ 用 START_NOT_STICKY
return START_NOT_STICKY
```

### 建议的测试流程
```
1. 用旧手机或备用机测试
2. 测试前连上电脑开好 ADB
3. 写好卸载脚本随时执行
4. 最后一步才开启无障碍服务全面测试
5. 手边常备一条数据线
```

## 8.3 PermOpener 权限全开

### pm grant 列表（30项危险权限）
定位、相机、麦克风、电话、短信、通讯录、日历、身体传感器、活动识别、通知、蓝牙、媒体...

### appops set 列表（146项标准命令）
大部分需要带 `android:` 前缀（如 `android:mock_location`）

### 执行流程
```kotlin
if (Shizuku.checkSelfPermission() == PERMISSION_GRANTED) {
    runAll() // 静默执行全部授权
} else {
    Shizuku.addRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PERMISSION_GRANTED) runAll()
    }
    Shizuku.requestPermission(1001)
}
```

### ⚠️ 注意
- 授权监听用 `addRequestPermissionResultListener`，**不是** `onActivityResult`！
- 不要通过 Shizuku 运行时授予 `MANAGE_EXTERNAL_STORAGE`，系统会杀进程

## 8.4 无障碍服务开机自启机制

| 特点 | 说明 |
|------|------|
| 触发机制 | system_server 内部的 AccessibilityManagerService 直接 bindService |
| 是否经 BOOT_COMPLETED | **否**，不经过广播 |
| 前提条件 | 用户必须在设置中手动开启该服务 |
| 国产 ROM 限制 | **不受限制**（残障用户必需功能）|
| 关闭方式 | 去设置关闭，或 ADB：`settings put secure enabled_accessibility_services ""`|
| 持久性 | system_server 持续管理，崩溃后自动重启 |
| 解锁前运行 | `directBootAware="true"` 时可在锁屏前运行 |

## 8.5 编译打包注意事项

### UP-TO-DATE 问题
```bash
# 在 tmp 目录修改代码后，Gradle 检测不到源码变化
# 必须先删除旧目录再重新复制

rm -rf /tmp/project
cp -r "工作区路径" /tmp/project
cd /tmp/project && ./gradlew assembleDebug --no-daemon
```

### 每次更新 APK 后的关键操作
```bash
# pm install -r 后 APP 被系统禁用
pm enable com.wanneng.app
```

### 无障碍服务修改后生效
```bash
# 需要关掉再重新开启无障碍服务
settings put secure enabled_accessibility_services ""
settings put secure enabled_accessibility_services "com.wanneng.app/.WannengAccessibilityService"
settings put secure accessibility_enabled 1
```

---

# 九、财经新闻APP避坑

## 9.1 环境版本

- AGP 9.0.0, Kotlin 2.3.10, Gradle 9.1.0
- Proot Ubuntu 编译环境

## 9.2 踩坑速查表

| 错误关键词 | 问题 | 秒修方案 |
|-----------|------|---------|
| `Cannot find implementation for ..._Impl does not exist` | Room注解处理器未生成代码 | 删Room，换Gson+JSON文件存储 |
| `not compatible with built-in Kotlin support` | KAPT和AGP 9.0不兼容 | 别用KAPT/KSP，用Gson替代 |
| `No value passed for parameter 'value'` | Kotlin 2.3强制初始值 | `MutableStateFlow<String?>()` |
| 数据为空但没崩溃，一直转圈 | collectLatest嵌套collect死锁 | 改用applyFilter()过滤模式 |
| `Cannot infer type for type parameter` | snapshotFlow类型推导失败 | 换成属性监听+手动过滤 |
| `INSTALL_FAILED_ABORTED: User rejected permissions` | SELinux禁止读sdcard | 从/data/local/tmp/安装 |
| `Syntax error` 在 try-catch 里 | withTimeout打乱了代码结构 | 不用withTimeout，用OkHttp超时就够了 |

## 9.3 最佳实践模板

### 不用 Room 的持久化方案
```kotlin
class JsonStorage(context: Context) {
    private val gson = Gson()
    private val file = File(context.filesDir, "data.json")
    private val _data = MutableStateFlow<List<Item>>(emptyList())

    fun getAll(): Flow<List<Item>> = _data.asStateFlow()
    fun getCached(): List<Item> = _data.value

    suspend fun load() {
        if (file.exists()) {
            val json = file.readText()
            _data.value = gson.fromJson(json, type)
        }
    }

    suspend fun save(list: List<Item>) {
        // 先写缓存再 rename，防止写坏
        val tmp = File(context.filesDir, "data.json.tmp")
        tmp.writeText(gson.toJson(list))
        tmp.renameTo(file)
        _data.value = list
    }
}
```

### 不烂尾的 ViewModel（不要用 collectLatest 嵌套 collect）
```kotlin
class SafeViewModel : ViewModel() {
    private var cache: List<Item> = emptyList()
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    // 不要用 combine/flatMapLatest/collectLatest
    // 只用：修改状态 → 调用 applyFilter()

    private fun applyFilter() {
        var result = cache
        if (query.isNotBlank()) result = result.filter { ... }
        if (category != "全部") result = result.filter { ... }
        _items.value = result
    }
}
```

### OkHttp 在协程中的正确用法
```kotlin
private suspend fun fetch(): List<Article> {
    // 即使内部是阻塞调用，函数也要加 suspend
    val resp = client.newCall(request).execute()
    return parse(resp.body!!.string())
}

// 在 async {} 里调它
async { fetch() }

// 设置超时
val client = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()
```

## 9.4 编译环境最佳实践

- **AGP 9.0 + Kotlin 2.3**：不要碰 kapt/ksp/annotationProcessor
- **国内镜像**：settings.gradle.kts 里配阿里云+华为云
- **AAPT2**：Proot 环境必须强制用 linux-aarch64 版
- **编译命令**：`./gradlew assembleDebug --no-daemon > build.log 2>&1`（防止管道卡死）
- **变基原则**：纯 Kotlin，不用 Java，不用注解处理器，依赖越少越好

---

# 十、Android 系统机制深度研究

## 10.1 AccessibilityService 开机自启机制

```
用户开启无障碍服务
  ↓
系统将 ComponentName 写入 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
文件：/data/system/users/0/settings_secure.xml
  ↓
重启手机
  ↓
system_server → AccessibilityManagerService 初始化
  ↓
读取 settings_secure.xml，获取 ComponentName 列表
  ↓
对每个组件：context.bindService(intent, connection, BIND_AUTO_CREATE)
  ↓
APP 进程不存在 → 系统先创建进程 → 实例化 Service
  ↓
系统调用 onServiceConnected() → 服务就绪
```

## 10.2 无障碍事件频率

| 事件类型 | 频率 | 说明 |
|---------|------|------|
| `TYPE_WINDOW_CONTENT_CHANGED` | **极高** | 任何界面内容变化都触发，开发时极度危险 |
| `TYPE_WINDOW_STATE_CHANGED` | 中等 | 页面切换时触发，适合记录页面变化 |
| `TYPE_VIEW_CLICKED` | 用户操作触发 | 用户点击时触发，适合行为记录 |

## 10.3 AccessibilityNodeInfo API 要点

| API | 说明 |
|-----|------|
| `getRootInActiveWindow()` | 获取当前活动窗口根节点（最常用） |
| `getWindows()` (API 21+) | 获取所有交互窗口（含对话框、悬浮窗） |
| `getViewIdResourceName()` (API 18+) | 获取控件资源 ID，UI 自动化核心 |
| `getUniqueId()` (API 33+) | 节点唯一标识，跨事件追踪同一控件 |
| 预取策略 Prefetching (API 33+) | 批量获取最多 50 个节点，减少跨进程调用 |
| 节点缓存 (API 33+) | `setCacheEnabled(true)` + `isContentInvalid()` |

### 关键 Flag

| Flag | 说明 |
|------|------|
| `canRetrieveWindowContent="true"` | **必须**，检索窗口内容总开关 |
| `FLAG_REPORT_VIEW_IDS` (API 18+) | 让节点携带 viewId 信息 |
| `FLAG_REQUEST_FILTER_KEY_EVENTS` | 拦截按键事件（如音量键） |
| `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` | 访问所有交互窗口（仅配合 `getWindows()` 需要） |
| `FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY` | **已废弃**（API 26 起标记 deprecated） |

---

# 十一、通用经验教训汇总

## 11.1 核心原则

> **代码结构比逻辑更重要**：括号不匹配、函数嵌套错了，逻辑再对也跑不起来

1. **先查资料再动手** — 这是所有教训的根因。出问题先看 logcat 确认异常类型 → 搜解决方案 → 确认 root cause 后再动手
2. **不要闭门造车** — 你在会遇到的问题，别人一定已经遇到并解决了
3. **每一步都验证** — 改一行代码，编译一次，不等到最后一次性验证

## 11.2 代码质量

1. **反射调用**：检查参数传递方式（`Method.invoke` 可变参数不要多包一层数组）
2. **异常不要完全吞掉**：最少输出到 logcat
3. **递归函数**：要有明确的终止条件
4. **花括号数量必须匹配**：编译通过不代表运行正常。定期 `grep -o '{' file | wc -l` 和 `grep -o '}' file | wc -l` 检查
5. **函数嵌套检查**：`grep -n "private fun\|fun "` 检查函数定义位置
6. **sed 全局替换要谨慎**：`sed -i 's/xxx/yyy/g'` 会替换所有匹配，包括已正确的地方
7. **`apply_file` 大段替换容易出事**：优先完整重写文件

## 11.3 测试与调试

1. **每步操作后截图确认** — 避免顺序错觉
2. **不用假设，用数据说话**
3. **运行时闪退先看 logcat**：`logcat -b crash -d` 看崩溃堆栈
4. **诊断命令**：
   ```bash
   dumpsys location | grep -A5 "gps provider [mock]"
   settings list secure | grep mock
   settings list secure | grep accessibility
   ```
5. **不要同时改太多东西**：一次改一个功能，每一步都验证后再继续

## 11.4 Shizuku 专区

1. 反射调用 newProcess 必须放 Java 文件（Kotlin 编译检查拦截 private 方法）
2. `AppOpsService.setMode()` 权限不够，用 shell 命令替代
3. 不要搞 Binder transact 或 UserService，shell 命令就是最直接的方式
4. Shizuku 绑定时序：**Service.onStartCommand 比 Activity.onCreate 更可靠**
5. 异常不要完全吞掉，最少输出到 logcat

## 11.5 文件与存储

1. 文件写入必须用 `filesDir`（内部存储），写 `/sdcard/` 在 Android 10+ 可能被 SELinux 拦截
2. 先写缓存文件再 rename，防止写坏
3. APK 从 `/sdcard/` 安装被 SELinux 限制 → 从 `/data/local/tmp/` 安装

## 11.6 不同项目的工作区路径

| 项目 | 工作区路径 |
|------|-----------|
| 虚拟定位APP v5.4 | `6c9c9b2f-2e5b-432b-9312-d599f08212a6` |
| 万能APP / PermOpener | `18c2e307-6fa8-4aaa-86e5-b0a7f47f7331/162d58ed-a2d9-4a38-82c7-b418cbd8cd5c/` |
| FloatRunner v1.0 | `/home/ShizukuRunner/`（直接 clone） |

---

> **本文件由 Operit AI 根据用户实际开发经历自动整理生成，2026-05-07**

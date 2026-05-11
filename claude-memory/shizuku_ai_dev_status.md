---
name: shizuku-ai 开发状态
description: Shizuku AI 项目的开发状态、已实现能力、待办和架构
type: project
originSessionId: f94680b6-a3b5-4b2e-ae80-f32511c7d14f
---
# Shizuku AI 开发状态（2026-05-11）

## 版本
- 当前: 1.6.0+ (versionCode 6+)
- Gitee: https://gitee.com/bbsbbs4321/claude (shizuku-ai/)

## 项目定位
轻量级 AI 助手，通过 Shizuku 获取系统权限 + 无障碍服务读屏 + 多引擎联网搜索。差异化于 Operit（不搞重型引擎/JS 工具系统/Ubuntu 环境等）。

## 架构（工具调用模式）

```
FloatService (悬浮窗主控)
  ├── AIAgent (DeepSeek API + 工具说明书)
  │   ├── 工具清单注入上下文 → AI 选工具填参数
  │   └── JSON {tool, params} → 调度中心
  ├── 工具调度中心
  │   ├── search_web → Bing/Baidu 搜索
  │   ├── browse_url → WebView 浏览器
  │   ├── read_page → 读浏览器页面
  │   ├── read_screen → 无障碍读屏（文字+控件+坐标bounds）
  │   ├── start_app → monkey 启动
  │   ├── list_apps → PackageManager
  │   ├── toggle_flashlight → settings system (vivo) / CameraManager
  │   ├── set_alarm → SET_ALARM Intent
  │   ├── execute_shell → ShizukuShell
  │   ├── execute_intent → am start
  │   ├── get_device_info → getprop
  │   ├── read_uimap → UiMapRecorder 导出
  │   ├── create_note → 原子笔记 Intent
  │   └── learn → key-value 记忆
  ├── ShizukuAccessibilityService
  │   ├── captureScreen() — rootInActiveWindow 为主 + getWindows 兜底
  │   ├── captureDetailedScreen() — 始终收集文字+控件结构(class/bounds/clickable)
  │   ├── TYPE_WINDOW_STATE_CHANGED
  │   └── TYPE_VIEW_CLICKED
  ├── WebView 浏览器浮层
  ├── SiriBall 悬浮球 (Canvas+ValueAnimator)
  ├── UiMapRecorder (ActionPilot 录制引擎移植)
  └── ShizukuShell (反射 newProcess 执行命令)
```

## 已实现能力

### ✅ read_screen 读屏（核心能力）
- `captureScreen()` — rootInActiveWindow 为主，getWindows() 兜底，收集所有可见文字
- `captureDetailedScreen()` — 始终收集控件结构信息（class name、label、bounds 坐标、clickable 标记）
- AI 可根据 bounds 坐标用 `input tap x y` 模拟点击
- 桌面启动器检测 + 自动附加应用列表（vivo 桌面 rootInActiveWindow=null）
- accessibility_service_config.xml + manifest meta-data 对齐系统配置

### ✅ 联网搜索 (Bing + Baidu)
- searchWeb() 先试 Bing，失败自动切 Baidu
- 从搜索结果 HTML 解析标题+链接+摘要
- 同步显示到悬浮窗输出区

### ✅ 内置浏览器 (WebView 浮层)
- 独立 WindowManager 浮层，比主浮窗宽 (75% 屏幕宽度)
- AI 用 [BROWSE] 打开网页，用户实时看到浏览内容
- [READ] 通过 evaluateJavascript 提取 document.body.innerText
- 新消息自动关闭浏览器

### ✅ 无障碍读屏
- ShizukuAccessibilityService (从 ActionPilot RecordAccessibilityService 移植)
- captureScreen() 抓取当前屏幕所有可见文本（rootInActiveWindow 为主）
- captureDetailedScreen() 无文字时自动收集节点结构信息（class name、bounds、clickable 标记）
- 自动检测前台 App 切换
- [SCREEN] 标签触发读屏
- 启动时自动 settings put secure 启用

### ✅ SiriBall 悬浮球
- Canvas+ValueAnimator 实现 Siri 风格动态球体
- 点击切换完整浮窗，浮窗可缩小回球
- 拖拽支持

### ✅ ActionPilot 录制引擎移植
- UiMapRecorder (Java 实现，无 Gson 依赖)
- `read_uimap` 工具输出操作地图 JSON
- dumpsys 轮询兜底窗口切换检测

### ✅ 基础能力
- 应用列表查询 (PackageManager)
- Shizuku Shell 执行
- create_note → vivo 原子笔记
- [LEARN] 经验记忆

## 待办 / 可以做的方向

### 短期
- **AI 主动点击** — 无障碍读到的 bounds 坐标，用 input tap 模拟点击
- **截图分析** — screencap + AI 视觉理解当前屏幕内容
- **更多搜索源** — 在 Bing/Baidu 基础上加 Sogou/Quark

### 中期
- **ADB 工具库** — 将 appops / pm grant 工具化
- **跨 App 操作流程** — 打开支付宝 → 截图 → 读余额 → 返回

## 当前的坑

### JSON 解析格式问题（2026-05-11 已修复）
AI 输出格式化 JSON（带换行缩进），但 `AIAgent.parseResponse()` 只搜精确的 `{"tool"` 字符串匹配，导致工具调用不执行。

**修复**：改用正则 `\{\s*"tool"` 匹配起始位置，`toolJsonStart >= 0` 进入解析，直接 `new JSONObject()` 解析（原生支持空格）。

### vivo 桌面启动器无障碍节点树为空
read_screen 在 vivo 桌面（com.bbk.launcher2）上读不到任何节点信息。rootInActiveWindow 返回 null，getWindows() 也拿不到有效节点。这是 vivo Funtouch OS 桌面启动器的系统限制。

**影响**：桌面启动器上无法获取图标的 bounds 坐标，AI 无法通过坐标点击桌面图标。

**当前方案**：
- 桌面场景自动检测，走 Shizuku 拉取已安装应用列表
- AI 用 start_app 打开应用（monkey 启动）
- 打开 App 后无障碍读屏正常工作

### 搜索不到结果
HttpURLConnection 不够可靠，换 OkHttp + Windows UA。三引擎：DuckDuckGo → Bing → Baidu。屏蔽知乎 zhihu.com。

## 已解决的历史问题

### JSON 解析不支持格式化输出（2026-05-11 已修复）
AI 输出格式化 JSON（带换行缩进），parseResponse 找不到工具调用。

**根因**：`indexOf("{\"tool\"")` 只搜精确的紧贴 `{"tool"`，AI 输出的是 `{\\n  "tool"`。
**第二道坑**：`toolJsonStart > 0` 排除了 `toolJsonStart = 0`（JSON 为唯一输出时）。
**第三道坑**：找到后还有 `jsonPart.startsWith("{\"tool\"")` 二次检查，同样不支持空格。

### IME 焦点切换问题（2026-05-09 已解决）
浮窗输入框和微信输入框之间无法丝滑切换焦点。改用 Compose (FocusRequester) 方案。

### read_screen 读不到屏幕内容（2026-05-11 已解决）
captureDetailedScreen() 在 captureScreen() 有文字时提前返回，不收集结构信息。
**修复**：去掉 early return，始终收集结构；saveStructure() 不再伪造 lastScreenText。

### dumpsys 兜底无用（2026-05-11 已移除）
vivo 手机上 dumpsys 读不到有用的 UI 信息，已完全移除 dumpsys 相关代码。

## 不做（跟 Operit 差异化）
- 不搞 JS 工具引擎
- 不内置 Linux 环境
- 不支持本地模型
- 不搞 MCP 生态

## 构建 & 安装

```bash
export JAVA_HOME="E:/Program Files/Android/Android Studio/jbr"
export ANDROID_HOME="C:/Users/ql/AppData/Local/Android/Sdk"
export MSYS_NO_PATHCONV=1
cd "F:/app/Claude/app/shizuku-ai"
./gradlew assembleDebug
adb -s 10CE2S1FPD0027X push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/shizuku-ai.apk
adb -s 10CE2S1FPD0027X shell pm install -r /data/local/tmp/shizuku-ai.apk
```

注意：
- 旧版签名不一致需要先 `adb uninstall com.shizuku.ai`
- JDK 用 Android Studio 自带的 JBR（JDK 21），不要用 Temp 的 jlink 裁剪版
- accessibility_service_config.xml 和 manifest meta-data 必须同时配置

## 已验证的 OEM 专用命令

### vivo 手电筒
```bash
settings put system FlashState 1
settings put system FlashState 0
```
*vivo 手电筒在 system 表，settings put secure torch_state 只改状态不触硬件。*

## ADB 设备信息
- 型号: vivo V2313A (Android 14)
- 设备号: 10CE2S1FPD0027X
- ADB: C:/Users/ql/AppData/Local/Android/Sdk/platform-tools/adb

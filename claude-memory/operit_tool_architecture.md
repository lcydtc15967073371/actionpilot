---
name: Operit 工具体系完整分析
description: Operit 全部 JS 工具包的分类、每个工具的签名和用途，供 shizuku-ai 参考设计
type: reference
originSessionId: f94680b6-a3b5-4b2e-ae80-f32511c7d14f
---
# Operit 工具体系完整分析（2026-05-09）

## 核心架构

Operit 不靠 AI 猜命令。它为 AI 准备三样东西：

1. **工具库** — 几十个写好的功能代码，每个对应真实 Android 操作
2. **工具说明书（Manifest）** — 每次请求把工具清单塞进上下文，AI 能看到"我有这些本事"
3. **提示策略** — "优先用工具解决问题，别自己瞎编"

AI 选工具 + 填参数 → app 执行验证过的代码 → 返回结果。

## 工具包清单

### 1. system_tools.js — 系统工具（13 个工具）

```
get_system_setting(setting, namespace?)
  → 读系统设置（system/secure/global）

modify_system_setting(setting, value, namespace?)
  → 改系统设置

install_app(path)
  → 安装 APK

uninstall_app(package_name, keep_data?)
  → 卸载应用

list_installed_apps(include_system_apps?)
  → 列出已安装应用

start_app(package_name, activity?)
  → 启动应用（可指定 Activity）

stop_app(package_name)
  → 强制停止应用

send_broadcast(action, package_name?, component?, uri?, extras?)
  → 发广播

execute_intent(type?, action?, package_name?, component?, uri?, flags?, extras?)
  → 执行任意 Intent

get_notifications(limit?, include_ongoing?)
  → 读取通知栏

get_app_usage_time(package_name?, since_hours?, limit?, include_system_apps?)
  → 应用使用时长

get_device_location(high_accuracy?, timeout?)
  → 获取位置

get_device_info()
  → 设备详细信息
```

### 2. automatic_ui_base.js — UI 自动化基础（11 个工具）

```
usage_advice() — AI 使用 UI 工具的策略说明（无参数，纯提示）

app_launch(package_name) — 按包名启动应用

get_page_info(format?, detail?) — 获取当前 UI 树（xml/json, minimal/summary/full）

get_page_screenshot_image() — 截图返回路径

tap(x, y) — 坐标点击

double_tap(x, y) — 双击

long_press(x, y) — 长按

click_element(resourceId?, className?, index?, partialMatch?, bounds?)
  → 按 resourceId/class/文本/坐标 找元素点击

set_input_text(text) — 输入文本

press_key(key_code) — 模拟按键（KEYCODE_BACK/HOME 等）

swipe(start_x, start_y, end_x, end_y, duration?) — 滑动
```

### 3. automatic_ui_subagent.js — 高级 UI 子代理

```
run_subagent_main(intent, target_app?, max_steps?)
  → 主屏运行 UI 子代理

run_subagent_virtual(intent, target_app?, max_steps?, agent_id?)
  → 虚拟屏运行 UI 子代理

run_subagent_parallel_virtual(intent_1~4, ...)
  → 并行运行 1-4 个 UI 子代理

close_all_virtual_displays()
  → 关闭所有虚拟屏
```
子代理本身其实是一个独立的 UI 控制器模型（如 autoglm-phone-9b）。

### 4. browser.js — 浏览器自动化（18 个工具）

类似 Playwright MCP，包含：
```
goto(url) → 导航
click(ref, selector?, element?, doubleClick?, button?, modifiers?) → 点击元素
snapshot(filename?, selector?, depth?) → 页面结构化快照
type(ref, text, element?, submit?, slowly?) → 输入文本
evaluate(function, element?, ref?) → 执行 JS
press_key(key) → 键盘按键
wait_for(time?, text?, textGone?) → 等待
back() / close() / resize(w, h) 等
fill_form(fields) / select_option(ref, values) 等
tabs(action, index?) → 标签页管理
console_messages(level?, filename?) → 控制台日志
network_requests(...) → 网络请求
```

### 5. various_search.js — 多平台搜索

```
search_bing(query, includeLinks?)
search_baidu(query, page?, includeLinks?)
search_sogou(query, page?, includeLinks?)
search_quark(query, page?, includeLinks?)
combined_search(query, platforms, includeLinks?) — 同时搜多个
search_bing_images(query) — 必应图片搜索
search_wikimedia_images(query)
```

### 6. google_search.js — Google 搜索

```
search_web(query, max_results?, language?, region?, includeLinks?)
search_scholar(query, max_results?, language?, includeLinks?)
search_scholar_mirror(query, ...) — 镜像站
```

### 7. extended_http_tools.js — HTTP 工具

```
http_request(url, method, headers?, body?, body_type?, ignore_ssl?)
multipart_request(url, method, ...) — 文件上传
manage_cookies(action, domain?, cookies?)
```

### 8. extended_file_tools.js — 文件工具

```
file_exists(path, environment?)
move_file(source, destination, environment?)
copy_file(source, destination, recursive?, ...)
file_info(path, environment?)
zip_files(source, destination, ...)
unzip_files(source, destination, ...)
open_file(path, environment?)
share_file(path, title?, environment?)
```

## 架构关键点

1. **所有工具有统一元数据格式**：name + description(zh/en) + parameters(name/type/required)
2. **JS runtime 调用 Java 后端**：`Tools.System.xxx()` / `Tools.Net.visit()` 映射到 Kotlin 实现
3. **AI 只选工具填参数**，不构造命令
4. **每次注入完整 Manifest** 到上下文，AI 实时知道能用什么
5. **支持组合调用**：一次返回多个工具调用，app 按序执行

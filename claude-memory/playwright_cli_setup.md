---
name: Playwright CLI 配置全流程
description: @playwright/cli v0.1.13 安装、Chrome 145 集成、bash 配置、Gitee 备份
type: reference
originSessionId: d85a314d-09e5-48f7-a1d1-b4bb8bc44f25
---
## 安装的组件

| 组件 | 版本 | 用途 |
|------|------|------|
| `playwright` (npm) | 1.58.0 | 传统 Playwright CLI（screenshot/codegen/pdf） |
| `@playwright/cli` (npm) | 0.1.13 | AI agent 专用 CLI（snapshot/click/fill），专为 Claude Code 设计 |
| `playwright` (Python) | 1.58.0 | Python Playwright 库（Trae 项目用） |

## Chrome 浏览器

- **路径**: `F:\app\Claude\chrome-win64\chrome.exe`
- **版本**: Chrome 145.0.7632.6（dev 版）
- **来源**: 独立下载的 Chrome-win64 便携版，非系统安装

## 配置文件

**位置**: `~/.playwright/cli.config.json`

```json
{
  "browser": {
    "browserName": "chromium",
    "launchOptions": {
      "executablePath": "F:\\app\\Claude\\chrome-win64\\chrome.exe",
      "headless": false
    }
  },
  "outputDir": "./playwright-output"
}
```

## Bash 配置

在 `~/.bashrc` 中添加了：
- `export PATH="/c/Users/ql/AppData/Roaming/npm:$PATH"` — npm 全局 bin
- `alias p='playwright-cli --config "C:/Users/ql/.playwright/cli.config.json"'` — 快捷命令

## 常用命令流

```bash
# 打开浏览器
p open https://example.com

# 查看页面快照（获取元素 ref，如 e3, e15）
p snapshot

# 操作页面
p click e15
p fill e8 "text" --submit
p press Enter

# 截图/导出
p screenshot
p pdf

# 关闭
p close
p close-all
p kill-all
```

## 实操成功命令流（MiMo 表单填写）

```bash
# 1. 打开页面
cd ~ && playwright-cli open https://100t.xiaomimimo.com --headed

# 2. 获取快照看元素
cd ~ && playwright-cli snapshot

# 3. 点击元素（用 ref）
cd ~ && playwright-cli click e42     # 点击「立即申请」
cd ~ && playwright-cli click e181    # 选择 Claude Code
cd ~ && playwright-cli click e204    # 选择 Claude 系列

# 4. 填写文本
cd ~ && playwright-cli fill e175 "邮箱地址"
cd ~ && playwright-cli fill e224 "项目描述..."
cd ~ && playwright-cli fill e239 "https://github.com/链接"

# 5. 截图
cd ~ && playwright-cli screenshot

# 6. 提交（若有验证码则需人工）
cd ~ && playwright-cli click e241    # 点提交（前提: 验证码已过）

# 7. 关闭
cd ~ && playwright-cli close
```

关键心得：
- **必须先 `cd ~`**（配置文件在 home 目录），`--config` 参数不一定所有子命令都认
- `snapshot` 拿到 ref 后操作非常快，不用写选择器
- 滑块验证码过不了，碰到需要人工拖
- `close / close-all / kill-all` 三个不同力度关浏览器
- `--headed` 能看到浏览器窗口，调试用

## 注意点

- `--config` 选项只能在某些子命令前使用，`snapshot` 等不支持。需要在配置文件所在目录执行，或使用 alias
- SKILL.md 自带：`C:\Users\ql\AppData\Roaming\npm\node_modules\@playwright\cli\node_modules\playwright-core\lib\tools\cli-client\skill\SKILL.md`

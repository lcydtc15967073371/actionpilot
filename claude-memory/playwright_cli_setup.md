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

## 注意点

- `--config` 选项只能在某些子命令前使用，`snapshot` 等不支持。需要在配置文件所在目录执行，或使用 alias
- SKILL.md 自带：`C:\Users\ql\AppData\Roaming\npm\node_modules\@playwright\cli\node_modules\playwright-core\lib\tools\cli-client\skill\SKILL.md`

---
name: code-server Windows 安装修复
description: 在 Windows (Node 24 + Python 3.14) 上绕过 argon2 编译问题安装 code-server 的方法
type: reference
originSessionId: f869cfea-0371-43b5-8ef2-43075c624303
---
## code-server Windows 安装（绕过 argon2 编译问题）

### 问题
- Node.js v24 + Python 3.14 + node-gyp 下 `argon2` 原生模块编译失败
- 错误：`gyp: Undefined variable module_name in binding.gyp`
- coder/code-server 官方不提供 Windows 独立二进制

### 解决方案

1. 安装 VS 2022 Build Tools（勾选"使用 C++ 的桌面开发"）
2. 用 npm 安装 code-server（跳过脚本编译）：
   ```powershell
   npm install --global code-server --ignore-scripts
   ```
3. 进入 code-server 目录，安装所有依赖（跳过脚本）：
   ```powershell
   cd $env:APPDATA\npm\node_modules\code-server
   npm install --ignore-scripts --no-audit --no-fund
   ```
4. 修改 `out\node\util.js`：
   - 注释掉 `const argon2 = __importStar(require("argon2"));`
   - 将 `hash` 函数改为使用 `crypto.createHash("sha256")` + salt
   - 将 `isHashMatch` 函数改为使用 crypto 比较 + salt 解析
5. 启动即可绕过 argon2 依赖

### 验证
- `code-server --version` 正常输出版本号
- 端口 8080 监听，页面可访问

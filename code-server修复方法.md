# code-server Windows 安装问题修复记录

## 问题描述

在 Windows 上通过 `npm install --global code-server` 安装时，`argon2` 原生模块编译失败。

**环境**:
- Node.js v24.15.0 LTS
- Python 3.14.3
- VS 2022 Build Tools
- Windows 10

**错误**:
```
gyp: Undefined variable module_name in binding.gyp while trying to load binding.gyp
```

根本原因：`argon2` 模块的 node-gyp 构建与 Node.js v24 + Python 3.14 不兼容，编译时 `module_name` 变量无法正确传递。

## 解决方案

### 思路

绕过 `argon2` 原生模块，用 Node.js 内置的 `crypto` 模块（SHA256 + salt）替代 argon2 的密码哈希功能。code-server 本身已有 SHA256 回退逻辑，只需替换核心函数即可。

### 步骤

#### 1. 安装 VS 2022 Build Tools

下载 `vs_BuildTools.exe`，以管理员身份运行，勾选"使用 C++ 的桌面开发"。

#### 2. 安装 code-server（跳过脚本编译）

```powershell
npm install --global code-server --ignore-scripts
```

#### 3. 安装完整依赖

```powershell
cd $env:APPDATA\npm\node_modules\code-server
npm install --ignore-scripts --no-audit --no-fund
```

#### 4. 替换 argon2 依赖

编辑 `out\node\util.js`：

**(a) 注释掉 argon2 导入**

```javascript
// argon2 disabled for Windows Node.js 24 compatibility - using SHA256 fallback
// const argon2 = __importStar(require("argon2"));
```

**(b) 替换 hash 函数**

原代码（使用 argon2）：
```javascript
const hash = (password) => __awaiter(void 0, void 0, void 0, function* () {
    return yield argon2.hash(password);
});
```

替换为（使用 crypto + 随机 salt）：
```javascript
const hash = (password) => __awaiter(void 0, void 0, void 0, function* () {
    const salt = crypto.randomBytes(16).toString("hex");
    return crypto.createHash("sha256").update(salt + password).digest("hex") + "." + salt;
});
```

**(c) 替换 isHashMatch 函数**

原代码（使用 argon2）：
```javascript
const isHashMatch = (password, hash) => __awaiter(void 0, void 0, void 0, function* () {
    if (password === "" || hash === "" || !hash.startsWith("$")) {
        return false;
    }
    return yield argon2.verify(hash, password);
});
```

替换为（使用 crypto 比较）：
```javascript
const isHashMatch = (password, hash) => __awaiter(void 0, void 0, void 0, function* () {
    if (password === "" || hash === "") {
        return false;
    }
    if (hash.includes(".")) {
        const parts = hash.split(".");
        if (parts.length !== 2) { return false; }
        const [expectedHash, salt] = parts;
        const actualHash = crypto.createHash("sha256").update(salt + password).digest("hex");
        return (0, safe_compare_1.default)(actualHash, expectedHash);
    }
    return (0, safe_compare_1.default)(hash, (0, exports.hashLegacy)(password));
});
```

#### 5. 配置并启动

修改配置文件 `$env:USERPROFILE\AppData\Roaming\code-server\Config\config.yaml`：

```yaml
bind-addr: 0.0.0.0:8080   # 改为 0.0.0.0 以允许局域网/Tailscale 访问
auth: password
password: 你的密码
cert: false
```

启动：
```powershell
code-server
```

### 验证

- 终端输出 `HTTP server listening on http://0.0.0.0:8080/`
- 浏览器访问 `http://localhost:8080` 显示 VS Code 登录页面
- 输入密码正常登录

## 其他方案（未采用）

### 1. 微软 VS Code Server Launcher
- 提供原生 Windows 二进制
- 但 `aka.ms/vscode-server-launcher/x86_64-pc-windows-msvc` 重定向到网页而非直接下载
- 需要接受许可证，下载流程不直接

### 2. WSL 方案
- 在 WSL2 中安装 code-server 可避免原生编译问题
- 但需要启用 WSL，流程更长

### 3. 降级 Node.js
- 使用 Node.js v18 或 v20 可能绕过编译问题
- 但用户已安装 v24，降级不符合常规需求

## 注意事项

- 本方案将 argon2（抗暴力破解哈希）替换为 SHA256 + salt，安全性略有降低但仍适用于个人开发环境
- 如果未来 `argon2` 更新支持 Node 24，可直接恢复原生模块
- 通过 Tailscale 访问时确保 `bind-addr` 设为 `0.0.0.0`
- 开机自启可配置为任务计划程序或启动项脚本

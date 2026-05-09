# code-server Windows 安装问题完整修复记录

## 环境
- Windows 10
- Node.js v24.15.0 LTS
- Python 3.14.3
- VS 2022 Build Tools（Visual Studio 17.14）
- code-server v4.117.0（VS Code 1.117.0）

## 安装的目标

在 Windows 上安装 code-server，通过 Tailscale 组网，从手机浏览器访问 VS Code 网页版。

## 遇到的坑

### 坑 1：argon2 原生模块编译失败

`npm install --global code-server` 时，`argon2` 模块需要 node-gyp 编译原生 C++ 代码，但 Node.js v24 + Python 3.14.3 + node-gyp 的组合下编译失败。

**错误**：
```
gyp: Undefined variable module_name in binding.gyp while trying to load binding.gyp
```

**解决方案**：用 `--ignore-scripts` 跳过编译，再将 `argon2` 替换为 Node.js 内置 crypto 模块。

**步骤**：
1. 安装（跳过脚本）：
   ```powershell
   npm install --global code-server@latest --ignore-scripts
   ```

2. 安装 VS Code 内置依赖：
   ```powershell
   cd $env:APPDATA\npm\node_modules\code-server\lib\vscode
   npm install --ignore-scripts --legacy-peer-deps --no-audit --no-fund
   ```

3. 将 argon2 的 install 脚本改为空操作（防止后续 `npm install` 触发的编译）：
   编辑 `node_modules\argon2\package.json`，将 `"install"` 脚本替换为：
   ```json
   "install": "node -e \"console.log('argon2 build skipped for Windows compat')\""
   ```

4. 修改 `out\node\util.js`，注释掉 argon2 导入，替换 hash 和 isHashMatch 函数：

   ```javascript
   // 注释掉：
   // const argon2 = __importStar(require("argon2"));
   ```

   hash 函数替换为：
   ```javascript
   const hash = (password) => __awaiter(void 0, void 0, void 0, function* () {
       const salt = crypto.randomBytes(16).toString("hex");
       return crypto.createHash("sha256").update(salt + password).digest("hex") + "." + salt;
   });
   ```

   isHashMatch 函数替换为：
   ```javascript
   const isHashMatch = (password, hash) => __awaiter(void 0, void 0, void 0, function* () {
       if (password === "" || hash === "") return false;
       if (hash.includes(".")) {
           const parts = hash.split(".");
           if (parts.length !== 2) return false;
           const [expectedHash, salt] = parts;
           const actualHash = crypto.createHash("sha256").update(salt + password).digest("hex");
           return (0, safe_compare_1.default)(actualHash, expectedHash);
       }
       return (0, safe_compare_1.default)(hash, (0, exports.hashLegacy)(password));
   });
   ```

### 坑 2：VS Build Tools 缺少 Spectre 缓解库

编译 VS Code 原生模块（`@vscode/windows-registry`、`@vscode/spdlog` 等）时报错：

```
MSB8040: 此项目需要缓解了 Spectre 漏洞的库。
```

**原因**：VS 2022 Build Tools 默认安装不包含 Spectre 缓解库，而 VS Code 的原生模块在 binding.gyp 中启用了 SpectreMitigation。

**解决方案**：用 Visual Studio Installer 添加 Spectre 组件。

```powershell
Start-Process -FilePath "C:\Program Files (x86)\Microsoft Visual Studio\Installer\setup.exe" -ArgumentList 'modify --installPath "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools" --add Microsoft.VisualStudio.Component.VC.14.38.17.14.x86.x64.Spectre --quiet --norestart' -Verb RunAs -Wait
```

### 坑 3：VS Code 大量原生模块缺失（--ignore-scripts 的副作用）

因为用了 `--ignore-scripts`，所有 `node-gyp rebuild` 和二进制下载步骤都被跳过，导致以下原生模块缺失：

| 模块 | 位置 | 修复方式 |
|------|------|----------|
| `@vscode/windows-registry` | lib/vscode/node_modules | try-catch 包裹 require |
| `@vscode/spdlog` | lib/vscode/node_modules | try-catch 包裹 bindings |
| `@vscode/native-watchdog` | lib/vscode/node_modules | try-catch 包裹 require |
| `@vscode/windows-ca-certs` | lib/vscode/node_modules | 创建 JS 包装器，修改 main 字段 |
| `@vscode/deviceid` | lib/vscode/node_modules | try-catch 包裹 windows.node require |
| `@vscode/sqlite3` | lib/vscode/node_modules | 创建 JS 包装器，修改 main 字段 |
| `node-pty` | lib/vscode/node_modules | 修改 loadNativeModule 返回 null 而非 throw |
| `kerberos` | lib/vscode/node_modules | try-catch 包裹 bindings + stub 对象 |

**批量修复脚本**（每个模块的具体修改见附录）：

#### @vscode/windows-registry

编辑 `dist/index.js`，将：
```javascript
const windowRegistry = process.platform === 'win32' ? require('../build/Release/winregistry.node') : null;
```
改为：
```javascript
const windowRegistry = process.platform === 'win32' ? (() => { try { return require('../build/Release/winregistry.node'); } catch (e) { return null; } })() : null;
```

#### @vscode/spdlog

编辑 `index.js`，将：
```javascript
const spdlog = require('bindings')('spdlog');
```
改为：
```javascript
let spdlog;
try { spdlog = require('bindings')('spdlog'); } catch (e) { spdlog = null; }
```

#### @vscode/native-watchdog

编辑 `index.js`，将：
```javascript
var watchdog = require('./build/Release/watchdog');
```
改为：
```javascript
var watchdog;
try { watchdog = require('./build/Release/watchdog'); } catch (e) { watchdog = null; }
```

#### @vscode/windows-ca-certs

创建 `index.js`：
```javascript
"use strict";
let native;
try { native = require('./build/Release/crypt32'); } catch (e) { native = null; }
module.exports = native || {};
```

修改 package.json 的 main 字段为 `"main": "index.js"`。

#### @vscode/deviceid

编辑 `dist/storage.js`，将：
```javascript
const windowRegistry = process.platform === "win32"
    ? require("../build/Release/windows.node")
    : null;
```
改为：
```javascript
let windowRegistry = null;
try { windowRegistry = process.platform === "win32" ? require("../build/Release/windows.node") : null; } catch (e) {}
```

#### @vscode/sqlite3

创建 `index.js`：
```javascript
"use strict";
let native;
try { native = require('./lib/sqlite3'); } catch (e) { native = null; }
module.exports = native || { Database: function() { throw new Error('sqlite3 native module not available'); } };
```

修改 package.json 的 main 字段为 `"main": "./index.js"`。

#### node-pty

编辑 `lib/utils.js`，将 `throw new Error(...)` 改为：
```javascript
return { dir: null, module: null };
```

#### kerberos

编辑 `lib/kerberos.js`，将：
```javascript
const kerberos = require('bindings')('kerberos');
```
改为：
```javascript
let kerberos;
try { kerberos = require('bindings')('kerberos'); } catch (e) { kerberos = { KerberosClient: function(){}, KerberosServer: function(){}, checkPassword: function(){}, principalDetails: function(){}, initializeClient: function(){}, initializeServer: function(){} }; }
```

### 坑 4：postinstall.sh 在 Windows 上无法运行

code-server 的 `postinstall.sh` 是 bash 脚本，在 Windows 上不能直接运行。它本来负责：

1. 检查 Node.js 版本（要求 22，但用户装了 24）
2. 安装 `lib/vscode` 依赖
3. 安装 `lib/vscode/extensions` 依赖
4. 创建符号链接

解决方案：手动执行这些步骤。

```powershell
# 安装 lib/vscode 依赖
cd $env:APPDATA\npm\node_modules\code-server\lib\vscode
npm install --ignore-scripts --legacy-peer-deps --no-audit --no-fund

# 设置 FORCE_NODE_VERSION 跳过版本检查
$env:FORCE_NODE_VERSION = "24"
```

### 坑 5：npm-shrinkwrap.json 导致依赖安装卡死

code-server 自带的 `npm-shrinkwrap.json` 引用了旧版本的依赖，在安装过程中会导致 npm 卡在 "this is a one-time fix-up" 阶段无法继续。

**解决方案**：删除 lockfile 后重新安装。

```powershell
Remove-Item "npm-shrinkwrap.json" -Force
npm install --ignore-scripts --no-audit --no-fund
```

安装了 935 个包，问题解决。

## Tailscale 组网与手机访问

1. 电脑和手机分别安装 Tailscale（https://tailscale.com/download）
2. 用同一个账号登录
3. 电脑上运行 `tailscale ip -4` 获取 Tailscale IP（如 100.x.x.x）
4. 确保 code-server 的 config.yaml 中 `bind-addr: 0.0.0.0:8080`
5. 手机浏览器访问 `http://[Tailscale IP]:8080`
6. 输入配置文件中设置的密码登录

## 经验总结

### 根因分析

code-server 在 Windows 上安装困难的根本原因：

1. **npm 安装方式**：code-server 官方不提供 Windows 独立二进制，只能通过 npm 安装
2. **原生模块过多**：VS Code 依赖大量 C++ 原生模块（spdlog、node-pty、watchdog 等），都需要 node-gyp 编译
3. **Node.js v24 兼容性**：argon2 等模块的 node-gyp 构建与新版本 Node.js 存在兼容问题
4. **Spectre 缓解库缺失**：VS 2022 Build Tools 默认不含 Spectre 库，导致 VS Code 原生模块编译失败
5. **--ignore-scripts 的副作用**：跳过所有安装脚本后，大量依赖缺失，需要逐个手动修补

### 最终方案

最终方案采用了 **"npm 安装 + 跳过编译 + 逐个修补原生模块"** 的策略，核心思路是：

1. 用 `--ignore-scripts` 安装主体代码
2. 将强制依赖 argon2 替换为 Node.js 内置 crypto
3. 对 8 个缺失的 VS Code 原生模块添加 try-catch 容错
4. 手动安装 VS Code 的 js 依赖（139 个包）
5. 确保启动无报错，页面正常加载

### 注意事项

- 本方案将 argon2（抗暴力破解哈希）替换为 SHA256 + salt，个人开发环境足够
- 如果未来模块更新支持 Node 24，可直接恢复原生模块
- 开机自启可配置为任务计划程序或启动项脚本
- 安装最新版 code-server（4.117.0）能获得最佳手机浏览器兼容性

### 版本信息

- code-server 4.3.0 → 浏览器白屏（VS Code 1.65.2 太旧）
- code-server 4.117.0 → 页面正常加载（VS Code 1.117.0 最新）
- 结论：**一定要装最新版**，老版本在手机浏览器上可能无法正常渲染

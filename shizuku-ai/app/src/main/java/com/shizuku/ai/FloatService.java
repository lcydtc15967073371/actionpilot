package com.shizuku.ai;

import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class FloatService extends Service {

    private WindowManager wm;
    private View v;
    private WindowManager.LayoutParams p;
    private float sx, sy, ix, iy;
    private boolean drag = false;

    // UI组件
    private EditText input, aiInput;
    private TextView output, aiOutput, tvStatus;
    private ScrollView scroll, aiScroll;

    // AI相关组件
    private View normalPanel, aiPanel;
    private Button btnTokenSetup;

    // 核心对象
    private TokenManager tokenManager;
    private ShizukuShellExecutor shellExecutor;
    private AIAgent aiAgent;
    private AIAgent.AICallback aiCallback;

    // 浏览器相关
    private View browserView;
    private WebView webView;
    private TextView browserUrlText;
    private WindowManager.LayoutParams browserParams;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        // 初始化核心组件
        tokenManager = new TokenManager(this);
        shellExecutor = new ShizukuShellExecutor();
        aiAgent = new AIAgent(tokenManager, shellExecutor);
        aiAgent.clearMemory(); // 每次启动清空上次记忆

        // 初始化adb命令库（传入Context以便持久化AI学习的新命令）
        AdbCommands.init(this);

        create();
    }

    @Override
    public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }

    @Override
    public IBinder onBind(Intent i) { return null; }

    private void create() {
        v = LayoutInflater.from(this).inflate(R.layout.float_layout, null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC222222);
        bg.setCornerRadius(18);
        v.setBackground(bg);

        // ====== 初始化UI组件 ======
        tvStatus = v.findViewById(R.id.tv_status);
        input = v.findViewById(R.id.input_cmd);
        output = v.findViewById(R.id.output_text);
        scroll = v.findViewById(R.id.output_scroll);
        normalPanel = v.findViewById(R.id.normal_panel);
        aiPanel = v.findViewById(R.id.ai_panel);
        aiInput = v.findViewById(R.id.ai_input);
        aiOutput = v.findViewById(R.id.ai_output_text);
        aiScroll = v.findViewById(R.id.ai_output_scroll);
        btnTokenSetup = v.findViewById(R.id.btn_token_setup);

        // 默认显示AI面板
        aiPanel.setVisibility(View.VISIBLE);
        normalPanel.setVisibility(View.GONE);

        // 更新状态
        tvStatus.setText(tokenManager.hasToken() ? "🤖 AI就绪" : "🔑 需设置Token");

        // ====== 事件监听 ======
        // 输出点击复制
        output.setOnClickListener(vv -> copyText(output));
        aiOutput.setOnClickListener(vv -> copyText(aiOutput));

        // 键盘唤起
        View.OnClickListener kb = vv -> showKeyboard((EditText) vv);
        input.setOnClickListener(kb);
        aiInput.setOnClickListener(kb);

        // 回车事件
        input.setOnEditorActionListener((vv, a, e) -> {
            if (a == EditorInfo.IME_ACTION_GO || a == EditorInfo.IME_ACTION_DONE ||
                    (e != null && e.getKeyCode() == 66 && e.getAction() == 0)) { exec(); return true; }
            return false;
        });
        aiInput.setOnEditorActionListener((vv, a, e) -> {
            if (a == EditorInfo.IME_ACTION_GO || a == EditorInfo.IME_ACTION_DONE ||
                    (e != null && e.getKeyCode() == 66 && e.getAction() == 0)) { sendToAI(); return true; }
            return false;
        });

        v.findViewById(R.id.btn_exec).setOnClickListener(vv -> exec());
        v.findViewById(R.id.btn_ai_send).setOnClickListener(vv -> sendToAI());
        v.findViewById(R.id.btn_ai_clear).setOnClickListener(vv -> clearAIChat());
        btnTokenSetup.setOnClickListener(vv -> showTokenDialog());
    v.findViewById(R.id.btn_close).setOnClickListener(vv -> {
        // 彻底关闭：清空AI对话 + 停掉所有线程 + 销毁悬浮窗
        aiAgent.shutdown();
        stopSelf();
    });

    // 点击"清除"后重置浮窗位置到右侧，方便看完整输出
    v.findViewById(R.id.btn_ai_clear).setOnLongClickListener(vv -> {
        int sw = getResources().getDisplayMetrics().widthPixels;
        p.x = sw - p.width - 10;
        p.y = getResources().getDisplayMetrics().heightPixels / 3 + 5;
        wm.updateViewLayout(v, p);
        Toast.makeText(this, "位置已重置", Toast.LENGTH_SHORT).show();
        return true;
    });

        // 拖拽
        v.findViewById(R.id.drag_handle).setOnTouchListener((vv, e) -> {
            switch (e.getAction()) {
                case 0:
                    hideKeyboard(); drag = false;
                    sx = e.getRawX(); sy = e.getRawY(); ix = p.x; iy = p.y;
                    return true;
                case 2:
                    float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) drag = true;
                    p.x = (int) (ix + dx);
                    p.y = Math.max(-200, (int) (iy + dy)); // 最多拖到状态栏以上200px
                    wm.updateViewLayout(v, p);
                    return true;
                case 1: return drag;
            }
            return false;
        });

        // ====== 初始化AI回调 ======
        initAICallback();

        // ====== 自动启用无障碍服务 ======
        if (ShizukuShell.isGranted()) {
            enableAccessibilityService();
        }

        // ====== 窗口布局参数 ======
        p = new WindowManager.LayoutParams();
        p.type = Build.VERSION.SDK_INT >= 26 ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE;
        p.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        p.format = PixelFormat.TRANSLUCENT;
        p.gravity = Gravity.TOP | Gravity.START;

        int sw = getResources().getDisplayMetrics().widthPixels;
        p.width = (int) (sw * 0.55f);
        p.height = WindowManager.LayoutParams.WRAP_CONTENT;
        p.x = sw - p.width - 10;
        p.y = getResources().getDisplayMetrics().heightPixels / 3 + 5;

        wm.addView(v, p);

        // 欢迎信息
        if (tokenManager.hasToken()) {
            appendAIOutput("🤖 说需求，我来搞定！");
        } else {
            appendAIOutput("🔑 点右上角🔑设置Token");
        }
    }

    /** 复制文本到剪贴板 */
    private void copyText(TextView tv) {
        String t = tv.getText().toString();
        if (t.length() > 0) {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("o", t));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        }
    }

    // 无toggleMode，只保留CMD模式为备用

    private void showKeyboard(EditText et) {
        et.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View f = v.findFocus();
        if (f != null) { f.clearFocus(); imm.hideSoftInputFromWindow(f.getWindowToken(), 0); }
    }

    private void exec() {
        String cmd = input.getText().toString().trim();
        if (cmd.length() == 0) return;
        hideKeyboard();
        try {
            if (rikka.shizuku.Shizuku.checkSelfPermission()
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku 未授权", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Exception e) {
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show();
            return;
        }
        new ExecThread(cmd).start();
    }

    private class ExecThread extends Thread {
        String c;
        ExecThread(String c) { this.c = c; }
        @Override
        public void run() {
            try {
                long t = System.currentTimeMillis();
                ShellResult result = ShizukuShell.exec(c);
                long el = System.currentTimeMillis() - t;

                String res = result.output.length() > 0 ? result.output : "(无输出)";
                post(() -> {
                    output.setText(res);
                    output.append("\n\n--- 返回值: " + result.exitCode + " | " + String.format("%.2f秒", el / 1000f) + " ---");
                    scroll.fullScroll(ScrollView.FOCUS_DOWN);
                    input.setText("");
                });
            } catch (Exception e) { post("执行出错: " + e.getMessage()); }
        }
        void post(String s) { new Handler(Looper.getMainLooper()).post(() -> output.setText(s)); }
        void post(Runnable r) { new Handler(Looper.getMainLooper()).post(r); }
    }

    // ====== AI 核心功能 ======

    /** 初始化AI回调（工具调用模式） */
    private void initAICallback() {
        aiCallback = new AIAgent.AICallback() {
            @Override
            public void onResponse(String text) {
                if (text != null && !text.isEmpty()) {
                    String brief = text;
                    if (brief.length() > 300) brief = brief.substring(0, 300) + "...";
                    appendAIOutput("🤖 " + brief);
                }
            }

            @Override
            public void onError(String error) {
                appendAIOutput("❌ " + error);
            }

            @Override
            public void onToolCall(String toolName, String paramsJson) {
                executeTool(toolName, paramsJson);
            }
        };
    }

    /** 工具调度中心：根据工具名分发到对应实现 */
    private void executeTool(String toolName, String paramsJson) {
        appendAIOutput("🛠️ 调用工具: " + toolName);
        try {
            JSONObject params = new JSONObject(paramsJson);
            switch (toolName) {
                case "search_web":
                    searchWeb(params.optString("query", ""));
                    break;
                case "browse_url":
                    showBrowser(params.optString("url", ""));
                    break;
                case "read_page":
                    readCurrentPage();
                    break;
                case "read_screen":
                    readScreenContent();
                    break;
                case "start_app":
                    startAppByPackage(params.optString("package", ""));
                    break;
                case "list_apps":
                    listInstalledApps(params.optString("keyword", ""));
                    break;
                case "toggle_flashlight":
                    toggleFlashlight();
                    break;
                case "set_alarm":
                    setAlarm(params.optInt("hour", 12), params.optInt("minutes", 0), params.optString("message", ""));
                    break;
                case "execute_shell":
                    executeShell(params.optString("command", ""));
                    break;
                case "execute_intent":
                    executeIntent(params);
                    break;
                case "get_device_info":
                    getDeviceInfo();
                    break;
                case "learn":
                    learnExperience(params.optString("key", ""), params.optString("value", ""));
                    break;
                default:
                    appendAIOutput("❌ 未知工具: " + toolName);
            }
        } catch (Exception e) {
            appendAIOutput("❌ 工具执行失败: " + e.getMessage());
            aiAgent.submitToolResult(toolName, "[错误] " + e.getMessage(), aiCallback);
        }
    }

    private void startAppByPackage(String pkg) {
        appendAIOutput("📱 打开: " + pkg);
        ShellResult sr = ShizukuShell.exec("monkey -p " + pkg + " 1");
        aiAgent.submitToolResult("start_app", sr.output, aiCallback);
    }

    private void executeShell(String command) {
        appendAIOutput("📋 执行: " + command);
        new Thread(() -> {
            ShellResult result = ShizukuShell.exec(command);
            String output = result.output.length() > 0 ? result.output : "(无输出)";
            aiAgent.submitToolResult("execute_shell", "退出码:" + result.exitCode + "\n" + output, aiCallback);
        }).start();
    }

    private void executeIntent(JSONObject params) {
        String action = params.optString("action", "");
        if (action.isEmpty()) {
            aiAgent.submitToolResult("execute_intent", "[错误] action 为空", aiCallback);
            return;
        }
        StringBuilder cmd = new StringBuilder("am start -a " + action);
        JSONObject extras = params.optJSONObject("extras");
        if (extras != null) {
            for (Iterator<String> it = extras.keys(); it.hasNext();) {
                String key = it.next();
                Object val = extras.opt(key);
                if (val instanceof Integer) {
                    cmd.append(" --ei ").append(key).append(" ").append(val);
                } else if (val instanceof Boolean) {
                    cmd.append(" --ez ").append(key).append(" ").append(val);
                } else {
                    cmd.append(" -e ").append(key).append(" ").append(val);
                }
            }
        }
        ShellResult sr = ShizukuShell.exec(cmd.toString());
        aiAgent.submitToolResult("execute_intent", sr.output, aiCallback);
    }

    private void setAlarm(int hour, int minutes, String message) {
        String cmd = "am start -a android.intent.action.SET_ALARM"
            + " --ei android.intent.extra.alarm.HOUR " + hour
            + " --ei android.intent.extra.alarm.MINUTES " + minutes;
        if (!message.isEmpty()) {
            cmd += " -e android.intent.extra.alarm.MESSAGE \"" + message + "\"";
        }
        ShizukuShell.exec(cmd);
        aiAgent.submitToolResult("set_alarm", "闹钟已设置: " + hour + ":" + String.format("%02d", minutes) + (message.isEmpty() ? "" : " (" + message + ")"), aiCallback);
    }

    private void getDeviceInfo() {
        new Thread(() -> {
            StringBuilder info = new StringBuilder();
            info.append("型号: ").append(ShizukuShell.exec("getprop ro.product.model")).append("\n");
            info.append("厂商: ").append(ShizukuShell.exec("getprop ro.product.manufacturer")).append("\n");
            info.append("Android: ").append(ShizukuShell.exec("getprop ro.build.version.release")).append("\n");
            info.append("SDK: ").append(ShizukuShell.exec("getprop ro.build.version.sdk")).append("\n");
            aiAgent.submitToolResult("get_device_info", info.toString().trim(), aiCallback);
        }).start();
    }

    private void learnExperience(String key, String value) {
        if (!key.isEmpty() && !value.isEmpty()) {
            appendAIOutput("📚 已记住: " + key);
        }
    }

    /** 发送消息给AI（自动携带相关记忆） */
    private void sendToAI() {
        String text = aiInput.getText().toString().trim();
        if (text.isEmpty()) return;
        if (!tokenManager.hasToken()) {
            Toast.makeText(this, "请先设置API Token！", Toast.LENGTH_LONG).show();
            showTokenDialog();
            return;
        }

        hideKeyboard();
        aiInput.setText("");
        autoCloseBrowser();
        appendAIOutput("👤 " + text);
        appendAIOutput("⏳ 思考中...");

        // 确保adb命令库已初始化
        AdbCommands.init(this);

        // 把文本包装一下，让AI先去查记忆
        aiAgent.sendMessage(text, aiCallback);
    }

    private String lastCommand = "";
    private int sameCmdCount = 0;
    private int listAppsCount = 0; // list_apps 执行次数统计

    /** 执行AI给出的命令 */
    private void executeAICommand(final String command) {
        // 内置功能：list_apps 列出已安装应用
        if (command.startsWith("list_apps")) {
            listAppsCount++;
            if (listAppsCount >= 3) {
                // 第三次执行 list_apps，直接告知AI停止并展示已有结果
                appendAIOutput("⏹️ 应用列表已展示完毕，不再重复扫描");
                aiAgent.submitToolResult("list_apps", "应用列表已经展示过了。请直接把已展示的应用列表呈现给用户，不要再执行list_apps命令了。如果你觉得只有包名不好看，那就直接告诉用户这些就是包名，不需要重新执行。", aiCallback);
                return;
            }
            String keyword = command.length() > 9 ? command.substring(9).trim() : "";
            listInstalledApps(keyword);
            return;
        }

        // 防重复：连续2次相同命令 → 告诉AI去上网查
        if (command.equals(lastCommand)) {
            sameCmdCount++;
            if (sameCmdCount >= 2) {
                appendAIOutput("⏹️ 重复命令已拦截，让AI上网查新方案...");
                // 让AI上网搜索替代方案
                appendAIOutput("🌐 AI上网搜索: " + command + " ...");
                searchWeb("adb " + command + " 替代方案");
                lastCommand = "";
                sameCmdCount = 0;
                return;
            }
        } else {
            lastCommand = command;
            sameCmdCount = 0;
        }
        // 检查Shizuku权限
        try {
            if (rikka.shizuku.Shizuku.checkSelfPermission()
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                appendAIOutput("⚠️ Shizuku 未授权，请授权后重试");
                return;
            }
        } catch (Exception e) {
            appendAIOutput("⚠️ Shizuku 未运行");
            return;
        }

        shellExecutor.execute(command, new ShizukuShellExecutor.ShellCallback() {
            @Override
            public void onResult(String output, int exitCode, long elapsedMs) {
                String result = output.length() > 0 ? output : "(无输出)";
                String timeStr = String.format("%.2f秒", elapsedMs / 1000f);
                appendAIOutput("📤 结果 (退出码:" + exitCode + ", " + timeStr + "):\n" + result);

                // 把结果喂回AI，让它分析
                aiAgent.submitToolResult("execute_shell", output, aiCallback);
            }

            @Override
            public void onError(String error) {
                appendAIOutput("❌ 命令执行失败: " + error);
                // 给AI反馈错误
                aiAgent.submitToolResult("execute_shell", "[ERROR] " + error, aiCallback);
            }
        });
    }

    /** 搜索内置ADB命令库 */
    private void searchCommandLibrary(String keyword) {
        List<AdbCommands.CmdEntry> results = AdbCommands.searchCommands(keyword);
        StringBuilder sb = new StringBuilder();
        if (results.isEmpty()) {
            sb.append("未找到匹配的内置命令，尝试联网搜索？");
        } else {
            sb.append("找到 ").append(results.size()).append(" 条相关命令：\n\n");
            for (AdbCommands.CmdEntry e : results) {
                sb.append("• ").append(e.command).append("\n");
                sb.append("  ").append(e.description).append("\n");
            }
        }
        // 显示搜索结果给用户观察
        appendAIOutput("📖 命令库返回:\n" + sb.toString());
        // 喂给AI继续分析
        aiAgent.submitToolResult("search_web", sb.toString(), aiCallback);
    }

    /** 列出已安装应用（通过 Shizuku shell 获取包名列表 + PackageManager 逐个查中文名） */
    private void listInstalledApps(final String keyword) {
        appendAIOutput("📱 扫描已安装应用...");

        // 第一步：通过 Shizuku shell 执行 pm list packages -3 获取全部第三方包名（关键词过滤在Java侧做）
        final String cmd = "pm list packages -3";
        
        shellExecutor.execute(cmd, new ShizukuShellExecutor.ShellCallback() {
            @Override
            public void onResult(String shellOutput, int exitCode, long elapsedMs) {
                if (shellOutput.isEmpty() || shellOutput.contains("无输出")) {
                    appendAIOutput("📱 未找到第三方应用");
                    aiAgent.submitToolResult("list_apps", "手机上没找到第三方应用。这是最终结果。", aiCallback);
                    return;
                }

                // 解析包名列表
                String[] lines = shellOutput.split("\n");
                final List<String> pkgs = new ArrayList<>();
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("package:")) {
                        pkgs.add(line.substring("package:".length()));
                    }
                }

                if (pkgs.isEmpty()) {
                    appendAIOutput("📱 未找到第三方应用");
                    return;
                }

                // 第二步：用 PackageManager 逐个查中文名（不需要 QUERY_ALL_PACKAGES，精准查特定包名即可）
                new Thread(() -> {
                    try {
                        PackageManager pm = getPackageManager();
                        StringBuilder sb = new StringBuilder();
                        int count = 0;
                        String kw = (keyword != null) ? keyword.toLowerCase() : "";

                        for (String pkg : pkgs) {
                            try {
                                ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                                // 跳过系统应用
                                boolean isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                                if (isSystem && kw.isEmpty()) continue;

                                String label = appInfo.loadLabel(pm).toString();
                                
                                // 关键词过滤
                                if (!kw.isEmpty()) {
                                    if (!label.toLowerCase().contains(kw) && !pkg.toLowerCase().contains(kw)) {
                                        continue;
                                    }
                                }

                                sb.append("• ").append(label).append("  (").append(pkg).append(")\n");
                                count++;
                                if (count >= 30) { sb.append("... 还有更多\n"); break; }
                            } catch (Exception ignored) {
                                // 包可能已卸载，跳过
                            }
                        }

                        String result = "共 " + count + " 个应用:\n" + sb.toString();
                        appendAIOutput("📱 " + result);
                        aiAgent.submitToolResult("list_apps", "这是手机上安装的应用列表（中文名+包名）：\n" + result +
                            "\n\n✅ 这是最终结果，直接展示给用户。不要再执行 list_apps 命令。", aiCallback);
                    } catch (Exception e) {
                        appendAIOutput("❌ 扫描失败: " + e.getMessage());
                        aiAgent.submitToolResult("list_apps", "扫描失败: " + e.getMessage(), aiCallback);
                    }
                }).start();
            }

            @Override
            public void onError(String error) {
                appendAIOutput("❌ 扫描失败: " + error);
                aiAgent.submitToolResult("list_apps", "扫描失败: " + error, aiCallback);
            }
        });
    }

    // ====== 手电筒（CameraManager API，不走 ADB 猜命令） ======

    private boolean torchOn = false;

    private void toggleFlashlight() {
        torchOn = !torchOn;
        final boolean enable = torchOn;
        appendAIOutput(enable ? "🔦 打开手电筒..." : "🔦 关闭手电筒...");

        // 先试 Shizuku shell（OEM 专用路径，如 vivo）
        if (ShizukuShell.isGranted()) {
            new Thread(() -> {
                try {
                    if (enable) {
                        ShizukuShell.exec("settings put system FlashState 1");
                        ShizukuShell.exec("settings put system back_flashlight_state 1");
                    } else {
                        ShizukuShell.exec("settings put system FlashState 0");
                        ShizukuShell.exec("settings put system back_flashlight_state 0");
                    }
                    // 验证
                    ShellResult check = ShizukuShell.exec("settings get system FlashState");
                    if ("1".equals(check.output.trim()) == enable) {
                        appendAIOutput(enable ? "🔦 手电筒已打开" : "🔦 手电筒已关闭");
                        return;
                    }
                } catch (Exception ignored) {}
                // Shizuku 方式失败，试 CameraManager
                fallbackFlashlight(enable);
            }).start();
        } else {
            fallbackFlashlight(enable);
        }
    }

    private void fallbackFlashlight(boolean enable) {
        CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (cm == null) { appendAIOutput("❌ 无法获取相机服务"); return; }
        try {
            String cameraId = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics chars = cm.getCameraCharacteristics(id);
                Boolean hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (hasFlash != null && hasFlash && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id; break;
                }
            }
            if (cameraId == null) { appendAIOutput("❌ 未找到闪光灯"); return; }
            cm.setTorchMode(cameraId, enable);
            appendAIOutput(enable ? "🔦 手电筒已打开" : "🔦 手电筒已关闭");
        } catch (CameraAccessException e) {
            appendAIOutput("❌ 手电筒控制失败: 无权限或相机被占用");
        } catch (Exception e) {
            appendAIOutput("❌ 手电筒控制失败: " + e.getMessage());
        }
    }

    // ====== 搜索引擎（Bing + Baidu 双引擎） ======

    // ====== 搜索（DuckDuckGo HTML + Bing 双引擎） ======

    /** 联网搜索 */
    private void searchWeb(final String keyword) {
        new Thread(() -> {
            appendAIOutput("🌐 搜索: " + keyword + " ...");
            String result = searchDuckDuckGo(keyword);
            if (result == null || result.isEmpty()) {
                result = searchBingV2(keyword);
            }
            if (result == null || result.isEmpty()) {
                result = "未找到相关结果";
            }
            final String finalResult = result;
            appendAIOutput("🌐 搜索结果:\n" + finalResult);
            aiAgent.submitToolResult("search_web", finalResult, aiCallback);
        }).start();
    }

    /** DuckDuckGo HTML 搜索（结构简单稳定） */
    private String searchDuckDuckGo(String keyword) {
        try {
            String query = URLEncoder.encode(keyword, "UTF-8");
            URL url = new URL("https://html.duckduckgo.com/html/?q=" + query);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder html = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (html.length() > 50000) break;
                html.append(line);
            }
            reader.close();

            String raw = html.toString();
            StringBuilder results = new StringBuilder();
            int count = 0;

            // DuckDuckGo 结果在 <a rel="nofollow" class="result__a" href="..."> 中
            int pos = 0;
            while (count < 8 && pos < raw.length()) {
                int aStart = raw.indexOf("class=\"result__a\"", pos);
                if (aStart < 0) break;
                int hrefStart = raw.indexOf("href=\"", aStart);
                hrefStart = hrefStart > 0 ? hrefStart + 6 : -1;
                int hrefEnd = hrefStart > 0 ? raw.indexOf("\"", hrefStart) : -1;
                String link = (hrefStart > 0 && hrefEnd > hrefStart) ? raw.substring(hrefStart, hrefEnd) : "";

                int tagStart = raw.indexOf(">", aStart);
                int tagEnd = tagStart > 0 ? raw.indexOf("</a>", tagStart) : -1;
                String title = (tagStart > 0 && tagEnd > tagStart) ?
                    raw.substring(tagStart + 1, tagEnd).trim() : "";
                if (title.isEmpty()) { pos = tagEnd + 1; continue; }

                // 找对应的摘要：class="result__snippet"
                int sStart = raw.indexOf("class=\"result__snippet\"", tagEnd);
                String snippet = "";
                if (sStart > 0 && sStart < tagEnd + 1000) {
                    int sTagStart = raw.indexOf(">", sStart);
                    int sTagEnd = sTagStart > 0 ? raw.indexOf("</", sTagStart) : -1;
                    snippet = (sTagStart > 0 && sTagEnd > sTagStart) ?
                        raw.substring(sTagStart + 1, sTagEnd).trim() : "";
                }

                count++;
                results.append("[").append(count).append("] ").append(title).append("\n");
                if (link.length() > 0) results.append("    ").append(link).append("\n");
                if (snippet.length() > 0) results.append("    ").append(snippet).append("\n\n");

                pos = tagEnd + 1;
            }

            return results.length() > 0 ? results.toString().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Bing 搜索（备用） */
    private String searchBingV2(String keyword) {
        try {
            String query = URLEncoder.encode(keyword, "UTF-8");
            URL url = new URL("https://www.bing.com/search?q=" + query + "&count=10");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder html = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (html.length() > 30000) break;
                html.append(line);
            }
            reader.close();

            String raw = html.toString();
            StringBuilder results = new StringBuilder();
            int count = 0;
            int pos = 0;

            while (count < 10 && pos < raw.length()) {
                int algoStart = raw.indexOf("class=\"b_algo\"", pos);
                if (algoStart < 0) break;

                // 往回找 <li 的开头
                int liStart = raw.lastIndexOf("<li", algoStart);
                if (liStart < 0 || liStart < pos - 100) { pos = algoStart + 12; continue; }
                int liEnd = raw.indexOf("</li>", algoStart);
                if (liEnd < 0) break;

                String block = raw.substring(liStart, liEnd + 5);

                // 标题 - 找 h2 > a
                int h2Start = block.indexOf("<h2");
                int aStart2 = h2Start > 0 ? block.indexOf("<a ", h2Start) : -1;
                int hrefStart2 = aStart2 > 0 ? block.indexOf("href=\"", aStart2) : -1;
                String link = "";
                if (hrefStart2 > 0) {
                    int hrefEnd2 = block.indexOf("\"", hrefStart2 + 6);
                    link = (hrefEnd2 > hrefStart2) ? block.substring(hrefStart2 + 6, hrefEnd2) : "";
                }
                int aTagStart2 = aStart2 > 0 ? block.indexOf(">", aStart2) : -1;
                int aTagEnd2 = aTagStart2 > 0 ? block.indexOf("</a>", aTagStart2) : -1;
                String title = (aTagStart2 > 0 && aTagEnd2 > aTagStart2) ?
                    block.substring(aTagStart2 + 1, aTagEnd2).replaceAll("<[^>]+>", "").trim() : "";

                if (title.length() > 1) {
                    count++;
                    results.append("[").append(count).append("] ").append(title).append("\n");
                    if (link.length() > 0) results.append("    ").append(link).append("\n");
                    // 摘要
                    int pStart = block.indexOf("<p");
                    int pTagStart = pStart > 0 ? block.indexOf(">", pStart) : -1;
                    int pEnd = pTagStart > 0 ? block.indexOf("</p>", pTagStart) : -1;
                    if (pTagStart > 0 && pEnd > pTagStart) {
                        results.append("    ").append(block.substring(pTagStart + 1, pEnd)
                            .replaceAll("<[^>]+>", "").trim()).append("\n\n");
                    }
                }
                pos = liEnd + 1;
            }

            return results.length() > 0 ? results.toString().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ====== 内置浏览器 ======

    private void showBrowser(String url) {
        if (browserView == null) {
            createBrowserOverlay();
        }
        browserUrlText.setText(url);
        webView.loadUrl(url);
        // 调整位置在主浮窗下方
        browserParams.x = p.x;
        browserParams.y = p.y + v.getHeight() + 5;
        if (browserParams.y < 0) browserParams.y = 0;
        try {
            wm.updateViewLayout(browserView, browserParams);
        } catch (Exception ignored) {}
        if (!browserView.isAttachedToWindow()) {
            try { wm.addView(browserView, browserParams); } catch (Exception ignored) {}
        }
        browserView.setVisibility(View.VISIBLE);
    }

    private void hideBrowser() {
        if (browserView != null) {
            browserView.setVisibility(View.GONE);
            try { wm.removeView(browserView); } catch (Exception ignored) {}
        }
    }

    private void createBrowserOverlay() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE222222);
        bg.setCornerRadius(12);
        layout.setBackground(bg);
        layout.setPadding(4, 4, 4, 4);

        // 标题栏
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 28));

        browserUrlText = new TextView(this);
        browserUrlText.setTextSize(9);
        browserUrlText.setTextColor(0xFF8888FF);
        browserUrlText.setMaxLines(1);
        browserUrlText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        browserUrlText.setPadding(4, 0, 4, 0);
        titleBar.addView(browserUrlText, new LinearLayout.LayoutParams(0, 28, 1));

        Button closeBtn = new Button(this);
        closeBtn.setText("✕");
        closeBtn.setTextSize(10);
        closeBtn.setTextColor(0xFFFF8888);
        closeBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55333333));
        closeBtn.setMinWidth(0);
        closeBtn.setMinHeight(0);
        closeBtn.setPadding(0, 0, 0, 0);
        closeBtn.setOnClickListener(v -> hideBrowser());
        titleBar.addView(closeBtn, 24, 24);
        layout.addView(titleBar);

        // WebView
        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setUserAgentString("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0");
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                browserUrlText.setText(url);
            }
        });
        webView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 350));
        layout.addView(webView);

        browserView = layout;

        // 窗口参数：比主浮窗宽一点
        int sw = getResources().getDisplayMetrics().widthPixels;
        browserParams = new WindowManager.LayoutParams();
        browserParams.type = Build.VERSION.SDK_INT >= 26 ?
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
            WindowManager.LayoutParams.TYPE_PHONE;
        browserParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        browserParams.format = PixelFormat.TRANSLUCENT;
        browserParams.gravity = Gravity.TOP | Gravity.START;
        browserParams.width = (int) (sw * 0.75f);
        browserParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
    }

    /** 被动用后关闭浏览器（如发新消息时自动隐藏） */
    private void autoCloseBrowser() {
        if (browserView != null && browserView.getVisibility() == View.VISIBLE) {
            hideBrowser();
        }
    }

    /** 读屏幕：获取无障碍服务捕获的当前屏幕文字 */
    private void readScreenContent() {
        String text = ShizukuAccessibilityService.lastScreenText;
        String app = ShizukuAccessibilityService.currentAppName;
        if (text.isEmpty()) {
            aiAgent.submitToolResult("read_screen", "（无障碍服务未捕获到屏幕内容，可能未启用）", aiCallback);
            return;
        }
        String result = "当前应用: " + app + "\n屏幕内容: " + text;
        appendAIOutput("📱 " + result.substring(0, Math.min(result.length(), 300)));
        aiAgent.submitToolResult("read_screen", result, aiCallback);
    }

    /** 通过 Shizuku 自动启用无障碍服务 */
    private void enableAccessibilityService() {
        new Thread(() -> {
            try {
                String component = "com.shizuku.ai/com.shizuku.ai.ShizukuAccessibilityService";
                ShizukuShell.exec("settings put secure enabled_accessibility_services \"" + component + "\"");
                ShizukuShell.exec("settings put secure accessibility_enabled 1");
                Thread.sleep(1000);
                ShellResult check2 = ShizukuShell.exec("settings get secure enabled_accessibility_services");
                if (check2.output.contains("com.shizuku.ai")) {
                    appendAIOutput("✅ 无障碍服务已启用");
                } else {
                    appendAIOutput("⚠️ 无障碍启用失败，请在设置中手动开启");
                }
            } catch (Exception e) {
                appendAIOutput("⚠️ 无障碍启用失败: " + e.getMessage());
            }
        }).start();
    }

    private void readCurrentPage() {
        if (webView == null) return;
        webView.evaluateJavascript(
            "(function() { return document.body.innerText.substring(0, 8000); })();",
            value -> {
                String text = value != null ? value.trim() : "";
                // 去掉 JS 返回的引号包裹
                if (text.startsWith("\"") && text.endsWith("\"")) {
                    text = text.substring(1, text.length() - 1);
                }
                text = text.replace("\\n", "\n").replace("\\t", "\t");
                if (text.isEmpty()) text = "（页面无文字内容）";
                final String result = text;
                appendAIOutput("📖 页面内容:\n" + result.substring(0, Math.min(result.length(), 500)));
                aiAgent.submitToolResult("read_page", "当前浏览器页面的文字内容：\n" + result, aiCallback);
            }
        );
    }

    /** 清空AI聊天 */
    private void clearAIChat() {
        aiAgent.clearMemory();
        aiOutput.setText("");
        appendAIOutput("🧹 记忆已清空，开启全新对话！");
    }

    /** 追加文本到AI输出 */
    private void appendAIOutput(final String text) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (aiOutput.getText().length() > 0 && !aiOutput.getText().toString().endsWith("\n")) {
                aiOutput.append("\n\n");
            }
            aiOutput.append("━━━━━━━━━━━━━━━━\n");
            aiOutput.append(text);
            // 自动滚动到底部
            aiScroll.fullScroll(ScrollView.FOCUS_DOWN);
        });
    }

    /** 显示Token设置对话框 */
    private void showTokenDialog() {
        // 使用悬浮窗中的编辑框来设置
        final View dialog = LayoutInflater.from(this).inflate(R.layout.token_dialog, null);
        final EditText tokenInput = dialog.findViewById(R.id.dialog_token_input);
        final EditText endpointInput = dialog.findViewById(R.id.dialog_endpoint_input);
        final EditText modelInput = dialog.findViewById(R.id.dialog_model_input);

        tokenInput.setText(tokenManager.getToken());
        endpointInput.setText(tokenManager.getEndpoint());
        modelInput.setText(tokenManager.getModel());

        // 创建独立的悬浮窗对话框
        WindowManager.LayoutParams dp = new WindowManager.LayoutParams();
        dp.type = Build.VERSION.SDK_INT >= 26 ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE;
        dp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        dp.format = PixelFormat.TRANSLUCENT;
        dp.gravity = Gravity.CENTER;
        dp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.8f);
        dp.height = WindowManager.LayoutParams.WRAP_CONTENT;

        GradientDrawable dg = new GradientDrawable();
        dg.setColor(0xEE333333);
        dg.setCornerRadius(16);
        dialog.setBackground(dg);

        dialog.findViewById(R.id.btn_token_save).setOnClickListener(vv -> {
            String token = tokenInput.getText().toString().trim();
            String endpoint = endpointInput.getText().toString().trim();
            String model = modelInput.getText().toString().trim();

            if (token.isEmpty()) {
                Toast.makeText(this, "Token不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            tokenManager.saveToken(token);
            if (!endpoint.isEmpty()) tokenManager.saveEndpoint(endpoint);
            if (!model.isEmpty()) tokenManager.saveModel(model);

            // 初始化adb命令库
            AdbCommands.init(FloatService.this);

            Toast.makeText(this, "Token已保存！", Toast.LENGTH_SHORT).show();
            appendAIOutput("✅ API配置已保存！AI助手已就绪。");
            try { wm.removeView(dialog); } catch (Exception ignored) {}
        });

        dialog.findViewById(R.id.btn_token_cancel).setOnClickListener(vv -> {
            try { wm.removeView(dialog); } catch (Exception ignored) {}
        });

        dialog.findViewById(R.id.btn_token_clear).setOnClickListener(vv -> {
            tokenManager.clearToken();
            Toast.makeText(this, "Token已清除", Toast.LENGTH_SHORT).show();
            try { wm.removeView(dialog); } catch (Exception ignored) {}
            appendAIOutput("🔑 Token已清除，请重新设置");
        });

        wm.addView(dialog, dp);
    }

    // 命令收藏功能已移除，保留CMD模式作为备用直接执行

    @Override
    public void onDestroy() {
        if (v != null && wm != null) { try { wm.removeView(v); } catch (Exception ignored) {} }
        super.onDestroy();
    }
}

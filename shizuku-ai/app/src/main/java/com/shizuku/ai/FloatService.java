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
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
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

    /** 初始化AI回调 */
    private void initAICallback() {
        aiCallback = new AIAgent.AICallback() {
            @Override
            public void onResponse(String text, String command, boolean hasCommand) {
                if (text != null && !text.isEmpty()) {
                    // 精简AI废话：取第一段有效内容，太长就截断
                    String brief = text;
                    int cut = brief.indexOf("\n\n");
                    if (cut > 0) brief = brief.substring(0, cut);
                    if (brief.length() > 120) brief = brief.substring(0, 120) + "...";
                    appendAIOutput("🤖 " + brief);
                }
                if (hasCommand && command != null) {
                    appendAIOutput("📋 执行: " + command);
                    executeAICommand(command);
                }
            }

            @Override
            public void onError(String error) {
                appendAIOutput("❌ " + error);
            }

            @Override
            public void onSearchRequest(String keyword, boolean isWebSearch) {
                if (isWebSearch) {
                    appendAIOutput("🌐 联网搜索: " + keyword + " ...");
                    searchWeb(keyword);
                } else {
                    appendAIOutput("📖 查询命令库: " + keyword + " ...");
                    searchCommandLibrary(keyword);
                }
            }
        };
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
                aiAgent.submitSearchResult("应用列表已经展示过了。请直接把已展示的应用列表呈现给用户，不要再执行list_apps命令了。如果你觉得只有包名不好看，那就直接告诉用户这些就是包名，不需要重新执行。", aiCallback);
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
                aiAgent.submitCommandResult(command, output, aiCallback);
            }

            @Override
            public void onError(String error) {
                appendAIOutput("❌ 命令执行失败: " + error);
                // 给AI反馈错误
                aiAgent.submitCommandResult(command, "[ERROR] " + error, aiCallback);
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
        aiAgent.submitSearchResult(sb.toString(), aiCallback);
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
                    aiAgent.submitSearchResult("手机上没找到第三方应用。这是最终结果。", aiCallback);
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
                        aiAgent.submitSearchResult("这是手机上安装的应用列表（中文名+包名）：\n" + result +
                            "\n\n✅ 这是最终结果，直接展示给用户。不要再执行 list_apps 命令。", aiCallback);
                    } catch (Exception e) {
                        appendAIOutput("❌ 扫描失败: " + e.getMessage());
                        aiAgent.submitSearchResult("扫描失败: " + e.getMessage(), aiCallback);
                    }
                }).start();
            }

            @Override
            public void onError(String error) {
                appendAIOutput("❌ 扫描失败: " + error);
                aiAgent.submitSearchResult("扫描失败: " + error, aiCallback);
            }
        });
    }

    /** 联网搜索（通过HTTP请求搜外网） */
    private void searchWeb(final String keyword) {
        new Thread(() -> {
            try {
                // 使用 DuckDuckGo lite (不需要API key，响应是文本)
                String query = URLEncoder.encode(keyword, "UTF-8");
                URL url = new URL("https://lite.duckduckgo.com/lite/?q=" + query);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder html = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (html.length() > 5000) break;
                    html.append(line);
                }
                reader.close();

                // 提取搜索结果：标题+链接+摘要
                String rawHtml = html.toString();
                StringBuilder results = new StringBuilder();

                // 先提取所有 result-link 和对应的 href / snippet
                int idx = 0;
                int count = 0;
                while (count < 8 && idx < rawHtml.length()) {
                    int linkIdx = rawHtml.indexOf("class=\"result-link\"", idx);
                    int snippetIdx = rawHtml.indexOf("class=\"result-snippet\"", idx);
                    if (linkIdx < 0 && snippetIdx < 0) break;

                    // 先找链接文本
                    if (linkIdx >= 0 && (snippetIdx < 0 || linkIdx < snippetIdx)) {
                        // 提取超链接href
                        int hrefStart = rawHtml.lastIndexOf("<a ", linkIdx);
                        int hrefEnd = rawHtml.indexOf(">", hrefStart);
                        String hrefAttr = "";
                        if (hrefStart >= 0 && hrefEnd > hrefStart) {
                            int h = rawHtml.indexOf("href=\"", hrefStart);
                            if (h >= 0 && h < hrefEnd) {
                                int hEnd = rawHtml.indexOf("\"", h + 6);
                                hrefAttr = rawHtml.substring(h + 6, hEnd);
                            }
                        }
                        int aTag = rawHtml.indexOf(">", linkIdx);
                        int closeTag = rawHtml.indexOf("</a>", aTag);
                        if (aTag >= 0 && closeTag > aTag) {
                            String text = rawHtml.substring(aTag + 1, closeTag)
                                .replaceAll("<[^>]+>", "").trim();
                            if (text.length() > 2) {
                                results.append("[").append(count + 1).append("] ").append(text);
                                if (!hrefAttr.isEmpty()) results.append(" (").append(hrefAttr).append(")");
                                results.append("\n");
                            }
                        }
                        idx = closeTag + 1;
                        count++;
                    } else if (snippetIdx >= 0) {
                        // 提取摘要文本
                        int aTag = rawHtml.indexOf(">", snippetIdx);
                        int closeTag = rawHtml.indexOf("</td>", aTag);
                        if (aTag >= 0 && closeTag > aTag) {
                            String text = rawHtml.substring(aTag + 1, closeTag)
                                .replaceAll("<[^>]+>", "").trim();
                            if (text.length() > 2) {
                                results.append("   ").append(text).append("\n\n");
                            }
                        }
                        idx = closeTag + 1;
                    } else {
                        break;
                    }
                }

                String resultText = results.toString().trim();
                if (resultText.isEmpty()) resultText = "未找到相关结果";

                appendAIOutput("🌐 联网返回:\n" + resultText);
                aiAgent.submitSearchResult("联网搜索结果：\n" + resultText, aiCallback);

            } catch (Exception e) {
                aiAgent.submitSearchResult("联网搜索失败: " + e.getMessage(), aiCallback);
            }
        }).start();
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

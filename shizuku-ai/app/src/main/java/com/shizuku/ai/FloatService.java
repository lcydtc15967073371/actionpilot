package com.shizuku.ai;

import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.annotation.SuppressLint;
import android.util.Log;
import android.graphics.Color;
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

    private static final String TAG = "ShizukuAI";    private WindowManager wm;
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

    // 悬浮球相关
    private View ballView;
    private boolean isBallMode = true;
    private float ballSx, ballSy, ballIx, ballIy;
    private boolean ballDragging = false;
    private static final int BALL_SIZE_DP = 72;

    // 浏览器相关
    private View browserView;
    private WebView webView;
    private TextView browserUrlText;
    private WindowManager.LayoutParams browserParams;

    // 窗口高度限制
    private int maxWindowHeight;

    // UI 操作录制
    private UiMapRecorder uiMapRecorder;
    private Handler recorderHandler = new Handler(Looper.getMainLooper());
    private Runnable dumpsysPoller;
    private boolean dumpsysPolling = false;
    private String lastDumpsysFocus = "";

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
        v.findViewById(R.id.btn_minimize_ball).setOnClickListener(vv -> minimizeToBall());
    v.findViewById(R.id.btn_close).setOnClickListener(vv -> {
        // 彻底关闭：清空焦点 + 清空AI对话 + 停掉所有线程 + 销毁悬浮窗
        cancelFocusBeforeExit();
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

        // ====== 窗口布局参数（照抄 Operit FloatingWindowManager） ======
        p = new WindowManager.LayoutParams();
        p.type = Build.VERSION.SDK_INT >= 26 ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE;
        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        p.format = PixelFormat.TRANSLUCENT;
        p.gravity = Gravity.TOP | Gravity.START;

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        maxWindowHeight = (int) (sh * 0.6f);
        p.width = (int) (sw * 0.55f);
        p.height = WindowManager.LayoutParams.WRAP_CONTENT;
        p.x = sw - p.width - 10;
        p.y = sh / 3 + 5;

        // Operit: 先加全屏遮罩（下层），再加主浮窗（上层），确保遮罩在主浮窗之下
        ensureFocusDismissView();

        wm.addView(v, p);

        // 先显示悬浮球模式（隐藏大窗口）
        isBallMode = true;
        v.setVisibility(View.GONE);
        showBallOverlay();

        // 欢迎信息
        if (tokenManager.hasToken()) {
            appendAIOutput("🤖 说需求，我来搞定！");
        } else {
            appendAIOutput("🔑 点右上角🔑设置Token");
        }
    }

    /** 创建悬浮球覆盖层（SiriBall） */
    @SuppressLint("ClickableViewAccessibility")
    private void showBallOverlay() {
        if (ballView != null) return;

        // 球容器
        LinearLayout container = new LinearLayout(this);
        container.setGravity(android.view.Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.TRANSPARENT);
        container.setBackground(bg);

        SiriBallView siriBall = new SiriBallView(this);
        int density = (int) getResources().getDisplayMetrics().density;
        int ballSize = BALL_SIZE_DP * density;
        container.addView(siriBall, ballSize, ballSize);

        // 拖拽 + 点击处理
        container.setOnTouchListener((vv, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    ballDragging = false;
                    ballSx = event.getRawX();
                    ballSy = event.getRawY();
                    ballIx = ballParams.x;
                    ballIy = ballParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - ballSx;
                    float dy = event.getRawY() - ballSy;
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        ballDragging = true;
                    }
                    ballParams.x = (int) (ballIx + dx);
                    ballParams.y = Math.max(-200, (int) (ballIy + dy));
                    try { wm.updateViewLayout(ballView, ballParams); } catch (Exception ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!ballDragging) {
                        // 点击：展开为完整浮窗
                        hideBallOverlay();
                        showFloatWindow();
                    }
                    return ballDragging;
            }
            return false;
        });

        ballView = container;

        // 窗口参数
        int sw = getResources().getDisplayMetrics().widthPixels;
        int ballPx = BALL_SIZE_DP * density;
        ballParams = new WindowManager.LayoutParams();
        ballParams.type = Build.VERSION.SDK_INT >= 26 ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE;
        ballParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        ballParams.format = PixelFormat.TRANSLUCENT;
        ballParams.gravity = Gravity.TOP | Gravity.START;
        ballParams.width = ballPx;
        ballParams.height = ballPx;
        ballParams.x = sw / 2 - ballPx / 2;
        ballParams.y = getResources().getDisplayMetrics().heightPixels / 2 - ballPx / 2;

        try {
            wm.addView(ballView, ballParams);
        } catch (Exception e) {
            ballView = null;
        }

        // 启动 UI 录制
        startUiRecording();
    }

    private WindowManager.LayoutParams ballParams;

    private void hideBallOverlay() {
        if (ballView != null) {
            try { wm.removeView(ballView); } catch (Exception ignored) {}
            ballView = null;
        }
    }

    private void showFloatWindow() {
        if (v != null) {
            v.setVisibility(View.VISIBLE);
        }
    }

    private void minimizeToBall() {
        if (v != null) {
            v.setVisibility(View.GONE);
        }
        hideKeyboard();
        showBallOverlay();
    }

    // ====== UI 录制 ======

    private void startUiRecording() {
        if (uiMapRecorder == null) {
            uiMapRecorder = new UiMapRecorder(this);
        }
        // 注册到无障碍服务
        ShizukuAccessibilityService.uiMapRecorder = uiMapRecorder;
        uiMapRecorder.start();
        startDumpsysPolling();
    }

    private void stopUiRecording() {
        stopDumpsysPolling();
        ShizukuAccessibilityService.uiMapRecorder = null;
        if (uiMapRecorder != null) {
            uiMapRecorder.stop();
        }
    }

    /** Shizuku dumpsys 轮询作为窗口切换的 fallback */
    private void startDumpsysPolling() {
        if (dumpsysPolling) return;
        dumpsysPolling = true;
        dumpsysPoller = new Runnable() {
            @Override
            public void run() {
                if (!dumpsysPolling) return;
                try {
                    ShellResult sr = ShizukuShell.exec("dumpsys window | grep mCurrentFocus");
                    String focus = parseDumpsysFocus(sr.output);
                    if (!focus.isEmpty() && !focus.equals(lastDumpsysFocus)) {
                        lastDumpsysFocus = focus;
                        String[] parts = focus.split("/");
                        if (parts.length >= 2) {
                            String pkg = parts[0];
                            int dotIdx = parts[1].lastIndexOf('.');
                            String activity = dotIdx >= 0 ? parts[1].substring(dotIdx + 1) : parts[1];
                            // 如果无障碍服务还没上报，手动触发录制
                            if (!pkg.equals(ShizukuAccessibilityService.currentPackage)) {
                                String appName = resolveAppName(pkg);
                                ShizukuAccessibilityService.currentPackage = pkg;
                                ShizukuAccessibilityService.currentAppName = appName;
                                uiMapRecorder.onWindowChanged(pkg, appName, activity);
                            }
                        }
                    }
                } catch (Exception ignored) {}
                recorderHandler.postDelayed(this, 1500);
            }
        };
        recorderHandler.postDelayed(dumpsysPoller, 1000);
    }

    private void stopDumpsysPolling() {
        dumpsysPolling = false;
        if (dumpsysPoller != null) {
            recorderHandler.removeCallbacks(dumpsysPoller);
        }
    }

    private String parseDumpsysFocus(String output) {
        String[] lines = output.split("\n");
        for (String line : lines) {
            if (line.contains("mCurrentFocus=") && !line.contains("null")) {
                int braceOpen = line.indexOf('{');
                int braceClose = line.lastIndexOf('}');
                if (braceOpen < 0 || braceClose <= braceOpen) continue;
                String inside = line.substring(braceOpen + 1, braceClose);
                String[] parts = inside.split(" ");
                int u0Idx = -1;
                for (int i = 0; i < parts.length; i++) {
                    if ("u0".equals(parts[i])) { u0Idx = i; break; }
                }
                if (u0Idx < 0 || u0Idx + 1 >= parts.length) continue;
                StringBuilder pkgAct = new StringBuilder();
                for (int i = u0Idx + 1; i < parts.length; i++) {
                    if (parts[i].startsWith("type=")) break;
                    if (pkgAct.length() > 0) pkgAct.append(" ");
                    pkgAct.append(parts[i]);
                }
                return pkgAct.toString();
            }
        }
        return "";
    }

    private String resolveAppName(String pkg) {
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return getPackageManager().getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
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

    // ====== 照抄 Operit 的焦点管理方案 ======
    // 参考: https://github.com/AAswordman/Operit 的 FloatingWindowManager.kt

    private Handler focusHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingImeFocusRunnable = null;
    private View focusDismissView = null;

    private static final int IME_FOCUS_DELAY_MS = 200;
    private static final int IME_FOCUS_RETRY_DELAY_MS = 50;
    private static final int MAX_IME_FOCUS_RETRIES = 4;

    /** 创建全屏透明 View，放在浮窗下层，用于捕获外部点击释放焦点 */
    private void ensureFocusDismissView() {
        if (focusDismissView != null) return;

        focusDismissView = new View(this);
        focusDismissView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        focusDismissView.setVisibility(View.GONE);
        focusDismissView.setClickable(true);
        focusDismissView.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hideKeyboard();
            }
            return true;
        });

        WindowManager.LayoutParams dp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            Build.VERSION.SDK_INT >= 26 ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        dp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(focusDismissView, dp);
        } catch (Exception e) {
            focusDismissView = null;
        }
    }

    private void setFocusDismissOverlayEnabled(boolean enabled) {
        if (focusDismissView != null) {
            focusDismissView.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
    }

    /** Operit 方案：显示键盘时去掉 NOT_FOCUSABLE + 加上 NOT_TOUCH_MODAL */
    private void showKeyboard(EditText et) {
        // Step 1: 窗口参数改为可聚焦
        p.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                 | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        try { wm.updateViewLayout(v, p); } catch (Exception ignored) {}

        // Step 2: 启用全屏透明遮罩，捕获外部点击
        ensureFocusDismissView();
        setFocusDismissOverlayEnabled(true);

        // Step 3: 取消旧任务，调度新 IME 显示（带重试）
        scheduleImeShow(et, 0, IME_FOCUS_DELAY_MS);
    }

    /** Operit 方案：带重试的 IME 显示调度 */
    private void scheduleImeShow(EditText et, int retryCount, long delayMillis) {
        if (pendingImeFocusRunnable != null) {
            focusHandler.removeCallbacks(pendingImeFocusRunnable);
        }

        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (pendingImeFocusRunnable != this) return;

                if (!v.isAttachedToWindow() || v.getWindowToken() == null) {
                    if (retryCount >= MAX_IME_FOCUS_RETRIES) {
                        pendingImeFocusRunnable = null;
                        return;
                    }
                    scheduleImeShow(et, retryCount + 1, IME_FOCUS_RETRY_DELAY_MS);
                    return;
                }

                et.requestFocus();

                // 验证焦点宿主是否就绪（是文本编辑器）
                View focused = v.findFocus();
                if (focused == null || !focused.isAttachedToWindow()
                        || focused.getWindowToken() == null) {
                    if (retryCount >= MAX_IME_FOCUS_RETRIES) {
                        pendingImeFocusRunnable = null;
                        return;
                    }
                    scheduleImeShow(et, retryCount + 1, IME_FOCUS_RETRY_DELAY_MS);
                    return;
                }

                pendingImeFocusRunnable = null;
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                imm.showSoftInput(focused, InputMethodManager.SHOW_IMPLICIT);
            }
        };

        pendingImeFocusRunnable = r;
        focusHandler.postDelayed(r, delayMillis);
    }

    /** Operit 方案：隐藏键盘时先清焦点 + 再加 NOT_FOCUSABLE + 去掉 NOT_TOUCH_MODAL */
    private void hideKeyboard() {
        // 取消任何待执行的 IME 焦点请求
        if (pendingImeFocusRunnable != null) {
            focusHandler.removeCallbacks(pendingImeFocusRunnable);
            pendingImeFocusRunnable = null;
        }

        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);

        // Step 1: 先清子焦点，再清根焦点（Operit 顺序）
        try {
            View focused = v.findFocus();
            if (focused != null) focused.clearFocus();
        } catch (Exception ignored) {}
        try { v.clearFocus(); } catch (Exception ignored) {}
        imm.hideSoftInputFromWindow(v.getWindowToken(), 0);

        // Step 2: 恢复 NOT_FOCUSABLE + 去掉 NOT_TOUCH_MODAL
        p.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        p.flags &= ~(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        try { wm.updateViewLayout(v, p); } catch (Exception ignored) {}

        // Step 3: 关闭全屏遮罩
        setFocusDismissOverlayEnabled(false);
    }

    /** Operit 方案：退出前清理焦点 */
    private void cancelFocusBeforeExit() {
        if (pendingImeFocusRunnable != null) {
            focusHandler.removeCallbacks(pendingImeFocusRunnable);
            pendingImeFocusRunnable = null;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        try { v.clearFocus(); } catch (Exception ignored) {}
        try { imm.hideSoftInputFromWindow(v.getWindowToken(), 0); } catch (Exception ignored) {}
        p.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try { wm.updateViewLayout(v, p); } catch (Exception ignored) {}
        setFocusDismissOverlayEnabled(false);
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
                case "create_note":
                    createNote(params.optString("content", ""), params.optString("title", ""));
                    break;
                case "read_uimap":
                    readUiMap();
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
            + " --ei android.intent.extra.alarm.MINUTES " + minutes
            + " --ez android.intent.extra.alarm.SKIP_UI true";
        if (!message.isEmpty()) {
            cmd += " -e android.intent.extra.alarm.MESSAGE \"" + message + "\"";
        }
        ShizukuShell.exec(cmd);
        aiAgent.submitToolResult("set_alarm", "闹钟已设置: " + hour + ":" + String.format("%02d", minutes) + (message.isEmpty() ? "" : " (" + message + ")"), aiCallback);
    }

    private void readUiMap() {
        if (uiMapRecorder == null || uiMapRecorder.getTotalActions() == 0) {
            aiAgent.submitToolResult("read_uimap", "（当前没有录制到任何操作数据，请先使用手机后再查询）", aiCallback);
            return;
        }
        String exported = uiMapRecorder.exportForAI();
        String summary = "UI 操作地图已生成，共 " + uiMapRecorder.getTotalActions() + " 条操作记录。\n" + exported;
        appendAIOutput("🗺️ " + summary.substring(0, Math.min(summary.length(), 500)));
        aiAgent.submitToolResult("read_uimap", summary, aiCallback);
    }

    private void createNote(String content, String title) {
        if (content.isEmpty()) {
            aiAgent.submitToolResult("create_note", "[错误] 内容不能为空", aiCallback);
            return;
        }
        // 先尝试带标题的 SEND 方式
        String cmd = "am start -a android.intent.action.SEND -t text/plain"
            + " --es android.intent.extra.TEXT \"" + content.replace("\"", "\\\"") + "\""
            + " -p com.android.notes";
        if (!title.isEmpty()) {
            cmd += " --es android.intent.extra.SUBJECT \"" + title.replace("\"", "\\\"") + "\"";
        }
        ShellResult sr = ShizukuShell.exec(cmd);
        String msg = "📝 已打开原子笔记编辑界面，内容已预填";
        if (!title.isEmpty()) msg += "（标题：" + title + "）";
        appendAIOutput(msg);
        aiAgent.submitToolResult("create_note", msg + "。请提醒用户手动保存。执行结果: " + sr.output, aiCallback);
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

        // 第一步：通过 Shizuku shell 获取全部包名（关键词过滤在Java侧做）
        final String cmd = "pm list packages";
        
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
                                boolean isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

                                String label = appInfo.loadLabel(pm).toString();
                                
                                // 关键词过滤
                                if (!kw.isEmpty()) {
                                    if (!label.toLowerCase().contains(kw) && !pkg.toLowerCase().contains(kw)) {
                                        continue;
                                    }
                                }

                                sb.append("• ").append(label).append("  (").append(pkg).append(")\n");
                                count++;
                                if (count >= 60) { sb.append("... 还有更多\n"); break; }
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

    /** 联网搜索（使用 OkHttp，三引擎） */
    private void searchWeb(final String keyword) {
        new Thread(() -> {
            appendAIOutput("🌐 搜索: " + keyword + " ...");
            String result = SearchEngine.search(keyword);
            if (result.isEmpty()) result = "未找到相关结果";
            final String finalResult = result;
            appendAIOutput("🌐 搜索结果:\n" + finalResult);
            aiAgent.submitToolResult("search_web", finalResult, aiCallback);
        }).start();
    }

    // ====== 内置浏览器 ======

    private void showBrowser(String url) {
        if (browserView == null) {
            createBrowserOverlay();
        }
        browserUrlText.setText(url);
        // 加载完成后自动读取内容返回给 AI
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                browserUrlText.setText(url);
                // 页面加载完自动读内容
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    readCurrentPage();
                }, 1500);
            }
        });
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
            ViewGroup.LayoutParams.MATCH_PARENT, 250));
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

    /** 读屏幕：获取无障碍服务捕获的当前屏幕文字 + 节点结构信息 */
    private void readScreenContent() {
        try {
            // 触发深度捕获
            if (ShizukuAccessibilityService.isRunning) {
                ShizukuAccessibilityService.requestDetailedScreenCapture();
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }

            String app = ShizukuAccessibilityService.currentAppName;
            String pkg = ShizukuAccessibilityService.currentPackage;
            String screenText = ShizukuAccessibilityService.lastScreenText;
            String structure = ShizukuAccessibilityService.lastScreenStructure;

            StringBuilder result = new StringBuilder();
            if (app != null && !app.isEmpty()) {
                result.append("当前应用: ").append(app);
                if (pkg != null && !pkg.isEmpty()) result.append(" (").append(pkg).append(")");
            }

            if (screenText != null && !screenText.isEmpty()) {
                result.append("\n\n屏幕文字:\n").append(screenText);
            }

            if (structure != null && !structure.isEmpty()) {
                result.append("\n\n可见控件:\n").append(structure);
            }

            // 桌面启动器：无障碍节点不可读，走 Shizuku 获取应用列表
            boolean hasContent = (screenText != null && !screenText.isEmpty())
                || (structure != null && !structure.isEmpty());
            if (!hasContent) {
                boolean isLauncher = pkg != null && isLauncherPackage(pkg);
                if (isLauncher) {
                    result.append("\n（桌面启动器图标不可读，已自动获取应用列表）");
                    // 直接从 Shizuku 拉应用列表，不依赖无障碍
                    result.append("\n\n已安装应用:\n").append(fetchAppListFromShell());
                    result.append("\n\n提示：可以用 start_app 打开应用。");
                } else {
                    result.append("\n\n屏幕不含文字或交互控件。");
                }
            }

            String resultStr = result.toString();
            appendAIOutput("📱 " + (app != null ? app : "未知"));
            aiAgent.submitToolResult("read_screen", resultStr, aiCallback);
        } catch (Exception e) {
            Log.e(TAG, "readScreenContent 异常: " + e.getMessage());
            appendAIOutput("📱 读取失败");
            aiAgent.submitToolResult("read_screen", "[错误] " + e.getMessage(), aiCallback);
        }
    }

    /** 直接从 Shell 获取应用列表（桌面兜底用） */
    private String fetchAppListFromShell() {
        StringBuilder sb = new StringBuilder();
        try {
            ShellResult sr = ShizukuShell.exec("pm list packages -3");
            if (sr.output != null && !sr.output.isEmpty()) {
                String[] lines = sr.output.split("\n");
                int count = 0;
                for (String line : lines) {
                    String pkgName = line.trim().replace("package:", "");
                    if (!pkgName.isEmpty()) {
                        String appName = resolveAppName(pkgName);
                        sb.append("  • ").append(appName).append(" (").append(pkgName).append(")\n");
                        if (++count >= 30) {
                            int remaining = lines.length - count;
                            if (remaining > 0) sb.append("  ... 还有 ").append(remaining).append(" 个应用");
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "fetchAppListFromShell 失败: " + e.getMessage());
        }
        // 如果第三方列表失败，回退试试更宽的查询
        if (sb.length() == 0) {
            try {
                ShellResult sr = ShizukuShell.exec("pm list packages");
                if (sr.output != null && !sr.output.isEmpty()) {
                    String[] lines = sr.output.split("\n");
                    int count = 0;
                    for (String line : lines) {
                        String pkgName = line.trim().replace("package:", "");
                        if (!pkgName.isEmpty() && !pkgName.equals("android")) {
                            sb.append("  • ").append(pkgName).append("\n");
                            if (++count >= 30) { sb.append("  ...\n"); break; }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return sb.length() > 0 ? sb.toString() : "（无法获取应用列表）";
    }

    /** 判断是否为桌面启动器（节点树不可读的已知包名） */
    private boolean isLauncherPackage(String pkg) {
        if (pkg == null) return false;
        return pkg.equals("com.bbk.launcher2")          // vivo Funtouch OS
            || pkg.equals("com.android.launcher3")      // AOSP
            || pkg.equals("com.google.android.apps.nexuslauncher") // Pixel
            || pkg.equals("com.sec.android.app.launcher") // Samsung
            || pkg.equals("com.miui.home")              // Xiaomi
            || pkg.equals("com.oneplus.launcher");      // OnePlus
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
                if (text.startsWith("\"") && text.endsWith("\"")) {
                    text = text.substring(1, text.length() - 1);
                }
                text = text.replace("\\n", "\n").replace("\\t", "\t");
                if (text.isEmpty()) text = "（页面无文字内容）";
                final String result = text;
                appendAIOutput("📖 页面内容:\n" + result.substring(0, Math.min(result.length(), 500)));
                aiAgent.submitToolResult("read_page", "当前浏览器页面的文字内容：\n" + result, aiCallback);
                // 读完自动关浏览器
                new Handler(Looper.getMainLooper()).postDelayed(() -> hideBrowser(), 500);
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
            constrainWindowHeight();
            // 不调 fullScroll，避免抢焦点
        });
    }

    /** 限制浮窗最大高度，对话过多时不会顶出屏幕 */
    private void constrainWindowHeight() {
        if (v == null || !v.isAttachedToWindow() || maxWindowHeight <= 0) return;
        if (v.getHeight() > maxWindowHeight) {
            p.height = maxWindowHeight;
            try { wm.updateViewLayout(v, p); } catch (Exception ignored) {}
            // 恢复 WRAP_CONTENT，下次内容少时能自动缩小
        } else if (v.getHeight() <= maxWindowHeight && p.height != WindowManager.LayoutParams.WRAP_CONTENT) {
            p.height = WindowManager.LayoutParams.WRAP_CONTENT;
            try { wm.updateViewLayout(v, p); } catch (Exception ignored) {}
        }
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
        dp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
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
        cancelFocusBeforeExit();
        stopUiRecording();
        hideBallOverlay();
        if (focusDismissView != null && wm != null) {
            try { wm.removeView(focusDismissView); } catch (Exception ignored) {}
            focusDismissView = null;
        }
        if (v != null && wm != null) { try { wm.removeView(v); } catch (Exception ignored) {} }
        super.onDestroy();
    }
}

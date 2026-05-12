package com.shizuku.ai;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * AI代理核心 - 工具调用架构（非标签模式）
 * AI 看到工具清单 → 选工具填参数 → app 执行验证过的代码
 */
public class AIAgent {

    private static final String TAG = "AIAgent";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final TokenManager tokenManager;
    private final ShizukuShellExecutor shellExecutor;
    private final List<Message> conversationHistory = new ArrayList<>();
    private final Map<String, String> memory = new HashMap<>();
    private volatile boolean cancelled = false;
    private HttpURLConnection currentConnection = null;

    // ===== 工具清单 =====
    static final String TOOL_MANIFEST = "" +
        "输出格式（严格）：{\"tool\":\"工具名\",\"params\":{...}}，不要写成 {\"工具名\":{}}。纯文本直接回复。\n\n" +
        "示例：{\"tool\":\"read_screen\",\"params\":{}}\n" +
        "示例：{\"tool\":\"click_screen\",\"params\":{\"action\":\"tap\",\"x\":500,\"y\":1000}}\n\n" +
        "工作流程：先打开目标App → 用 read_uimap 看操作地图了解页面结构 → 用 read_screen 看当前位置（\"可见控件\"里已标注中点坐标，直接用） → 用 click_screen 执行 → 系统会自动读屏问下一步\n\n" +
        "点击规则：坐标直接取 read_screen 返回的\"可见控件\"中的 \"中点(x,y)\"，不要自己计算。如果可见控件为空，告知用户坐标不可用。\n\n" +
        "可用工具：\n" +
        "1. search_web | 搜索 | params: {\"query\": \"\"}\n" +
        "2. browse_url | 打开网页 | params: {\"url\": \"\"}\n" +
        "3. read_page | 读浏览器页面内容 | params: {}\n" +
        "4. read_screen | 读当前屏幕UI（文字+控件坐标bounds） | params: {}\n" +
        "5. start_app | 打开应用 | params: {\"package\": \"\"}\n" +
        "6. list_apps | 列出应用 | params: {\"keyword\": \"\"}\n" +
        "7. toggle_flashlight | 手电筒开关 | params: {}\n" +
        "8. set_alarm | 设闹钟。hour 为 0-23（24 小时制），注意当前时间判断 AM/PM。例：下午 1:45 = hour=13, minutes=45 | params: {\"hour\": 0, \"minutes\": 0, \"message\": \"\"}\n" +
        "9. execute_shell | 执行Shell命令 | params: {\"command\": \"\"}\n" +
        "10. execute_intent | 执行Intent | params: {\"action\": \"\", \"extras\": {...}}\n" +
        "11. learn | 记住经验 | params: {\"key\": \"\", \"value\": \"\"}\n" +
        "12. get_device_info | 设备信息 | params: {}\n" +
        "13. read_uimap | 读UI操作历史地图。传 goal 参数搜索相关内容（如\"设置闹钟\"\"打开手电筒\"），系统会自动过滤出相关 App 和操作路径。不传 goal 时返回轻量概览。 | params: {\"goal\": \"要搜索的关键词\"}\n" +
        "14. create_note | 创建原子笔记 | params: {\"content\": \"\", \"title\": \"\"}\n" +
        "15. click_screen | 点击屏幕（坐标直接用 read_screen 里\"可见控件\"的\"中点\"，不要自己算） | params: {\"action\": \"tap\", \"x\": 0, \"y\": 0} 或 {\"action\": \"back\"}";

    public interface AICallback {
        void onResponse(String text);
        void onError(String error);
        void onToolCall(String toolName, String paramsJson);
    }

    public AIAgent(TokenManager tokenManager, ShizukuShellExecutor shellExecutor) {
        this.tokenManager = tokenManager;
        this.shellExecutor = shellExecutor;
    }

    public void clearMemory() {
        conversationHistory.clear();
        Log.d(TAG, "会话记忆已清空");
    }

    /** 用户发送消息 */
    public void sendMessage(String userInput, AICallback callback) {
        Log.d(TAG, "sendMessage: " + userInput);
        if (!tokenManager.hasToken()) {
            callback.onError("请先设置API Token！");
            return;
        }
        resetCancelState();
        conversationHistory.add(new Message("user", userInput));
        Log.d(TAG, "sendMessage: 队列 executor 任务");
        executor.execute(() -> {
            Log.d(TAG, "sendMessage: executor 任务开始");
            try {
                if (cancelled) { Log.d(TAG, "sendMessage: cancelled 跳过"); return; }
                callAIAPI(callback, false);
            } catch (Exception e) {
                if (!cancelled) {
                    mainHandler.post(() -> callback.onError("API调用失败: " + e.getMessage()));
                }
            }
        });
    }

    /** 提交工具执行结果给AI */
    public void submitToolResult(String toolName, String result, AICallback callback) {
        if (cancelled) return;
        conversationHistory.add(new Message("system",
            "[工具: " + toolName + "] 执行结果:\n" + result));
        executor.execute(() -> {
            try {
                if (cancelled) return;
                callAIAPI(callback, true);
            } catch (Exception e) {
                if (!cancelled) {
                    mainHandler.post(() -> callback.onError("API调用失败: " + e.getMessage()));
                }
            }
        });
    }

    // ===== 阻塞 API（用于 /ai HTTP 端点，复用异步 executor） =====

    /** 阻塞发送消息，返回 JSON */
    public String sendMessageSync(String userInput) {
        Log.d(TAG, "sendMessageSync ENTER: " + userInput);
        if (!tokenManager.hasToken()) return jsonError("Token未设置");
        return blockOnCallback(cb -> sendMessage(userInput, cb));
    }

    /** 阻塞提交工具结果，返回 JSON */
    public String submitToolResultSync(String toolName, String resultStr) {
        Log.d(TAG, "submitToolResultSync ENTER: " + toolName);
        if (cancelled) return jsonError("cancelled");
        return blockOnCallback(cb -> submitToolResult(toolName, resultStr, cb));
    }

    /** 通用阻塞：在异步回调上等待，返回 JSON */
    private String blockOnCallback(java.util.function.Consumer<AICallback> invoker) {
        Log.d(TAG, "blockOnCallback: 创建 latch");
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] result = new String[1];

        Log.d(TAG, "blockOnCallback: 调用 invoker");
        invoker.accept(new AICallback() {
            @Override
            public void onResponse(String text) {
                Log.d(TAG, "blockOnCallback onResponse");
                setResult(text, null);
            }
            @Override
            public void onError(String error) {
                Log.e(TAG, "blockOnCallback onError: " + error);
                setResult(null, error);
            }
            @Override
            public void onToolCall(String toolName, String paramsJson) {
                Log.d(TAG, "blockOnCallback onToolCall: " + toolName);
                try {
                    JSONObject r = new JSONObject();
                    r.put("type", "tool_call");
                    r.put("tool", toolName);
                    r.put("params", new JSONObject(paramsJson));
                    result[0] = r.toString();
                } catch (Exception e) { result[0] = jsonError(e.getMessage()); }
                latch.countDown();
            }
            private void setResult(String text, String error) {
                try {
                    JSONObject r = new JSONObject();
                    if (text != null) { r.put("type", "text"); r.put("content", text); }
                    else { r.put("type", "error"); r.put("content", error != null ? error : "unknown"); }
                    result[0] = r.toString();
                } catch (Exception e) { result[0] = jsonError(e.getMessage()); }
                latch.countDown();
            }
        });

        Log.d(TAG, "blockOnCallback: 等待结果...");
        try {
            if (!latch.await(120, TimeUnit.SECONDS)) {
                Log.e(TAG, "blockOnCallback: 超时!");
                return jsonError("timeout（AI 响应超时）");
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "blockOnCallback: 中断");
            return jsonError("interrupted");
        }
        Log.d(TAG, "blockOnCallback: 完成");
        return result[0] != null ? result[0] : jsonError("no response");
    }

    private String jsonError(String msg) {
        try {
            JSONObject err = new JSONObject();
            err.put("type", "error");
            err.put("content", msg);
            return err.toString();
        } catch (Exception e) {
            return "{\"type\":\"error\",\"content\":\"internal error\"}";
        }
    }

    private void callAIAPI(AICallback callback, boolean isToolResult) throws Exception {
        String endpoint = tokenManager.getEndpoint();
        String token = tokenManager.getToken();
        String model = tokenManager.getModel();

        // 自动补全 API 路径：兼容 Base URL 和完整 URL 两种格式
        if (!endpoint.endsWith("/chat/completions")) {
            if (!endpoint.endsWith("/")) endpoint += "/";
            endpoint += "chat/completions";
        }

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.0);
        body.put("max_tokens", 4096);

        JSONArray messages = new JSONArray();

        // 构建系统消息：工具说明书 + 记忆
        StringBuilder sysContent = new StringBuilder();
        sysContent.append("你是一个Android手机助手，通过Shizuku获取系统权限执行操作。\n\n");
        sysContent.append("当前时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(new Date())).append("\n\n");
        sysContent.append(TOOL_MANIFEST);
        if (!memory.isEmpty()) {
            sysContent.append("\n\n你记住的经验：\n");
            for (Map.Entry<String, String> e : memory.entrySet()) {
                sysContent.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
        }
        if (isToolResult) {
            sysContent.append("\n\n注意：以上是工具执行结果。根据结果决定下一步：如果任务完成了就直接告诉用户；如果需要继续操作，调用下一个工具。");
        }

        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", sysContent.toString());
        messages.put(sysMsg);

        // 对话历史
        int start = Math.max(0, conversationHistory.size() - 30);
        for (int i = start; i < conversationHistory.size(); i++) {
            Message msg = conversationHistory.get(i);
            JSONObject m = new JSONObject();
            m.put("role", msg.role);
            m.put("content", msg.content);
            messages.put(m);
        }
        body.put("messages", messages);

        // 发送请求
        Log.d(TAG, "调用API: " + endpoint);
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        currentConnection = conn;
        if (cancelled) { conn.disconnect(); return; }
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            BufferedReader errReader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
            StringBuilder errResp = new StringBuilder();
            String line;
            while ((line = errReader.readLine()) != null) errResp.append(line);
            errReader.close();
            throw new Exception("API错误 " + responseCode + ": " + errResp.toString());
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) response.append(line);
        reader.close();

        JSONObject json = new JSONObject(response.toString());
        String content = json.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content");

        conversationHistory.add(new Message("assistant", content));

        // 解析响应：JSON工具调用 或 纯文本
        parseResponse(content, callback);
    }

    /** 解析AI响应：检测JSON工具调用，否则当纯文本 */
    private void parseResponse(String content, AICallback callback) {
        if (cancelled) return;
        String trimmed = content.trim();
        String textPart = "";
        String jsonPart = trimmed;

        // 扫描全文找 {"tool": 开头的 JSON（AI 可能先说一句话再输出 JSON）
        // 注意：AI 可能输出格式化 JSON（带换行），不能只搜 {"tool"
        int toolJsonStart = trimmed.indexOf("{\"tool\"");
        if (toolJsonStart < 0) {
            // 匹配格式化 JSON: { \n "tool": ... 等
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\{\\s*\"tool\"").matcher(trimmed);
            if (m.find()) toolJsonStart = m.start();
        }

        if (toolJsonStart >= 0) {
            String textBefore = toolJsonStart > 0 ? trimmed.substring(0, toolJsonStart).trim() : "";
            jsonPart = trimmed.substring(toolJsonStart).trim();
            try {
                JSONObject toolCall = new JSONObject(jsonPart);
                if (toolCall.has("tool")) {
                    if (!textBefore.isEmpty()) {
                        mainHandler.post(() -> callback.onResponse(textBefore));
                    }
                    String toolName = toolCall.getString("tool");
                    String params = toolCall.has("params") ? toolCall.getJSONObject("params").toString() : "{}";
                    mainHandler.post(() -> callback.onToolCall(toolName, params));
                    return;
                }
            } catch (Exception ignored) {}
        }

        // 尝试数组格式 [{"tool":..., ...}]
        if (trimmed.startsWith("[")) {
            try {
                JSONArray toolCalls = new JSONArray(trimmed);
                if (toolCalls.length() > 0) {
                    JSONObject first = toolCalls.getJSONObject(0);
                    if (first.has("tool")) {
                        String toolName = first.getString("tool");
                        String params = first.has("params") ? first.getJSONObject("params").toString() : "{}";
                        mainHandler.post(() -> callback.onToolCall(toolName, params));
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }

        // 兼容格式：{"read_screen": {}} → 提取 key 作为工具名
        if (toolJsonStart < 0 && (trimmed.startsWith("{") || trimmed.startsWith("{\n"))) {
            try {
                JSONObject altCall = new JSONObject(trimmed);
                String[] knownTools = {"search_web","browse_url","read_page","read_screen","start_app",
                    "list_apps","toggle_flashlight","set_alarm","execute_shell","execute_intent",
                    "learn","get_device_info","read_uimap","create_note","click_screen"};
                for (String t : knownTools) {
                    if (altCall.has(t)) {
                        String params = altCall.get(t).toString();
                        if (params.equals("{}") || params.startsWith("{")) {
                            mainHandler.post(() -> callback.onToolCall(t, params));
                        } else {
                            mainHandler.post(() -> callback.onToolCall(t, "{}"));
                        }
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }

        // 检测 LEARN 内联
        int learnIdx = trimmed.indexOf("learn(");
        if (learnIdx >= 0) {
            int close = trimmed.indexOf(")", learnIdx);
            if (close > learnIdx) {
                String inner = trimmed.substring(learnIdx + 6, close);
                String[] parts = inner.split(",", 2);
                if (parts.length >= 2) {
                    memory.put(parts[0].trim().replaceAll("^\"|\"$", ""),
                               parts[1].trim().replaceAll("^\"|\"$", ""));
                }
                String text = (trimmed.substring(0, learnIdx) + trimmed.substring(close + 1)).trim();
                if (text.length() > 0) {
                    mainHandler.post(() -> callback.onResponse(text));
                } else {
                    mainHandler.post(() -> callback.onResponse("📚 已记住"));
                }
                return;
            }
        }

        // 纯文本回复
        mainHandler.post(() -> callback.onResponse(trimmed));
    }

    /** 取消当前正在进行的 AI 请求 */
    public void cancelCurrentRequest() {
        cancelled = true;
        if (currentConnection != null) {
            try { currentConnection.disconnect(); } catch (Exception ignored) {}
        }
    }

    /** 重置取消状态（每次新请求前调用） */
    private void resetCancelState() {
        cancelled = false;
        currentConnection = null;
    }

    public void addMemory(String key, String value) {
        if (key != null && value != null && !key.isEmpty() && !value.isEmpty()) {
            memory.put(key, value);
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void shutdown() {
        cancelCurrentRequest();
        executor.shutdownNow();
        conversationHistory.clear();
        Log.d(TAG, "AI代理已关闭");
    }

    public List<Message> getHistory() {
        return new ArrayList<>(conversationHistory);
    }

    static class Message {
        String role;
        String content;
        Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    // ===== 工具清单（工具说明书） =====
    private static final String TOOL_MANIFEST = "" +
        "你有以下工具可用。需要执行操作时，输出如下JSON格式，不要加多余标记：\n" +
        "{\"tool\":\"工具名\",\"params\":{...}}\n" +
        "需要执行多个操作时，输出JSON数组：\n" +
        "[{\"tool\":\"...\",\"params\":{...}},{\"tool\":\"...\",\"params\":{...}}]\n" +
        "纯文本回复正常说话即可。不是所有问题都需要用工具，闲聊直接回复。\n\n" +
        "可用工具：\n" +
        "1. search_web | 联网搜索 | params: {\"query\": \"搜索关键词\"}\n" +
        "2. browse_url | 在浏览器打开网页 | params: {\"url\": \"https://...\"}\n" +
        "3. read_page | 读取当前浏览器页面的文字内容 | params: {}\n" +
        "4. read_screen | 读取手机当前屏幕上的文字（无障碍） | params: {}\n" +
        "5. start_app | 打开应用 | params: {\"package\": \"com.example.app\"}\n" +
        "6. list_apps | 列出已安装应用 | params: {\"keyword\": \"可选搜索词\"}\n" +
        "7. toggle_flashlight | 切换手电筒开关 | params: {}\n" +
        "8. set_alarm | 设置闹钟 | params: {\"hour\": 12, \"minutes\": 0, \"message\": \"可选标签\"}\n" +
        "9. execute_shell | 执行Shell命令 | params: {\"command\": \"shell命令\"}\n" +
        "10. execute_intent | 执行Android Intent | params: {\"action\": \"android.intent.action.XXX\", \"extras\": {...}}\n" +
        "11. learn | 记住一条经验 | params: {\"key\": \"事项\", \"value\": \"内容\"}\n" +
        "12. get_device_info | 获取设备信息 | params: {}\n" +
        "\n使用原则：\n" +
        "- 能用工具有现成工具的，优先用工具，不自己编命令\n" +
        "- 搜索后用 browse_url 打开结果链接查看详情\n" +
        "- 不清楚包名时先用 list_apps 查\n" +
        "- 如果一个工具调用返回的结果显示需要后续操作，继续调用下一个工具";

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
        if (!tokenManager.hasToken()) {
            callback.onError("请先设置API Token！");
            return;
        }
        conversationHistory.add(new Message("user", userInput));
        executor.execute(() -> {
            try {
                callDeepSeekAPI(callback, false);
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("API调用失败: " + e.getMessage()));
            }
        });
    }

    /** 提交工具执行结果给AI */
    public void submitToolResult(String toolName, String result, AICallback callback) {
        conversationHistory.add(new Message("system",
            "[工具: " + toolName + "] 执行结果:\n" + result));
        executor.execute(() -> {
            try {
                callDeepSeekAPI(callback, true);
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("API调用失败: " + e.getMessage()));
            }
        });
    }

    private void callDeepSeekAPI(AICallback callback, boolean isToolResult) throws Exception {
        String endpoint = tokenManager.getEndpoint();
        String token = tokenManager.getToken();
        String model = tokenManager.getModel();

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.1);
        body.put("max_tokens", 4096);

        JSONArray messages = new JSONArray();

        // 构建系统消息：工具说明书 + 记忆
        StringBuilder sysContent = new StringBuilder();
        sysContent.append("你是一个Android手机助手，通过Shizuku获取系统权限执行操作。\n\n");
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
        String trimmed = content.trim();

        // 尝试解析为JSON（可能是单个工具或工具数组）
        if (trimmed.startsWith("{")) {
            try {
                JSONObject toolCall = new JSONObject(trimmed);
                if (toolCall.has("tool")) {
                    String toolName = toolCall.getString("tool");
                    String params = toolCall.has("params") ? toolCall.getJSONObject("params").toString() : "{}";
                    mainHandler.post(() -> callback.onToolCall(toolName, params));
                    return;
                }
            } catch (Exception ignored) {}
        }

        if (trimmed.startsWith("[")) {
            try {
                JSONArray toolCalls = new JSONArray(trimmed);
                // 只取第一个工具调用（后续工具等上一个执行完再说）
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

    public void shutdown() {
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

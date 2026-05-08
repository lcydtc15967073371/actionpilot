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
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI代理核心 - 调用DeepSeek API，理解用户意图，操控Shell执行命令
 */
public class AIAgent {

    private static final String TAG = "AIAgent";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final TokenManager tokenManager;
    private final ShizukuShellExecutor shellExecutor;
    private final List<Message> conversationHistory = new ArrayList<>();
    private final Map<String, String> memory = new HashMap<>(); // [LEARN]记忆

    // System Prompt
    private static final String SYSTEM_PROMPT = "你通过Shizuku执行Android shell命令。规则：\n" +
            "1. 用[CMD]命令[/CMD]执行\n" +
            "2. 不知道用[WEB]搜关键词[/WEB]联网查\n" +
            "3. 不瞎编命令\n" +
            "4. 用[LEARN]关键词|内容[/LEARN]记住经验\n" +
            "5. 查已安装应用用[CMD]list_apps 关键词[/CMD]或[CMD]list_apps[/CMD]列出全部\n" +
            "6. 重要：list_apps返回的就是最终结果，不要重复执行。每次收到list_apps结果后直接展示给用户。\n" +
            "7. 启动应用统一用[CMD]monkey -p 包名 1[/CMD]，不要用am start -n（因为不指定activity名会启动失败）。";

    public interface AICallback {
        void onResponse(String text, String command, boolean hasCommand);
        void onError(String error);
        void onSearchRequest(String keyword, boolean isWebSearch);
    }

    public AIAgent(TokenManager tokenManager, ShizukuShellExecutor shellExecutor) {
        this.tokenManager = tokenManager;
        this.shellExecutor = shellExecutor;
    }

    /** 清空会话记忆 */
    public void clearMemory() {
        conversationHistory.clear();
        Log.d(TAG, "会话记忆已清空");
    }

    /** 用户发送消息给AI */
    public void sendMessage(String userInput, AICallback callback) {
        if (!tokenManager.hasToken()) {
            callback.onError("请先设置API Token！");
            return;
        }

        // 添加用户消息到历史
        conversationHistory.add(new Message("user", userInput));

        executor.execute(() -> {
            try {
                callDeepSeekAPI(callback);
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("API调用失败: " + e.getMessage()));
            }
        });
    }

    /** 提交搜索结果给AI（追加到对话） */
    public void submitSearchResult(String searchResult, AICallback callback) {
        conversationHistory.add(new Message("system", "以下是搜索结果：\n" + searchResult + "\n\n请基于这些信息，给出合适的命令。"));
        executor.execute(() -> {
            try {
                callDeepSeekAPI(callback);
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("API调用失败: " + e.getMessage()));
            }
        });
    }

    /** 提交命令执行结果给AI */
    public void submitCommandResult(String command, String result, AICallback callback) {
        conversationHistory.add(new Message("system",
            "[执行结果] 命令: " + command + "\n输出:\n" + result));
        executor.execute(() -> {
            try {
                callDeepSeekAPI(callback);
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("API调用失败: " + e.getMessage()));
            }
        });
    }

    private void callDeepSeekAPI(AICallback callback) throws Exception {
        String endpoint = tokenManager.getEndpoint();
        String token = tokenManager.getToken();
        String model = tokenManager.getModel();

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.1);
        body.put("max_tokens", 2048);

        JSONArray messages = new JSONArray();
        // 添加系统prompt（附带记忆）
        String fullPrompt = SYSTEM_PROMPT;
        if (!memory.isEmpty()) {
            StringBuilder mem = new StringBuilder("\n\n你记住的经验：\n");
            for (Map.Entry<String, String> e : memory.entrySet()) {
                mem.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
            fullPrompt += mem.toString();
        }
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", fullPrompt);
        messages.put(sysMsg);

        // 添加对话历史（最多保留最近20条）
        int start = Math.max(0, conversationHistory.size() - 20);
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
        Log.d(TAG, "响应码: " + responseCode);

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

        // 解析AI响应
        parseAIResponse(content, callback);

        // 添加到历史
        conversationHistory.add(new Message("assistant", content));
    }

    private void parseAIResponse(String content, AICallback callback) {
        // 在 textContent 上操作，始终用 textContent 找位置
        String textContent = content;

        // 循环处理所有管理标签，直到没有标签为止
        boolean handledTag = true;
        while (handledTag) {
            handledTag = false;

            // 处理 [LEARN] 标签 — 记住经验
            int learnStart = textContent.indexOf("[LEARN]");
            int learnEnd = textContent.indexOf("[/LEARN]");
            if (learnStart >= 0 && learnEnd > learnStart) {
                String learnData = textContent.substring(learnStart + 7, learnEnd).trim();
                String[] parts = learnData.split("\\|", 2);
                if (parts.length >= 2) {
                    String key = parts[0].trim();
                    String val = parts[1].trim();
                    memory.put(key, val);
                    mainHandler.post(() -> callback.onResponse("📚 已记住: " + key, null, false));
                }
                textContent = textContent.substring(0, learnStart) + textContent.substring(learnEnd + 8);
                handledTag = true;
                continue;
            }

            // 处理 [NOTE] 标签
            int noteStart = textContent.indexOf("[NOTE]");
            int noteEnd = textContent.indexOf("[/NOTE]");
            if (noteStart >= 0 && noteEnd > noteStart) {
                String noteData = textContent.substring(noteStart + 6, noteEnd).trim();
                String[] parts = noteData.split("\\|", 2);
                if (parts.length >= 2) {
                    String result = AdbCommands.updateNote(parts[0].trim(), parts[1].trim());
                    mainHandler.post(() -> callback.onResponse("📝 " + result, null, false));
                }
                textContent = textContent.substring(0, noteStart) + textContent.substring(noteEnd + 7);
                handledTag = true;
                continue;
            }

            // 处理 [TAG] 标签
            int tagStart = textContent.indexOf("[TAG]");
            int tagEnd = textContent.indexOf("[/TAG]");
            if (tagStart >= 0 && tagEnd > tagStart) {
                String tagData = textContent.substring(tagStart + 5, tagEnd).trim();
                String[] parts = tagData.split("\\|", 2);
                if (parts.length >= 2) {
                    String result = AdbCommands.tagCommand(parts[0].trim(), parts[1].trim());
                    mainHandler.post(() -> callback.onResponse("🏷️ " + result, null, false));
                }
                textContent = textContent.substring(0, tagStart) + textContent.substring(tagEnd + 6);
                handledTag = true;
                continue;
            }
        }

        // 清理剩余文本
        textContent = textContent.trim();
        if (textContent.isEmpty()) return; // 全是标签，处理完了直接返回

        // 检查是否有命令标记
        int cmdStart = textContent.indexOf("[CMD]");
        int cmdEnd = textContent.indexOf("[/CMD]");

        if (cmdStart >= 0 && cmdEnd > cmdStart) {
            String beforeCmd = textContent.substring(0, cmdStart).trim();
            String command = textContent.substring(cmdStart + 5, cmdEnd).trim();
            String afterCmd = textContent.substring(cmdEnd + 6).trim();
            String text = (beforeCmd.length() > 0 ? beforeCmd + "\n" : "") + afterCmd;
            mainHandler.post(() -> callback.onResponse(text.trim(), command, true));
            return;
        }

        // 检查搜索请求
        int webStart = textContent.indexOf("[WEB]");
        int webEnd = textContent.indexOf("[/WEB]");
        if (webStart >= 0 && webEnd > webStart) {
            String keyword = textContent.substring(webStart + 5, webEnd).trim();
            mainHandler.post(() -> callback.onSearchRequest(keyword, true));
            return;
        }

        int searchStart = textContent.indexOf("[SEARCH]");
        int searchEnd = textContent.indexOf("[/SEARCH]");
        if (searchStart >= 0 && searchEnd > searchStart) {
            String keyword = textContent.substring(searchStart + 8, searchEnd).trim();
            mainHandler.post(() -> callback.onSearchRequest(keyword, false));
            return;
        }

        // 纯文本回复
        final String finalReply = textContent;
        mainHandler.post(() -> callback.onResponse(finalReply, null, false));
    }

    /** 彻底关闭：停掉所有后台线程 */
    public void shutdown() {
        executor.shutdownNow();
        conversationHistory.clear();
        Log.d(TAG, "AI代理已关闭");
    }

    /** 获取对话历史 */
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

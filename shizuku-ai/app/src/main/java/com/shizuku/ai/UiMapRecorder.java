package com.shizuku.ai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UI 操作地图录制器 —— 静默记录用户的操作路径（窗口切换、点击等）
 * 数据持久化到 JSON 文件，支持累积更新。
 */
public class UiMapRecorder {

    private static final String FILE_NAME = "uimap.json";

    private final Context context;
    private final Map<String, UiNode> nodes = new LinkedHashMap<>();
    private final List<UiEdge> edges = new ArrayList<>();
    private final List<UiAction> actions = new ArrayList<>();
    private String currentId;
    private long startedAt;
    private int totalActions;
    private boolean recording;

    private long lastClickTime;
    private String lastClickLabel;

    public UiMapRecorder(Context context) {
        this.context = context;
    }

    /** 开始/继续录制 —— 如果已有持久化数据则加载 */
    public void start() {
        if (recording) return;
        recording = true;
        totalActions = 0;
        lastClickLabel = "";
        lastClickTime = 0L;
        load();
        if (startedAt == 0) startedAt = System.currentTimeMillis();
    }

    /** 停止录制并持久化 */
    public void stop() {
        if (!recording) return;
        recording = false;
        save();
    }

    /** 窗口/Activity 切换 */
    public void onWindowChanged(String appPackage, String appName, String screenName) {
        if (!recording) return;
        String nodeId = nodeId(appPackage, screenName);
        if (nodeId.equals(currentId)) return;

        long now = System.currentTimeMillis();
        String prevId = currentId;

        // 创建/更新节点
        UiNode existing = nodes.get(nodeId);
        nodes.put(nodeId, existing != null
            ? new UiNode(nodeId, appPackage, appName, screenName, existing.firstSeen, now, existing.visitCount + 1)
            : new UiNode(nodeId, appPackage, appName, screenName, now, now, 1));

        // 记录跳转边
        if (prevId != null && !prevId.equals(nodeId)) {
            UiEdge existingEdge = findEdge(prevId, nodeId);
            if (existingEdge != null) {
                edges.remove(existingEdge);
                edges.add(new UiEdge(prevId, nodeId, existingEdge.actionType, existingEdge.elementLabel,
                    existingEdge.count + 1, now));
            } else {
                String label = (now - lastClickTime < 1500) ? consumeLastClick() : "";
                edges.add(new UiEdge(prevId, nodeId, "CLICK", label, 1, now));
            }
        }

        // 记录 transition action
        actions.add(new UiAction(prevId != null ? prevId : nodeId, "TRANSITION",
            appName + "/" + screenName, "", now));

        currentId = nodeId;
        totalActions++;
    }

    /** 记录用户操作（点击、长按、输入等） */
    public void onAction(String actionType, String elementLabel, String viewId) {
        if (!recording) return;
        long now = System.currentTimeMillis();
        totalActions++;
        actions.add(new UiAction(currentId != null ? currentId : "", actionType, elementLabel, viewId, now));

        if ("CLICK".equals(actionType) || "LONG_CLICK".equals(actionType)) {
            lastClickLabel = elementLabel;
            lastClickTime = now;
        }
    }

    /** 记录屏幕内容（自动去重：连续相同/包含的内容只存一次） */
    public void onScreenContent(String content) {
        if (!recording || content.isEmpty()) return;
        // 如果上一条也是 SCREEN_CONTENT 且内容包含当前内容或反之，跳过（页面未实质性变化）
        if (!actions.isEmpty()) {
            UiAction last = actions.get(actions.size() - 1);
            if ("SCREEN_CONTENT".equals(last.actionType)) {
                String lastLabel = last.elementLabel;
                if (content.contains(lastLabel) || lastLabel.contains(content)) {
                    return; // 内容高度重叠，去重
                }
            }
        }
        actions.add(new UiAction(currentId != null ? currentId : "", "SCREEN_CONTENT", content, "", System.currentTimeMillis()));
    }

    /** AI 导出 —— 格式化的操作地图 JSON */
    public String exportForAI() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", "1.0");
            root.put("totalActions", totalActions);
            root.put("recordingDuration", startedAt > 0 ? (System.currentTimeMillis() - startedAt) / 1000 : 0);

            // 按 App 分组的节点
            JSONObject apps = new JSONObject();
            Map<String, JSONArray> byPkg = new LinkedHashMap<>();
            for (UiNode node : nodes.values()) {
                JSONArray arr = byPkg.get(node.appPackage);
                if (arr == null) {
                    arr = new JSONArray();
                    byPkg.put(node.appPackage, arr);
                }
                JSONObject n = new JSONObject();
                n.put("screen", node.screenName);
                n.put("visits", node.visitCount);
                arr.put(n);
            }
            for (Map.Entry<String, JSONArray> e : byPkg.entrySet()) {
                String pkg = e.getKey();
                UiNode sample = null;
                for (UiNode n : nodes.values()) {
                    if (n.appPackage.equals(pkg)) { sample = n; break; }
                }
                JSONObject appObj = new JSONObject();
                appObj.put("name", sample != null ? sample.appName : pkg);
                appObj.put("screens", e.getValue());
                apps.put(pkg, appObj);
            }
            root.put("apps", apps);

            // 跳转流
            JSONArray flows = new JSONArray();
            for (UiEdge edge : edges) {
                JSONObject f = new JSONObject();
                UiNode from = nodes.get(edge.fromId);
                UiNode to = nodes.get(edge.toId);
                f.put("from", from != null ? from.screenName : edge.fromId);
                f.put("trigger", edge.elementLabel);
                f.put("to", to != null ? to.screenName : edge.toId);
                f.put("count", edge.count);
                flows.put(f);
            }
            root.put("flows", flows);

            // 最近操作时间线（去重连续相似的 SCREEN_CONTENT，限制 50 条）
            JSONArray timeline = new JSONArray();
            int start = Math.max(0, actions.size() - 50);
            String lastScreenContent = null;
            for (int i = start; i < actions.size(); i++) {
                UiAction a = actions.get(i);
                // SCREEN_CONTENT 去重
                if ("SCREEN_CONTENT".equals(a.actionType)) {
                    if (a.elementLabel.equals(lastScreenContent)) continue;
                    lastScreenContent = a.elementLabel;
                }
                JSONObject act = new JSONObject();
                act.put("type", a.actionType);
                int maxLen = "SCREEN_CONTENT".equals(a.actionType) ? 120 : 200;
                String label = a.elementLabel;
                if (label.length() > maxLen) label = label.substring(0, maxLen);
                act.put("label", label);
                UiNode node = nodes.get(a.nodeId);
                act.put("screen", node != null ? node.screenName : a.nodeId);
                timeline.put(act);
            }
            root.put("timeline", timeline);

            return root.toString(2);
        } catch (Exception e) {
            return "{\"error\":\"导出失败: " + e.getMessage() + "\"}";
        }
    }

    /** 轻量概览导出 —— 仅 app 列表+总数，无 flows/timeline */
    public String exportSummaryForAI() {
        try {
            JSONObject root = new JSONObject();
            root.put("type", "summary");
            root.put("totalActions", totalActions);
            root.put("recordingDuration", startedAt > 0 ? (System.currentTimeMillis() - startedAt) / 1000 : 0);

            JSONArray apps = new JSONArray();
            Map<String, JSONObject> byPkg = new LinkedHashMap<>();
            for (UiNode node : nodes.values()) {
                JSONObject existing = byPkg.get(node.appPackage);
                if (existing == null) {
                    JSONObject appObj = new JSONObject();
                    appObj.put("package", node.appPackage);
                    appObj.put("name", node.appName);
                    appObj.put("screenCount", 1);
                    appObj.put("visitCount", node.visitCount);
                    byPkg.put(node.appPackage, appObj);
                } else {
                    existing.put("screenCount", existing.getInt("screenCount") + 1);
                    existing.put("visitCount", existing.getInt("visitCount") + node.visitCount);
                }
            }
            for (JSONObject app : byPkg.values()) {
                apps.put(app);
            }
            root.put("apps", apps);

            return root.toString(2);
        } catch (Exception e) {
            return "{\"error\":\"导出失败: " + e.getMessage() + "\"}";
        }
    }

    /** 基于 goal 关键词过滤导出 —— 只返回匹配的 nodes/edges/timeline */
    public String exportFilteredForAI(String goal) {
        try {
            if (goal == null || goal.trim().isEmpty()) {
                return exportForAI();
            }

            String goalLower = goal.toLowerCase().trim();

            // 1. 过滤节点：包名/应用名/屏幕名任一匹配关键词
            Map<String, UiNode> filteredNodes = new LinkedHashMap<>();
            for (UiNode node : nodes.values()) {
                if (matches(node, goalLower)) {
                    filteredNodes.put(node.id, node);
                }
            }

            // 2. 过滤动作：elementLabel 匹配关键词（同时收集涉及到的 nodeId）
            //    去重：连续相同的 SCREEN_CONTENT 只保留最后一条
            Map<String, UiNode> actionNodes = new LinkedHashMap<>();
            List<UiAction> matchingActions = new ArrayList<>();
            String lastScreenContent = null;
            for (UiAction a : actions) {
                if (a.elementLabel.toLowerCase().contains(goalLower)
                        || a.nodeId.toLowerCase().contains(goalLower)) {
                    // SCREEN_CONTENT 去重：内容相同的跳过
                    if ("SCREEN_CONTENT".equals(a.actionType)) {
                        if (a.elementLabel.equals(lastScreenContent)) continue;
                        lastScreenContent = a.elementLabel;
                    }
                    matchingActions.add(a);
                    UiNode n = nodes.get(a.nodeId);
                    if (n != null) actionNodes.put(a.nodeId, n);
                }
            }

            // 合并节点：显式匹配的 + 动作涉及的
            filteredNodes.putAll(actionNodes);

            if (filteredNodes.isEmpty() && matchingActions.isEmpty()) {
                return "{\"message\":\"未找到与「" + goal + "」相关的操作记录\",\"totalActions\":" + totalActions + "}";
            }

            // 3. 过滤跳转边：保留导航路径但限制数量，避免枢纽节点（桌面/交互池）拉入大量无关边
            List<UiEdge> filteredEdges = new ArrayList<>();
            int oneSideCount = 0;
            for (UiEdge edge : edges) {
                if (edge.elementLabel.toLowerCase().contains(goalLower)) {
                    filteredEdges.add(edge);
                } else if (filteredNodes.containsKey(edge.fromId) && filteredNodes.containsKey(edge.toId)) {
                    filteredEdges.add(edge); // 两端都匹配，直接保留
                } else if (filteredNodes.containsKey(edge.fromId) || filteredNodes.containsKey(edge.toId)) {
                    // 一端匹配（如桌面→支付宝），保留导航路径但限 20 条防膨胀
                    if (oneSideCount < 20) {
                        filteredEdges.add(edge);
                        oneSideCount++;
                    }
                }
            }

            // 4. 截取动作：最多 10 条
            int start = Math.max(0, matchingActions.size() - 10);
            List<UiAction> trimmedActions = matchingActions.subList(start, matchingActions.size());

            // 5. 组装 JSON（与 exportForAI 结构一致）
            JSONObject root = new JSONObject();
            root.put("type", "search_result");
            root.put("query", goal);
            root.put("totalActions", totalActions);
            root.put("matchedActions", matchingActions.size());

            // Apps
            JSONObject apps = new JSONObject();
            Map<String, JSONArray> byPkg = new LinkedHashMap<>();
            for (UiNode node : filteredNodes.values()) {
                JSONArray arr = byPkg.computeIfAbsent(node.appPackage, k -> new JSONArray());
                JSONObject n = new JSONObject();
                n.put("screen", node.screenName);
                n.put("visits", node.visitCount);
                arr.put(n);
            }
            for (Map.Entry<String, JSONArray> e : byPkg.entrySet()) {
                String pkg = e.getKey();
                UiNode sample = null;
                for (UiNode n : filteredNodes.values()) {
                    if (n.appPackage.equals(pkg)) { sample = n; break; }
                }
                JSONObject appObj = new JSONObject();
                appObj.put("name", sample != null ? sample.appName : pkg);
                appObj.put("screens", e.getValue());
                apps.put(pkg, appObj);
            }
            root.put("apps", apps);

            // Flows
            JSONArray flows = new JSONArray();
            for (UiEdge edge : filteredEdges) {
                JSONObject f = new JSONObject();
                UiNode from = nodes.get(edge.fromId);
                UiNode to = nodes.get(edge.toId);
                f.put("from", from != null ? from.screenName : edge.fromId);
                f.put("trigger", edge.elementLabel);
                f.put("to", to != null ? to.screenName : edge.toId);
                f.put("count", edge.count);
                // 泛控件 ID 的 trigger（如 usual_item_root），附上源页面屏幕内容供 AI 参考
                if (isGenericId(edge.elementLabel)) {
                    String ctx = getLastScreenContentForNode(edge.fromId);
                    if (!ctx.isEmpty()) f.put("screen", ctx);
                }
                flows.put(f);
            }
            root.put("flows", flows);

            // Timeline
            JSONArray timeline = new JSONArray();
            for (UiAction a : trimmedActions) {
                JSONObject act = new JSONObject();
                act.put("type", a.actionType);
                // SCREEN_CONTENT 日志更长，压缩到 120 字符
                int maxLen = "SCREEN_CONTENT".equals(a.actionType) ? 120 : 200;
                String label = a.elementLabel;
                if (label.length() > maxLen) label = label.substring(0, maxLen);
                act.put("label", label);
                UiNode node = nodes.get(a.nodeId);
                act.put("screen", node != null ? node.screenName : a.nodeId);
                timeline.put(act);
            }
            root.put("timeline", timeline);

            return root.toString(2);
        } catch (Exception e) {
            return "{\"error\":\"搜索导出失败: " + e.getMessage() + "\"}";
        }
    }

    private boolean matches(UiNode node, String goalLower) {
        return node.appPackage.toLowerCase().contains(goalLower)
            || node.appName.toLowerCase().contains(goalLower)
            || node.screenName.toLowerCase().contains(goalLower);
    }
    public String getRecentAppsJson() {
        try {
            JSONArray arr = new JSONArray();
            LinkedHashMap<String, String> seen = new LinkedHashMap<>();
            for (UiNode node : nodes.values()) {
                if (!seen.containsKey(node.appPackage)) {
                    seen.put(node.appPackage, node.appName);
                    JSONObject o = new JSONObject();
                    o.put("package", node.appPackage);
                    o.put("name", node.appName);
                    arr.put(o);
                }
            }
            return arr.toString(2);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 根据用户目标从地图中提炼建议路线 */
    public String suggestRoute(String goal) {
        if (goal == null || goal.isEmpty() || totalActions == 0)
            return "";
        try {
            String goalLower = goal.toLowerCase();
            // 筛选相关跳转边
            List<UiEdge> relevantEdges = new ArrayList<>();
            for (UiEdge e : edges) {
                String label = e.elementLabel.toLowerCase();
                String fromName = nodeName(e.fromId).toLowerCase();
                String toName = nodeName(e.toId).toLowerCase();
                if (label.contains(goalLower) || fromName.contains(goalLower) || toName.contains(goalLower)) {
                    relevantEdges.add(e);
                }
            }

            // 筛选相关操作
            List<UiAction> relevantActions = new ArrayList<>();
            for (UiAction a : actions) {
                if (a.elementLabel.toLowerCase().contains(goalLower)) {
                    relevantActions.add(a);
                }
            }

            StringBuilder sb = new StringBuilder();
            if (!relevantEdges.isEmpty()) {
                sb.append("\n📋 根据历史记录的路线建议：\n");
                // 去重并排序
                LinkedHashMap<String, Integer> seen = new LinkedHashMap<>();
                for (UiEdge e : relevantEdges) {
                    String key = nodeName(e.fromId) + " → [" + e.elementLabel + "] → " + nodeName(e.toId);
                    if (!seen.containsKey(key)) {
                        seen.put(key, e.count);
                        sb.append("  ").append(key).append(" (执行过").append(e.count).append("次)\n");
                    }
                }
                sb.append("\n");
            }
            if (!relevantActions.isEmpty()) {
                sb.append("📌 相关操作：\n");
                LinkedHashMap<String, Integer> seen = new LinkedHashMap<>();
                for (UiAction a : relevantActions) {
                    if (!seen.containsKey(a.elementLabel)) {
                        seen.put(a.elementLabel, 1);
                        sb.append("  - ").append(a.elementLabel).append("\n");
                    }
                }
            }

            return sb.length() > 0 ? sb.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String nodeName(String nodeId) {
        UiNode n = nodes.get(nodeId);
        return n != null ? n.screenName : nodeId;
    }

    /** 持久化到文件 */
    public void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("startedAt", startedAt);
            root.put("totalActions", totalActions);
            root.put("currentId", currentId != null ? currentId : "");

            JSONArray nodesArr = new JSONArray();
            for (UiNode node : nodes.values()) {
                JSONObject n = new JSONObject();
                n.put("id", node.id);
                n.put("appPackage", node.appPackage);
                n.put("appName", node.appName);
                n.put("screenName", node.screenName);
                n.put("firstSeen", node.firstSeen);
                n.put("lastSeen", node.lastSeen);
                n.put("visitCount", node.visitCount);
                nodesArr.put(n);
            }
            root.put("nodes", nodesArr);

            JSONArray edgesArr = new JSONArray();
            for (UiEdge edge : edges) {
                JSONObject e = new JSONObject();
                e.put("fromId", edge.fromId);
                e.put("toId", edge.toId);
                e.put("actionType", edge.actionType);
                e.put("elementLabel", edge.elementLabel);
                e.put("count", edge.count);
                e.put("lastTime", edge.lastTime);
                edgesArr.put(e);
            }
            root.put("edges", edgesArr);

            JSONArray actionsArr = new JSONArray();
            for (UiAction a : actions) {
                JSONObject act = new JSONObject();
                act.put("nodeId", a.nodeId);
                act.put("actionType", a.actionType);
                act.put("elementLabel", a.elementLabel.length() > 500 ? a.elementLabel.substring(0, 500) : a.elementLabel);
                act.put("viewId", a.viewId);
                act.put("timestamp", a.timestamp);
                actionsArr.put(act);
            }
            root.put("actions", actionsArr);

            File file = getFile();
            file.getParentFile().mkdirs();
            // 先写 tmp 再原子重命名
            File tmp = new File(file.getParentFile(), FILE_NAME + ".tmp");
            java.io.FileWriter fw = new java.io.FileWriter(tmp);
            fw.write(root.toString(2));
            fw.close();
            tmp.renameTo(file);
        } catch (Exception ignored) {}
    }

    /** 从文件加载 */
    private void load() {
        try {
            File file = getFile();
            if (!file.exists()) return;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            loadFromJson(sb.toString());
        } catch (Exception ignored) {}
    }

    private void loadFromJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            startedAt = root.optLong("startedAt", 0);
            totalActions = root.optInt("totalActions", 0);
            String cid = root.optString("currentId", "");
            if (!cid.isEmpty()) currentId = cid;

            JSONArray nodesArr = root.optJSONArray("nodes");
            if (nodesArr != null) {
                for (int i = 0; i < nodesArr.length(); i++) {
                    JSONObject n = nodesArr.getJSONObject(i);
                    UiNode node = new UiNode(
                        n.getString("id"),
                        n.getString("appPackage"),
                        n.getString("appName"),
                        n.getString("screenName"),
                        n.getLong("firstSeen"),
                        n.getLong("lastSeen"),
                        n.getInt("visitCount")
                    );
                    nodes.put(node.id, node);
                }
            }

            JSONArray edgesArr = root.optJSONArray("edges");
            if (edgesArr != null) {
                for (int i = 0; i < edgesArr.length(); i++) {
                    JSONObject e = edgesArr.getJSONObject(i);
                    edges.add(new UiEdge(
                        e.getString("fromId"),
                        e.getString("toId"),
                        e.optString("actionType", "CLICK"),
                        e.optString("elementLabel", ""),
                        e.optInt("count", 1),
                        e.optLong("lastTime", 0)
                    ));
                }
            }

            JSONArray actionsArr = root.optJSONArray("actions");
            if (actionsArr != null) {
                for (int i = 0; i < actionsArr.length(); i++) {
                    JSONObject a = actionsArr.getJSONObject(i);
                    actions.add(new UiAction(
                        a.getString("nodeId"),
                        a.getString("actionType"),
                        a.optString("elementLabel", ""),
                        a.optString("viewId", ""),
                        a.getLong("timestamp")
                    ));
                }
            }
        } catch (Exception ignored) {}
    }

    private File getFile() {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private String consumeLastClick() {
        String lbl = lastClickLabel;
        lastClickLabel = "";
        lastClickTime = 0L;
        return lbl;
    }

    private UiEdge findEdge(String fromId, String toId) {
        for (UiEdge e : edges) {
            if (e.fromId.equals(fromId) && e.toId.equals(toId)) return e;
        }
        return null;
    }

    public boolean isRecording() { return recording; }
    public int getTotalActions() { return totalActions; }

    /** 泛控件 ID 检测：无中文，看起来像资源名 */
    private boolean isGenericId(String label) {
        if (label == null || label.isEmpty()) return true;
        for (char c : label.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) return false;
        }
        return true;
    }

    /** 获取节点最近一条 SCREEN_CONTENT（截短 120 字） */
    private String getLastScreenContentForNode(String nodeId) {
        for (int i = actions.size() - 1; i >= 0; i--) {
            UiAction a = actions.get(i);
            if ("SCREEN_CONTENT".equals(a.actionType) && nodeId.equals(a.nodeId)) {
                String label = a.elementLabel;
                return label.length() > 120 ? label.substring(0, 120) : label;
            }
        }
        return "";
    }

    public static String nodeId(String appPackage, String screenName) {
        return appPackage + "#" + screenName;
    }

    // ====== 数据类 ======

    static class UiNode {
        final String id;
        final String appPackage;
        final String appName;
        final String screenName;
        final long firstSeen;
        final long lastSeen;
        final int visitCount;

        UiNode(String id, String appPackage, String appName, String screenName,
               long firstSeen, long lastSeen, int visitCount) {
            this.id = id;
            this.appPackage = appPackage;
            this.appName = appName;
            this.screenName = screenName;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.visitCount = visitCount;
        }
    }

    static class UiEdge {
        final String fromId;
        final String toId;
        final String actionType;
        final String elementLabel;
        final int count;
        final long lastTime;

        UiEdge(String fromId, String toId, String actionType, String elementLabel, int count, long lastTime) {
            this.fromId = fromId;
            this.toId = toId;
            this.actionType = actionType;
            this.elementLabel = elementLabel;
            this.count = count;
            this.lastTime = lastTime;
        }
    }

    static class UiAction {
        final String nodeId;
        final String actionType;
        final String elementLabel;
        final String viewId;
        final long timestamp;

        UiAction(String nodeId, String actionType, String elementLabel, String viewId, long timestamp) {
            this.nodeId = nodeId;
            this.actionType = actionType;
            this.elementLabel = elementLabel;
            this.viewId = viewId;
            this.timestamp = timestamp;
        }
    }
}

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

    /** 记录屏幕内容 */
    public void onScreenContent(String content) {
        if (!recording || content.isEmpty()) return;
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

            // 最近操作时间线
            JSONArray timeline = new JSONArray();
            int start = Math.max(0, actions.size() - 50);
            for (int i = start; i < actions.size(); i++) {
                UiAction a = actions.get(i);
                JSONObject act = new JSONObject();
                act.put("type", a.actionType);
                act.put("label", a.elementLabel.length() > 200 ? a.elementLabel.substring(0, 200) : a.elementLabel);
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

    /** 最近访问的 App 列表（去重） */
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

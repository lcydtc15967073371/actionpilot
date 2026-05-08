package com.shizuku.ai;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令库已清空 - AI不再依赖内置命令，改为联网搜索查询ADB命令
 */
public class AdbCommands {

    private static final String TAG = "AdbCommands";

    private static Context appContext = null;
    private static final Map<String, List<CmdEntry>> CATEGORIES = new HashMap<>();

    public static class CmdEntry {
        String name;
        String command;
        String description;
        String example;
        boolean isCustom;

        CmdEntry(String name, String command, String description, String example, boolean isCustom) {
            this.name = name;
            this.command = command;
            this.description = description;
            this.example = example;
            this.isCustom = isCustom;
        }
    }

    /** 初始化（保留Context引用） */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
        Log.d(TAG, "命令库已清空，AI将在线查询命令");
    }

    /** 搜索命令 - 始终返回空列表 */
    public static List<CmdEntry> searchCommands(String keyword) {
        return new ArrayList<>();
    }

    /** 获取所有分类名称 - 返回空 */
    public static List<String> getCategories() {
        return new ArrayList<>();
    }

    /** 获取某个分类下的命令 - 返回空 */
    public static List<CmdEntry> getCommandsByCategory(String category) {
        return new ArrayList<>();
    }

    /** 获取所有命令文本 - 返回空字符串 */
    public static String getAllCommandsText() {
        return "命令库已清空，请通过联网搜索查询ADB命令。";
    }

    /** 给AI使用：提交web搜索结果后继续对话 */
    public static void submitWebResult(String result, AIAgent.AICallback callback) {
        // 这个方法由AIAgent自己管理，AdbCommands不再负责
    }

    /** AI学习新命令 - 不再存储 */
    public static String learnCommand(String category, String name, String command, String desc) {
        return "命令库已停用，" + name + " 不会被保存。请联网搜索获取命令。";
    }

    /** 添加备注 - 不再存储 */
    public static String updateNote(String command, String note) {
        return "命令库已停用，备注不会被保存。";
    }

    /** 打标签 - 不再存储 */
    public static String tagCommand(String command, String tag) {
        return "命令库已停用，标签不会被保存。";
    }

    public static String getNote(String command) { return ""; }
}

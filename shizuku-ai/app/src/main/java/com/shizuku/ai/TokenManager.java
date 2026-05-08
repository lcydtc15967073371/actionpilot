package com.shizuku.ai;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Token管理器 - 保存DeepSeek API Key
 */
public class TokenManager {
    private static final String PREFS_NAME = "ai_token_prefs";
    private static final String KEY_TOKEN = "deepseek_api_key";
    private static final String KEY_PROVIDER = "ai_provider";
    private static final String KEY_MODEL = "ai_model";
    private static final String KEY_ENDPOINT = "api_endpoint";

    private final SharedPreferences prefs;

    public TokenManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveToken(String token) {
        prefs.edit().putString(KEY_TOKEN, token).apply();
    }

    public String getToken() {
        String token = prefs.getString(KEY_TOKEN, "");
        return token;
    }

    public boolean hasToken() {
        return !getToken().isEmpty();
    }

    public void saveProvider(String provider) {
        prefs.edit().putString(KEY_PROVIDER, provider).apply();
    }

    public String getProvider() {
        return prefs.getString(KEY_PROVIDER, "deepseek");
    }

    public void saveModel(String model) {
        prefs.edit().putString(KEY_MODEL, model).apply();
    }

    public String getModel() {
        return prefs.getString(KEY_MODEL, "deepseek-chat");
    }

    public void saveEndpoint(String endpoint) {
        prefs.edit().putString(KEY_ENDPOINT, endpoint).apply();
    }

    public String getEndpoint() {
        return prefs.getString(KEY_ENDPOINT, "https://api.deepseek.com/v1/chat/completions");
    }

    public void clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply();
    }
}

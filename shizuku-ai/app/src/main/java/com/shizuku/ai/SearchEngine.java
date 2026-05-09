package com.shizuku.ai;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 搜索引擎 - 使用 OkHttp，模仿 Operit 的 WebVisit 实现
 * 三引擎：DuckDuckGo HTML → Bing → Baidu
 */
public class SearchEngine {

    private static final String TAG = "SearchEngine";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36";

    // 屏蔽的域名：这些站点的链接不会出现在结果中
    private static final String[] BLOCKED_DOMAINS = {
        "zhihu.com", "www.zhihu.com"
    };

    private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build();

    /** 三引擎搜索，返回结构化结果 */
    public static String search(String keyword) {
        String result = searchDuckDuckGo(keyword);
        if (isEmpty(result)) result = searchBing(keyword);
        if (isEmpty(result)) result = searchBaidu(keyword);
        return result != null ? result : "";
    }

    private static String searchDuckDuckGo(String keyword) {
        try {
            String html = httpGet("https://html.duckduckgo.com/html/?q=" + encode(keyword));
            if (html == null || html.isEmpty()) return null;

            StringBuilder results = new StringBuilder();
            int count = 0, pos = 0;

            while (count < 8 && pos < html.length()) {
                int aStart = html.indexOf("class=\"result__a\"", pos);
                if (aStart < 0) break;

                int hrefStart = html.indexOf("href=\"", aStart);
                int hrefEnd = hrefStart > 0 ? html.indexOf("\"", hrefStart + 6) : -1;
                String link = (hrefStart > 0 && hrefEnd > hrefStart) ? html.substring(hrefStart + 6, hrefEnd) : "";

                int tagStart = html.indexOf(">", aStart);
                int tagEnd = tagStart > 0 ? html.indexOf("</a>", tagStart) : -1;
                String title = (tagStart > 0 && tagEnd > tagStart) ? html.substring(tagStart + 1, tagEnd).trim() : "";
                if (title.isEmpty()) { pos = tagEnd + 1; continue; }
                if (isBlocked(link)) { pos = tagEnd + 1; continue; }

                int sStart = html.indexOf("class=\"result__snippet\"", tagEnd);
                String snippet = "";
                if (sStart > 0 && sStart < tagEnd + 2000) {
                    int sTagStart = html.indexOf(">", sStart);
                    int sTagEnd = sTagStart > 0 ? html.indexOf("</", sTagStart) : -1;
                    snippet = (sTagStart > 0 && sTagEnd > sTagStart) ? html.substring(sTagStart + 1, sTagEnd).trim() : "";
                }

                count++;
                results.append("[").append(count).append("] ").append(title).append("\n");
                if (link.length() > 0) results.append("    ").append(link).append("\n");
                if (snippet.length() > 0) results.append("    ").append(snippet).append("\n\n");
                pos = tagEnd + 1;
            }
            return results.length() > 0 ? results.toString().trim() : null;
        } catch (Exception e) {
            Log.e(TAG, "DDG error", e);
            return null;
        }
    }

    private static String searchBing(String keyword) {
        try {
            String html = httpGet("https://www.bing.com/search?q=" + encode(keyword) + "&count=10");
            if (html == null || html.isEmpty()) return null;

            StringBuilder results = new StringBuilder();
            int count = 0, pos = 0;

            while (count < 10 && pos < html.length()) {
                int algoStart = html.indexOf("class=\"b_algo\"", pos);
                if (algoStart < 0) break;
                int liStart = html.lastIndexOf("<li", algoStart);
                if (liStart < 0 || liStart < pos - 100) { pos = algoStart + 12; continue; }
                int liEnd = html.indexOf("</li>", algoStart);
                if (liEnd < 0) break;

                String block = html.substring(liStart, liEnd + 5);

                int h2Start = block.indexOf("<h2");
                int aStart2 = h2Start > 0 ? block.indexOf("<a ", h2Start) : -1;
                int hrefStart2 = aStart2 > 0 ? block.indexOf("href=\"", aStart2) : -1;
                String link = "";
                if (hrefStart2 > 0) {
                    int hrefEnd2 = block.indexOf("\"", hrefStart2 + 6);
                    link = block.substring(hrefStart2 + 6, hrefEnd2);
                }
                int aTagStart2 = aStart2 > 0 ? block.indexOf(">", aStart2) : -1;
                int aTagEnd2 = aTagStart2 > 0 ? block.indexOf("</a>", aTagStart2) : -1;
                String title = (aTagStart2 > 0 && aTagEnd2 > aTagStart2)
                    ? block.substring(aTagStart2 + 1, aTagEnd2).replaceAll("<[^>]+>", "").trim() : "";

                if (title.length() > 1 && !isBlocked(link)) {
                    count++;
                    results.append("[").append(count).append("] ").append(title).append("\n");
                    if (link.length() > 0) results.append("    ").append(link).append("\n");
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
            Log.e(TAG, "Bing error", e);
            return null;
        }
    }

    private static String searchBaidu(String keyword) {
        try {
            String html = httpGet("https://www.baidu.com/s?wd=" + encode(keyword) + "&rn=10");
            if (html == null || html.isEmpty()) return null;

            StringBuilder results = new StringBuilder();
            int count = 0, pos = 0;

            while (count < 10 && pos < html.length()) {
                int rStart = html.indexOf("class=\"c-container\"", pos);
                if (rStart < 0) { rStart = html.indexOf("class=\"result\"", pos); if (rStart < 0) break; }
                int divStart = html.lastIndexOf("<div", rStart);
                if (divStart < 0 || divStart < pos - 200) { pos = rStart + 12; continue; }

                int depth = 1, dpos = divStart + 5;
                while (depth > 0 && dpos < html.length()) {
                    int nOpen = html.indexOf("<div", dpos);
                    int nClose = html.indexOf("</div>", dpos);
                    if (nClose < 0) break;
                    if (nOpen >= 0 && nOpen < nClose) { depth++; dpos = nOpen + 5; }
                    else { depth--; dpos = nClose + 6; }
                }
                if (depth != 0) break;
                String block = html.substring(divStart, dpos);

                int hrefStart = block.indexOf("href=\"");
                int hrefEnd = hrefStart > 0 ? block.indexOf("\"", hrefStart + 6) : -1;
                String link = (hrefStart > 0 && hrefEnd > hrefStart) ? block.substring(hrefStart + 6, hrefEnd) : "";

                int tStart = block.indexOf("class=\"t\"");
                if (tStart < 0) tStart = block.indexOf("class=\"c-title-text\"");
                int aTS = tStart > 0 ? block.indexOf(">", tStart) : -1;
                int aTE = aTS > 0 ? block.indexOf("</a>", aTS) : -1;
                String title = (aTS > 0 && aTE > aTS) ? block.substring(aTS + 1, aTE).replaceAll("<[^>]+>", "").trim() : "";

                if (title.length() > 1 && !isBlocked(link)) {
                    count++;
                    results.append("[").append(count).append("] ").append(title).append("\n");
                    if (link.length() > 0) results.append("    ").append(link).append("\n");
                }
                pos = dpos + 1;
            }
            return results.length() > 0 ? results.toString().trim() : null;
        } catch (Exception e) {
            Log.e(TAG, "Baidu error", e);
            return null;
        }
    }

    private static String httpGet(String url) throws Exception {
        Request req = new Request.Builder().url(url).header("User-Agent", UA).build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) return null;
            String body = resp.body() != null ? resp.body().string() : "";
            return body.length() > 100 ? body : null;
        }
    }

    /** 检查链接是否被屏蔽 */
    private static boolean isBlocked(String link) {
        if (link == null || link.isEmpty()) return false;
        String lower = link.toLowerCase();
        for (String domain : BLOCKED_DOMAINS) {
            if (lower.contains(domain)) return true;
        }
        return false;
    }

    private static String encode(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}

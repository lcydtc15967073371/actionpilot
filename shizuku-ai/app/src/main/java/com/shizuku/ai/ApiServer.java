package com.shizuku.ai;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight HTTP API server for remote debugging via ADB port forwarding.
 * Listens on 127.0.0.1:8765, JSON-only responses.
 * Thread-per-connection model.
 */
public class ApiServer {

    private static final String TAG = "ApiServer";
    private static final int DEFAULT_PORT = 8765;

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running = false;
    private Thread serverThread;

    /** 请求处理器：method, path, query, body → JSON response string */
    public interface Handler {
        String handle(String method, String path, String query, String body);
    }

    private final Handler handler;

    public ApiServer(Handler handler) {
        this.handler = handler;
    }

    /** 在后台线程启动 HTTP 服务（非阻塞） */
    public void start() {
        start(DEFAULT_PORT);
    }

    public void start(int port) {
        if (running) return;
        try {
            serverSocket = new ServerSocket(port, 10, InetAddress.getByName("127.0.0.1"));
            running = true;
            pool = Executors.newCachedThreadPool();
            serverThread = new Thread(this::acceptLoop, "api-server");
            serverThread.setDaemon(true);
            serverThread.start();
            Log.d(TAG, "API server started on 127.0.0.1:" + port);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start API server: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (pool != null) pool.shutdownNow();
        if (serverThread != null) serverThread.interrupt();
        Log.d(TAG, "API server stopped");
    }

    public boolean isRunning() { return running; }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                pool.execute(() -> handleClient(client));
            } catch (Exception e) {
                if (running) Log.e(TAG, "Accept error: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            OutputStream out = client.getOutputStream();

            // 解析请求行: METHOD PATH HTTP/1.1
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) { client.close(); return; }

            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 2) { client.close(); return; }

            String method = parts[0];
            String fullPath = parts[1];

            // 分离 path 和 query
            String path = fullPath;
            String query = "";
            int qIdx = fullPath.indexOf('?');
            if (qIdx >= 0) {
                path = fullPath.substring(0, qIdx);
                query = fullPath.substring(qIdx + 1);
            }

            // 读取 headers
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.length() > 16 && line.substring(0, 15).equalsIgnoreCase("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            // 读取 body
            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[Math.min(contentLength, 65536)];
                int totalRead = 0;
                while (totalRead < contentLength && totalRead < buf.length) {
                    int read = reader.read(buf, totalRead, Math.min(buf.length - totalRead, contentLength - totalRead));
                    if (read < 0) break;
                    totalRead += read;
                }
                if (totalRead > 0) body = new String(buf, 0, totalRead);
            }

            // URL decode query
            if (query != null && !query.isEmpty()) {
                try { query = URLDecoder.decode(query, "UTF-8"); } catch (Exception ignored) {}
            }

            // 交给业务处理
            String response = handler.handle(method, path, query, body);
            if (response == null) {
                response = "{\"ok\":false,\"error\":\"handler returned null\"}";
            }

            // 发送 HTTP 响应
            byte[] respBytes = response.getBytes("UTF-8");
            String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + respBytes.length + "\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "\r\n";
            out.write(header.getBytes("UTF-8"));
            out.write(respBytes);
            out.flush();

            client.close();
        } catch (Exception e) {
            Log.e(TAG, "Handle client error: " + e.getMessage());
            try { client.close(); } catch (Exception ignored) {}
        }
    }
}

package com.shizuku.ai;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Shell执行器 - 封装ShizukuShell，提供异步执行回调
 */
public class ShizukuShellExecutor {

    private static final String TAG = "ShellExecutor";

    public interface ShellCallback {
        void onResult(String output, int exitCode, long elapsedMs);
        void onError(String error);
    }

    /** 异步执行shell命令（直接使用 ShizukuShell.exec 的底层方法） */
    public void execute(final String command, final ShellCallback callback) {
        new Thread(() -> {
            try {
                long startTime = System.currentTimeMillis();
                ShellResult result = ShizukuShell.exec(command);
                long elapsed = System.currentTimeMillis() - startTime;

                if (result.exitCode == -1 && result.output.startsWith("错误")) {
                    postError(callback, result.output);
                    return;
                }

                postResult(callback, result.output, result.exitCode, elapsed);

            } catch (Exception e) {
                Log.e(TAG, "执行失败", e);
                postError(callback, "执行错误: " + e.getMessage());
            }
        }).start();
    }

    private void postResult(final ShellCallback callback, final String output, final int exitCode, final long elapsed) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (callback != null) callback.onResult(output, exitCode, elapsed);
        });
    }

    private void postError(final ShellCallback callback, final String error) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (callback != null) callback.onError(error);
        });
    }
}

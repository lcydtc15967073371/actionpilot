package com.shizuku.ai;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;

public class ShizukuShell {
    private static Method newProcessMethod;
    private static Class<?> shizukuClass;

    /**
     * 检查 Shizuku 是否可用（服务是否运行）
     */
    public static boolean isAvailable() {
        try {
            if (shizukuClass == null) {
                shizukuClass = Class.forName("rikka.shizuku.Shizuku");
            }
            Method ping = shizukuClass.getMethod("ping");
            return (boolean) ping.invoke(null);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查是否已获得 Shizuku 权限
     */
    public static boolean isGranted() {
        try {
            if (shizukuClass == null) {
                shizukuClass = Class.forName("rikka.shizuku.Shizuku");
            }
            Method csp = shizukuClass.getMethod("checkSelfPermission");
            return (int) csp.invoke(null) == 0; // 0 = GRANTED
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 请求 Shizuku 权限
     * @param requestCode 请求码，用于回调识别
     */
    public static void requestPermission(int requestCode) {
        try {
            if (shizukuClass == null) {
                shizukuClass = Class.forName("rikka.shizuku.Shizuku");
            }
            Method rp = shizukuClass.getMethod("requestPermission", int.class);
            rp.invoke(null, requestCode);
        } catch (Exception ignored) {
        }
    }

    /**
     * 反射获取 Shizuku.newProcess("sh") 的 Process 对象
     */
    private static Process getShizukuProcess() throws Exception {
        if (shizukuClass == null) {
            shizukuClass = Class.forName("rikka.shizuku.Shizuku");
        }
        if (newProcessMethod == null) {
            newProcessMethod = shizukuClass.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            newProcessMethod.setAccessible(true);
        }
        return (Process) newProcessMethod.invoke(null, new String[]{"sh"}, null, null);
    }

    /**
     * 执行 shell 命令并返回结果（核心底层方法）
     *
     * @param cmd 要执行的 shell 命令
     * @return ShellResult 对象，包含 exitCode 和 output
     */
    public static ShellResult exec(String cmd) {
        Process process = null;
        OutputStream os = null;
        InputStream is = null;
        BufferedReader reader = null;

        try {
            // 1. 通过 Shizuku 获取 shell 进程
            process = getShizukuProcess();
            if (process == null) {
                return new ShellResult(-1, "错误：无法获取 Shizuku shell 进程");
            }

            // 2. 获取输出流（写入命令）和输入流（读取结果）
            os = process.getOutputStream();
            is = process.getInputStream();
            reader = new BufferedReader(new InputStreamReader(is));

            // 3. 写入命令 + 退出标记
            String fullCmd = cmd + "\necho \"__SHIZUKU_EXIT__:$?\"\n";
            os.write(fullCmd.getBytes());
            os.flush();

            // 关掉 stdin，让 shell 收到 EOF 后退出，否则读 stderr 会死锁
            os.close();

            // 4. 读取输出
            StringBuilder output = new StringBuilder();
            String line;
            int exitCode = 0;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("__SHIZUKU_EXIT__:")) {
                    exitCode = Integer.parseInt(line.substring("__SHIZUKU_EXIT__:".length()).trim());
                    break;
                }
                output.append(line).append("\n");
            }

            // 5. 清理输出末尾多余的换行
            String result = output.toString().trim();

            // 5b. 读取 stderr（如有）
            InputStream es = process.getErrorStream();
            BufferedReader errReader = new BufferedReader(new InputStreamReader(es));
            StringBuilder errOutput = new StringBuilder();
            String errLine;
            while ((errLine = errReader.readLine()) != null) {
                errOutput.append(errLine).append("\n");
            }
            errReader.close();
            if (errOutput.length() > 0) {
                String errStr = errOutput.toString().trim();
                if (!errStr.isEmpty()) {
                    result = result.isEmpty() ? errStr : result + "\n[stderr]\n" + errStr;
                }
            }

            // 处理特殊返回值
            if (exitCode == 0 && result.isEmpty()) {
                // am force-stop 等命令成功时输出为空，返回友好提示
                return new ShellResult(0, "执行成功（无输出）");
            }

            // 处理 Warning: Activity not started（说明应用已在运行，不算错误）
            if (result.contains("Warning: Activity not started")) {
                return new ShellResult(0, result + "\n（应用已在运行）");
            }

            return new ShellResult(exitCode, result);

        } catch (Exception e) {
            return new ShellResult(-1, "执行异常: " + e.getMessage());
        } finally {
            // 6. 关闭资源
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            try { if (is != null) is.close(); } catch (Exception ignored) {}
            try { if (os != null) os.close(); } catch (Exception ignored) {}
            if (process != null) {
                try { process.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 简化版：只输出结果字符串（少数核心非重要场景用）
     */
    public static String execSimple(String cmd) {
        ShellResult r = exec(cmd);
        return r.exitCode == 0 ? r.output : "失败(" + r.exitCode + "): " + r.output;
    }

    /**
     * 通过 Shizuku 读取文件的原始字节（二进制安全）
     * 用于 app 无权直接访问的路径（如 /data/local/tmp/）
     *
     * 先用 stat 获取文件大小，再读精确字节数，避免二进制内容中有标记冲突
     */
    public static byte[] readFileBytes(String path) throws Exception {
        // 1. 先获取文件大小
        ShellResult sizeResult = exec("stat -c %s \"" + path.replace("\"", "\\\"") + "\"");
        if (sizeResult.exitCode != 0) {
            throw new Exception("无法获取文件大小: " + sizeResult.output);
        }
        long fileSize = Long.parseLong(sizeResult.output.trim());

        // 2. 通过 Shizuku 进程 cat 文件
        Process process = getShizukuProcess();
        if (process == null) {
            throw new Exception("无法获取 Shizuku shell 进程");
        }

        OutputStream os = process.getOutputStream();
        InputStream is = process.getInputStream();

        String cmd = "cat \"" + path.replace("\"", "\\\"") + "\"\n";
        os.write(cmd.getBytes());
        os.flush();
        // 不关 stdin，后续读取精确字节数后关闭
        os.close();

        // 3. 读取精确字节数
        ByteArrayOutputStream buffer = new ByteArrayOutputStream((int) fileSize);
        byte[] readBuf = new byte[8192];
        long remaining = fileSize;
        int n;
        while (remaining > 0 && (n = is.read(readBuf, 0, (int) Math.min(readBuf.length, remaining))) != -1) {
            buffer.write(readBuf, 0, n);
            remaining -= n;
        }

        is.close();
        process.destroy();

        return buffer.toByteArray();
    }
}

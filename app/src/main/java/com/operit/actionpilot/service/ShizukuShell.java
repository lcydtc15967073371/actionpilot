package com.operit.actionpilot.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;

/**
 * Shizuku shell helper — executes shell commands via Shizuku's private newProcess().
 * Must be Java because Kotlin blocks access to private methods.
 */
public class ShizukuShell {

    private static Method checkSelfPermissionMethod;
    private static Method newProcessMethod;
    private static Object shizukuClass;

    private static Object getShizukuClass() throws Exception {
        if (shizukuClass == null) {
            shizukuClass = Class.forName("rikka.shizuku.Shizuku");
        }
        return shizukuClass;
    }

    private static Method getMethod(String name, Class<?>... paramTypes) throws Exception {
        Class<?> clz = (Class<?>) getShizukuClass();
        Method m = clz.getDeclaredMethod(name, paramTypes);
        m.setAccessible(true);
        return m;
    }

    /**
     * Check if Shizuku permission is granted (does NOT call newProcess).
     * Uses Shizuku.checkSelfPermission() which is safe to call without permission.
     */
    public static boolean isPermissionGranted() {
        try {
            Method m = getMethod("checkSelfPermission");
            int result = (int) m.invoke(null);
            return result == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if Shizuku is fully available (permission granted + can create process).
     */
    public static boolean isAvailable() {
        if (!isPermissionGranted()) return false;
        // Verify by trying to create a process
        try {
            Process proc = createProcess();
            if (proc == null) return false;
            proc.destroy();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Process createProcess() throws Exception {
        if (newProcessMethod == null) {
            newProcessMethod = getMethod("newProcess",
                    String[].class, String[].class, String.class);
        }
        return (Process) newProcessMethod.invoke(null,
                new String[]{"sh"}, null, null);
    }

    /**
     * Execute a shell command via Shizuku.
     * Returns stdout output.
     */
    public static String exec(String cmd) {
        try {
            Process proc = createProcess();
            if (proc == null) return "";

            PrintWriter writer = new PrintWriter(new OutputStreamWriter(proc.getOutputStream()));
            writer.println(cmd);
            writer.println("echo __EXIT__:$?");
            writer.flush();
            writer.close();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("__EXIT__:")) break;
                out.append(line).append("\n");
            }
            proc.waitFor();
            String result = out.toString().trim();
            return result;
        } catch (Exception e) {
            return "";
        }
    }
}

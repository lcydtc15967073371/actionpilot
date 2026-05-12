package com.shizuku.ai;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

import rikka.shizuku.ShizukuBinderWrapper;

/**
 * 通过 Shizuku Binder 提权静默安装 APK（不弹确认框）
 * <p>
 * 原理：通过 ShizukuBinderWrapper 包装 system_service 的 Binder，
 * 使 IPackageInstaller 及 IPackageInstallerSession 调用以 shell UID 身份执行。
 */
public class SilentInstaller {

    private static final String TAG = "SilentInstaller";
    private static volatile boolean bypassAttempted = false;

    private final Context context;

    public SilentInstaller(Context context) {
        this.context = context;
    }

    /** 绕过 Android 9+ 隐藏 API 限制 */
    private static void ensureHiddenApiAccess() {
        if (bypassAttempted) return;
        bypassAttempted = true;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        try {
            Class<?> cls = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass");
            cls.getMethod("addHiddenApiExemptions", String[].class)
                    .invoke(null, new Object[]{new String[]{"L"}});
            Log.d(TAG, "Hidden API bypass enabled");
        } catch (Exception e) {
            Log.w(TAG, "Hidden API bypass failed", e);
        }
    }

    /**
     * 静默安装 APK（全部通过 Shizuku Binder 提权）
     */
    public String install(File apkFile) {
        ensureHiddenApiAccess();

        if (!apkFile.exists()) {
            return "错误: APK 文件不存在: " + apkFile.getAbsolutePath();
        }

        try {
            // 1. 创建 SessionParams（公共 API）
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);

            // 2. 通过 Shizuku 获取提权后的 IPackageInstaller
            Object ipi = getPrivilegedPackageInstaller();
            if (ipi == null) {
                return "错误: 无法获取 IPackageInstaller (Shizuku 未就绪?)";
            }

            // 3. createSession() — 通过 Shizuku（shell UID）
            // Android 14+: createSession(SessionParams, String, String, int)
            // 旧版本: createSession(SessionParams, String, int)
            Method createSession;
            try {
                createSession = ipi.getClass().getMethod("createSession",
                        PackageInstaller.SessionParams.class, String.class, String.class, int.class);
            } catch (NoSuchMethodException e) {
                createSession = ipi.getClass().getMethod("createSession",
                        PackageInstaller.SessionParams.class, String.class, int.class);
            }
            int sessionId;
            if (createSession.getParameterCount() == 4) {
                sessionId = (int) createSession.invoke(ipi, params,
                        context.getPackageName(), null, 0);
            } else {
                sessionId = (int) createSession.invoke(ipi, params,
                        context.getPackageName(), 0);
            }
            Log.d(TAG, "Session created: " + sessionId);

            // 4. openSession() — 也通过 Shizuku
            Method openSession = ipi.getClass().getMethod("openSession", int.class);
            Object rawSession = openSession.invoke(ipi, sessionId);

            // 5. 包装 IPackageInstallerSession 的 Binder — 后续所有操作也走 Shizuku
            Method asBinderSess = rawSession.getClass().getMethod("asBinder");
            IBinder sessBinder = (IBinder) asBinderSess.invoke(rawSession);
            IBinder wrappedSess = new ShizukuBinderWrapper(sessBinder);
            Class<?> ipisStub = Class.forName("android.content.pm.IPackageInstallerSession$Stub");
            Method asInterfaceSess = ipisStub.getMethod("asInterface", IBinder.class);
            Object ipiSession = asInterfaceSess.invoke(null, wrappedSess);

            // 6. 先将 APK 复制到 app cache（如需要），然后通过 IPackageInstallerSession.write() 传入
            //    避免 openWrite() 返回的 PFD 经 Shizuku Binder 后无法正确关闭的问题
            long sizeBytes = apkFile.length();

            // 6a. 如果 apkFile 不在 cache 目录，先复制
            if (!apkFile.getAbsolutePath().startsWith(context.getCacheDir().getAbsolutePath())) {
                File tempForWrite = new File(context.getCacheDir(), "install_payload.apk");
                byte[] fileBytes = com.shizuku.ai.ShizukuShell.readFileBytes(apkFile.getAbsolutePath());
                FileOutputStream fos = new FileOutputStream(tempForWrite);
                try { fos.write(fileBytes); } finally { fos.close(); }
                apkFile = tempForWrite;
                Log.d(TAG, "Copied to cache for write(): " + apkFile.length() + " bytes");
            }

            // 6b. 创建本地的 PFD（不走 Shizuku）
            ParcelFileDescriptor localFd = ParcelFileDescriptor.open(apkFile,
                    ParcelFileDescriptor.MODE_READ_ONLY);
            try {
                // 6c. 尝试 write() 方法 — session 读取 PFD 数据，不残留 fd 跟踪
                Method writeMethod;
                try {
                    writeMethod = ipiSession.getClass().getMethod("write",
                            String.class, long.class, long.class, ParcelFileDescriptor.class);
                    writeMethod.invoke(ipiSession, "base.apk", 0L, sizeBytes, localFd);
                    Log.d(TAG, "write() via Shizuku OK");
                } catch (NoSuchMethodException e) {
                    // fallback: openWrite + 直接写入
                    Log.w(TAG, "write() not found, fallback to openWrite");
                    Method openWrite = ipiSession.getClass().getMethod("openWrite",
                            String.class, long.class, long.class);
                    Object pfd = openWrite.invoke(ipiSession, "base.apk", 0L, sizeBytes);
                    ParcelFileDescriptor fd = (ParcelFileDescriptor) pfd;
                    ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
                    try {
                        byte[] buf = new byte[65536];
                        InputStream in2 = new FileInputStream(apkFile);
                        int totalRead = 0;
                        try { int n; while ((n = in2.read(buf)) >= 0) { out.write(buf, 0, n); totalRead += n; } }
                        finally { in2.close(); }
                        out.flush();
                        Log.d(TAG, "Written " + totalRead + " bytes via openWrite");
                    } finally { out.close(); }
                    fd.close();
                }
            } finally {
                localFd.close();
            }

            // 8. commit() via reflection — 走 Shizuku
            Intent intent = new Intent(context, InstallReceiver.class);
            intent.setAction("com.shizuku.ai.INSTALL_RESULT");
            android.app.PendingIntent pendingIntent = android.app.PendingIntent.getBroadcast(
                    context, sessionId, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE
                            | android.app.PendingIntent.FLAG_UPDATE_CURRENT);

            android.content.IntentSender sender = pendingIntent.getIntentSender();
            Method commit;
            try {
                commit = ipiSession.getClass().getMethod("commit",
                        android.content.IntentSender.class, boolean.class);
                commit.invoke(ipiSession, sender, false);
            } catch (NoSuchMethodException e) {
                commit = ipiSession.getClass().getMethod("commit",
                        android.content.IntentSender.class);
                commit.invoke(ipiSession, sender);
            }
            Log.d(TAG, "Session committed: " + sessionId);

            // 10. close()
            Method close = ipiSession.getClass().getMethod("close");
            close.invoke(ipiSession);

            return "安装已提交 (session=" + sessionId + ")，稍后检查结果";

        } catch (Exception e) {
            Log.e(TAG, "安装失败", e);
            // 收集完整异常信息（含 InvocationTargetException 的 cause）
            StringBuilder sb = new StringBuilder();
            sb.append(e.getClass().getName()).append(": ").append(e.getMessage());
            Throwable cause = e.getCause();
            if (cause != null) {
                sb.append("\nCaused by: ").append(cause.getClass().getName()).append(": ").append(cause.getMessage());
            }
            for (StackTraceElement el : e.getStackTrace()) {
                sb.append("\n  at ").append(el.toString());
            }
            return "安装失败: " + sb.toString();
        }
    }

    /**
     * 通过 ShizukuBinderWrapper 获取提权后的 IPackageInstaller 对象
     */
    private Object getPrivilegedPackageInstaller() throws Exception {
        // 1. ServiceManager.getService("package")
        Class<?> smClass = Class.forName("android.os.ServiceManager");
        Method getService = smClass.getDeclaredMethod("getService", String.class);
        IBinder pmBinder = (IBinder) getService.invoke(null, "package");

        // 2. ShizukuBinderWrapper 包装 → 调用走 Shizuku 提权
        IBinder wrappedPm = new ShizukuBinderWrapper(pmBinder);

        // 3. IPackageManager.Stub.asInterface()
        Class<?> ipmStubClass = Class.forName("android.content.pm.IPackageManager$Stub");
        Method asInterface = ipmStubClass.getDeclaredMethod("asInterface", IBinder.class);
        Object ipm = asInterface.invoke(null, wrappedPm);

        // 4. IPackageManager.getPackageInstaller()
        Method getPkgInstaller = ipm.getClass().getMethod("getPackageInstaller");
        Object pkgInstaller = getPkgInstaller.invoke(ipm);

        // 5. 取底层 Binder → 再包装
        Method asBinder = pkgInstaller.getClass().getMethod("asBinder");
        IBinder installerBinder = (IBinder) asBinder.invoke(pkgInstaller);
        IBinder wrappedInstaller = new ShizukuBinderWrapper(installerBinder);

        // 6. IPackageInstaller.Stub.asInterface()
        Class<?> ipiStubClass = Class.forName("android.content.pm.IPackageInstaller$Stub");
        Method asInterface2 = ipiStubClass.getDeclaredMethod("asInterface", IBinder.class);
        return asInterface2.invoke(null, wrappedInstaller);
    }
}

package com.shizuku.ai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 接收 PackageInstaller.commit() 的结果回调
 */
public class InstallReceiver extends BroadcastReceiver {

    private static final String TAG = "InstallReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS,
                android.content.pm.PackageInstaller.STATUS_FAILURE);
        String packageName = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_PACKAGE_NAME);

        if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
            Log.d(TAG, "安装成功: " + packageName);
        } else {
            String message = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
            Log.e(TAG, "安装失败: " + packageName + " status=" + status + " msg=" + message);
        }
    }
}

package com.operit.actionpilot.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.operit.actionpilot.ActionPilotApp
import com.operit.actionpilot.MainActivity
import com.operit.actionpilot.recorder.MapBuilder
import kotlinx.coroutines.*

class RecordService : Service() {

    companion object {
        const val TAG = "ActionPilot"
        const val CHANNEL_ID = "actionpilot_recording"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.operit.actionpilot.START_RECORDING"
        const val ACTION_STOP = "com.operit.actionpilot.STOP_RECORDING"

        var mapBuilder: MapBuilder? = null
        var isRunning: Boolean = false
            set
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null
    private var lastFocus: String = ""
    private var notified = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        android.util.Log.e(TAG, "handleStart called, isRunning=$isRunning")
        if (isRunning) return

        // Must call startForeground within 5 seconds on Android 14+
        startForeground(NOTIF_ID, createNotification("Initializing..."))
        notified = true

        Log.e(TAG, "Step 1: startForeground done")

        val perm = ShizukuShell.isPermissionGranted()
        Log.e(TAG, "Step 2: permission check = $perm")

        if (!perm) {
            Log.e(TAG, "Shizuku permission not granted")
            updateNotification("Shizuku not authorized")
            stopSelf()
            return
        }

        Log.e(TAG, "Step 3: creating MapBuilder")
        val builder = MapBuilder()
        mapBuilder = builder
        builder.start()
        isRunning = true

        updateNotification("Recording via Shizuku...")
        startPolling(builder)
        Log.e(TAG, "Recording started")
    }

    private fun handleStop() {
        pollingJob?.cancel()
        pollingJob = null
        val map = mapBuilder?.stop()
        if (map != null && map.nodes.isNotEmpty()) {
            try {
                val repo = (application as ActionPilotApp).repository
                repo.save(map)
                Log.e(TAG, "Saved ${map.nodes.size} nodes, ${map.edges.size} edges")
            } catch (e: Exception) {
                Log.e(TAG, "Save failed: ${e.message}")
            }
        } else {
            Log.e(TAG, "No nodes captured, map=${map != null}, nodes=${map?.nodes?.size}")
        }
        mapBuilder = null
        isRunning = false
        lastFocus = ""
        if (notified) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notified = false
        }
        stopSelf()
        Log.e(TAG, "Recording stopped")
    }

    private fun startPolling(builder: MapBuilder) {
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    val output = ShizukuShell.exec("dumpsys window | grep mCurrentFocus")
                    Log.e(TAG, "dumpsys raw: ${output.take(200)}")
                    val focus = parseFocus(output)
                    Log.e(TAG, "parsed focus: '$focus'")
                    if (focus.isNotEmpty() && focus != lastFocus) {
                        lastFocus = focus
                        val parts = focus.split("/")
                        if (parts.size >= 2) {
                            val pkg = parts[0]
                            val activity = parts[1].substringAfterLast('.')
                            builder.onWindowChanged(pkg, pkg, activity)
                            Log.e(TAG, "Window detected: $pkg/$activity")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error: ${e.message}")
                }
                delay(500)
            }
        }
    }

    private fun parseFocus(output: String): String {
        val lines = output.lines().filter { it.contains("mCurrentFocus=") && !it.contains("null") }
        if (lines.isEmpty()) return ""
        val line = lines.first()
        // Extract content inside braces: Window{hash u0 pkg/activity type=N}
        val braceOpen = line.indexOf('{')
        val braceClose = line.lastIndexOf('}')
        if (braceOpen < 0 || braceClose <= braceOpen) return ""
        val inside = line.substring(braceOpen + 1, braceClose)
        // inside format: "hash u0 pkg/activity type=N"
        val parts = inside.split(" ")
        // Find "u0" index, the next token is pkg/activity
        val u0Idx = parts.indexOfFirst { it == "u0" }
        if (u0Idx < 0 || u0Idx + 1 >= parts.size) return ""
        // pkg/activity is parts[u0Idx+1], but there might be "type=N" after it
        // Extract up to the part that starts with "type="
        val pkgActivity = parts.drop(u0Idx + 1).takeWhile { !it.startsWith("type=") }.joinToString(" ")
        return pkgActivity
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, createNotification(text))
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "ActionPilot Recording",
            NotificationManager.IMPORTANCE_LOW)
        ch.description = "Recording app operations via Shizuku"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun createNotification(text: String): android.app.Notification {
        val stopIntent = Intent(this, RecordService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(this, 1, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ActionPilot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        if (notified) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notified = false
        }
        super.onDestroy()
    }
}

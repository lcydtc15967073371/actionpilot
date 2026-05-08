package com.operit.actionpilot

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.operit.actionpilot.service.RecordService
import com.operit.actionpilot.ui.MainScreen
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    companion object {
        const val SHIZUKU_REQUEST_CODE = 1001
        const val TAG = "ActionPilot"
    }

    private var shizukuReady = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        shizukuReady = true
        requestShizukuPermission()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        shizukuReady = false
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            Log.d(TAG, "Shizuku permission: ${if (grantResult == 0) "GRANTED" else "DENIED"}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register Shizuku lifecycle listeners
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        // If Shizuku is already connected, request permission
        if (Shizuku.pingBinder()) {
            shizukuReady = true
            requestShizukuPermission()
        }

        // Handle intent to start recording
        handleIntent(intent)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    private fun requestShizukuPermission() {
        if (Shizuku.checkSelfPermission() != 0) {
            Log.d(TAG, "Requesting Shizuku permission...")
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } else {
            Log.d(TAG, "Shizuku permission already granted")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "com.operit.actionpilot.START_RECORDING") {
            val serviceIntent = Intent(this, RecordService::class.java).apply {
                action = RecordService.ACTION_START
            }
            startForegroundService(serviceIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
    }
}

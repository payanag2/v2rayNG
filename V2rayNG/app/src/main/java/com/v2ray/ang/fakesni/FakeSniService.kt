package com.v2ray.ang.fakesni

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.v2ray.ang.R
import com.v2ray.ang.root.RootManager
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Runs the standalone FakeSNI binary as one stable local TCP proxy. */
class FakeSniService : Service() {
    companion object {
        const val ACTION_START = "com.v2ray.ang.fakesni.START"
        const val ACTION_STOP = "com.v2ray.ang.fakesni.STOP"
        private const val CHANNEL_ID = "integrated_fakesni"
        private const val NOTIFICATION_ID = 10043
        private const val BINARY_NAME = "sni-spoofing"
        private const val ARM64_ASSET = "sni-spoofing-arm64"
        private const val ARM7_ASSET = "sni-spoofing-arm7"

        fun start(context: android.content.Context) {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, FakeSniService::class.java).setAction(ACTION_START))
        }
        fun stop(context: android.content.Context) {
            context.startService(Intent(context, FakeSniService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var process: Process? = null
    @Volatile private var starting = false
    @Volatile private var running = false

    override fun onCreate() { super.onCreate(); createNotificationChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startOnce()
            ACTION_STOP -> stopNow()
        }
        return START_NOT_STICKY
    }

    private fun startOnce() {
        val prefs = FakeSniPreferences(this)
        if (!prefs.enabled) { stopSelf(); return }
        if (running || starting) { log("Ignoring duplicate FakeSNI start"); return }
        starting = true
        startForeground(NOTIFICATION_ID, notification("Starting FakeSNI…"))
        scope.launch {
            try { startProxy() } finally { starting = false }
        }
    }

    private suspend fun startProxy() {
        if (!RootManager.isRootAvailable()) {
            updateNotification("FakeSNI requires root access")
            return
        }
        val prefs = FakeSniPreferences(this)
        if (prefs.connectIp.isBlank() || prefs.connectPort !in 1..65535) {
            updateNotification("Invalid Connect IP or Port")
            return
        }
        if (prefs.listenPort !in 1024..65535) {
            updateNotification("Invalid Listen Port")
            return
        }

        stopBinaryOnly()
        val binary = extractBinary() ?: return
        val args = buildString {
            append("'${binary.absolutePath}'")
            append(" -listen '127.0.0.1:${prefs.listenPort}'")
            append(" -connect '${shellEscape(prefs.connectIp)}:${prefs.connectPort}'")
            append(" -fake-sni '${shellEscape(prefs.fakeSniHostname)}'")
            append(" -utls '${shellEscape(prefs.utls)}'")
            append(" -injector ${shellEscape(prefs.injector)}")
            append(" -fake-repeat ${prefs.fakeRepeat}")
            append(" -fake-delay ${shellEscape(prefs.fakeDelay)}")
            append(" -ack-timeout ${shellEscape(prefs.ackTimeout)}")
            append(" -enable-fragment=${prefs.enableFragment}")
            append(" -fragment-delay ${shellEscape(prefs.fragmentDelay)}")
            append(" -sni-chunk ${prefs.sniChunk}")
        }

        log("FakeSNI local: 127.0.0.1:${prefs.listenPort}")
        log("Connect: ${prefs.connectIp}:${prefs.connectPort}")
        log("Fake SNI: ${prefs.fakeSniHostname}")
        updateNotification("FakeSNI active: ${prefs.fakeSniHostname} → ${prefs.connectIp}:${prefs.connectPort}")

        val script = File(filesDir, "fakesni-launch.sh")
        script.writeText("#!/system/bin/sh\nchmod 755 '${binary.absolutePath}'\nexec $args\n")
        script.setExecutable(true)
        try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", "sh '${script.absolutePath}'"))
            running = true
        } catch (e: Exception) {
            log("FakeSNI start failed: ${e.message}")
            updateNotification("Could not start FakeSNI")
            return
        }

        val p = process
        scope.launch { p?.errorStream?.bufferedReader()?.forEachLine { log(it) } }
        scope.launch { p?.inputStream?.bufferedReader()?.forEachLine { log(it) } }
        scope.launch {
            if (p != null) runCatching { p.waitFor() }
            if (process === p) {
                process = null
                running = false
                updateNotification("FakeSNI stopped")
            }
        }
    }

    private fun extractBinary(): File? {
        val asset = when {
            Build.SUPPORTED_ABIS.contains("arm64-v8a") -> ARM64_ASSET
            Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> ARM7_ASSET
            else -> null
        } ?: run { updateNotification("Unsupported CPU architecture"); return null }
        val target = File(filesDir, BINARY_NAME)
        return try {
            val assetLength = runCatching { assets.openFd(asset).use { it.length } }.getOrDefault(-1L)
            if (!target.exists() || target.length() == 0L || (assetLength > 0 && target.length() != assetLength)) {
                assets.open(asset).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            }
            Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 755 '${target.absolutePath}'")).waitFor(2, TimeUnit.SECONDS)
            target
        } catch (e: Exception) {
            log("Binary install failed: ${e.message}")
            updateNotification("FakeSNI binary install failed")
            null
        }
    }

    private fun stopBinaryOnly() {
        process?.destroy()
        process = null
        running = false
        val binaryPath = File(filesDir, BINARY_NAME).absolutePath
        try { Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -TERM -f '$binaryPath' 2>/dev/null || true")).waitFor(2, TimeUnit.SECONDS) } catch (_: Exception) { }
    }

    private fun stopNow() {
        starting = false
        stopBinaryOnly()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shellEscape(value: String) = value.replace("'", "'\\''")
    private fun log(message: String) = android.util.Log.i("IntegratedFakeSNI", message)
    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "FakeSNI", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_name).setContentTitle("FakeSNI").setContentText(text).setOngoing(true).setSilent(true).build()
    private fun updateNotification(text: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }
    override fun onDestroy() { stopNow(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

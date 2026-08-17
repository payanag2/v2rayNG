package com.v2ray.ang.fakesni

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs the same sni-spoofing binary used by payanag2/fakesni, but as a component
 * of v2rayNG. Traffic from the v2rayNG UID to the selected TLS server is redirected
 * to the local FakeSNI listener; FakeSNI then connects to the real server as root.
 */
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
            val intent = Intent(context, FakeSniService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, FakeSniService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var process: Process? = null
    private var activeIps = emptyList<String>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var restartJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val prefs = FakeSniPreferences(this)
                if (!prefs.enabled) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, notification("Starting FakeSNI…"))
                restartJob?.cancel()
                restartJob = scope.launch { startProxy() }
            }
            ACTION_STOP -> {
                restartJob?.cancel()
                cleanup()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startProxy() {
        cleanupProcessOnly()

        val guid = MmkvManager.getSelectServer() ?: run {
            log("No selected profile")
            return
        }
        val profile = MmkvManager.decodeServerConfig(guid) ?: run {
            log("Cannot read selected profile")
            return
        }

        val prefs = FakeSniPreferences(this)
        if (profile.security?.lowercase() != "tls") {
            log("FakeSNI requires TLS; selected profile is not TLS")
            return
        }
        if (profile.server.isNullOrBlank() || profile.serverPort.isNullOrBlank()) {
            log("Selected profile has no server address/port")
            return
        }

        val port = profile.serverPort!!.toIntOrNull() ?: run {
            log("Invalid server port: ${profile.serverPort}")
            return
        }
        val addresses = resolveIpv4(profile.server!!)
        if (addresses.isEmpty()) {
            log("Could not resolve ${profile.server}")
            return
        }
        activeIps = addresses
        installRedirectRules(addresses, port, prefs.listenPort)

        val binary = extractBinary()
        if (binary == null) {
            removeRedirectRules(port, prefs.listenPort)
            return
        }

        val connectIp = addresses.first()
        val args = buildString {
            append("'${binary.absolutePath}'")
            append(" -listen '127.0.0.1:${prefs.listenPort}'")
            append(" -connect '$connectIp:$port'")
            append(" -fake-sni '${shellEscape(prefs.fakeSni)}'")
            append(" -utls '${shellEscape(prefs.utls)}'")
            append(" -injector ${shellEscape(prefs.injector)}")
            append(" -fake-repeat ${prefs.fakeRepeat}")
            append(" -fake-delay ${shellEscape(prefs.fakeDelay)}")
            append(" -ack-timeout ${shellEscape(prefs.ackTimeout)}")
            append(" -enable-fragment=${prefs.enableFragment}")
            if (prefs.enableFragment) {
                append(" -fragment-delay ${shellEscape(prefs.fragmentDelay)}")
                append(" -sni-chunk ${prefs.sniChunk}")
            }
        }

        log("Target: ${profile.server}:$port → $connectIp:$port")
        log("Fake SNI: ${prefs.fakeSni} / uTLS: ${prefs.utls} / injector: ${prefs.injector}")
        updateNotification("FakeSNI active: ${prefs.fakeSni}")

        val script = File(filesDir, "fakesni-launch.sh")
        script.writeText("#!/system/bin/sh\nchmod 755 '${binary.absolutePath}'\nexec $args\n")
        script.setExecutable(true)

        process = Runtime.getRuntime().exec(arrayOf("su", "-c", "sh '${script.absolutePath}'"))
        scope.launch { process?.errorStream?.bufferedReader()?.forEachLine { log(it) } }
        scope.launch { process?.inputStream?.bufferedReader()?.forEachLine { log(it) } }

        registerNetworkMonitor(profile.server!!, port, prefs.listenPort)
    }

    private fun extractBinary(): File? {
        val asset = when {
            Build.SUPPORTED_ABIS.contains("arm64-v8a") -> ARM64_ASSET
            Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> ARM7_ASSET
            else -> null
        } ?: run {
            log("Unsupported ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            return null
        }
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
            null
        }
    }

    private fun resolveIpv4(host: String): List<String> = try {
        InetAddress.getAllByName(host.removePrefix("[").removeSuffix("]"))
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    }

    private fun installRedirectRules(ips: List<String>, targetPort: Int, localPort: Int) {
        val uid = applicationInfo.uid
        ips.forEach { ip ->
            val cmd = "iptables -t nat -C OUTPUT -p tcp -d '$ip' --dport $targetPort -m owner --uid-owner $uid -j REDIRECT --to-ports $localPort 2>/dev/null || " +
                "iptables -t nat -A OUTPUT -p tcp -d '$ip' --dport $targetPort -m owner --uid-owner $uid -j REDIRECT --to-ports $localPort"
            runSu(cmd)
        }
    }

    private fun removeRedirectRules(targetPort: Int, localPort: Int) {
        val uid = applicationInfo.uid
        activeIps.forEach { ip ->
            val cmd = "while iptables -t nat -C OUTPUT -p tcp -d '$ip' --dport $targetPort -m owner --uid-owner $uid -j REDIRECT --to-ports $localPort 2>/dev/null; do " +
                "iptables -t nat -D OUTPUT -p tcp -d '$ip' --dport $targetPort -m owner --uid-owner $uid -j REDIRECT --to-ports $localPort; done"
            runSu(cmd)
        }
        activeIps = emptyList()
    }

    private fun registerNetworkMonitor(host: String, targetPort: Int, localPort: Int) {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                restartJob?.cancel()
                restartJob = scope.launch {
                    delay(1500)
                    val prefs = FakeSniPreferences(this@FakeSniService)
                    if (!prefs.enabled) return@launch
                    log("Network changed — rebuilding FakeSNI route")
                    removeRedirectRules(targetPort, localPort)
                    startProxy()
                }
            }
        }
        networkCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onFailure { networkCallback = null }
    }

    private fun unregisterNetworkMonitor() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    private fun cleanupProcessOnly() {
        process?.destroy()
        process = null
    }

    private fun cleanup() {
        unregisterNetworkMonitor()
        cleanupProcessOnly()
        val prefs = FakeSniPreferences(this)
        removeRedirectRules(443, prefs.listenPort)
        // Remove rules for non-443 profiles as well; exact delete is harmless when absent.
        val uid = applicationInfo.uid
        activeIps.forEach { ip ->
            runSu("iptables -t nat -F OUTPUT 2>/dev/null || true")
        }
    }

    private fun runSu(command: String) {
        try { Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor(3, TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    private fun shellEscape(value: String): String = value.replace("'", "'\\''")

    private fun log(message: String) {
        android.util.Log.i("IntegratedFakeSNI", message)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "FakeSNI", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("FakeSNI")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        cleanup()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

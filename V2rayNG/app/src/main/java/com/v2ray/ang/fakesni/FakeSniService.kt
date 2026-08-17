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
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.root.RootManager
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Integrated runner for the rooted sni-spoofing binary from payanag2/fakesni. */
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
    private var activeIps = emptyList<String>()
    private var activeTargetPort: Int? = null
    private var activeLocalPort: Int? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var restartJob: Job? = null

    override fun onCreate() { super.onCreate(); createNotificationChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val prefs = FakeSniPreferences(this)
                if (!prefs.enabled) { stopSelf(); return START_NOT_STICKY }
                startForeground(NOTIFICATION_ID, notification("Checking root access…"))
                restartJob?.cancel()
                restartJob = scope.launch { startProxy() }
            }
            ACTION_STOP -> {
                restartJob?.cancel(); cleanup(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startProxy() {
        cleanupProcessOnly(); removeActiveRedirectRules()
        if (!RootManager.isRootAvailable()) {
            log("Root is required for integrated FakeSNI")
            updateNotification("FakeSNI requires root access")
            return
        }

        val guid = MmkvManager.getSelectServer() ?: run { log("No selected profile"); return }
        val profile = MmkvManager.decodeServerConfig(guid) ?: run { log("Cannot read selected profile"); return }
        val prefs = FakeSniPreferences(this)
        if (profile.security?.lowercase() != "tls") {
            log("FakeSNI requires TLS; selected profile is not TLS")
            updateNotification("FakeSNI requires a TLS profile")
            return
        }
        val host = profile.server?.takeIf { it.isNotBlank() } ?: run { log("Selected profile has no server address"); return }
        val port = profile.serverPort?.toIntOrNull() ?: run { log("Invalid server port: ${profile.serverPort}"); return }

        val connectIp = prefs.connectIp.trim()
        val connectPort = prefs.connectPort
        if (connectIp.isEmpty()) {
            log("Connect IP is empty")
            updateNotification("Connect IP is required")
            return
        }
        if (connectPort !in 1..65535) {
            log("Invalid Connect Port: $connectPort")
            updateNotification("Invalid Connect Port")
            return
        }

        val addresses = resolveIpv4(host)
        if (addresses.isEmpty()) {
            log("Could not resolve $host")
            updateNotification("Could not resolve server")
            return
        }

        activeIps = addresses; activeTargetPort = port; activeLocalPort = prefs.listenPort
        installRedirectRules(addresses, port, prefs.listenPort)
        val binary = extractBinary() ?: run { removeActiveRedirectRules(); return }

        // Keep the command-line contract identical to standalone FakeSNI:
        // listen, connect, fake-sni, utls, injector and the remaining tuning flags.
        val args = buildString {
            append("'${binary.absolutePath}'")
            append(" -listen '127.0.0.1:${prefs.listenPort}'")
            append(" -connect '${shellEscape(connectIp)}:${prefs.connectPort}'")
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

        log("Redirect: $host:$port → 127.0.0.1:${prefs.listenPort}")
        log("Connect: $connectIp:${prefs.connectPort}")
        log("Fake SNI: ${prefs.fakeSniHostname} / uTLS: ${prefs.utls} / injector: ${prefs.injector}")
        updateNotification("FakeSNI: ${prefs.fakeSniHostname} → $connectIp:${prefs.connectPort}")

        val script = File(filesDir, "fakesni-launch.sh")
        script.writeText("#!/system/bin/sh\nchmod 755 '${binary.absolutePath}'\nexec $args\n")
        script.setExecutable(true)
        process = Runtime.getRuntime().exec(arrayOf("su", "-c", "sh '${script.absolutePath}'"))
        scope.launch { process?.errorStream?.bufferedReader()?.forEachLine { log(it) } }
        scope.launch { process?.inputStream?.bufferedReader()?.forEachLine { log(it) } }
        registerNetworkMonitor()
    }

    private fun extractBinary(): File? {
        val asset = when {
            Build.SUPPORTED_ABIS.contains("arm64-v8a") -> ARM64_ASSET
            Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> ARM7_ASSET
            else -> null
        } ?: run { log("Unsupported ABI: ${Build.SUPPORTED_ABIS.joinToString()}"); return null }
        val target = File(filesDir, BINARY_NAME)
        return try {
            val assetLength = runCatching { assets.openFd(asset).use { it.length } }.getOrDefault(-1L)
            if (!target.exists() || target.length() == 0L || (assetLength > 0 && target.length() != assetLength)) {
                assets.open(asset).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            }
            Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 755 '${target.absolutePath}'")).waitFor(2, TimeUnit.SECONDS)
            target
        } catch (e: Exception) { log("Binary install failed: ${e.message}"); null }
    }

    private fun resolveIpv4(host: String): List<String> = try {
        InetAddress.getAllByName(host.removePrefix("[").removeSuffix("]")).filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.distinct()
    } catch (_: Exception) { emptyList() }

    private fun installRedirectRules(ips: List<String>, targetPort: Int, localPort: Int) {
        val uid = applicationInfo.uid
        ips.forEach { ip ->
            val rule = "-p tcp -d '$ip' --dport $targetPort -m owner --uid-owner $uid -j REDIRECT --to-ports $localPort"
            runSu("iptables -t nat -C OUTPUT $rule 2>/dev/null || iptables -t nat -A OUTPUT $rule")
        }
    }

    private fun removeActiveRedirectRules() {
        val targetPort = activeTargetPort ?: return
        val localPort = activeLocalPort ?: return
        val uid = applicationInfo.uid
        activeIps.forEach { ip ->
            val rule = "-p tcp -d '$ip' --dport $targetPort -m owner --uid-owner $uid -j REDIRECT --to-ports $localPort"
            runSu("while iptables -t nat -C OUTPUT $rule 2>/dev/null; do iptables -t nat -D OUTPUT $rule; done")
        }
        activeIps = emptyList(); activeTargetPort = null; activeLocalPort = null
    }

    private fun registerNetworkMonitor() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                restartJob?.cancel()
                restartJob = scope.launch {
                    delay(1500)
                    if (!FakeSniPreferences(this@FakeSniService).enabled) return@launch
                    log("Network changed — rebuilding FakeSNI route")
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
        process?.destroy(); process = null
        val binaryPath = File(filesDir, BINARY_NAME).absolutePath
        runSu("pkill -TERM -f '$binaryPath' 2>/dev/null || true")
    }

    private fun cleanup() { unregisterNetworkMonitor(); restartJob?.cancel(); cleanupProcessOnly(); removeActiveRedirectRules() }
    private fun runSu(command: String) { try { Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor(3, TimeUnit.SECONDS) } catch (_: Exception) {} }
    private fun shellEscape(value: String): String = value.replace("'", "'\\''")
    private fun log(message: String) = android.util.Log.i("IntegratedFakeSNI", message)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "FakeSNI", NotificationManager.IMPORTANCE_LOW))
        }
    }
    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_name).setContentTitle("FakeSNI").setContentText(text).setOngoing(true).setSilent(true).build()
    private fun updateNotification(text: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }
    override fun onDestroy() { cleanup(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

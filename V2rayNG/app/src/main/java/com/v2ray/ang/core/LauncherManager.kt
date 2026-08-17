package com.v2ray.ang.core

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.fakesni.FakeSniPreferences
import com.v2ray.ang.fakesni.FakeSniService
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.service.CoreProxyOnlyService
import com.v2ray.ang.service.CoreRootService
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

object LauncherManager {
    fun startServiceFromToggle(context: Context): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        try { startContextService(context) } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: ${e.message}", e)
            context.toast(e.message ?: e.javaClass.simpleName)
            return false
        }
        return true
    }

    fun startService(context: Context, guid: String? = null) {
        LogUtil.i(AppConfig.TAG, "LauncherManager: startService from ${context::class.java.simpleName}")
        if (guid != null) MmkvManager.setSelectServer(guid)
        try { startContextService(context) } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: ${e.message}", e)
            context.toast(e.message ?: e.javaClass.simpleName)
        }
    }

    fun stopService(context: Context) {
        FakeSniService.stop(context)
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    fun restartService(context: Context) {
        if (!SettingsManager.isRootMode() && FakeSniPreferences(context).enabled) {
            FakeSniService.start(context)
        }
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_RESTART, "")
    }

    fun restartServiceOrStart(context: Context, startIfStopped: () -> Unit) {
        MessageHelper.sendMsg2ServiceForResult(context, AppConfig.MSG_STATE_RESTART, "") { handled ->
            if (!handled) startIfStopped()
            else if (!SettingsManager.isRootMode() && FakeSniPreferences(context).enabled) FakeSniService.start(context)
        }
    }

    @Throws(Exception::class)
    private fun startContextService(context: Context) {
        val guid = MmkvManager.getSelectServer() ?: run {
            LogUtil.e(AppConfig.TAG, "LauncherManager: No server selected")
            error(context.getString(R.string.app_tile_first_use))
        }
        val config = MmkvManager.decodeServerConfig(guid) ?: run {
            LogUtil.e(AppConfig.TAG, "LauncherManager: Failed to decode server config")
            error(context.getString(R.string.toast_config_file_invalid))
        }

        if (!config.configType.isComplexType() && !Utils.isValidUrl(config.server) && !Utils.isPureIpAddress(config.server.orEmpty())) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: Invalid server configuration")
            error(context.getString(R.string.toast_config_file_invalid))
        }

        SettingsManager.refreshRuntimeSocksPort()
        if (config.insecure == true && config.pinnedCA256.isNullOrEmpty()) {
            context.toastError(R.string.toast_allow_insecure_deprecated)
            Utils.setClipboard(context, context.getString(R.string.toast_allow_insecure_deprecated))
        }
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) context.toast(R.string.toast_warning_pref_proxysharing_short)
        else context.toast(R.string.toast_services_start)

        val isRootMode = SettingsManager.isRootMode()
        val fakeSniEnabled = FakeSniPreferences(context).enabled

        if (isRootMode && !RootManager.isRootAvailable()) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: root mode requires root but none available")
            error(context.getString(R.string.toast_root_required))
        }

        // Integrated FakeSNI itself requires root, but Xray must NOT run in v2rayNG's
        // root mode: the REDIRECT rule identifies Xray traffic by v2rayNG's app UID.
        if (fakeSniEnabled) {
            if (!RootManager.isRootAvailable()) {
                LogUtil.e(AppConfig.TAG, "LauncherManager: integrated FakeSNI requires root")
                error("FakeSNI requires root access")
            }
            if (isRootMode) {
                LogUtil.e(AppConfig.TAG, "LauncherManager: FakeSNI is incompatible with Xray root mode")
                error("FakeSNI requires VPN/Proxy mode, not Xray Root mode")
            }
            LogUtil.i(AppConfig.TAG, "LauncherManager: starting integrated FakeSNI with root")
            FakeSniService.start(context)
        }

        val intent = if (isRootMode) {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting Root service")
            Intent(context.applicationContext, CoreRootService::class.java)
        } else if (SettingsManager.isVpnMode()) {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting VPN service")
            Intent(context.applicationContext, CoreVpnService::class.java)
        } else {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting Proxy service")
            Intent(context.applicationContext, CoreProxyOnlyService::class.java)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: Missing permission to start foreground service", e)
            throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
        } catch (e: RuntimeException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException") {
                LogUtil.e(AppConfig.TAG, "LauncherManager: Foreground service start not allowed", e)
                throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
            }
            throw e
        }
    }
}

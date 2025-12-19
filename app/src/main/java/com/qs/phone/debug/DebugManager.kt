package com.qs.phone.debug

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 无线调试管理器
 * 负责管理 USB 调试和无线调试的启用/禁用
 */
class DebugManager(private val context: Context) {
    companion object {
        private const val TAG = "DebugManager"
    }

    /**
     * 检查是否有安全设置权限
     */
    fun hasSecureSettingsPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查 USB 调试是否启用
     */
    fun isUSBDebuggingEnabled(): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查无线调试是否启用
     */
    fun isWirelessDebuggingEnabled(): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查移动数据是否始终开启
     */
    fun isMobileDataAlwaysOnEnabled(): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, "mobile_data_always_on", 0) == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 禁用移动数据始终开启
     */
    fun disableMobileDataAlwaysOn() {
        if (!hasSecureSettingsPermission()) {
            Log.w(TAG, "No secure settings permission to disable mobile data always on")
            return
        }

        if (isMobileDataAlwaysOnEnabled()) {
            Log.d(TAG, "Disabling 'Mobile data always on'...")
            Settings.Global.putInt(
                context.contentResolver,
                "mobile_data_always_on",
                0
            )
            Thread.sleep(3_000)
        }
    }

    /**
     * 启用 USB 调试（Android 10 及以下）
     */
    fun enableUSBDebugging(): Boolean {
        if (!hasSecureSettingsPermission()) {
            Log.w(TAG, "No secure settings permission to enable USB debugging")
            return false
        }

        if (!isUSBDebuggingEnabled()) {
            Log.d(TAG, "Turning on USB debugging...")
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                1
            )
            Thread.sleep(5_000)
        }
        return true
    }

    /**
     * 循环切换无线调试（Android 11+）
     * 这个操作会重新启动无线调试服务，用于发现端口
     */
    fun cycleWirelessDebugging(): Boolean {
        if (!hasSecureSettingsPermission() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Cannot cycle wireless debugging: insufficient permissions or wrong Android version")
            return false
        }

        Log.d(TAG, "Cycling wireless debugging, please wait...")

        // Only turn it off if it's already on
        if (isWirelessDebuggingEnabled()) {
            Log.d(TAG, "Turning off wireless debugging...")
            Settings.Global.putInt(
                context.contentResolver,
                "adb_wifi_enabled",
                0
            )
            Thread.sleep(3_000)
        }

        Log.d(TAG, "Turning on wireless debugging...")
        Settings.Global.putInt(
            context.contentResolver,
            "adb_wifi_enabled",
            1
        )
        Thread.sleep(3_000)

        Log.d(TAG, "Turning off wireless debugging...")
        Settings.Global.putInt(
            context.contentResolver,
            "adb_wifi_enabled",
            0
        )
        Thread.sleep(3_000)

        Log.d(TAG, "Turning on wireless debugging...")
        Settings.Global.putInt(
            context.contentResolver,
            "adb_wifi_enabled",
            1
        )
        Thread.sleep(3_000)

        return true
    }

    /**
     * 等待 USB 调试启用
     */
    fun waitForUSBDebugging(timeoutSeconds: Int = 30): Boolean {
        if (isUSBDebuggingEnabled()) return true

        var waitCount = 0
        while (!isUSBDebuggingEnabled() && waitCount < timeoutSeconds) {
            Thread.sleep(1_000)
            waitCount++
        }

        return isUSBDebuggingEnabled()
    }

    /**
     * 等待无线调试启用
     */
    fun waitForWirelessDebugging(timeoutSeconds: Int = 30): Boolean {
        if (isWirelessDebuggingEnabled()) return true

        var waitCount = 0
        while (!isWirelessDebuggingEnabled() && waitCount < timeoutSeconds) {
            Thread.sleep(1_000)
            waitCount++
        }

        return isWirelessDebuggingEnabled()
    }
}
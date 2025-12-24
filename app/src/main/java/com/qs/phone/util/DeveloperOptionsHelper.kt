package com.qs.phone.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.qs.phone.shell.ShellExecutor

object DeveloperOptionsHelper {

    /**
     * 打开开发者选项设置页面
     * @param activity 当前活动
     * @param onSuccess 成功打开后的回调
     * @param onError 打开失败后的回调
     */
    fun openDeveloperOptionsSettings(
        activity: Activity,
        onSuccess: (() -> Unit)? = null,
        onError: (() -> Unit)? = null
    ) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // 兼容部分设备的包名映射
                setPackage("com.android.settings")
            }
            // 检查设备是否支持该 Intent（避免崩溃）
            if (intent.resolveActivity(activity.packageManager) != null) {
                activity.startActivity(intent)
                Toast.makeText(
                    activity,
                    "请在设置中开启「开发者选项」和「USB调试」",
                    Toast.LENGTH_LONG
                ).show()
                onSuccess?.invoke()
            } else {
                // 备选方案：打开设置主页面
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(fallbackIntent)
                Toast.makeText(activity, "请在设置中查找「开发者选项」", Toast.LENGTH_LONG).show()
                onSuccess?.invoke()
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "无法打开设置页面", Toast.LENGTH_SHORT).show()
            onError?.invoke()
        }
    }

    /**
     * 检查开发者选项是否启用，如果未启用则显示引导对话框
     * @param activity 当前活动
     * @param onDeveloperOptionsEnabled 开发者选项已启用的回调
     * @param onCancelled 用户取消操作的回调
     */
    fun checkAndGuideDeveloperOptions(
        activity: Activity,
        onDeveloperOptionsEnabled: () -> Unit,
        onCancelled: (() -> Unit)? = null
    ) {
        // 第一步：检查开发者选项是否开启
        val shellExecutor = ShellExecutor(activity)
        val developerOptionsEnabled = shellExecutor.checkUSBDebuggingEnabled()
        val wirelessDebuggingEnabled = shellExecutor.checkWirelessDebuggingEnabled()

        if (!developerOptionsEnabled && !wirelessDebuggingEnabled) {
            // 开发者选项未开启
            AlertDialog.Builder(activity)
                .setTitle("需要开启开发者选项")
                .setMessage("为了使用 ADB 调试功能，需要先开启开发者选项。\n\n请在接下来的设置页面中：\n1. 连续点击「版本号」7次开启开发者选项\n2. 返回上一层开启「USB调试」或「无线调试」")
                .setPositiveButton("去开启") { _, _ ->
                    openDeveloperOptionsSettings(activity)
                }
                .setNegativeButton("取消") { _, _ ->
                    onCancelled?.invoke()
                }
                .show()
        } else {
            // 开发者选项已启用，执行回调
            onDeveloperOptionsEnabled()
        }
    }

    /**
     * 检查开发者选项是否启用
     * @return Pair<Boolean, Boolean> - 第一个值表示USB调试是否启用，第二个值表示无线调试是否启用
     */
    fun checkDeveloperOptionsStatus(activity: Activity): Pair<Boolean, Boolean> {
        val shellExecutor = ShellExecutor(activity)
        return Pair(
            shellExecutor.checkUSBDebuggingEnabled(),
            shellExecutor.checkWirelessDebuggingEnabled()
        )
    }
}
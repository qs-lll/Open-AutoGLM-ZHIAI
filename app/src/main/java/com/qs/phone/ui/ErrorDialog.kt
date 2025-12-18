package com.qs.phone.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 错误对话框工具类
 */
object ErrorDialog {

    /**
     * 显示 LADB 不可用错误对话框
     */
    fun showLadbNotAvailable(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("内置 LADB 不可用")
            .setMessage(
                "内置 ADB 库加载失败。这通常由以下原因造成：\n\n" +
                "1. 应用未正确安装\n" +
                "2. 设备架构不兼容\n" +
                "3. 系统阻止了原生库加载\n\n" +
                "解决方案：\n" +
                "• 重新安装应用\n" +
                "• 检查设备架构（ARM64/ARM/x86/x86_64）\n" +
                "• 查看应用权限设置\n\n" +
                "是否前往 LADB 项目页面了解详情？"
            )
            .setPositiveButton("前往 LADB") { _, _ ->
                openLadbPage(context)
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("打开开发者选项") { _, _ ->
                openDeveloperSettings(context)
            }
            .show()
    }

    /**
     * 显示设备未连接错误对话框
     */
    fun showDeviceNotConnected(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("设备未连接")
            .setMessage(
                "未检测到 ADB 设备。请检查：\n\n" +
                "• 是否已开启无线调试（Android 11+）\n" +
                "• 是否已开启 USB 调试（Android 10 及以下）\n" +
                "• USB 线是否连接正常（USB 调试）\n" +
                "• 是否已授权此电脑调试"
            )
            .setPositiveButton("打开开发者选项") { _, _ ->
                openDeveloperSettings(context)
            }
            .setNegativeButton("确定", null)
            .show()
    }

    /**
     * 显示权限不足错误对话框
     */
    fun showPermissionDenied(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("权限不足")
            .setMessage(
                "当前权限不足以执行 ADB 操作。请确保：\n\n" +
                "• 已开启无障碍服务\n" +
                "• 已授权所有必要权限\n" +
                "• LADB 已正确授权"
            )
            .setPositiveButton("打开无障碍服务") { _, _ ->
                openAccessibilitySettings(context)
            }
            .setNegativeButton("确定", null)
            .show()
    }

    /**
     * 打开 LADB 项目页面
     */
    private fun openLadbPage(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tytydraco/LADB"))
            context.startActivity(intent)
        } catch (e: Exception) {
            // 忽略错误
        }
    }

    /**
     * 打开开发者选项
     */
    private fun openDeveloperSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:${context.packageName}")
            context.startActivity(intent)
        } catch (e: Exception) {
            // 忽略错误
        }
    }

    /**
     * 打开无障碍服务设置
     */
    private fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            context.startActivity(intent)
        } catch (e: Exception) {
            // 忽略错误
        }
    }
}

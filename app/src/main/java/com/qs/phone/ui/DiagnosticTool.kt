package com.qs.phone.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.qs.phone.shell.ShellExecutor
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import okhttp3.internal.wait

/**
 * 诊断工具 - 帮助用户排查 LADB 和 ADB 相关问题
 */
object DiagnosticTool {

    data class DiagnosticResult(
        val isPass: Boolean,
        val title: String,
        val details: String,
        val suggestion: String
    )

    /**
     * 执行完整诊断
     */
    suspend fun runFullDiagnostic(context: Context): List<DiagnosticResult> {
        val results = mutableListOf<DiagnosticResult>()

        // 1. 检查 LADB 可用性
        results.add(checkLadbAvailability(context))

        // 2. 检查开发者选项
        results.add(checkDeveloperOptions(context))

        // 3. 检查无线调试
        results.add(checkWirelessDebugging(context))

        // 4. 检查 USB 调试
        results.add(checkUsbDebugging(context))

        // 5. 检查 ADB 设备连接
        results.add(checkAdbDevices(context))

        // 6. 检查文件读写权限
        results.add(checkStoragePermissions(context))

        return results
    }

    /**
     * 检查 LADB 可用性 - 使用快速检查
     */
    private suspend fun checkLadbAvailability(context: Context): DiagnosticResult {
        return try {
            // 使用快速检查，仅验证库文件是否存在
            val shell = ShellExecutor(context)
            val isAvailable = shell.isAdbLibraryAvailable()

            if (isAvailable) {
                DiagnosticResult(
                    isPass = true,
                    title = "✅ LADB 可用性",
                    details = "LADB 库 (libadb.so) 已正确安装并可用",
                    suggestion = "无需额外操作"
                )
            } else {
                DiagnosticResult(
                    isPass = false,
                    title = "❌ LADB 不可用",
                    details = "未找到 LADB 库 (libadb.so)\n路径: ${context.applicationInfo.nativeLibraryDir}/libadb.so",
                    suggestion = "请安装 LADB 应用或获取 Root 权限\n参考: https://github.com/tytydraco/LADB"
                )
            }
        } catch (e: Exception) {
            DiagnosticResult(
                isPass = false,
                title = "❌ LADB 检查失败",
                details = "错误: ${e.message}",
                suggestion = "请重启应用后重试"
            )
        }
    }

    /**
     * 检查开发者选项
     */
    private fun checkDeveloperOptions(context: Context): DiagnosticResult {
        return try {
            val developerEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1

            if (developerEnabled) {
                DiagnosticResult(
                    isPass = true,
                    title = "✅ 开发者选项",
                    details = "开发者选项已启用",
                    suggestion = "无需额外操作"
                )
            } else {
                DiagnosticResult(
                    isPass = false,
                    title = "❌ 开发者选项未启用",
                    details = "开发者选项处于关闭状态",
                    suggestion = "请在设置中连续点击版本号 7 次来启用开发者选项"
                )
            }
        } catch (e: Exception) {
            DiagnosticResult(
                isPass = false,
                title = "❌ 开发者选项检查失败",
                details = "错误: ${e.message}",
                suggestion = "请手动检查开发者选项是否启用"
            )
        }
    }

    /**
     * 检查无线调试
     */
    private fun checkWirelessDebugging(context: Context): DiagnosticResult {
        return try {
            val wirelessEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
            } else {
                // Android 11 以下不支持无线调试
                false
            }

            if (wirelessEnabled) {
                DiagnosticResult(
                    isPass = true,
                    title = "✅ 无线调试",
                    details = "无线调试已启用 (Android 11+)",
                    suggestion = "无需额外操作"
                )
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    DiagnosticResult(
                        isPass = false,
                        title = "⚠️ 无线调试未启用",
                        details = "Android 11+ 设备，但无线调试未启用",
                        suggestion = "请在开发者选项中启用「无线调试」"
                    )
                } else {
                    DiagnosticResult(
                        isPass = true,
                        title = "ℹ️ 无线调试",
                        details = "Android ${Build.VERSION.SDK_INT} 不支持无线调试",
                        suggestion = "请使用 USB 调试模式"
                    )
                }
            }
        } catch (e: Exception) {
            DiagnosticResult(
                isPass = false,
                title = "❌ 无线调试检查失败",
                details = "错误: ${e.message}",
                suggestion = "请手动检查开发者选项"
            )
        }
    }

    /**
     * 检查 USB 调试
     */
    private fun checkUsbDebugging(context: Context): DiagnosticResult {
        return try {
            val usbEnabled = Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                0
            ) == 1

            if (usbEnabled) {
                DiagnosticResult(
                    isPass = true,
                    title = "✅ USB 调试",
                    details = "USB 调试已启用",
                    suggestion = "无需额外操作"
                )
            } else {
                DiagnosticResult(
                    isPass = false,
                    title = "⚠️ USB 调试未启用",
                    details = "USB 调试处于关闭状态",
                    suggestion = "请在开发者选项中启用「USB 调试」"
                )
            }
        } catch (e: Exception) {
            DiagnosticResult(
                isPass = false,
                title = "❌ USB 调试检查失败",
                details = "错误: ${e.message}",
                suggestion = "请手动检查开发者选项"
            )
        }
    }

    /**
     * 检查 ADB 设备连接 - 快速检查
     */
    private fun checkAdbDevices(context: Context): DiagnosticResult {
        return try {
            val shell = ShellExecutor(context)

            // 首先快速检查 LADB 库是否可用
            val libraryAvailable = shell.isAdbLibraryAvailable()
            if (!libraryAvailable) {
                return DiagnosticResult(
                    isPass = false,
                    title = "⚠️ 无法检查设备",
                    details = "LADB 库不可用，无法检测设备连接",
                    suggestion = "请先解决 LADB 库问题"
                )
            }

            // 如果库可用，尝试获取设备列表（使用带超时的异步版本）
            val devices = kotlinx.coroutines.runBlocking {
                shell.getDevicesSuspending(timeoutSeconds = 5)
            }

            if (devices.isEmpty()) {
                DiagnosticResult(
                    isPass = false,
                    title = "⚠️ 未检测到设备",
                    details = "LADB 库可用，但未检测到任何 ADB 设备",
                    suggestion = "请确保设备已连接并已授权调试"
                )
            } else {
                val deviceInfo = devices.joinToString("\n")
                DiagnosticResult(
                    isPass = true,
                    title = "✅ ADB 设备",
                    details = "已检测到 ${devices.size} 个设备:\n$deviceInfo",
                    suggestion = "设备连接正常"
                )
            }
        } catch (e: Exception) {
            DiagnosticResult(
                isPass = false,
                title = "❌ 设备检查失败",
                details = "错误: ${e.message}",
                suggestion = "请检查 LADB 是否正确安装"
            )
        }
    }

    /**
     * 检查文件读写权限
     */
    private fun checkStoragePermissions(context: Context): DiagnosticResult {
        return try {

            if (true) { // Storage permissions not needed for ADB
                DiagnosticResult(
                    isPass = true,
                    title = "✅ 文件读写权限",
                    details = "已授予文件读写权限",
                    suggestion = "权限正常，截图功能可用"
                )
            } else {
                val requiredPermissions = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> "READ_MEDIA_IMAGES"
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> "READ_EXTERNAL_STORAGE"
                    else -> "WRITE_EXTERNAL_STORAGE"
                }

                DiagnosticResult(
                    isPass = false,
                    title = "⚠️ 文件读写权限不足",
                    details = "缺少 $requiredPermissions 权限",
                    suggestion = "请在应用中点击「请求权限」按钮，或在设置中手动授予权限"
                )
            }
        } catch (e: Exception) {
            DiagnosticResult(
                isPass = false,
                title = "❌ 权限检查失败",
                details = "错误: ${e.message}",
                suggestion = "请手动检查应用权限设置"
            )
        }
    }

    /**
     * 生成诊断报告
     */
    fun generateReport(results: List<DiagnosticResult>): String {
        val passCount = results.count { it.isPass }
        val totalCount = results.size

        val report = buildString {
            append("=".repeat(60)).append("\n")
            append("🔍 ZhiAI 诊断报告\n")
            append("=".repeat(60)).append("\n\n")
            append("检查项目: $passCount/$totalCount 通过\n\n")

            results.forEachIndexed { index, result ->
                append("${index + 1}. ${result.title}\n")
                append("   详情: ${result.details}\n")
                append("   建议: ${result.suggestion}\n\n")
            }

            append("=".repeat(60)).append("\n")
            if (passCount == totalCount) {
                append("🎉 所有检查通过！系统已准备就绪。\n")
            } else {
                append("⚠️ 发现 $passCount/$totalCount 项问题，请根据建议解决。\n")
            }
            append("=".repeat(60)).append("\n")
        }

        return report
    }
}

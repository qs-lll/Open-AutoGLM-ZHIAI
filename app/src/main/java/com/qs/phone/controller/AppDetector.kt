package com.qs.phone.controller

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityWindowInfo
import com.qs.phone.config.AppPackages
import com.qs.phone.service.FloatingWindowService
import java.util.concurrent.TimeUnit

/**
 * 不使用 ADB 的应用检测器
 * 使用无障碍服务和 UsageStatsManager 来检测当前运行的应用
 */
class AppDetector(private val context: Context) {

    companion object {
        private const val TAG = "AppDetector"
        private const val USAGE_STATS_PERMISSION = "android.permission.PACKAGE_USAGE_STATS"
    }

    private val usageStatsManager by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }

    private val accessibilityManager by lazy {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    }

    /**
     * 检测当前运行的应用（不使用 ADB）
     * @return 应用名称
     */
    fun getCurrentApp(): String {
        // 方法1: 使用无障碍服务（最准确）
        getCurrentAppViaAccessibility()?.let { return it }

        // 方法2: 使用 UsageStatsManager
        getCurrentAppViaUsageStats()?.let { return it }

        // 方法3: 通过 ActivityManager 获取前台应用（需要部分权限）
        getCurrentAppViaActivityManager()?.let { return it }

        Log.w(TAG, "All detection methods failed")
        return "Unknown"
    }

    /**
     * 方法1: 通过无障碍服务获取当前应用
     */
    private fun getCurrentAppViaAccessibility(): String? {
        return try {
            val service = FloatingWindowService.instance
            if (service != null && accessibilityManager.isEnabled) {
                // 获取当前活动的窗口
                val windows = service.windows
                val focusedAppWindow = windows?.find { window ->
                    window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused
                }

                if (focusedAppWindow != null) {
                    // 从根节点获取包名
                    val rootNode = service.rootInActiveWindow
                    val packageName = rootNode?.packageName?.toString()

                    if (!packageName.isNullOrEmpty()) {
                        Log.d(TAG, "Accessibility service detected package: $packageName")
                        return mapPackageToAppName(packageName)
                    }
                } else {
                    // 如果没有找到焦点窗口，尝试从最近的事件中获取
                    return getPackageNameFromLastEvent()
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "Failed to get current app via accessibility service", e)
            null
        }
    }

    /**
     * 从最近的无障碍事件中获取包名
     */
    private fun getPackageNameFromLastEvent(): String? {
        return try {
            // 这个方法需要在 FloatingWindowService 中保存最后一个事件
            val service = FloatingWindowService.instance
            val lastEvent = service?.getLastAccessibilityEvent()

            if (lastEvent != null) {
                val packageName = lastEvent.packageName?.toString()
                if (!packageName.isNullOrEmpty()) {
                    Log.d(TAG, "Got package from last event: $packageName")
                    return mapPackageToAppName(packageName)
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "Failed to get package from last event", e)
            null
        }
    }

    /**
     * 方法2: 通过 UsageStatsManager 获取当前应用
     */
    private fun getCurrentAppViaUsageStats(): String? {
        if (!hasUsageStatsPermission()) {
            Log.d(TAG, "Usage stats permission not granted")
            return null
        }

        return try {
            val endTime = System.currentTimeMillis()
            val startTime = endTime - TimeUnit.SECONDS.toMillis(10) // 查询最近10秒的使用记录

            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            if (stats.isNotEmpty()) {
                // 找到最后使用的前台应用
                val lastUsedApp = stats
                    .filter { it.lastTimeUsed > 0 }
                    .maxByOrNull { it.lastTimeUsed }

                if (lastUsedApp != null) {
                    val packageName = lastUsedApp.packageName
                    Log.d(TAG, "UsageStats detected package: $packageName")
                    return mapPackageToAppName(packageName)
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "Failed to get current app via UsageStats", e)
            null
        }
    }

    /**
     * 方法3: 通过 ActivityManager 获取前台应用
     * 注意：这个方法在某些 Android 版本上可能受限
     */
    private fun getCurrentAppViaActivityManager(): String? {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val tasks = activityManager.getRunningTasks(1)
                if (tasks.isNotEmpty()) {
                    val topActivity = tasks[0].topActivity
                    if (topActivity != null) {
                        val packageName = topActivity.packageName
                        Log.d(TAG, "ActivityManager detected package: $packageName")
                        return mapPackageToAppName(packageName)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "Failed to get current app via ActivityManager", e)
            null
        }
    }

    /**
     * 检查是否有 Usage Stats 权限
     */
    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                context.packageName?.let { packageName ->
                    val op = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        AppOpsManager.MODE_ALLOWED
                    } else {
                        @Suppress("DEPRECATION")
                        context.packageManager.checkPermission(
                            USAGE_STATS_PERMISSION,
                            packageName
                        )
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val opMode = (context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
                            .unsafeCheckOpNoThrow(
                                AppOpsManager.OPSTR_GET_USAGE_STATS,
                                android.os.Process.myUid(),
                                packageName
                            )
                        opMode == AppOpsManager.MODE_ALLOWED
                    } else {
                        op == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                } ?: false
            } else {
                context.packageManager.checkPermission(
                    USAGE_STATS_PERMISSION,
                    context.packageName
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            mode
        } catch (e: Exception) {
            Log.d(TAG, "Error checking usage stats permission", e)
            false
        }
    }

    /**
     * 将包名映射为应用名称
     */
    private fun mapPackageToAppName(packageName: String): String {
        // 首先尝试从已知的应用映射中查找
        val appName = AppPackages.getAppName(packageName)
        if (!appName.isNullOrEmpty()) {
            return appName
        }

        // 处理系统应用
        return when {
            packageName.startsWith("com.android.") -> {
                when {
                    packageName.contains("launcher") || packageName.contains("launcher3") -> "Home"
                    packageName.contains("settings") -> "Settings"
                    packageName.contains("systemui") -> "SystemUI"
                    else -> "System"
                }
            }
            packageName.startsWith("com.google.android.") -> {
                when {
                    packageName.contains("launcher") -> "Home"
                    packageName.contains("gm") -> "Gmail"
                    packageName.contains("youtube") -> "YouTube"
                    packageName.contains("maps") -> "Maps"
                    packageName.contains("chrome") -> "Chrome"
                    else -> "Google App"
                }
            }
            packageName.contains("launcher") -> "Home"
            packageName.contains("desktop") -> "Home"
            else -> packageName // 返回包名本身
        }
    }

    /**
     * 获取应用检测方法的详细信息（用于调试）
     */
    fun getDetectionMethodsInfo(): String {
        val info = StringBuilder()
        info.append("=== App Detection Methods Status ===\n\n")

        // 无障碍服务状态
        val service = FloatingWindowService.instance
        info.append("Accessibility Service: ${if (service != null) "✅ Running" else "❌ Not running"}\n")

        // UsageStats 权限状态
        info.append("UsageStats Permission: ${if (hasUsageStatsPermission()) "✅ Granted" else "❌ Not granted"}\n")

        // 当前检测到的应用
        info.append("\nCurrent App Detection:\n")
        val currentApp = getCurrentApp()
        info.append("Result: $currentApp\n")

        info.append("\nDetection Priority:\n")
        info.append("1. Accessibility Service (most accurate)\n")
        info.append("2. UsageStats Manager (requires permission)\n")
        info.append("3. ActivityManager (limited on newer Android)\n")

        return info.toString()
    }
}
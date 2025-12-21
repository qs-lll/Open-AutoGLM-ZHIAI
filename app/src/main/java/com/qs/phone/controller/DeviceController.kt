package com.qs.phone.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.qs.phone.config.AppPackages
import com.qs.phone.service.FloatingWindowService
import com.qs.phone.shell.ShellExecutor
import com.qs.phone.shell.ShellResult
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * ????? - ?? Shell ???? Android ??
 */
class DeviceController(
    private val context: Context
) {
    companion object {
        private const val TAG = "DeviceController"
        private const val DEFAULT_TAP_DELAY = 500L
        private const val DEFAULT_SWIPE_DELAY = 800L
        private const val DEFAULT_LAUNCH_DELAY = 2000L
        private const val DEFAULT_LONG_PRESS_DURATION = 3000
    }

    // ?? lazy ??????????
    private val shell by lazy { ShellExecutor(context) }

    // 应用检测器（不使用 ADB）
    private val appDetector by lazy { AppDetector(context) }

    // 保存原有输入法ID
    private var originalInputMethod: String? = null

    /**
     * 压缩Bitmap到指定文件大小以内
     * @param bitmap 原始图片
     * @param maxSizeBytes 最大文件大小（字节）
     * @return 压缩后的输出流
     */
    private fun compressBitmapToSize(bitmap: Bitmap, maxSizeBytes: Int): ByteArrayOutputStream {
        // 1. 先进行尺寸压缩
        val width = bitmap.width
        val height = bitmap.height
        val maxSize = 1080 // 最大尺寸

        val scaleRatio = minOf(
            maxSize.toFloat() / width,
            maxSize.toFloat() / height,
            1.0f
        ).coerceIn(0.1f, 1.0f)

        val scaledWidth = (width * scaleRatio).toInt()
        val scaledHeight = (height * scaleRatio).toInt()

        val scaledBitmap = if (scaleRatio < 1.0f) {
            Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        } else {
            bitmap
        }

        // 2. 渐进式质量压缩，直到满足大小要求
        var quality = 90
        var outputStream: ByteArrayOutputStream

        while (quality > 10) {
            outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

            if (outputStream.size() <= maxSizeBytes) {
                // 如果创建了新的bitmap，回收它
                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }
                return outputStream
            }

            quality -= 10
        }

        // 最后的兜底压缩
        outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 10, outputStream)

        // 如果创建了新的bitmap，回收它
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        return outputStream
    }

    /**
     * 判断文件是否处于写入/加载中（非最终完成状态）
     * @param file 图片文件
     * @return true=加载中/写入中，false=已完成
     */
    private suspend fun isFileWriting(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext true // 文件不存在，视为未完成
        }

        // 特征1：文件大小为0 或 大小持续变化（如下载中）
        val firstSize = file.length()
        delay(100) // 短暂等待后再次检查大小
        val secondSize = file.length()
        if (firstSize != secondSize || firstSize == 0L) {
            return@withContext true // 大小变化/为空，说明正在写入
        }

        // 特征2：尝试获取文件独占读权限（写入中的文件会被占用）
        try {
            FileInputStream(file).use { fis ->
                // 能正常打开且获取文件通道，说明未被占用
                val channel = fis.channel
                !channel.isOpen
            }
        } catch (e: IOException) {
            // 抛出异常（如文件被占用），说明正在写入
            true
        }
    }

    /**
     * ????????
     */
    suspend fun initialize(): Boolean {
        Log.d(TAG, "Initializing DeviceController...")
        Log.d(TAG, "Shell running status: ${shell.running}")
        val initResult = shell.initialize()
        Log.d(TAG, "Shell initialization result: $initResult")
        Log.d(TAG, "Shell running status after init: ${shell.running}")
        if (!initResult) {
            Log.w(TAG, "Shell initialization failed")
        } else {
            Log.d(TAG, "Shell initialized successfully")
        }
        return initResult
    }

    /**
     * ?? LADB ????
     */
    fun isLadbAvailable(): Boolean = shell.running.value == true

    /**
     * ??????????
     */
//    fun isWirelessDebuggingEnabled(): Boolean = shell.checkWirelessDebuggingEnabled()

    /**
     * ?? USB ??????
     */
    fun isUSBDebuggingEnabled(): Boolean = shell.checkUSBDebuggingEnabled()

    /**
     * ?????? - ?? ADB ??
     */
    suspend fun takeScreenshot(): ScreenshotResult {
        return try {
            Log.d(TAG, "Taking screenshot via ADB")

            // 通知 FloatingWindowService 隐藏状态指示器
            FloatingWindowService.instance?.hideMarqueeStatusIndicator()

            // ?? ADB ????
            val result = takeScreenshotViaAdbExecOut()

            // 截图完成后显示状态指示器
            FloatingWindowService.instance?.showMarqueeStatusIndicator()

            result
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot error", e)
            // 出错时也要显示状态指示器
            FloatingWindowService.instance?.showMarqueeStatusIndicator()
            ScreenshotResult(null, null, 0, 0)
        }
    }

    /**
     * 获取应用检测方法信息（用于调试）
     */
    fun getAppDetectionInfo(): String = appDetector.getDetectionMethodsInfo()

    /**
     * 清理所有临时截屏文件
     */
    fun cleanupScreenshots() {
        try {
            val screenshotDir = File("/sdcard/Android/data/${context.packageName}/files")
            if (screenshotDir.exists()) {
                var deletedCount = 0
                var failedCount = 0

                // 删除所有截图文件
                screenshotDir.listFiles()?.let { files ->
                    for (file in files) {
                        if (file.name.startsWith("screenshot_") && file.name.endsWith(".png")) {
                            if (file.delete()) {
                                deletedCount++
                            } else {
                                failedCount++
                            }
                        }
                    }
                }

                Log.d(TAG, "Screenshot cleanup: $deletedCount files deleted, $failedCount files failed")
            } else {
                Log.d(TAG, "Screenshot directory does not exist")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup screenshot files", e)
        }
    }

    /**
     * ??????
     */
    suspend fun diagnoseScreenshotIssue(): String {
        val sb = StringBuilder()
        sb.append("=== Screenshot Diagnosis ===\n\n")

        // ?? LADB ????
        val isLadbAvailable = isLadbAvailable()
        sb.append("LADB Status: ${if (isLadbAvailable) "? Available" else "? Not Available"}\n")

//        // ??????
//        val isWirelessEnabled = isWirelessDebuggingEnabled()
//        sb.append("Wireless Debugging: ${if (isWirelessEnabled) "? Enabled" else "? Disabled"}\n")

        // ?? USB ??
        val isUsbEnabled = isUSBDebuggingEnabled()
        sb.append("USB Debugging: ${if (isUsbEnabled) "? Enabled" else "? Disabled"}\n")

        // ??????
        val devices = getDevices()
        sb.append("Connected Devices: ${if (devices.isNotEmpty()) devices.joinToString(", ") else "? None"}\n\n")

        if (!isLadbAvailable) {
            sb.append("? LADB not available\n")
            sb.append("   Please install LADB app or enable ADB\n")
        } else if (devices.isEmpty()) {
            sb.append("?? No devices connected\n")
            sb.append("   Enable wireless or USB debugging\n")
        } else {
            sb.append("? Everything looks good!\n")
            sb.append("   You can now use screenshot features.\n")
        }

        sb.append("\nNote: This uses ADB commands\n")
        sb.append("No MediaProjection permissions required!\n")

        return sb.toString()
    }

    /**
     * ?? ADB ?? - ???????
     * ?????????????
     */
    suspend fun takeScreenshotViaAdbExecOut(): ScreenshotResult {
        val timestamp = System.currentTimeMillis()
        val screenshotPath =
            "/sdcard/Android/data/${context.packageName}/files/screenshot_${timestamp}.png"

        return try {
            Log.d(TAG, "Taking screenshot via ADB (internal storage)")

            // ??????
//            shell.executeShell("mkdir -p /sdcard/Android/data/${context.packageName}/files")

            // ??????
            val command = "screencap -p $screenshotPath"
            Log.d(TAG, "Executing: $command")
            shell.executeShell(command)

            // ????????
            var localScreenshotPath =  File(context.getExternalFilesDir(""), "screenshot_${timestamp}.png")

            var count = 0
            var fileExists = false

            // ?????? - ?? async/await ?????
            try {
                withTimeout(10_000) { // 10秒超时
                    while (!fileExists && count < 100) { // 最多重试10次，每次500ms
                        // ?? withContext ??????，??? shell ????
                        fileExists = withContext(Dispatchers.IO) {
                            localScreenshotPath.exists()
                        }

                        if (!fileExists&&isFileWriting(localScreenshotPath)) {
                            delay(500) // ?????????
                            count++
                            Log.d(TAG, "Waiting for screenshot file... attempt $count")
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Screenshot file creation timeout")
                return takeScreenshotViaAdbExecOut()
            }

            if (!fileExists) {
                Log.e(TAG, "Screenshot file not found after retries")
                return takeScreenshotViaAdbExecOut()
            }


            // ???????? Bitmap
            val bitmap = BitmapFactory.decodeFile(localScreenshotPath.absolutePath)

            if (bitmap != null) {
                val width = bitmap.width
                val height = bitmap.height

                // 压缩图片 - 控制在500KB以内
                val outputStream = compressBitmapToSize(bitmap, 500 * 1024)
                val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                Log.d(TAG, "Screenshot decoded and compressed successfully: ${width}x${height} -> ${outputStream.size()} bytes")

                // ?????????


                // 回收原始bitmap以节省内存
                bitmap.recycle()

                ScreenshotResult(null, base64, width, height)
            } else {
                Log.e(TAG, "Failed to decode PNG data")
                takeScreenshotViaAdbExecOut()
            }
        } catch (e: Exception) {
            Log.e(TAG, "ADB screenshot failed", e)
            ScreenshotResult(null, null, 100, 100)
        } finally {
            // 清理当前截图文件
            try {
                val screenshotFile = File(screenshotPath)
                if (screenshotFile.exists()) {
                    if (screenshotFile.delete()) {
                        Log.d(TAG, "Temporary screenshot file deleted")
                    } else {
                        Log.w(TAG, "Failed to delete temporary screenshot file")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cleanup temporary screenshot file", e)
            }
        }
    }


    /**
     * ???????? - ????
     */
    private fun parseScreenshotBytes(bytes: ByteArray): ScreenshotResult {
        return try {
            if (bytes.isEmpty()) {
                Log.e(TAG, "Screenshot bytes are empty")
                return ScreenshotResult(null, null, 0, 0)
            }

            // ?? PNG ??
            if (bytes.size < 8 || bytes[0] != 0x89.toByte() || bytes[1] != 0x50.toByte()) {
                Log.e(
                    TAG,
                    "Invalid PNG header: ${bytes.take(8).joinToString(" ") { "%02x".format(it) }}"
                )
                return ScreenshotResult(null, null, 0, 0)
            }

            Log.d(TAG, "Valid PNG header detected, size: ${bytes.size} bytes")

            // ????????? Bitmap
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            if (bitmap != null) {
                // 压缩图片 - 控制在500KB以内
                val outputStream = compressBitmapToSize(bitmap, 500 * 1024)
                val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                Log.d(TAG, "Screenshot decoded and compressed successfully: ${bitmap.width}x${bitmap.height} -> ${outputStream.size()} bytes")

                // 回收bitmap以节省内存
                bitmap.recycle()

                ScreenshotResult(null, base64, bitmap.width, bitmap.height)
            } else {
                Log.e(TAG, "BitmapFactory.decodeByteArray returned null")
                ScreenshotResult(null, null, 0, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse screenshot failed", e)
            ScreenshotResult(null, null, 0, 0)
        }
    }

    /**
     * ?????????? - ??????????
     */
    @Deprecated("Use parseScreenshotBytes instead")
    private fun parseScreenshot(data: String): ScreenshotResult {
        return try {
            val bytes = data.toByteArray(Charsets.ISO_8859_1)
            parseScreenshotBytes(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Parse screenshot failed", e)
            ScreenshotResult(null, null, 0, 0)
        }
    }

    /**
     * 获取当前应用（不使用 ADB）
     */
    suspend fun getCurrentApp(): String {
        return try {
            Log.d(TAG, "Getting current app without ADB")
            val appName = appDetector.getCurrentApp()
            Log.d(TAG, "Detected app: $appName")
            appName
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current app without ADB", e)
            // 作为最后的备用方案，使用 ADB 方法
            getCurrentAppViaADB()
        }
    }

    /**
     * 通过 ADB 获取当前应用（备用方案）
     */
    private suspend fun getCurrentAppViaADB(): String {
        // 直接从顶层Activity中查找包名
        val result = shell.executeShell("am dumpsys activity top")
        if (result.success) {
            Log.d(TAG, "Activity dump output: ${result.stdout.take(500)}")

            // 查找 ACTIVITY 行
            val lines = result.stdout.lines()
            for (line in lines) {
                if (line.trim().startsWith("ACTIVITY")) {
                    Log.d(TAG, "Found activity line: $line")

                    // 解析包名: ACTIVITY com.package.name/...
                    val parts = line.trim().split(" ")
                    if (parts.size >= 2) {
                        val packageActivity = parts[1]
                        val packageName = packageActivity.split("/")[0]

                        Log.d(TAG, "Parsed package: $packageName")

                        // 查找对应的应用名称
                        val appName = AppPackages.getAppName(packageName)
                        if (!appName.isNullOrEmpty()) {
                            return appName
                        }

                        // 处理系统应用
                        return when {
                            packageName.startsWith("com.android.") -> "System"
                            packageName.startsWith("com.google.android.") &&
                                (packageName.contains("launcher") || packageName.contains("launcher3")) -> "Home"
                            packageName.contains("launcher") -> "Home"
                            else -> packageName // 返回包名本身
                        }
                    }
                }
            }
        }

        // 如果获取焦点失败，尝试获取顶层应用
        Log.d(TAG, "Focus detection failed, trying top activity...")
        val topResult = shell.executeShell("dumpsys activity top | grep 'ACTIVITY' | grep -v 'mimeType' | tail -1")
        if (topResult.success && topResult.stdout.isNotEmpty()) {
            Log.d(TAG, "Top activity output: ${topResult.stdout}")
            // 解析顶层Activity的包名
            val activityPattern = """ACTIVITY ([^/]+)/""".toRegex()
            val matchResult = activityPattern.find(topResult.stdout)
            val packageName = matchResult?.groupValues?.get(1)

            if (!packageName.isNullOrEmpty()) {
                val appName = AppPackages.getAppName(packageName)
                if (!appName.isNullOrEmpty()) {
                    return appName
                }

                return when {
                    packageName.startsWith("com.android.") -> "System"
                    packageName.contains("launcher") -> "Home"
                    else -> packageName
                }
            }
        }

        // 最后尝试获取最近运行的应用
        Log.d(TAG, "Top activity detection failed, trying app stack...")
        val recentResult = shell.executeShell("am stack list | head -1")
        if (recentResult.success && recentResult.stdout.isNotEmpty()) {
            Log.d(TAG, "App stack output: ${recentResult.stdout}")
            // 解析堆栈顶部的包名
            val stackPattern = """TaskRecord\{[^}]* ([^/]+)""".toRegex()
            val matchResult = stackPattern.find(recentResult.stdout)
            val packageName = matchResult?.groupValues?.get(1)

            if (!packageName.isNullOrEmpty()) {
                val appName = AppPackages.getAppName(packageName)
                if (!appName.isNullOrEmpty()) {
                    return appName
                }

                return when {
                    packageName.startsWith("com.android.") -> "System"
                    packageName.contains("launcher") -> "Home"
                    else -> packageName
                }
            }
        }

        // 最后的备用方法：尝试获取正在运行的进程
        Log.d(TAG, "All methods failed, trying to get running processes...")
        val psResult = shell.executeShell("ps -A | grep -E 'com\\.' | head -5")
        if (psResult.success) {
            Log.d(TAG, "Process output: ${psResult.stdout}")
            val lines = psResult.stdout.lines()

            // 查找最可能是用户应用的包名
            for (line in lines) {
                val match = Regex("([a-zA-Z0-9.]+\\.[a-zA-Z0-9.]+\\.[a-zA-Z0-9.]+)").find(line)
                if (match != null) {
                    val pkg = match.groupValues[1]
                    // 跳过系统应用
                    if (!pkg.startsWith("com.android.") &&
                        !pkg.contains("system") &&
                        !pkg.contains("server") &&
                        !pkg.contains("service")) {

                        val appName = AppPackages.getAppName(pkg)
                        if (!appName.isNullOrEmpty()) {
                            return appName
                        }
                        return pkg
                    }
                }
            }
        }

        // 如果所有方法都失败，返回未知状态
        Log.d(TAG, "All detection methods failed")
        return "Unknown"
    }

    /**
     * ??????
     */
    suspend fun tap(x: Int, y: Int) {
        shell.executeShell("input tap $x $y")
        delay(DEFAULT_TAP_DELAY)
    }

    /**
     * ??
     */
    suspend fun doubleTap(x: Int, y: Int) {
        shell.executeShell("input tap $x $y")
        delay(100)
        shell.executeShell("input tap $x $y")
        delay(DEFAULT_TAP_DELAY)
    }

    /**
     * ??
     */
    suspend fun longPress(x: Int, y: Int, durationMs: Int = DEFAULT_LONG_PRESS_DURATION) {
        shell.executeShell("input swipe $x $y $x $y $durationMs")
        delay(DEFAULT_TAP_DELAY)
    }

    /**
     * ??
     */
    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int? = null) {
        val duration = durationMs ?: run {
            val distSq = (startX - endX) * (startX - endX) + (startY - endY) * (startY - endY)
            (distSq / 1000).coerceIn(1000, 2000)
        }
        shell.executeShell("input swipe $startX $startY $endX $endY $duration")
        delay(DEFAULT_SWIPE_DELAY)
    }

    /**
     * ???
     */
    suspend fun back() {
        shell.executeShell("input keyevent 4")
        delay(DEFAULT_TAP_DELAY)
    }

    /**
     * Home ?
     */
    suspend fun home() {
        shell.executeShell("input keyevent KEYCODE_HOME")
        delay(DEFAULT_TAP_DELAY)
    }

    /**
     * 异步执行点击 - 不等待结果
     */
    fun tapAsync(x: Int, y: Int, callback: ((Boolean) -> Unit)? = null) {
        val command = "input tap $x $y"
        shell.executeAsync(command) { result: ShellResult ->
            callback?.invoke(result.success)
        }
    }

    /**
     * 异步执行双击 - 不等待结果
     */
    fun doubleTapAsync(x: Int, y: Int, callback: ((Boolean) -> Unit)? = null) {
        shell.executeAsync("input tap $x $y") { result1: ShellResult ->
            if (result1.success) {
                // 在新的协程中处理延迟和第二次点击
                GlobalScope.launch {
                    delay(100)
                    shell.executeAsync("input tap $x $y") { result2: ShellResult ->
                        callback?.invoke(result2.success)
                    }
                }
            } else {
                callback?.invoke(false)
            }
        }
    }

    /**
     * 异步执行长按 - 不等待结果
     */
    fun longPressAsync(x: Int, y: Int, durationMs: Int = DEFAULT_LONG_PRESS_DURATION, callback: ((Boolean) -> Unit)? = null) {
        val command = "input swipe $x $y $x $y $durationMs"
        shell.executeAsync(command) { result: ShellResult ->
            callback?.invoke(result.success)
        }
    }

    /**
     * 异步执行滑动 - 不等待结果
     */
    fun swipeAsync(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int? = null, callback: ((Boolean) -> Unit)? = null) {
        val duration = durationMs ?: run {
            val distSq = (startX - endX) * (startX - endX) + (startY - endY) * (startY - endY)
            (distSq / 1000).coerceIn(1000, 2000)
        }
        val command = "input swipe $startX $startY $endX $endY $duration"
        shell.executeAsync(command) { result: ShellResult ->
            callback?.invoke(result.success)
        }
    }

    /**
     * 异步执行返回 - 不等待结果
     */
    fun backAsync(callback: ((Boolean) -> Unit)? = null) {
        shell.executeAsync("input keyevent 4") { result: ShellResult ->
            callback?.invoke(result.success)
        }
    }

    /**
     * 异步执行 Home - 不等待结果
     */
    fun homeAsync(callback: ((Boolean) -> Unit)? = null) {
        shell.executeAsync("input keyevent KEYCODE_HOME") { result: ShellResult ->
            callback?.invoke(result.success)
        }
    }

    /**
     * 异步输入文本 - 不等待结果
     */
    fun typeTextAsync(text: String, callback: ((Boolean) -> Unit)? = null) {
        val escapedText = text.replace("\"", "\\\"")
        val command = "adb shell am broadcast -a ADB_INPUT_TEXT --es msg '$escapedText'"
        Log.d(TAG, "Executing async text input: $command")
        shell.executeAsync(command) { result: ShellResult ->
            callback?.invoke(result.success)
        }
    }

    /**
     * ????
     */
    suspend fun typeText(text: String) {
        // 使用 am broadcast 方案发送文字，支持中文和多语言
        val escapedText = text.replace("\"", "\\\"")
        val command = "am broadcast -a ADB_INPUT_TEXT --es msg '$escapedText'"
        Log.d(TAG, "Executing text input: $command")
        shell.executeShell(command)
        delay(DEFAULT_TAP_DELAY)
    }

    /**
     * ????搜索奶茶
     */
    suspend fun clearText() {
        // ???????????
        repeat(1) {
            shell.executeShell("for i in {1..50}; do input keyevent KEYCODE_DEL; done")
        }
    }

    /**
     * ????
     */
    suspend fun launchApp(appName: String): Boolean {
        val packageName = AppPackages.getPackageName(appName) ?: return false
        shell.executeShell("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        delay(DEFAULT_LAUNCH_DELAY)
        return true
    }

    /**
     * ??????
     */
    suspend fun getScreenSize(): Pair<Int, Int> {
        val result = shell.executeShell("wm size")
        if (result.success) {
            // ?? "Physical size: 1080x2400" ??
            val match = Regex("(\\d+)x(\\d+)").find(result.stdout)
            if (match != null) {
                return Pair(match.groupValues[1].toInt(), match.groupValues[2].toInt())
            }
        }
        return Pair(1080, 1920) // ???
    }

    /**
     * ??????
     */
    fun getDevices(): List<String> = shell.getDevices()

    /**
     * ????
     */
    fun cleanup() {
        shell.cleanup()
    }

    // ==================== 输入法管理功能 ====================

    /**
     * 安装 ADBKeyboard APK
     * @return 安装是否成功
     */
    suspend fun installADBKeyboard(): Boolean {
        return try {
            Log.d(TAG, "开始安装 ADBKeyboard...")

            // 检查是否已经安装
            if (isADBKeyboardInstalled()) {
                Log.d(TAG, "ADBKeyboard 已安装")
                return true
            }

            // 从 assets 复制 APK 到临时文件
            val tempApkFile = copyADBKeyboardToTemp()
            if (tempApkFile == null) {
                Log.e(TAG, "复制 ADBKeyboard.apk 失败")
                return false
            }

            try {
                // 安装 APK
                val installCommand = "pm install -r ${tempApkFile.absolutePath}"
                Log.d(TAG, "执行安装命令: $installCommand")
                val result = shell.executeShell(installCommand)

                if (result.success) {
                    Log.d(TAG, "ADBKeyboard 安装成功")
                    // 删除临时文件
                    tempApkFile.delete()
                    return true
                } else {
                    Log.e(TAG, "ADBKeyboard 安装失败: ${result.stderr}")
                    return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "安装 ADBKeyboard 时发生异常", e)
                return false
            } finally {
                // 确保删除临时文件
                try {
                    tempApkFile.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "删除临时文件失败", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "安装 ADBKeyboard 失败", e)
            false
        }
    }

    /**
     * 检查 ADBKeyboard 是否已安装
     */
    suspend fun isADBKeyboardInstalled(): Boolean {
        return try {
            val result = shell.executeShell("pm list packages | grep com.android.adbkeyboard")
            result.success && result.stdout.contains("com.android.adbkeyboard")
        } catch (e: Exception) {
            Log.e(TAG, "检查 ADBKeyboard 安装状态失败", e)
            false
        }
    }

    /**
     * 从 assets 复制 ADBKeyboard.apk 到临时文件
     */
    private fun copyADBKeyboardToTemp(): File? {
        return try {
            // 打开 assets 中的 APK 文件
            val inputStream = context.assets.open("ADBKeyboard.apk")
            val tempFile = File(context.cacheDir, "ADBKeyboard_temp.apk")

            // 复制文件
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }

            inputStream.close()
            Log.d(TAG, "ADBKeyboard.apk 已复制到: ${tempFile.absolutePath}")
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "复制 ADBKeyboard.apk 失败", e)
            null
        }
    }

    /**
     * 获取当前输入法
     */
    suspend fun getCurrentInputMethod(): String? {
        return try {
            val result = shell.executeShell("ime list -s | head -1")
            if (result.success && result.stdout.isNotBlank()) {
                val currentIme = result.stdout.trim()
                Log.d(TAG, "当前输入法: $currentIme")
                currentIme
            } else {
                // 备用方法：获取默认输入法
                val defaultResult = shell.executeShell("settings get secure default_input_method")
                if (defaultResult.success) {
                    val defaultIme = defaultResult.stdout.trim()
                    Log.d(TAG, "默认输入法: $defaultIme")
                    if (defaultIme.isNotEmpty()) defaultIme else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取当前输入法失败", e)
            null
        }
    }

    /**
     * 启用 ADBKeyboard 输入法
     */
    suspend fun enableADBKeyboard(): Boolean {
        return try {
            Log.d(TAG, "启用 ADBKeyboard...")

            // 启用输入法
            val enableCommand = "ime enable com.android.adbkeyboard/.AdbIME"
            val enableResult = shell.executeShell(enableCommand)

            if (!enableResult.success) {
                Log.e(TAG, "启用 ADBKeyboard 失败: ${enableResult.stderr}")
                return false
            }

            Log.d(TAG, "ADBKeyboard 启用成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "启用 ADBKeyboard 失败", e)
            false
        }
    }

    /**
     * 切换到 ADBKeyboard 输入法
     */
    suspend fun switchToADBKeyboard(): Boolean {
        return try {
            Log.d(TAG, "切换到 ADBKeyboard...")

            // 先保存当前输入法
            if (originalInputMethod == null) {
                originalInputMethod = getCurrentInputMethod()
                Log.d(TAG, "保存原有输入法: $originalInputMethod")
            }

            // 设置 ADBKeyboard 为当前输入法
            val setCommand = "ime set com.android.adbkeyboard/.AdbIME"
            val setResult = shell.executeShell(setCommand)

            if (setResult.success) {
                Log.d(TAG, "已切换到 ADBKeyboard")
                true
            } else {
                Log.e(TAG, "切换到 ADBKeyboard 失败: ${setResult.stderr}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "切换到 ADBKeyboard 失败", e)
            false
        }
    }

    /**
     * 恢复原有输入法
     */
    suspend fun restoreOriginalInputMethod(): Boolean {
        return try {
            val originalIme = originalInputMethod
            if (originalIme.isNullOrEmpty()) {
                Log.d(TAG, "没有保存的原有输入法，跳过恢复")
                return true
            }

            Log.d(TAG, "恢复原有输入法: $originalIme")

            // 检查原有输入法是否仍然可用
            val listResult = shell.executeShell("ime list -s")
            if (listResult.success && listResult.stdout.contains(originalIme)) {
                // 恢复原有输入法
                val setCommand = "ime set $originalIme"
                val setResult = shell.executeShell(setCommand)

                if (setResult.success) {
                    Log.d(TAG, "已恢复原有输入法: $originalIme")
                    originalInputMethod = null
                    return true
                } else {
                    Log.e(TAG, "恢复原有输入法失败: ${setResult.stderr}")
                    return false
                }
            } else {
                Log.w(TAG, "原有输入法不可用: $originalIme")
                originalInputMethod = null
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "恢复原有输入法失败", e)
            false
        }
    }

    /**
     * 初始化输入法（安装并启用 ADBKeyboard）
     */
    suspend fun initializeInputMethod(): Boolean {
        return try {
            Log.d(TAG, "初始化输入法...")

            // 1. 安装 ADBKeyboard
            if (!installADBKeyboard()) {
                Log.e(TAG, "安装 ADBKeyboard 失败")
                return false
            }

            // 2. 启用 ADBKeyboard
            if (!enableADBKeyboard()) {
                Log.e(TAG, "启用 ADBKeyboard 失败")
                return false
            }

            // 3. 切换到 ADBKeyboard
            if (!switchToADBKeyboard()) {
                Log.e(TAG, "切换到 ADBKeyboard 失败")
                return false
            }

            Log.d(TAG, "输入法初始化完成")
            true
        } catch (e: Exception) {
            Log.e(TAG, "初始化输入法失败", e)
            false
        }
    }

    /**
     * 使用改进的文本输入方法（支持 ADBKeyboard）
     */
    suspend fun typeTextImproved(text: String): Boolean {
        return try {
            Log.d(TAG, "输入文本: $text")

            // 首先尝试使用 ADB_INPUT_TEXT 广播（适用于 ADBKeyboard）
            val escapedText = text.replace("\"", "\\\"").replace("'", "\\'")
            val broadcastCommand = "am broadcast -a ADB_INPUT_TEXT --es msg '$escapedText'"

            val result = shell.executeShell(broadcastCommand)
            if (result.success) {
                Log.d(TAG, "使用 ADB_INPUT_TEXT 输入成功")
                delay(DEFAULT_TAP_DELAY)
                return true
            }

            // 如果广播失败，尝试使用 Base64 编码方式
            Log.d(TAG, "ADB_INPUT_TEXT 失败，尝试 Base64 方式")
            val base64Text = android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)
            val base64Command = "am broadcast -a ADB_INPUT_B64 --es msg '$base64Text'"

            val base64Result = shell.executeShell(base64Command)
            if (base64Result.success) {
                Log.d(TAG, "使用 Base64 方式输入成功")
                delay(DEFAULT_TAP_DELAY)
                return true
            }

            // 最后尝试传统方式（仅适用于英文）
            Log.d(TAG, "Base64 方式失败，尝试传统 input text 方式")
            if (text.all { it.code <= 127 }) { // 只有 ASCII 字符
                val inputCommand = "input text '${text.replace("'", "\\'")}'"
                val inputResult = shell.executeShell(inputCommand)
                if (inputResult.success) {
                    Log.d(TAG, "使用传统 input text 方式输入成功")
                    delay(DEFAULT_TAP_DELAY)
                    return true
                }
            }

            Log.e(TAG, "所有文本输入方法都失败")
            false
        } catch (e: Exception) {
            Log.e(TAG, "输入文本失败", e)
            false
        }
    }
}

data class ScreenshotResult(
    val bitmap: Bitmap?,
    val base64: String?,
    val width: Int,
    val height: Int
)

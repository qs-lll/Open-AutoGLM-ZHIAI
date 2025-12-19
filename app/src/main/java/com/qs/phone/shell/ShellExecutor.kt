package com.qs.phone.shell

import android.content.Context
import android.net.nsd.NsdManager
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.qs.phone.debug.DebugManager
import com.qs.phone.discovery.DnsDiscoveryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADB Shell 执行器 - 简化版本
 * 专注于 ADB 命令执行和设备连接管理
 */
class ShellExecutor(private val context: Context) {
    companion object {
        private const val TAG = "ShellExecutor"
        const val MAX_OUTPUT_BUFFER_SIZE = 1024 * 16
    }

    // 核心依赖
    private val debugManager = DebugManager(context)
    private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val dnsDiscoveryManager by lazy { DnsDiscoveryManager(context, nsdManager) }

    // LADB 方式：直接从 nativeLibraryDir 获取路径
    private val adbPath = "${context.applicationInfo.nativeLibraryDir}/libadb.so"

    // 输出缓冲
    private val _outputBufferFile by lazy {
        File.createTempFile("buffer", ".txt").also {
            it.deleteOnExit()
        }
    }
    val outputBufferFile: File get() = _outputBufferFile

    // 状态管理
    private val _running = MutableLiveData(false)
    val running: LiveData<Boolean> = _running

    private val _closed = MutableLiveData(false)
    val closed: LiveData<Boolean> = _closed

    // 连接状态
    private var tryingToPair = AtomicBoolean(false)
    private var shellProcess: Process? = null

    /**
     * 初始化 ADB 连接
     */
    suspend fun initServer(): Boolean = withContext(Dispatchers.IO) {
        if (_running.value == true || tryingToPair.get())
            return@withContext true

        tryingToPair.set(true)
        debug("Starting initialization...")

        try {
            // 检查 ADB 库文件
            if (!isAdbLibraryAvailable()) {
                Log.e(TAG, "ADB library not found")
                tryingToPair.set(false)
                return@withContext false
            }

            // 配置调试选项
            if (debugManager.hasSecureSettingsPermission()) {
                debugManager.disableMobileDataAlwaysOn()

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    debugManager.cycleWirelessDebugging()
                    debugManager.waitForWirelessDebugging()
                } else {
                    debugManager.enableUSBDebugging()
                    debugManager.waitForUSBDebugging()
                }
            }

            // 测试 ADB 连接
            if (!testAdbConnection()) {
                tryingToPair.set(false)
                return@withContext false
            }

            _running.postValue(true)
            tryingToPair.set(false)
            debug("Initialization completed successfully!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
            debug("Init failed: ${e.message}")
            tryingToPair.set(false)
            false
        }
    }

    /**
     * 向后兼容的别名
     */
    suspend fun initialize(): Boolean = initServer()

    /**
     * 执行 shell 命令
     */
    suspend fun executeShell(command: String): ShellResult = withContext(Dispatchers.IO) {
        executeCommand(listOf("shell", command))
    }

    /**
     * 执行 ADB 命令
     */
    suspend fun executeADB(command: String): ShellResult = withContext(Dispatchers.IO) {
        executeCommand(listOf(command))
    }

    /**
     * 执行命令
     */
    suspend fun executeCommand(command: List<String>): ShellResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Executing: $command")

            val process = createAdbProcess(command)
            val completed = process.waitFor(30, TimeUnit.SECONDS)

            val stdout = BufferedReader(process.inputStream.reader()).readText()
            val stderr = BufferedReader(process.errorStream.reader()).readText()
            val exitCode = if (completed) process.exitValue() else -1

            Log.d(TAG, "Command completed: $command")
            Log.d(TAG, "Exit code: $exitCode")

            ShellResult(
                success = exitCode == 0,
                stdout = stdout,
                stderr = stderr,
                exitCode = exitCode
            )
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.e(TAG, "Command timeout: $command")
            ShellResult(false, "", "命令执行超时", -1)
        } catch (e: Exception) {
            Log.e(TAG, "Command failed", e)
            ShellResult(false, "", e.message ?: "Unknown error", -1)
        }
    }

    /**
     * 异步执行命令
     */
    fun executeAsync(command: String, callback: ((ShellResult) -> Unit)? = null) {
        GlobalScope.launch(Dispatchers.IO) {
            val result = executeShell(command)
            callback?.invoke(result)
        }
    }


    /**
     * 连接到指定设备
     */
    suspend fun connectToDevice(ip: String, port: Int = 5555): Boolean = withContext(Dispatchers.IO) {
        try {
            val address = "$ip:$port"
            Log.d(TAG, "Connecting to device at $address...")
            val result = executeCommand(listOf("connect", address))

            if (result.success) {
                Log.d(TAG, "Connected to $address")
                true
            } else {
                Log.w(TAG, "Failed to connect: ${result.stderr}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to device", e)
            false
        }
    }

    /**
     * 断开所有设备连接
     */
    suspend fun disconnectAll(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Disconnecting all devices...")
            val result = executeADB("disconnect")
            result.success
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting devices", e)
            false
        }
    }

    /**
     * 获取设备列表
     */
    fun getDevices(): List<String> {
        return try {
            val devicesProcess = createAdbProcess(listOf("devices"))
            devicesProcess.waitFor()

            val linesRaw = BufferedReader(devicesProcess.inputStream.reader()).readLines()

            linesRaw
                .filterNot { it.contains("List of devices attached") }
                .map { it.split("\t").first() }
                .filterNot { it.isEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get devices", e)
            emptyList()
        }
    }

    /**
     * 检查是否已连接到本地设备
     */
    fun isConnectedToLocalDevice(): Boolean {
        val devices = getDevices()
        return devices.any { it.contains("localhost") || it.contains("127.0.0.1") }
    }

    /**
     * 启动 DNS 扫描
     */
    fun startDnsScan() {
        dnsDiscoveryManager.scanAdbPorts()
    }

    /**
     * 停止 DNS 扫描
     */
    fun stopDnsScan() {
        dnsDiscoveryManager.stopScan()
    }

    /**
     * 获取发现的端口
     */
    fun getDiscoveredPorts(): List<Int> {
        return dnsDiscoveryManager.getDiscoveredPorts()
    }

    /**
     * 自动重连机制
     */
    fun waitForDeathAndReset() {
        Thread {
            try {
                while (true) {
                    if (!tryingToPair.get()) {
                        shellProcess?.waitFor()
                        _running.postValue(false)
                        debug("Shell is dead, resetting...")
                        createAdbProcess(listOf("kill-server")).waitFor()

                        Thread.sleep(3_000)
                        GlobalScope.launch {
                            initServer()
                        }
                    }
                    Thread.sleep(1_000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in reset loop", e)
            }
        }.start()
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        try {
            shellProcess?.destroy()
            shellProcess = null
            dnsDiscoveryManager.stopScan()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed", e)
        }
    }

    /**
     * 写调试消息
     */
    fun debug(msg: String) {
        Log.e("debug", msg)
        synchronized(outputBufferFile) {
            Log.d(TAG, "* $msg")
            if (outputBufferFile.exists()) {
                outputBufferFile.appendText("* $msg" + System.lineSeparator())
            }
        }
    }

    /**
     * 检查是否可用
     */
    fun isAvailable(): Boolean {
        return _running.value == true
    }

    /**
     * 检查 ADB 库是否可用
     */
    fun isAdbLibraryAvailable(): Boolean {
        return try {
            val adbFile = File(adbPath)
            adbFile.exists() && adbFile.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check ADB library", e)
            false
        }
    }

    /**
     * 调试状态检查方法（向后兼容）
     */
    fun checkWirelessDebuggingEnabled(): Boolean = debugManager.isWirelessDebuggingEnabled()
    fun checkUSBDebuggingEnabled(): Boolean = debugManager.isUSBDebuggingEnabled()

    // 私有方法
    private fun testAdbConnection(): Boolean {
        return try {
            val testProcess = createAdbProcess(listOf("devices"))
            val testSuccess = testProcess.waitFor(10, TimeUnit.SECONDS)
            val testOutput = BufferedReader(testProcess.inputStream.reader()).readText()

            if (testSuccess) {
                Log.d(TAG, "ADB connection test successful")
                Log.d(TAG, "Test output: $testOutput")
                true
            } else {
                Log.e(TAG, "ADB connection test failed")
                Log.e(TAG, "Test output: $testOutput")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "ADB connection test failed", e)
            false
        }
    }

    private fun createAdbProcess(command: List<String>): Process {
        val commandList = command.toMutableList().also {
            it.add(0, adbPath)
        }
        Log.d(TAG, "Running ADB command: $commandList")

        return ProcessBuilder(commandList)
            .directory(context.filesDir)
            .apply {
                environment().apply {
                    put("HOME", context.filesDir.path)
                    put("TMPDIR", context.cacheDir.path)
                }
            }
            .start()
    }
}

/**
 * Shell 执行结果
 */
data class ShellResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int
)
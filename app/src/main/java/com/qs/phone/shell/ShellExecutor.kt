package com.qs.phone.shell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.PrintStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LADB Shell 执行器 - 100% 实现 LADB 所有功能
 * 使用内置的 libadb.so 本地库执行 ADB 命令
 * 参考 LADB 项目：https://github.com/tytydraco/LADB
 */
class ShellExecutor(private val context: Context) {
    companion object {
        private const val TAG = "ShellExecutor"
        const val MAX_OUTPUT_BUFFER_SIZE = 1024 * 16
        const val OUTPUT_BUFFER_DELAY_MS = 100L

        // DNS 发现相关
        private const val SERVICE_TYPE = "_adb-tls-connect._tcp"
    }

    // LADB 方式：直接从 nativeLibraryDir 获取路径，无需显式加载
    private val adbPath = "${context.applicationInfo.nativeLibraryDir}/libadb.so"

    private val _outputBufferFile by lazy {
        File.createTempFile("buffer", ".txt").also {
            it.deleteOnExit()
        }
    }
    val outputBufferFile: File get() = _outputBufferFile

    /**
     * Shell 是否正在运行 - 使用 LiveData 保持与 LADB 一致
     */
    private val _running = MutableLiveData(false)
    val running: LiveData<Boolean> = _running

    /**
     * Shell 是否已关闭
     */
    private val _closed = MutableLiveData(false)
    val closed: LiveData<Boolean> = _closed

    /**
     * DNS 发现相关
     */
    private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private var dnsDiscover: DnsDiscover? = null

    private var tryingToPair = false
    private var shellProcess: Process? = null

    /**
     * 初始化 ADB 连接 - 简化版本，不依赖交互式 shell
     */
    suspend fun initServer(): Boolean = withContext(Dispatchers.IO) {
        if (_running.value == true || tryingToPair)
            return@withContext true

        tryingToPair = true
        debug("Starting initialization...")

        try {
            // 检查 ADB 库文件是否存在
            val adbFile = File(adbPath)
            if (!adbFile.exists()) {
                Log.e(TAG, "ADB library not found at $adbPath")
                tryingToPair = false
                return@withContext false
            }

            Log.d(TAG, "Found ADB library: ${adbFile.length()} bytes")

            // 检查权限
            val secureSettingsGranted =
                context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

            // 自动模式：启用无线调试（Android 11+）或 USB 调试（Android 10 及以下）
            if (secureSettingsGranted) {
                disableMobileDataAlwaysOn()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cycleWirelessDebugging()
                } else if (!isUSBDebuggingEnabled()) {
                    debug("Turning on USB debugging...")
                    Settings.Global.putInt(
                        context.contentResolver,
                        Settings.Global.ADB_ENABLED,
                        1
                    )
                    Thread.sleep(5_000)
                }
            }

            // 等待调试启用
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!isWirelessDebuggingEnabled()) {
                    debug("Wireless debugging is not enabled!")
                    debug("Settings -> Developer options -> Wireless debugging")
                    debug("Waiting for wireless debugging...")

                    var waitCount = 0
                    while (!isWirelessDebuggingEnabled() && waitCount < 30) {
                        Thread.sleep(1_000)
                        waitCount++
                    }
                }
            } else {
                if (!isUSBDebuggingEnabled()) {
                    debug("USB debugging is not enabled!")
                    debug("Settings -> Developer options -> USB debugging")
                    debug("Waiting for USB debugging...")

                    var waitCount = 0
                    while (!isUSBDebuggingEnabled() && waitCount < 30) {
                        Thread.sleep(1_000)
                        waitCount++
                    }
                }
            }

            // 测试 ADB 连接
            Log.d(TAG, "Testing ADB connection...")
            val testProcess = adb(false, listOf("devices"))
            val testSuccess = testProcess.waitFor(10, TimeUnit.SECONDS)
            val testOutput = BufferedReader(testProcess.inputStream.reader()).readText()

            if (testSuccess) {
                Log.d(TAG, "ADB connection test successful")
                Log.d(TAG, "Test output: $testOutput")
            } else {
                Log.e(TAG, "ADB connection test failed")
                Log.e(TAG, "Test output: $testOutput")
                tryingToPair = false
                return@withContext false
            }

            // 设置为已初始化状态
            _running.postValue(true)
            tryingToPair = false
            debug("Initialization completed successfully!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
            debug("Init failed: ${e.message}")
            tryingToPair = false
            false
        }
    }

    /**
     * 向后兼容的别名
     */
    suspend fun initialize(): Boolean = initServer()

    /**
     * 执行 ADB 命令 - LADB 方式：直接将库路径作为命令执行
     */
    private fun adb(redirect: Boolean, command: List<String>): Process {
        // LADB 方式：将库路径添加到命令列表开头
        val commandList = command.toMutableList().also {
            it.add(0, adbPath)
        }
        Log.d(TAG, "Running ADB command: $commandList")
        return shell(redirect, commandList)
    }

    /**
     * 执行 shell 命令
     */
    private fun shell(redirect: Boolean, command: List<String>): Process {
        val processBuilder = ProcessBuilder(command)
            .directory(context.filesDir)
            .apply {
                if (redirect) {
                    redirectErrorStream(true)
                    // API 26+ 支持 redirectOutput，API 24-25 需要手动处理
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        redirectOutput(outputBufferFile)
                    }
                }

                environment().apply {
                    put("HOME", context.filesDir.path)
                    put("TMPDIR", context.cacheDir.path)
                }
            }

        return processBuilder.start()!!
    }

    /**
     * 获取设备列表
     */
    fun getDevices(): List<String> {
        try {
            val devicesProcess = adb(false, listOf("devices"))
            devicesProcess.waitFor()

            val linesRaw = BufferedReader(devicesProcess.inputStream.reader()).readLines()

            val deviceLines = linesRaw.filterNot { it ->
                it.contains("List of devices attached")
            }

            val deviceNames = deviceLines.map { it ->
                it.split("\t").first()
            }.filterNot { it.isEmpty() }

            return deviceNames
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get devices", e)
            return emptyList()
        }
    }


    /**
     * 执行命令 - 使用 adb() 函数方式，和 getDevices 一样
     */
    suspend fun executeShell(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Executing: $command")

            // 使用和 getDevices 完全一样的方式：adb() 函数
            val process = adb(false, listOf("shell", command))
            val completed = process.waitFor(30, TimeUnit.SECONDS)

            // 分别读取标准输出和标准错误流
            val stdout = BufferedReader(process.inputStream.reader()).readText()
            val stderr = BufferedReader(process.errorStream.reader()).readText()
            val exitCode = if (completed) process.exitValue() else -1

            Log.d(TAG, "Command completed: $command")
            Log.d(TAG, "Exit code: $exitCode")
            Log.d(TAG, "=== Full Shell Output ===")
            Log.d(TAG, stdout)
            Log.d(TAG, "=== Shell Error ===")
            Log.d(TAG, stderr)
            Log.d(TAG, "=== End Output ===")

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
     * 执行命令 - 使用 adb() 函数方式，和 getDevices 一样
     */
    suspend fun executeADB(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Executing: $command")


            // 使用和 getDevices 完全一样的方式：adb() 函数
            val process = adb(false, listOf(command))
            val completed = process.waitFor(30, TimeUnit.SECONDS)

            // 分别读取标准输出和标准错误流
            val stdout = BufferedReader(process.inputStream.reader()).readText()
            val stderr = BufferedReader(process.errorStream.reader()).readText()
            val exitCode = if (completed) process.exitValue() else -1

            Log.d(TAG, "Command completed: $command")
            Log.d(TAG, "Final command was: $command")
            Log.d(TAG, "Exit code: $exitCode")
            Log.d(TAG, "=== Full Shell Output ===")
            Log.d(TAG, stdout)
            Log.d(TAG, "=== Shell Error ===")
            Log.d(TAG, stderr)
            Log.d(TAG, "=== End Output ===")

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
     * 执行命令 - 使用 adb() 函数方式，和 getDevices 一样
     */
    suspend fun executeCommand(command: List<String>): ShellResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Executing: $command")


            // 使用和 getDevices 完全一样的方式：adb() 函数
            val process = adb(false, (command))
            val completed = process.waitFor(30, TimeUnit.SECONDS)

            // 分别读取标准输出和标准错误流
            val stdout = BufferedReader(process.inputStream.reader()).readText()
            val stderr = BufferedReader(process.errorStream.reader()).readText()
            val exitCode = if (completed) process.exitValue() else -1

            Log.d(TAG, "Command completed: $command")
            Log.d(TAG, "Final command was: $command")
            Log.d(TAG, "Exit code: $exitCode")
            Log.d(TAG, "=== Full Shell Output ===")
            Log.d(TAG, stdout)
            Log.d(TAG, "=== Shell Error ===")
            Log.d(TAG, stderr)
            Log.d(TAG, "=== End Output ===")

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
     * 异步执行命令 - 不等待结果，立即返回
     * 适用于不需要立即等待结果的场景（如点击、滑动）
     */
    fun executeAsync(command: String, callback: ((ShellResult) -> Unit)? = null) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Executing async: $command")

                // 直接调用 execute 方法
                val result = executeShell(command)
                callback?.invoke(result)

            } catch (e: Exception) {
                Log.e(TAG, "Async command failed", e)
                val result = ShellResult(
                    success = false,
                    stdout = "",
                    stderr = e.message ?: "Unknown error",
                    exitCode = -1
                )
                callback?.invoke(result)
            }
        }
    }


    /**
     * 配对功能（Android 11+）- LADB 的 pair 方法
     */
    suspend fun pair(port: String, pairingCode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            debug("Pairing to port $port with code $pairingCode...")
            val pairShell = adb(false, listOf("pair", "localhost:$port"))

            Thread.sleep(5000)

            PrintStream(pairShell.outputStream).apply {
                println(pairingCode)
                flush()
            }

            val success = pairShell.waitFor(10, TimeUnit.SECONDS)
            pairShell.destroyForcibly().waitFor()

            val killShell = adb(false, listOf("kill-server"))
            killShell.waitFor(3, TimeUnit.SECONDS)
            killShell.destroyForcibly()

            val result = pairShell.exitValue() == 0
            if (result) {
                debug("Pairing successful!")
            } else {
                debug("Pairing failed!")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Pairing failed", e)
            debug("Pairing failed: ${e.message}")
            false
        }
    }

    /**
     * 自动重连机制 - LADB 的 waitForDeathAndReset 方法
     */
    fun waitForDeathAndReset() {
        Thread {
            try {
                while (true) {
                    if (!tryingToPair) {
                        shellProcess?.waitFor()
                        _running.postValue(false)
                        debug("Shell is dead, resetting...")
                        adb(false, listOf("kill-server")).waitFor()

                        Thread.sleep(3_000)
                        // 使用协程启动 initServer
                        kotlinx.coroutines.GlobalScope.launch {
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
     * 检查无线调试是否启用
     */
    private fun isWirelessDebuggingEnabled(): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查 USB 调试是否启用
     */
    private fun isUSBDebuggingEnabled(): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查移动数据是否始终开启
     */
    private fun isMobileDataAlwaysOnEnabled(): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, "mobile_data_always_on", 0) == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 禁用移动数据始终开启 - LADB 的 disableMobileDataAlwaysOn 方法
     */
    private fun disableMobileDataAlwaysOn() {
        val secureSettingsGranted =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        if (secureSettingsGranted) {
            if (isMobileDataAlwaysOnEnabled()) {
                debug("Disabling 'Mobile data always on'...")
                Settings.Global.putInt(
                    context.contentResolver,
                    "mobile_data_always_on",
                    0
                )
                Thread.sleep(3_000)
            }
        }
    }

    /**
     * 循环切换无线调试 - LADB 的 cycleWirelessDebugging 方法
     */
    private fun cycleWirelessDebugging() {
        val secureSettingsGranted =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        if (secureSettingsGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                debug("Cycling wireless debugging, please wait...")

                // Only turn it off if it's already on
                if (isWirelessDebuggingEnabled()) {
                    debug("Turning off wireless debugging...")
                    Settings.Global.putInt(
                        context.contentResolver,
                        "adb_wifi_enabled",
                        0
                    )
                    Thread.sleep(3_000)
                }

                debug("Turning on wireless debugging...")
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    1
                )
                Thread.sleep(3_000)

                debug("Turning off wireless debugging...")
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    0
                )
                Thread.sleep(3_000)

                debug("Turning on wireless debugging...")
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    1
                )
                Thread.sleep(3_000)
            }
        }
    }


    /**
     * 连接到指定 IP 的设备
     */
    suspend fun connectToDevice(ip: String, port: Int = 5555): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val address = "$ip:$port"
                Log.d(TAG, "Connecting to device at $address...")
                val result = executeCommand(listOf("connect", address))
//            executeADB("devices")

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
     * 检查是否已连接到本地设备
     */
    fun isConnectedToLocalDevice(): Boolean {
        val devices = getDevices()
        return devices.any { it.contains("localhost") || it.contains("127.0.0.1") }
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        try {
            shellProcess?.destroy()
            shellProcess = null
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed", e)
        }
    }

    /**
     * 写调试消息 - LADB 的 debug 方法
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
     * 检查是否可用 - 现在只检查初始化状态
     */
    fun isAvailable(): Boolean {
        return _running.value == true
    }

    /**
     * 获取输出缓冲区大小
     */
    fun getOutputBufferSize(): Int = MAX_OUTPUT_BUFFER_SIZE

    /**
     * 公开的无线调试状态检查（供其他类使用）
     */
    fun checkWirelessDebuggingEnabled(): Boolean = isWirelessDebuggingEnabled()

    /**
     * 公开的 USB 调试状态检查（供其他类使用）
     */
    fun checkUSBDebuggingEnabled(): Boolean = isUSBDebuggingEnabled()

    /**
     * 快速检查 LADB 是否可用 - 仅检查库文件，不执行完整初始化
     * 用于状态显示和 UI 更新，速度极快
     */
    fun isLadbLibraryAvailable(): Boolean {
        return try {
            val adbFile = File(adbPath)
            adbFile.exists() && adbFile.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check ADB library", e)
            false
        }
    }
}

/**
 * DNS 发现类 - 完整实现 LADB 的 DnsDiscover 功能
 */
class DnsDiscover private constructor(
    private val context: Context,
    private val nsdManager: NsdManager,
    private val debugTag: String,
    private val serviceType: String
) {
    private var started = false
    private var bestExpirationTime: Long? = null
    private var bestServiceName: String? = null

    private var pendingServices: MutableList<NsdServiceInfo> =
        Collections.synchronizedList(ArrayList())

    companion object {
        var bestAdbPort: Int? = null
        var adbPorts: ArrayList<Int> = arrayListOf()
        var pendingResolves = AtomicBoolean(false)
        var aliveTime: Long? = null

        // 清理ADB端口列表，防止重复累积
        fun clearAdbPorts() {
            adbPorts.clear()
            Log.d("DnsDiscover", "清理ADB端口列表")
        }

        fun getInstance(context: Context, nsdManager: NsdManager): DnsDiscover {
            return DnsDiscover(context, nsdManager, "DnsDiscover", "_adb-tls-connect._tcp")
        }
    }

    /**
     * 扫描 ADB 端口
     */
    fun scanAdbPorts() {
        clearAdbPorts()
        if (started) {
            Log.w(debugTag, "DNS scan already started")
            return
        }
        started = true
        aliveTime = System.currentTimeMillis()

        try {
            nsdManager.discoverServices(
                serviceType,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
            Log.d(debugTag, "Started DNS service discovery")
        } catch (e: Exception) {
            Log.e(debugTag, "Failed to start DNS discovery", e)
        }
    }

    /**
     * 获取本地 IP 地址
     */
    fun getLocalIpAddress(): String? {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val addresses = networkInterface.inetAddresses

                    while (addresses.hasMoreElements()) {
                        val inetAddress = addresses.nextElement()
                        if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                            return inetAddress.hostAddress
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(debugTag, "Failed to get local IP", e)
            }
        }

        return null
    }

    /**
     * 更新最新的服务
     */
    private fun updateIfNewest(serviceInfo: NsdServiceInfo) {
        val port = serviceInfo.port
        val expirationTime = parseExpirationTime(serviceInfo.toString())
        val serviceName = serviceInfo.serviceName

        fun getHighestNumberedString(strings: List<String>): String {
            return strings.maxByOrNull {
                """\((\d+)\)""".toRegex().find(it)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            } ?: strings.first()
        }

        fun update() {
            bestAdbPort = port
            bestExpirationTime = expirationTime
            bestServiceName = serviceName
            Log.d(debugTag, "Updated best ADB port: $bestAdbPort")
        }

        if (bestAdbPort == null) {
            update()
            return
        }

        if (expirationTime != null) {
            if (bestExpirationTime == null) {
                update()
                return
            }

            if (expirationTime > bestExpirationTime!!) {
                update()
                return
            }
        }

        if (serviceName == getHighestNumberedString(listOf(bestServiceName ?: "", serviceName))) {
            update()
            return
        }
    }

    /**
     * 解析过期时间
     */
    private fun parseExpirationTime(rawString: String): Long? {
        val regex = """expirationTime: (\S+)""".toRegex()
        val expirationTimeStr = regex.find(rawString)?.groupValues?.get(1)

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")

        return try {
            dateFormat.parse(expirationTimeStr ?: "")?.time
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 处理已解析的服务
     */
    private fun handleResolvedService(serviceInfo: NsdServiceInfo) {
        Log.d(debugTag, "Resolve successful: $serviceInfo")

        val ipAddress = getLocalIpAddress()
        Log.d("IP ADDRESS", ipAddress ?: "N/A")

        val discoveredAddress = serviceInfo.host?.hostAddress
        if (ipAddress != null && discoveredAddress != null && discoveredAddress != ipAddress) {
            Log.d(debugTag, "IP does not match device")
            return
        }

        if (serviceInfo.port == 0) {
            Log.d(debugTag, "Port is zero, skipping...")
            return
        }
        adbPorts.add(serviceInfo.port)
        updateIfNewest(serviceInfo)
    }

    /**
     * 解析服务
     */
    private fun resolveService(service: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(debugTag, "Resolve failed: $errorCode: $serviceInfo")

                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    resolveService(serviceInfo)
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                handleResolvedService(serviceInfo)
                pendingServices.removeAll { it.serviceName == serviceInfo.serviceName }

                if (pendingServices.isEmpty()) {
                    pendingResolves.set(false)
                }

                Log.d(debugTag, "Service resolved, pending: ${pendingServices.size}")
            }
        }

        try {
            nsdManager.resolveService(service, resolveListener)
        } catch (e: Exception) {
            Log.e(debugTag, "Failed to resolve service", e)
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(debugTag, "Service discovery started: $regType")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d(debugTag, "Service found: ${service.serviceName}, port: ${service.port}")

            pendingServices.add(service)
            pendingResolves.set(true)

            resolveService(service)
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.e(debugTag, "Service lost: ${service.serviceName}")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(debugTag, "Discovery stopped: $serviceType")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(debugTag, "Discovery failed: Error code: $errorCode")
            nsdManager.stopServiceDiscovery(this)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(debugTag, "Stop discovery failed: Error code: $errorCode")
            nsdManager.stopServiceDiscovery(this)
        }
    }
}

data class ShellResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int
)

package com.qs.phone

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.qs.phone.service.FloatingWindowService
import com.qs.phone.shell.ShellExecutor
import com.qs.phone.shell.DnsDiscover
import com.qs.phone.ui.DiagnosticTool
import com.qs.phone.ui.ErrorDialog
import com.qs.phone.util.NativeLibraryLoader
import com.qs.phone.util.PermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Log
import android.content.Context

class MainActivity : AppCompatActivity() {

    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var enableServiceButton: Button
    private lateinit var baseUrlInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var modelNameInput: EditText
    private lateinit var saveButton: Button
    private lateinit var ladbStatusText: TextView
    private lateinit var ladbHelpButton: Button
    private lateinit var dnsConnectButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var listDevicesButton: Button
    private lateinit var diagnosticButton: Button
    private lateinit var requestPermissionButton: Button
    private lateinit var permissionStatusText: TextView
    private lateinit var checkWirelessButton: Button
    private lateinit var wirelessStatusText: TextView
    private lateinit var screenshotTestButton: Button

    private val prefs by lazy {
        getSharedPreferences("zhiai_config", Context.MODE_PRIVATE)
    }

    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val shellExecutor by lazy { ShellExecutor(this@MainActivity) }

    // DNS连接相关变量
    private var dnsSearchJob: kotlinx.coroutines.Job? = null
    private var isDnsSearching = false
    private var dnsDiscover: DnsDiscover? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 加载原生库
        NativeLibraryLoader.loadLibraries(this)

        initViews()
        loadConfig()
        setupListeners()
        updatePermissionStatus()
        checkLadbStatus()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun initViews() {
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        enableServiceButton = findViewById(R.id.enableServiceButton)
        baseUrlInput = findViewById(R.id.baseUrlInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        modelNameInput = findViewById(R.id.modelNameInput)
        saveButton = findViewById(R.id.saveButton)
        ladbStatusText = findViewById(R.id.ladbStatusText)
        ladbHelpButton = findViewById(R.id.ladbHelpButton)
        dnsConnectButton = findViewById(R.id.dnsConnectButton)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        listDevicesButton = findViewById(R.id.listDevicesButton)
        diagnosticButton = findViewById(R.id.diagnosticButton)
        requestPermissionButton = findViewById(R.id.requestPermissionButton)
        permissionStatusText = findViewById(R.id.permissionStatusText)
        checkWirelessButton = findViewById(R.id.checkWirelessButton)
        wirelessStatusText = findViewById(R.id.wirelessStatusText)
        screenshotTestButton = findViewById(R.id.screenshotTestButton)
    }

    private fun loadConfig() {
        baseUrlInput.setText(prefs.getString("base_url", "http://localhost:8000/v1"))
        apiKeyInput.setText(prefs.getString("api_key", "EMPTY"))
        modelNameInput.setText(prefs.getString("model_name", "autoglm-phone-9b"))

        // 同步到服务
        syncConfigToService()
    }

    private fun setupListeners() {
        enableServiceButton.setOnClickListener {
            openAccessibilitySettings()
        }

        saveButton.setOnClickListener {
            saveConfig()
        }

        ladbHelpButton.setOnClickListener {
            ErrorDialog.showLadbNotAvailable(this)
        }

        requestPermissionButton.setOnClickListener {
            PermissionManager.requestStoragePermissions(this) { granted ->
                if (granted) {
                    Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
                    updatePermissionStatus()
                } else {
                    Toast.makeText(this, "权限被拒绝", Toast.LENGTH_SHORT).show()
                    updatePermissionStatus()
                }
            }
        }

        dnsConnectButton.setOnClickListener {
            showDnsConnectionDialog()
        }

        connectButton.setOnClickListener {
            mainScope.launch {
                try {
                    val shell = shellExecutor
                    val success = shell.connectToDevice("localhost", 5555)
                    Toast.makeText(
                        this@MainActivity,
                        if (success) "已连接到本地设备" else "连接失败",
                        Toast.LENGTH_SHORT
                    ).show()
                    checkLadbStatus()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "错误: ${e.message}", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }

        disconnectButton.setOnClickListener {
            mainScope.launch {
                try {
                    val shell = shellExecutor
                    val success = shell.disconnectAll()
                    Toast.makeText(
                        this@MainActivity,
                        if (success) "已断开所有连接" else "断开连接失败",
                        Toast.LENGTH_SHORT
                    ).show()
                    checkLadbStatus()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "错误: ${e.message}", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }

        listDevicesButton.setOnClickListener {
            mainScope.launch {
                try {
                    val shell = shellExecutor
                    val devices = shell.getDevices()

                    if (devices.isEmpty()) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("设备列表")
                            .setMessage("未检测到任何 ADB 设备\n\n请检查：\n• 是否已启用调试模式\n• LADB 是否已正确授权\n• 设备是否已连接")
                            .setPositiveButton("确定", null)
                            .show()
                    } else {
                        val deviceList = devices.joinToString("\n")
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("检测到的设备 (${devices.size} 个)")
                            .setMessage(deviceList)
                            .setPositiveButton("确定", null)
                            .show()
                    }

                    // 更新状态显示
                    checkLadbStatus()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "列出设备失败: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        diagnosticButton.setOnClickListener {
            mainScope.launch {
                try {
                    Toast.makeText(this@MainActivity, "正在运行诊断...", Toast.LENGTH_SHORT).show()

                    val results = DiagnosticTool.runFullDiagnostic(this@MainActivity)
                    val report = DiagnosticTool.generateReport(results)

                    // 显示诊断报告
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("🔍 诊断报告")
                        .setMessage(report)
                        .setPositiveButton("确定", null)
                        .setNeutralButton("分享报告") { _, _ ->
                            shareDiagnosticReport(report)
                        }
                        .show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "诊断失败: ${e.message}", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }

        checkWirelessButton.setOnClickListener {
            checkWirelessDebuggingStatus()
        }

        screenshotTestButton.setOnClickListener {
            performScreenshotTest()
        }
    }

    /**
     * 单独检查无线调试状态 - 快速检测，不依赖 LADB 初始化
     */
    private fun checkWirelessDebuggingStatus() {
        mainScope.launch {
            try {
                wirelessStatusText.text = "🔄 检测中..."
                checkWirelessButton.isEnabled = false

                // 直接检查系统设置，无需初始化 LADB
                val wirelessEnabled = shellExecutor.checkWirelessDebuggingEnabled()
                val usbEnabled = shellExecutor.checkUSBDebuggingEnabled()

                val status = buildString {
                    if (wirelessEnabled) {
                        append("✅ 无线调试已启用")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            append("\n   📱 Android 11+ 无线调试")
                        }
                    } else if (usbEnabled) {
                        append("⚠️ 无线调试未启用")
                        append("\n   🔌 USB 调试已启用")
                    } else {
                        append("❌ 无线调试未启用")
                        append("\n   ⚠️ 请在开发者选项中启用调试模式")
                    }
                }

                wirelessStatusText.text = status
                checkWirelessButton.isEnabled = true
            } catch (e: Exception) {
                wirelessStatusText.text = "❌ 检测失败: ${e.message}"
                checkWirelessButton.isEnabled = true
            }
        }
    }

    private fun checkLadbStatus() {
        mainScope.launch {
            try {
                // 使用快速检查，仅验证库文件是否存在，不执行完整初始化
                val ladbAvailable = shellExecutor.isLadbLibraryAvailable()
                val usbEnabled = shellExecutor.checkUSBDebuggingEnabled()

                val status = buildString {
                    append("LADB 状态: ")
                    if (ladbAvailable) {
                        append("✅ 已内置\n")
                        append("   📦 本地 ADB 库已集成到应用中\n")
                    } else {
                        append("❌ 不可用\n")
                        append("   💡 内置 ADB 库加载失败，请重新安装应用\n")
                    }
                    append("USB 调试: ")
                    if (usbEnabled) {
                        append("✅ 已启用")
                    } else {
                        append("⚠️ 未启用")
                    }
                }

                ladbStatusText.text = status

                // 显示/隐藏帮助按钮
                ladbHelpButton.visibility =
                    if (ladbAvailable) android.view.View.GONE else android.view.View.VISIBLE

                // 启用/禁用按钮 - 只有库文件存在时才允许操作
                val enableControls = ladbAvailable
                val hasNetworkPermission =
                    checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                dnsConnectButton.isEnabled =
                    enableControls && !isDnsSearching && hasNetworkPermission
                connectButton.isEnabled = enableControls
                disconnectButton.isEnabled = enableControls
                listDevicesButton.isEnabled = enableControls
                diagnosticButton.isEnabled = true  // 诊断按钮总是启用

                if (!ladbAvailable) {
                    dnsConnectButton.text = "需要 LADB 或 Root"
                    connectButton.text = "需要 LADB 或 Root"
                    disconnectButton.text = "需要 LADB 或 Root"
                    listDevicesButton.text = "需要 LADB 或 Root"
                } else if (!hasNetworkPermission) {
                    dnsConnectButton.text = "需要网络权限"
                }

                // 如果 LADB 不可用，显示诊断提示
                if (!usbEnabled) {
                    ladbStatusText.text = status + "\n\n⚠️ 请先在开发者选项中启用调试模式"
                }
            } catch (e: Exception) {
                ladbStatusText.text = "状态检查失败: ${e.message}"
                ladbHelpButton.visibility = android.view.View.VISIBLE
            }
        }
    }

    /**
     * 更新权限状态显示
     */
    private fun updatePermissionStatus() {
        val hasPermissions = PermissionManager.hasStoragePermissions(this)

        val status = buildString {
            if (hasPermissions) {
                append("✅ 文件读写权限已授予\n\n")
            } else {
                append("❌ 文件读写权限未授予\n\n")
            }

            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    append("需要权限：READ_MEDIA_IMAGES\n")
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    append("需要权限：READ_EXTERNAL_STORAGE\n")
                }

                else -> {
                    append("需要权限：WRITE_EXTERNAL_STORAGE\n")
                    append("需要权限：READ_EXTERNAL_STORAGE\n")
                }
            }

            if (!hasPermissions) {
                append("\n点击下方按钮申请权限")
            } else {
                append("\n✅ 可以保存截图和日志文件")
            }
        }

        permissionStatusText.text = status

        // 更新按钮状态
        if (hasPermissions) {
            requestPermissionButton.text = "权限已授予"
            requestPermissionButton.isEnabled = false
        } else {
            requestPermissionButton.text = "请求文件读写权限"
            requestPermissionButton.isEnabled = true
        }
    }

    private fun saveConfig() {
        val baseUrl = baseUrlInput.text.toString().trim()
        val apiKey = apiKeyInput.text.toString().trim()
        val modelName = modelNameInput.text.toString().trim()

        if (baseUrl.isEmpty()) {
            Toast.makeText(this, "请输入 Base URL", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit().apply {
            putString("base_url", baseUrl)
            putString("api_key", apiKey)
            putString("model_name", modelName)
            apply()
        }

        syncConfigToService()
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun syncConfigToService() {
        FloatingWindowService.baseUrl =
            prefs.getString("base_url", "http://localhost:8000/v1") ?: "http://localhost:8000/v1"
        FloatingWindowService.apiKey = prefs.getString("api_key", "EMPTY") ?: "EMPTY"
        FloatingWindowService.modelName =
            prefs.getString("model_name", "autoglm-phone-9b") ?: "autoglm-phone-9b"
    }

    private fun updateServiceStatus() {
        val isEnabled = isAccessibilityServiceEnabled()

        if (isEnabled) {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_on)
            statusText.setText(R.string.service_enabled)
            enableServiceButton.text = "已启用"
        } else {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_off)
            statusText.setText(R.string.service_disabled)
            enableServiceButton.setText(R.string.enable_service)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/${FloatingWindowService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.contains(serviceName)
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "请在列表中找到「ZhiAI」并开启", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开设置页面", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 执行截图测试 - 调用 ADB 命令并显示截图
     */
    private fun performScreenshotTest() {
        mainScope.launch {
            try {
                Toast.makeText(this@MainActivity, "正在截图...", Toast.LENGTH_SHORT).show()

//                // 确保 LADB 已初始化
                val shell = shellExecutor

//                if (!shell.isLadbLibraryAvailable()) {
//                    Toast.makeText(this@MainActivity, "LADB 库不可用", Toast.LENGTH_SHORT).show()
//                    return@launch
//                }

//                // 初始化 LADB
//                val initSuccess = shell.initServer()
//                if (!initSuccess) {
//                    Toast.makeText(this@MainActivity, "LADB 初始化失败", Toast.LENGTH_SHORT).show()
//                    return@launch
//                }

                // 创建截图目录
                val screenshotDir = File(getExternalFilesDir(null), "screenshots")
                if (!screenshotDir.exists()) {
                    screenshotDir.mkdirs()
                }

                val timestamp = System.currentTimeMillis()
                val remotePath = "/sdcard/Android/data/${packageName}/screenshot_${timestamp}.png"
                val localPath = File(remotePath)

                // 执行截图命令
                val result = shell.executeShell("screencap -p $remotePath")
                if (!result.success) {
                    Toast.makeText(
                        this@MainActivity,
                        "截图失败: ${result.stderr}",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                // 等待一下让截图完成
//                kotlinx.coroutines.delay(500)

                // 从设备拉取截图
                if (localPath.exists() && localPath.length() > 0) {
                    // 显示截图 dialog
                    showScreenshotDialog(localPath.absolutePath)
                    Toast.makeText(
                        this@MainActivity,
                        "截图成功！保存到: ${localPath.absolutePath}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this@MainActivity, "拉取截图失败", Toast.LENGTH_SHORT).show()
                }

                // 用完即删
//                shell.execute("adb shell rm $remotePath")

            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "截图测试失败: ${e.message}", Toast.LENGTH_SHORT)
                    .show()
                e.printStackTrace()
            }
        }
    }

    /**
     * 显示截图的 dialog
     */
    private fun showScreenshotDialog(imagePath: String) {
        try {
            // 创建自定义 dialog 布局
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_screenshot, null)
            val imageView = dialogView.findViewById<ImageView>(R.id.screenshotImageView)

            // 加载并显示图片
            val bitmap = BitmapFactory.decodeFile(imagePath)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                imageView.setImageDrawable(
                    ContextCompat.getDrawable(
                        this,
                        android.R.drawable.ic_dialog_alert
                    )
                )
            }

            // 创建 dialog
            val dialog = AlertDialog.Builder(this)
                .setTitle("📸 截图测试结果")
                .setView(dialogView)
                .setPositiveButton("确定", null)
                .setNeutralButton("分享") { _, _ ->
                    shareImage(imagePath)
                }
                .create()

            dialog.show()

        } catch (e: Exception) {
            Toast.makeText(this, "显示截图失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 分享截图图片
     */
    private fun shareImage(imagePath: String) {
        try {
            val imageFile = File(imagePath)
            val imageUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                imageFile
            )

            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, imageUri)
                putExtra(Intent.EXTRA_SUBJECT, "ZhiAI 截图测试")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "分享截图"))

        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareDiagnosticReport(report: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, report)
                putExtra(Intent.EXTRA_SUBJECT, "ZhiAI 诊断报告")
            }
            startActivity(Intent.createChooser(intent, "分享诊断报告"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        val granted =
            PermissionManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatus()
    }

    /**
     * 显示DNS连接对话框
     */
    private fun showDnsConnectionDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_dns_connection, null)
        val statusText = dialogView.findViewById<TextView>(R.id.dnsStatusText)
        val cancelButton = dialogView.findViewById<Button>(R.id.dnsCancelButton)

        // 创建对话框
        val dialog = AlertDialog.Builder(this)
            .setTitle("DNS 无线调试连接")
            .setView(dialogView)
            .setCancelable(false)  // 默认不可取消，除非用户点击取消按钮
            .create()

        // 开始DNS搜索
        startDnsSearch(statusText, dialog, cancelButton)

        // 取消按钮点击事件
        cancelButton.setOnClickListener {
            stopDnsSearch()
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * 开始DNS搜索设备（按照LADB完整初始化流程）
     */
    private fun startDnsSearch(statusText: TextView, dialog: AlertDialog, cancelButton: Button) {
        isDnsSearching = true
        dnsConnectButton.text = "搜索中..."
        dnsConnectButton.isEnabled = false
        cancelButton.isEnabled = true

        dnsSearchJob = mainScope.launch {
            try {
                // 检查网络状态权限
                if (checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    statusText.text = "❌ 缺少网络状态权限\n\n请授予权限后重试"
                    delay(3000)
                    dialog.dismiss()
                    return@launch
                }

                statusText.text = "🔄 正在初始化ADB服务..."
                statusText.append("\n\n请确保已开启无线调试")

                // 完全按照LADB的initServer方式实现
                val shell = shellExecutor
                if (!shell.isLadbLibraryAvailable()) {
                    statusText.text = "❌ LADB 库不可用\n\n请确保应用权限正常"
                    return@launch
                }

                // 按照LADB方式执行完整初始化
                val success = performLadbDnsConnection(shell, statusText)

                if (success) {
                    val devices = shell.getDevices()
                    statusText.text = "✅ DNS连接成功！\n\n发现设备:\n${devices.joinToString("\n")}"
                    checkLadbStatus()
                } else {
                    statusText.text =
                        "❌ DNS连接失败\n\n请确保：\n• 无线调试已开启\n• 已配对本机设备\n• 网络连接正常"
                }

            } catch (e: Exception) {
                Log.e("MainActivity", "DNS search error", e)
                statusText.text = "❌ 连接过程中发生错误\n\n${e.message}"
            } finally {
                // 重置状态
                isDnsSearching = false
                dnsDiscover = null

                runOnUiThread {
                    dnsConnectButton.text = "DNS 连接无线调试"
                    dnsConnectButton.isEnabled =
                        shellExecutor.isLadbLibraryAvailable() && !isDnsSearching
                }

                // 3秒后自动关闭对话框
                delay(3000)
                dialog.dismiss()
            }
        }
    }

    /**
     * 按照LADB方式执行DNS连接
     */
    private suspend fun performLadbDnsConnection(
        shell: ShellExecutor,
        statusText: TextView
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 更新UI必须在主线程
            runOnUiThread { statusText.text = "🔄 启动DNS服务发现..." }

            // 获取NSD管理器并开始DNS发现
            val nsdManager = getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager
            dnsDiscover = DnsDiscover.getInstance(this@MainActivity, nsdManager)

            // 重置静态变量
            DnsDiscover.bestAdbPort = null
            DnsDiscover.pendingResolves.set(false)
            DnsDiscover.aliveTime = System.currentTimeMillis()

            // 开始扫描
            dnsDiscover?.scanAdbPorts()

            runOnUiThread { statusText.text = "🔍 搜索无线调试服务..." }

            // 等待DNS解析完成（按照LADB的等待逻辑）
            val nowTime = System.currentTimeMillis()
            val maxTimeoutTime = nowTime + 10000 // 10秒超时
            val minDnsScanTime = (DnsDiscover.aliveTime ?: nowTime) + 3000 // 最少3秒

            var dnsWaitCount = 0
            while (true) {
                if (!isDnsSearching) break

                val currentTime = System.currentTimeMillis()
                val pendingResolves = DnsDiscover.pendingResolves.get()

                // 更新UI状态 - 必须在主线程
                val elapsedSeconds = (currentTime - nowTime) / 1000
                runOnUiThread {
                    statusText.text =
                        "🔍 搜索无线调试服务 (${elapsedSeconds}s)...\n\n⏳ 正在发现ADB端口"
                }

                if (currentTime >= minDnsScanTime && !pendingResolves) {
                    runOnUiThread { statusText.text = "✅ DNS解析完成" }
                    break
                }

                if (currentTime >= maxTimeoutTime) {
                    runOnUiThread { statusText.text = "⚠️ DNS发现超时" }
                    break
                }

                Thread.sleep(1000)
                dnsWaitCount++
                if (dnsWaitCount >= 30) break
            }

            val adbPort = DnsDiscover.bestAdbPort
            Log.e("ports", DnsDiscover.adbPorts.toString())
            if (adbPort != null) {
                runOnUiThread {
                    statusText.text = "✅ 发现ADB端口: $adbPort\n\n正在启动ADB服务器..."
                }

//                // 按照LADB方式：启动ADB服务器
//                shell.executeADB("adb start-server")
//                Thread.sleep(2000)


                // 连接到发现的端口s   只要有一个成功连接那么就可以了
                var connected = false
                for (port in DnsDiscover.adbPorts) {
                    runOnUiThread { statusText.text = "🔄 正在连接到 localhost:$port..." }
                    Log.e("在连接到 local  ","ports"+port+"")
                    connected = connected or shell.connectToDevice("localhost", port)
                }
                if (connected) {
                    return@withContext true
                }
            } else {
                runOnUiThread { statusText.text = "❌ 未发现ADB端口\n\n尝试默认连接方式..." }

//                // 回退到LADB的默认方式：wait-for-device
//                shell.executeADB("adb start-server")
//                Thread.sleep(2000)

                runOnUiThread { statusText.text = "🔄 等待设备连接..." }
                val devices = shell.getDevices()
                return@withContext devices.isNotEmpty()
            }

            return@withContext false
        } catch (e: Exception) {
            Log.e("MainActivity", "Ladb DNS connection failed", e)
            runOnUiThread { statusText.text = "❌ 连接失败: ${e.message}" }
            return@withContext false
        }
    }

    /**
     * 停止DNS搜索
     */
    private fun stopDnsSearch() {
        isDnsSearching = false
        dnsSearchJob?.cancel()
        dnsSearchJob = null

        dnsConnectButton.text = "DNS 连接无线调试"
        dnsConnectButton.isEnabled = shellExecutor.isLadbLibraryAvailable()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理协程，避免内存泄漏
        dnsSearchJob?.cancel()
        dnsSearchJob = null
    }
}

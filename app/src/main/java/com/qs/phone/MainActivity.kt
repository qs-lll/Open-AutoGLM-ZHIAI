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
import com.qs.phone.service.WirelessAdbPairingService
import com.qs.phone.shell.ShellExecutor
import com.qs.phone.discovery.DnsDiscoveryManager
import com.qs.phone.ui.DiagnosticTool
import com.qs.phone.ui.ErrorDialog
import com.qs.phone.util.NativeLibraryLoader
import com.qs.phone.util.PermissionManager
import com.qs.phone.controller.AppDetectionTest
import com.qs.phone.controller.DeviceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Log
import android.content.Context

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_INSTALL_PERMISSION = 1001
    }

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
    private lateinit var continuousSearchButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var listDevicesButton: Button
    private lateinit var diagnosticButton: Button
    private lateinit var checkWirelessButton: Button
    private lateinit var wirelessStatusText: TextView
    private lateinit var screenshotTestButton: Button

    // 检测项视图
    private lateinit var ladbStatusImageView: ImageView
    private lateinit var setupLadbButton: Button
    private lateinit var deviceStatusImageView: ImageView
    private lateinit var connectDeviceButton: Button
    private lateinit var imeStatusImageView: ImageView
    private lateinit var installImeButton: Button

    private val prefs by lazy {
        getSharedPreferences("zhiai_config", Context.MODE_PRIVATE)
    }

    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val shellExecutor by lazy { ShellExecutor(this@MainActivity) }

    // DNS连接相关变量
    private var dnsSearchJob: kotlinx.coroutines.Job? = null
    private var isDnsSearching = false
    private var dnsDiscoveryManager: DnsDiscoveryManager? = null

    // 持续搜索相关变量
    private var continuousSearchJob: kotlinx.coroutines.Job? = null
    private var isContinuousSearching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 在后台线程加载原生库
        mainScope.launch(Dispatchers.IO) {
//            NativeLibraryLoader.loadLibraries(this@MainActivity)
        }

        initViews()
        loadConfig()
        setupListeners()

        // 自动申请权限
        autoRequestPermissions()

        // 延迟检查 LADB 状态，等待库加载完成
//        mainScope.launch {
//            delay(3300)  // 给库加载一些时间
//            checkLadbStatus()
//            initDetectionStatus()
//        }
//        // 系统检测时安装 ADBKeyboard
//        performSystemCheck()
    }

    /**
     * 自动申请权限（静默申请，不显示 UI）
     */
    private fun autoRequestPermissions() {
        if (ZhiAIApplication.shouldRequestPermissions(this)) {
            Log.d("MainActivity", "自动申请存储权限")
            PermissionManager.requestStoragePermissions(this) { granted ->
                if (granted) {
                    Log.i("MainActivity", "✅ 权限已自动授予")
                } else {
                    Log.w("MainActivity", "⚠️ 权限被拒绝，部分功能可能受限")
                }
            }
        } else {
            Log.i("MainActivity", "✅ 权限已存在，跳过申请")
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()

        // 重新检查检测状态
        checkAllDetectionStatus()

        // 移除自动设备检查，避免触发 ADB 自动重连
        // 如果需要检查设备，用户应该手动点击"列出设备"按钮
        // mainScope.launch {
        //     val devices = shellExecutor.getDevicesSuspending()
        //     Log.e("resume=devices",devices.toString())
        // }
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
        continuousSearchButton = findViewById(R.id.continuousSearchButton)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        listDevicesButton = findViewById(R.id.listDevicesButton)
        diagnosticButton = findViewById(R.id.diagnosticButton)
        checkWirelessButton = findViewById(R.id.checkWirelessButton)
        wirelessStatusText = findViewById(R.id.wirelessStatusText)
        screenshotTestButton = findViewById(R.id.screenshotTestButton)

        // 初始化检测项视图
        ladbStatusImageView = findViewById(R.id.iv_ladb_status)
        setupLadbButton = findViewById(R.id.btn_setup_ladb)
        deviceStatusImageView = findViewById(R.id.iv_device_status)
        connectDeviceButton = findViewById(R.id.btn_connect_device)
        imeStatusImageView = findViewById(R.id.iv_ime_status)
        installImeButton = findViewById(R.id.btn_install_ime)
    }

    private fun loadConfig() {
        baseUrlInput.setText(prefs.getString("base_url", "https://open.bigmodel.cn/api/paas/v4"))
        apiKeyInput.setText(prefs.getString("api_key", "EMPTY"))
        modelNameInput.setText(prefs.getString("model_name", "autoglm-phone"))

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

        dnsConnectButton.setOnClickListener {
            showDnsConnectionDialog()
        }

        continuousSearchButton.setOnClickListener {
            if (!isContinuousSearching) {
                startContinuousSearch()
            } else {
                stopContinuousSearch()
            }
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
                    val devices = shell.getDevicesSuspending()

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

                    // 测试新的应用检测功能
                    val appDetectionTest = AppDetectionTest(this@MainActivity)
                    appDetectionTest.testAppDetection()

                    val results = DiagnosticTool.runFullDiagnostic(this@MainActivity)
                    var report = DiagnosticTool.generateReport(results)

                    // 添加输入法状态诊断
                    try {
                        val deviceController = DeviceController(this@MainActivity)
                        val inputMethodInfo = deviceController.getInputMethodInfo()
                        report = "\n\n$inputMethodInfo\n\n$report"
                    } catch (e: Exception) {
                        Log.w("MainActivity", "获取输入法状态失败", e)
                    }

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

        // 设置检测项的点击事件
        setupLadbButton.setOnClickListener {
            setupLadb()
        }

        connectDeviceButton.setOnClickListener {
            connectDevice()
        }

        installImeButton.setOnClickListener {
            installInputMethod()
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
                val ladbAvailable = shellExecutor.isAdbLibraryAvailable()
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
                continuousSearchButton.isEnabled =
                    enableControls && !isContinuousSearching && hasNetworkPermission
                connectButton.isEnabled = enableControls
                disconnectButton.isEnabled = enableControls
                listDevicesButton.isEnabled = enableControls
                diagnosticButton.isEnabled = true  // 诊断按钮总是启用

                if (!ladbAvailable) {
                    dnsConnectButton.text = "需要 LADB 或 Root"
                    continuousSearchButton.text = "需要 LADB 或 Root"
                    connectButton.text = "需要 LADB 或 Root"
                    disconnectButton.text = "需要 LADB 或 Root"
                    listDevicesButton.text = "需要 LADB 或 Root"
                } else if (!hasNetworkPermission) {
                    dnsConnectButton.text = "需要网络权限"
                    continuousSearchButton.text = "需要网络权限"
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
            prefs.getString("base_url", "https://open.bigmodel.cn/api/paas/v4")
                ?: "https://open.bigmodel.cn/api/paas/v4"
        FloatingWindowService.apiKey = prefs.getString("api_key", "EMPTY") ?: "EMPTY"
        FloatingWindowService.modelName =
            prefs.getString("model_name", "autoglm-phone") ?: "autoglm-phone"
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

//                if (!shell.isAdbLibraryAvailable()) {
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

        Log.d("MainActivity", if (granted) "✅ 权限已授予" else "⚠️ 权限被拒绝")
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
                if (!shell.isAdbLibraryAvailable()) {
                    statusText.text = "❌ LADB 库不可用\n\n请确保应用权限正常"
                    return@launch
                }

                // 按照LADB方式执行完整初始化
                val success = performLadbDnsConnection(shell, statusText)

                if (success) {
                    val devices = shell.getDevicesSuspending()
                    statusText.text = "✅ DNS连接成功！\n\n发现设备:\n${devices.joinToString("\n")}"
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
                dnsDiscoveryManager = null

                runOnUiThread {
                    dnsConnectButton.text = "DNS 连接无线调试"
                    dnsConnectButton.isEnabled =
                        shellExecutor.isAdbLibraryAvailable() && !isDnsSearching
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
            dnsDiscoveryManager = DnsDiscoveryManager(this@MainActivity, nsdManager)

            // 开始扫描
            val scanResult = dnsDiscoveryManager?.scanAdbPorts()

            // 等待DNS扫描完成
            runOnUiThread { statusText.text = "🔍 搜索无线调试服务..." }
            var elapsedSeconds = 0
            for (i in 0 until 8) { // 等待8秒
                if (!isDnsSearching) break
                delay(1000)
                elapsedSeconds++
                runOnUiThread {
                    statusText.text =
                        "🔍 搜索无线调试服务 (${elapsedSeconds}s)...\n\n⏳ 正在发现ADB端口"
                }
            }

            // 检查发现的端口
            val discoveredPorts = dnsDiscoveryManager?.getDiscoveredPorts() ?: emptyList()
            val adbPort = dnsDiscoveryManager?.getBestPort()
            Log.e("ports", discoveredPorts.toString())

            if (adbPort != null && discoveredPorts.isNotEmpty()) {
                runOnUiThread {
                    statusText.text = "✅ 发现ADB端口: $adbPort\n\n正在启动ADB服务器..."
                }

                // 连接到发现的端口s   只要有一个成功连接那么就可以了
                var connected = false
                for (port in discoveredPorts) {
                    runOnUiThread { statusText.text = "🔄 正在连接到 localhost:$port..." }
                    Log.e("在连接到 local  ", "ports" + port + "")
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
                val devices = shell.getDevicesSuspending()
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
        dnsConnectButton.isEnabled = shellExecutor.isAdbLibraryAvailable()
    }

    /**
     * 开始持续搜索设备
     * 搜索条件：
     * 1. 自动检测设备连接，成功时停止搜索
     * 2. DNS服务发现方式
     * 3. 用户手动停止
     */
    private fun startContinuousSearch() {
        isContinuousSearching = true
        continuousSearchButton.text = "停止搜索"
        continuousSearchButton.isEnabled = true

        // 启动配对服务
        val intent = Intent(this, WirelessAdbPairingService::class.java).apply {
            action = WirelessAdbPairingService.ACTION_START_PAIRING
        }
        startForegroundService(intent)

        Log.d("MainActivity", "Started WirelessAdbPairingService")
    }

    /**
     * 停止持续搜索
     */
    private fun stopContinuousSearch() {
        isContinuousSearching = false

        // 停止配对服务
        val intent = Intent(this, WirelessAdbPairingService::class.java).apply {
            action = WirelessAdbPairingService.ACTION_STOP_PAIRING
        }
        startService(intent)

        continuousSearchButton.text = "🔍 持续搜索设备"
        continuousSearchButton.isEnabled = shellExecutor.isAdbLibraryAvailable() &&
                checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED

        Log.d("MainActivity", "Stopped WirelessAdbPairingService")
    }

    /**
     * 执行系统检测和初始化
     */
    private fun performSystemCheck() {
        mainScope.launch {
            try {
                // 等待 LADB 初始化完成
                delay(2000)

                Log.d("MainActivity", "开始系统检测和 ADBKeyboard 安装...")

                // 检查 LADB 是否可用
                if (!shellExecutor.isAdbLibraryAvailable()) {
                    Log.w("MainActivity", "LADB 不可用，跳过 ADBKeyboard 安装")
                    return@launch
                }

                // 初始化 ShellExecutor
                val initSuccess = shellExecutor.initialize()
                if (!initSuccess) {
                    Log.w("MainActivity", "ShellExecutor 初始化失败，跳过 ADBKeyboard 安装")
                    return@launch
                }

                // 等待设备连接
                var retryCount = 0
                val maxRetries = 5
                while (retryCount < maxRetries) {
                    val devices = shellExecutor.getDevicesSuspending()
                    if (devices.isNotEmpty()) {
                        Log.d("MainActivity", "检测到设备: $devices")
                        break
                    }
                    Log.d("MainActivity", "等待设备连接... (${retryCount + 1}/$maxRetries)")
                    delay(1000)
                    retryCount++
                }

                if (retryCount >= maxRetries) {
                    Log.w("MainActivity", "未检测到设备，跳过 ADBKeyboard 安装")
                    return@launch
                }

                // 创建临时的 DeviceController 来安装 ADBKeyboard
                val deviceController = com.qs.phone.controller.DeviceController(this@MainActivity)

                // 安装并初始化 ADBKeyboard
                val installSuccess = deviceController.initializeInputMethod()
                if (installSuccess) {
                    Log.d("MainActivity", "✅ ADBKeyboard 安装和初始化成功")
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "✅ ADBKeyboard 安装成功",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Log.w("MainActivity", "⚠️ ADBKeyboard 安装失败，将使用备用输入方案")
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "⚠️ ADBKeyboard 安装失败，将使用备用输入方案",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

            } catch (e: Exception) {
                Log.e("MainActivity", "系统检测和 ADBKeyboard 安装失败", e)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "系统检测失败: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * 初始化检测状态
     */
    private fun initDetectionStatus() {
        checkAllDetectionStatus()
    }

    /**
     * 检查所有检测项状态
     */
    private fun checkAllDetectionStatus() {
        checkLadbDetectionStatus()
        checkDeviceConnectionStatus()
        checkInputMethodStatus()
    }

    /**
     * 检查 LADB 状态
     */
    private fun checkLadbDetectionStatus() {
        val isLadbAvailable = shellExecutor.isAdbLibraryAvailable()

        runOnUiThread {
            if (isLadbAvailable) {
                ladbStatusImageView.visibility = View.VISIBLE
                ladbStatusImageView.setImageResource(android.R.drawable.ic_menu_info_details)
                ladbStatusImageView.setColorFilter(
                    ContextCompat.getColor(
                        this@MainActivity,
                        android.R.color.holo_green_dark
                    )
                )
                setupLadbButton.visibility = View.GONE
            } else {
                ladbStatusImageView.visibility = View.VISIBLE
                ladbStatusImageView.setImageResource(android.R.drawable.ic_menu_info_details)
                ladbStatusImageView.setColorFilter(
                    ContextCompat.getColor(
                        this@MainActivity,
                        android.R.color.holo_red_dark
                    )
                )
                setupLadbButton.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 检查设备连接状态
     */
    private fun checkDeviceConnectionStatus() {
        mainScope.launch {
            try {
                val isConnected = shellExecutor.getDevicesSuspending().isNotEmpty()

                runOnUiThread {
                    if (isConnected) {
                        deviceStatusImageView.visibility = View.VISIBLE
                        deviceStatusImageView.setImageResource(android.R.drawable.ic_menu_info_details)
                        deviceStatusImageView.setColorFilter(
                            ContextCompat.getColor(
                                this@MainActivity,
                                android.R.color.holo_green_dark
                            )
                        )
                        connectDeviceButton.visibility = View.GONE
                    } else {
                        deviceStatusImageView.visibility = View.VISIBLE
                        deviceStatusImageView.setImageResource(android.R.drawable.ic_menu_info_details)
                        deviceStatusImageView.setColorFilter(
                            ContextCompat.getColor(
                                this@MainActivity,
                                android.R.color.holo_red_dark
                            )
                        )
                        connectDeviceButton.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    deviceStatusImageView.visibility = View.VISIBLE
                    deviceStatusImageView.setImageResource(android.R.drawable.ic_menu_info_details)
                    deviceStatusImageView.setColorFilter(
                        ContextCompat.getColor(
                            this@MainActivity,
                            android.R.color.holo_red_dark
                        )
                    )
                    connectDeviceButton.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * 检查输入法安装状态
     */
    private fun checkInputMethodStatus() {
        try {
            val deviceController = DeviceController(this@MainActivity)
            val isImeInstalled = deviceController.isADBKeyboardInstalled()

            runOnUiThread {
                if (isImeInstalled) {
                    imeStatusImageView.visibility = View.VISIBLE
                    imeStatusImageView.setImageResource(android.R.drawable.ic_menu_info_details)
                    imeStatusImageView.setColorFilter(
                        ContextCompat.getColor(
                            this@MainActivity,
                            android.R.color.holo_green_dark
                        )
                    )
                    installImeButton.visibility = View.GONE
                } else {
                    imeStatusImageView.visibility = View.VISIBLE
                    imeStatusImageView.setImageResource(android.R.drawable.ic_menu_info_details)
                    imeStatusImageView.setColorFilter(
                        ContextCompat.getColor(
                            this@MainActivity,
                            android.R.color.holo_red_dark
                        )
                    )
                    installImeButton.visibility = View.VISIBLE
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                imeStatusImageView.visibility = View.VISIBLE
                imeStatusImageView.setImageResource(android.R.drawable.ic_menu_info_details)
                imeStatusImageView.setColorFilter(
                    ContextCompat.getColor(
                        this@MainActivity,
                        android.R.color.holo_red_dark
                    )
                )
                installImeButton.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 设置 LADB
     */
    private fun setupLadb() {
        ErrorDialog.showLadbNotAvailable(this)
    }

    /**
     * 连接设备
     */
    private fun connectDevice() {
        // 第一步：检查开发者选项是否开启
        val developerOptionsEnabled = shellExecutor.checkUSBDebuggingEnabled()
        val wirelessDebuggingEnabled = shellExecutor.checkWirelessDebuggingEnabled()

        if (!developerOptionsEnabled && !wirelessDebuggingEnabled) {
            // 开发者选项未开启
            AlertDialog.Builder(this)
                .setTitle("需要开启开发者选项")
                .setMessage("为了使用 ADB 调试功能，需要先开启开发者选项。\n\n请在接下来的设置页面中：\n1. 连续点击「版本号」7次开启开发者选项\n2. 返回上一层开启「USB调试」或「无线调试」")
                .setPositiveButton("去开启") { _, _ ->
                    // 跳转到开发者选项设置
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            // 兼容部分设备的包名映射
                            setPackage("com.android.settings")
                        }
                        // 检查设备是否支持该 Intent（避免崩溃）
                        if (intent.resolveActivity(packageManager) != null) {
                            startActivity(intent)
                        } else {
                            // 备选方案：打开设置主页面
                            val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(fallbackIntent)
                        }
                        Toast.makeText(
                            this,
                            "请在设置中开启「开发者选项」和「USB调试」",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "无法打开设置页面", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // 开发者选项已开启，开始第二步
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：使用无线调试
            if (!wirelessDebuggingEnabled) {
                AlertDialog.Builder(this)
                    .setTitle("开启无线调试")
                    .setMessage("检测到 Android 11+ 系统，建议使用无线调试功能。\n\n请在开发者选项中开启「无线调试」，然后点击确定继续。")
                    .setPositiveButton("我已开启") { _, _ ->
                        // 重新检查并执行
                        connectDevice()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                // 已开启无线调试，执行DNS连接
                Toast.makeText(this, "正在连接无线调试设备...", Toast.LENGTH_SHORT).show()
                showDnsConnectionDialog()
            }
        } else {
            // Android 10 及以下：使用USB调试
            connectWithUSB()
        }
    }

    /**
     * 使用USB调试连接（Android 10及以下）
     */
    private fun connectWithUSB() {
        mainScope.launch {
            try {
                Toast.makeText(this@MainActivity, "正在启用USB调试模式...", Toast.LENGTH_SHORT)
                    .show()

                // 先检查当前设备列表状态
                val currentDevices = shellExecutor.getDevicesSuspending()
                Log.d("MainActivity", "当前设备列表: $currentDevices")

                // 执行 adb tcpip 5555
                val tcpipResult = shellExecutor.executeADB("tcpip 5555")
                if (tcpipResult.success) {
                    Toast.makeText(
                        this@MainActivity,
                        "TCP/IP模式已启用，等待设备... (10秒)",
                        Toast.LENGTH_SHORT
                    ).show()

                    // 等待端口启动，增加时间
                    kotlinx.coroutines.delay(3000)

                    // 多次尝试连接，等待授权
                    var connected = false
                    var attempts = 3
                    var lastError = ""

                    while (attempts > 0 && !connected) {
                        // 重新获取设备列表
                        val devicesBeforeConnect = shellExecutor.getDevicesSuspending()
                        Log.d("MainActivity", "尝试连接前的设备列表: $devicesBeforeConnect")

                        // 尝试连接到本地 5555 端口
                        connected = shellExecutor.connectToDevice("localhost", 5555)

                        if (!connected) {
                            // 检查连接错误原因
                            val devicesAfterConnect = shellExecutor.getDevicesSuspending()
                            Log.d("MainActivity", "连接失败后设备列表: $devicesAfterConnect")

                            // 如果是授权问题，提示用户
                            if (devicesAfterConnect.isEmpty()) {
                                lastError = "连接被拒绝或未授权"
                            } else {
                                val unauthorizedDevices =
                                    devicesAfterConnect.filter { it.contains("unauthorized") }
                                if (unauthorizedDevices.isNotEmpty()) {
                                    lastError = "设备需要授权，请确认手机上的授权弹窗"
                                } else {
                                    lastError = "连接超时，请检查网络连接"
                                }
                            }

                            attempts--
                            if (attempts > 0) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "连接失败，正在重试... (剩余${attempts}次)\n错误: $lastError",
                                    Toast.LENGTH_SHORT
                                ).show()
                                kotlinx.coroutines.delay(2000)
                            }
                        } else {
                            // 等待一下确保连接完成
                            kotlinx.coroutines.delay(1000)
                            val finalDevices = shellExecutor.getDevicesSuspending()
                            Log.d("MainActivity", "连接后的设备列表: $finalDevices")

                            // 检查是否有 unauthorized 标记
                            if (finalDevices.any { it.contains("unauthorized") }) {
                                connected = false
                                lastError = "设备未授权，请确认手机上的授权弹窗"
                                attempts--
                                if (attempts > 0) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "⚠️ 未授权，请确认手机上的授权弹窗",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    kotlinx.coroutines.delay(3000)
                                }
                            } else {
                                break
                            }
                        }
                    }

                    // 根据连接结果显示提示
                    val finalDevices = shellExecutor.getDevicesSuspending()
                    if (finalDevices.isNotEmpty() && !finalDevices.any { it.contains("unauthorized") }) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "✅ 设备连接成功", Toast.LENGTH_SHORT)
                                .show()
                            checkDeviceConnectionStatus()
                        }
                    } else if (finalDevices.any { it.contains("unauthorized") }) {
                        // 显示授权提示对话框
                        showAuthorizationDialog()
                    } else {
                        runOnUiThread {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("连接失败")
                                .setMessage("无法连接到设备，可能原因：\n\n1. 设备未授权ADB连接\n2. 授权弹窗被忽略或跳过\n3. 网络连接问题\n\n解决方案：\n1. 在手机上确认授权弹窗（必须启用\"始终允许\"）\n2. 重新插拔USB线\n3. 运行adb kill-server后重试")
                                .setPositiveButton("清除授权并重试") { _, _ ->
                                    clearAuthorizations()
                                }
                                .setNegativeButton("好的", null)
                                .show()
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "TCP/IP 启动失败：${tcpipResult.stderr}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "连接失败: ${e.message}", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    /**
     * 显示授权提示对话框
     */
    private fun showAuthorizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要授权确认")
            .setMessage("设备已连接，但需要完成授权确认。\n\n请按以下步骤操作：\n1. 查看手机屏幕，应该有授权弹窗\n2. 勾选「总是允许此计算机」\n3. 点击「确定」\n\n如果未看到弹窗，请：\n• 重新插拔 USB 线\n• 或调用 adb kill-server 后重试")
            .setPositiveButton("重新连接") { _, _ ->
                connectDevice()
            }
            .setNeutralButton("检查设备列表") { _, _ ->
                mainScope.launch {
                    val devices = shellExecutor.getDevicesSuspending()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("当前设备列表")
                        .setMessage(
                            if (devices.isEmpty()) "未检测到设备" else devices.joinToString(
                                "\n"
                            )
                        )
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 清除已授权的设备列表
     */
    private fun clearAuthorizations() {
        mainScope.launch {
            try {
                Toast.makeText(this@MainActivity, "正在清除授权...", Toast.LENGTH_SHORT).show()

                // 停止ADB服务器
                val killResult = shellExecutor.executeADB("kill-server")
                if (killResult.success) {
                    kotlinx.coroutines.delay(2000)

                    // 尝试重新启动服务器
                    val startResult = shellExecutor.executeADB("start-server")
                    if (startResult.success) {
                        Toast.makeText(
                            this@MainActivity,
                            "已清除授权，请重新连接设备",
                            Toast.LENGTH_SHORT
                        ).show()
                        // 重新连接
                        connectDevice()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "重新启动服务器失败：${startResult.stderr}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "清除授权失败：${killResult.stderr}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "清除授权时出错：${e.message}", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    /**
     * 安装输入法
     */
    private fun installInputMethod() {
        // 先检查是否有安装权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                // 没有权限，先请求权限
                requestInstallPermission()
                return
            }
        }

        // 有权限，直接安装
        performInputMethodInstallation()
    }

    /**
     * 执行输入法安装
     */
    private fun performInputMethodInstallation() {
        mainScope.launch {
            try {
                val deviceController = DeviceController(this@MainActivity)
                val installSuccess = deviceController.initializeInputMethod()

                runOnUiThread {
                    if (installSuccess) {
                        Toast.makeText(this@MainActivity, "输入法安装成功", Toast.LENGTH_SHORT)
                            .show()
                        checkInputMethodStatus() // 重新检查状态
                    } else {
                        Toast.makeText(this@MainActivity, "输入法安装失败", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "输入法安装失败: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * 主动请求安装未知来源应用权限
     */
    private fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 显示说明对话框
            AlertDialog.Builder(this)
                .setTitle("需要安装权限")
                .setMessage("为了安装 ADBKeyboard 输入法，需要授予「安装未知来源应用」权限。\n\n请在接下来的弹窗中允许此权限。")
                .setPositiveButton("去授权") { _, _ ->
                    // 主动请求权限
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                        startActivityForResult(intent, REQUEST_INSTALL_PERMISSION)
                    } catch (e: Exception) {
                        // 如果无法直接跳转到权限页面，则跳转到应用详情页面
                        try {
                            val intent =
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.parse("package:$packageName")
                                }
                            startActivityForResult(intent, REQUEST_INSTALL_PERMISSION)
                        } catch (e2: Exception) {
                            Toast.makeText(
                                this,
                                "无法打开设置页面，请手动前往设置开启权限",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_INSTALL_PERMISSION -> {
                // 权限请求结果处理
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (packageManager.canRequestPackageInstalls()) {
                        Toast.makeText(this, "权限已授予，正在安装...", Toast.LENGTH_SHORT).show()
                        // 权限已授予，继续安装
                        performInputMethodInstallation()
                    } else {
                        Toast.makeText(this, "权限被拒绝，无法安装输入法", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        // 清理协程，避免内存泄漏
        dnsSearchJob?.cancel()
        dnsSearchJob = null
        continuousSearchJob?.cancel()
        continuousSearchJob = null

        // 如果正在搜索，停止配对服务
        if (isContinuousSearching) {
            val intent = Intent(this, WirelessAdbPairingService::class.java).apply {
                action = WirelessAdbPairingService.ACTION_STOP_PAIRING
            }
            startService(intent)
            Log.d("MainActivity", "Stopped WirelessAdbPairingService on onDestroy")
        }
    }
}

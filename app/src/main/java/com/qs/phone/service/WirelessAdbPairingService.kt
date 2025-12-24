package com.qs.phone.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.qs.phone.R
import com.qs.phone.discovery.PairingDiscoveryManager
import com.qs.phone.shell.ShellExecutor
import kotlinx.coroutines.*

/**
 * 无线调试配对服务
 * 参考 Android WirelessAdbPairingService 实现
 *
 * 流程：
 * 1. startSearch() - 启动 mDNS 服务发现
 * 2. onServiceFound() - 发现配对服务
 * 3. createInputNotification() - 显示配对码输入通知
 * 4. onInput() - 用户输入配对码后执行配对
 * 5. onPairingSuccess() - 配对成功后保存信息
 */
class WirelessAdbPairingService : Service() {

    companion object {
        private const val TAG = "WirelessAdbPairing"
        private const val CHANNEL_ID = "wireless_adb_pairing"
        private const val NOTIFICATION_ID = 2001

        // Intent Actions
        const val ACTION_START_PAIRING = "com.qs.phone.START_PAIRING"
        const val ACTION_STOP_PAIRING = "com.qs.phone.STOP_PAIRING"
        const val ACTION_REPLY = "com.qs.phone.REPLY"

        // Extras
        const val EXTRA_CODE = "extra_code"
        const val EXTRA_PORT = "extra_port"

        // RemoteInput Key
        const val KEY_REMOTE_INPUT_CODE = "remote_input_code"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pairingJob: Job? = null

    private lateinit var shellExecutor: ShellExecutor
    private var pairingDiscoveryManager: PairingDiscoveryManager? = null
    private var isPairing = false
    private var discoveredPort: Int? = null
    private var notificationManager: NotificationManager? = null

    // 保存发现的配对服务列表
    private data class DiscoveredService(
        val serviceInfo: android.net.nsd.NsdServiceInfo,
        val port: Int
    )
    private val discoveredServices = mutableListOf<DiscoveredService>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        shellExecutor = ShellExecutor(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        pairingJob?.cancel()
        serviceScope.cancel()
        stopPairing()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PAIRING -> {
                startSearch()
                return START_STICKY
            }
            ACTION_STOP_PAIRING -> {
                stopPairing()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REPLY -> {
                handleReply(intent)
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    /**
     * 启动配对服务搜索
     * 对应 WirelessAdbPairingService.startSearch()
     */
    private fun startSearch() {
        if (isPairing) {
            Log.w(TAG, "Pairing already in progress")
            return
        }

        isPairing = true
        Log.d(TAG, "Starting wireless ADB pairing search")

        // 显示搜索通知
        showSearchingNotification()

        val nsdManager = getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager
        pairingDiscoveryManager = PairingDiscoveryManager(this, nsdManager)

        // 启动服务发现
        val success = pairingDiscoveryManager?.startPairingDiscovery(object :
            PairingDiscoveryManager.PairingCallback {
            override fun onServiceFound(serviceInfo: android.net.nsd.NsdServiceInfo, port: Int) {
                Log.d(TAG, "Pairing service found: port=$port")
                // 保存到列表
                discoveredServices.add(DiscoveredService(serviceInfo, port))
                // 处理发现的服务
                this@WirelessAdbPairingService.onServiceFound(serviceInfo, port)
            }

            override fun onServiceLost(serviceName: String) {
                Log.d(TAG, "Pairing service lost: $serviceName")
            }

            override fun onDiscoveryFailed(errorCode: Int) {
                Log.e(TAG, "Discovery failed: $errorCode")
                onDiscoveryFailed()
            }
        }) ?: false

        if (!success) {
            isPairing = false
            showFailedNotification("启动服务发现失败")
        }
    }

    /**
     * 停止配对
     */
    private fun stopPairing() {
        if (!isPairing) return

        isPairing = false
        pairingDiscoveryManager?.stopPairingDiscovery()
        pairingJob?.cancel()
        pairingJob = null

        Log.d(TAG, "Pairing stopped")
    }

    /**
     * 发现配对服务
     * 对应 WirelessAdbPairingService.onServiceFound()
     */
    private fun onServiceFound(serviceInfo: android.net.nsd.NsdServiceInfo, port: Int) {

        discoveredPort = port

        // 显示输入配对码通知
        createInputNotification(port)

        // 停止搜索
        pairingDiscoveryManager?.stopPairingDiscovery()
    }

    /**
     * 显示搜索中通知
     */
    private fun showSearchingNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.logo)
            .setContentTitle("正在搜索无线调试配对服务")
            .setContentText("请在设备上启用无线调试")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * 创建输入配对码通知
     * 对应 WirelessAdbPairingService.createInputNotification()
     */
    private fun createInputNotification(port: Int) {
        // RemoteInput 用于输入配对码
        val remoteInput = RemoteInput.Builder(KEY_REMOTE_INPUT_CODE)
            .setLabel("输入无线调试配对码")
            .build()

        // 创建回复 PendingIntent
        val replyIntent = Intent(this, WirelessAdbPairingService::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_PORT, port)
        }
        val replyPendingIntent = PendingIntent.getService(
            this,
            0,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // 创建输入配对码 Action
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit,
            "输入配对码",
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        // 创建停止搜索 Action
        val stopIntent = Intent(this, WirelessAdbPairingService::class.java).apply {
            action = ACTION_STOP_PAIRING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "停止搜索",
            stopPendingIntent
        ).build()

        // 构建通知
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("已找到无线调试服务")
            .setContentText("在通知中输入无线调试配对码")
            .addAction(replyAction)
            .addAction(stopAction)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(false)
            .setAutoCancel(false)
            .build()

        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 处理用户输入的配对码
     * 对应 WirelessAdbPairingService.onStartCommand() 处理 ACTION_REPLY
     */
    private fun handleReply(intent: Intent) {

        // 获取 RemoteInput 输入的配对码
        var resultsFromIntent = RemoteInput.getResultsFromIntent(intent)


        val code = resultsFromIntent?.getCharSequence(KEY_REMOTE_INPUT_CODE)?.toString()
        val port = intent.getIntExtra(EXTRA_PORT, -1)

        Log.d(TAG, "Extracted code: '$code', port: $port")

        if (code.isNullOrEmpty() || port == -1) {
            Log.e(TAG, "Invalid reply: code='$code', port=$port")
            return
        }

        Log.d(TAG, "User entered pairing code: ****, port: $port")

//        // 执行配对
//        onInput(code, port)
    }


    /**
     * 执行配对
     * 对应 WirelessAdbPairingService.onInput()
     * 简化版：直接使用 ADB 连接命令
     */
    private fun onInput(code: String, port: Int) {
        // 显示配对进度通知
        showPairingProgressNotification()

        pairingJob = serviceScope.launch {
            try {
                // TODO: 实现完整的 TLS + SPAKE2 配对协议
                // 当前简化版：使用 ADB connect 命令

                // 先尝试断开现有连接
                try {
//                    shellExecutor.executeCommand("adb disconnect localhost:$port")
                } catch (e: Exception) {
                    Log.w(TAG, "Disconnect failed (expected)", e)
                }

                // 连接到设备
//                val result = shellExecutor.executeCommand("adb connect localhost:$port")
//                Log.d(TAG, "Connect result: $result")
//
//                if (result.contains("connected")) {
//                    // 配对成功
//                    onPairingSuccess(port, code)
//                } else {
                    // 配对失败
                    onPairingFailed("连接失败")
//                }
            } catch (e: Exception) {
                Log.e(TAG, "Pairing error", e)
                onPairingFailed(e.message ?: "未知错误")
            }
        }
    }

    /**
     * 配对成功
     * 对应 WirelessAdbPairingService 配对成功后的操作
     */
    private fun onPairingSuccess(port: Int, code: String) {
        Log.d(TAG, "Pairing successful: port=$port")

        // 保存配对信息
        val prefs = getSharedPreferences("wireless_adb", MODE_PRIVATE)
        prefs.edit()
            .putString("port", port.toString())
            .putString("pairCode", code)
            .putBoolean("paired", true)
            .apply()

//        // 确保 WiFi ADB 始终开启
//        ensureWirelessAdbAlwaysOn(port)

        // 显示成功通知
        showSuccessNotification(port)

        // 停止服务
        stopPairing()
        stopSelf()
    }

    /**
     * 配对失败
     */
    private fun onPairingFailed(reason: String) {
        Log.e(TAG, "Pairing failed: $reason")
        showFailedNotification(reason)
        stopPairing()
    }

    /**
     * 发现服务失败
     */
    private fun onDiscoveryFailed() {
        showFailedNotification("服务发现失败")
        stopPairing()
    }


    /**
     * 显示配对进度通知
     */
    private fun showPairingProgressNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.logo)
            .setContentTitle("正在进行无线调试配对...")
            .setContentText("请稍候")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()

        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 显示成功通知
     */
    private fun showSuccessNotification(port: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("无线调试授权成功")
            .setContentText("端口: $port")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 显示失败通知
     */
    private fun showFailedNotification(reason: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("无线调试配对失败")
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "无线调试配对",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "无线 ADB 配对服务通知"
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }
}

package com.qs.phone

import android.app.Application
import android.util.Log
import com.qs.phone.shell.ShellExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ZhiAI Application 类
 * 负责全局初始化和清理资源
 */
class ZhiAIApplication : Application() {

    companion object {
        private const val TAG = "ZhiAIApplication"
        lateinit var instance: ZhiAIApplication
            private set
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var shellExecutor: ShellExecutor? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        Log.d(TAG, "ZhiAI Application started")

        // 初始化 ShellExecutor（延后创建，避免阻塞启动）
        applicationScope.launch {
            try {
                shellExecutor = ShellExecutor(this@ZhiAIApplication)

                // 立即断开所有之前的连接，防止 ADB daemon 自动重连
                Log.d(TAG, "Clearing previous ADB connections...")
                val disconnectResult = shellExecutor?.disconnectAll()
                Log.d(TAG, "Disconnect result: $disconnectResult")

                // 杀掉 ADB server，清除连接记录（可选，更彻底）
                try {
                    shellExecutor?.executeADB("kill-server")
                    Log.d(TAG, "ADB server killed, connection records cleared")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to kill ADB server: ${e.message}")
                }

                Log.d(TAG, "ShellExecutor initialized and connections cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize ShellExecutor", e)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.d(TAG, "ZhiAI Application terminating")

        // 注意：onTerminate 在真实设备上不会被调用（仅在模拟器上调用）
        // 真正的清理应该在 onLowMemory() 或 onTrimMemory() 中处理
        cleanup()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.d(TAG, "System low memory, cleaning up resources")
        cleanup()
    }


    /**
     * 清理资源，断开所有 ADB 连接
     */
    private fun cleanup() {
        applicationScope.launch {
            try {
                shellExecutor?.let { shell ->
                    Log.d(TAG, "Disconnecting all ADB devices...")
                    val success = shell.disconnectAll()
                    Log.d(TAG, "Disconnect result: $success")

                    // 清理其他资源
                    shell.cleanup()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }

    /**
     * 获取全局 ShellExecutor 实例
     */
    fun getShellExecutor(): ShellExecutor? {
        return shellExecutor
    }
}

package com.qs.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.util.Log
import com.qs.phone.action.ActionHandler
import com.qs.phone.action.ActionParser
import com.qs.phone.config.AppPackages
import com.qs.phone.config.Prompts
import com.qs.phone.controller.DeviceController
import com.qs.phone.discovery.DnsDiscoveryManager
import com.qs.phone.model.MessageBuilder
import com.qs.phone.model.ModelClient
import com.qs.phone.model.ModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Timer
import kotlin.concurrent.scheduleAtFixedRate

/**
 * Agent 配置
 */
data class AgentConfig(
    val maxSteps: Int = 100,
    val lang: String = "cn",
    val verbose: Boolean = true
)

/**
 * 步骤结果
 */
data class StepResult(
    val success: Boolean,
    val finished: Boolean,
    val action: Map<String, Any>?,
    val thinking: String,
    val message: String? = null
)

/**
 * Agent 状态
 */
sealed class AgentState {
    object Idle : AgentState()
    object Running : AgentState()
    data class Thinking(val content: String) : AgentState()
    data class ThinkingMsg(val content: String) : AgentState()
    data class Executing(val action: String) : AgentState()
    data class Completed(val message: String) : AgentState()
    data class Error(val message: String) : AgentState()
    data class TakeoverRequired(val message: String) : AgentState()
}

/**
 * PhoneAgent - AI 驱动的手机自动化代理
 */
class PhoneAgent(
    private val context: Context,
    private val modelConfig: ModelConfig,
    private val agentConfig: AgentConfig = AgentConfig(),
    private val onConfirmation: ((String) -> Boolean)? = null,
    private val onTakeover: ((String) -> Unit)? = null,
    private val onAdbPortDiscovered: ((Map<String, List<Int>>) -> Unit)? = null
) {
    companion object {
        private const val TAG = "PhoneAgent"
    }

    val deviceController: DeviceController = DeviceController(context)
    private val modelClient = ModelClient(modelConfig)
    private val actionHandler = ActionHandler(deviceController, onConfirmation, onTakeover)

    // DNS 端口发现管理器
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }
    private val dnsDiscoveryManager: DnsDiscoveryManager by lazy {
        DnsDiscoveryManager(context, nsdManager)
    }

    private val conversationContext = mutableListOf<Map<String, Any>>()
    private var stepCount = 0

    // 端口监听相关
    private var portMonitorTimer: Timer? = null
    private var isMonitoringPorts = false
    private var lastKnownPorts: Set<Int> = emptySet()

    val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val mainScope = CoroutineScope(Dispatchers.Main)

    /**
     * 初始化 Agent
     */
    suspend fun initialize(): Boolean {
        Log.d(TAG, "Initializing PhoneAgent...")
        val success = deviceController.initialize()

        if (success) {
            // 启动 ADB 端口监听
            startAdbPortMonitoring()
            Log.d(TAG, "ADB port monitoring started")
        }

        return success
    }

    /**
     * 运行任务
     */
    suspend fun run(task: String): String {
        conversationContext.clear()
        stepCount = 0
        _state.value = AgentState.Running

        log("📋 开始任务: $task")

        // 切换到 ADBKeyboard 输入法
        try {
            log("⌨️ 切换到 ADBKeyboard...")
            val inputMethodSwitched = deviceController.switchToADBKeyboard()
            if (inputMethodSwitched) {
                log("✅ 已切换到 ADBKeyboard")
            } else {
                log("⚠️ ADBKeyboard 切换失败，将使用备用输入方案")
            }
        } catch (e: Exception) {
            log("⚠️ 输入法切换失败: ${e.message}")
        }

        try {
            // 第一步
            var result = executeStep(task, isFirst = true)

            if (result.finished) {
                val message = result.message ?: "任务完成"
                // 检查是否是错误消息
                if (message.startsWith("连接失败") || message.startsWith("错误:")) {
                    _state.value = AgentState.Error(message)
                    cleanupScreenshotsOnError()
                } else {
                    _state.value = AgentState.Completed(message)
                    cleanupScreenshotsOnSuccess()
                }
                // 恢复原有输入法
                restoreInputMethod()
                return message
            }

            // 继续执行直到完成或达到最大步数
            while (stepCount < agentConfig.maxSteps) {
                result = executeStep(isFirst = false)

                if (result.finished) {
                    val message = result.message ?: "任务完成"
                    // 检查是否是错误消息
                    if (message.startsWith("连接失败") || message.startsWith("错误:")) {
                        _state.value = AgentState.Error(message)
                        cleanupScreenshotsOnError()
                    } else {
                        _state.value = AgentState.Completed(message)
                        cleanupScreenshotsOnSuccess()
                    }
                    // 恢复原有输入法
                    restoreInputMethod()
                    return message
                }
            }

            val message = "达到最大步数限制"
            _state.value = AgentState.Error(message)
            cleanupScreenshotsOnError()
            // 恢复原有输入法
            restoreInputMethod()
            return message
        } catch (e: Exception) {
            Log.e(TAG, "Task execution failed", e)
            val message = "任务执行异常: ${e.message}"
            _state.value = AgentState.Error(message)
            cleanupScreenshotsOnError()
            // 恢复原有输入法
            restoreInputMethod()
            return message
        }
    }

    /**
     * 执行单步
     */
    private suspend fun executeStep(userPrompt: String? = null, isFirst: Boolean = false): StepResult {
        stepCount++
        log("⏳ 步骤 $stepCount...")

        try {
            // 截图
            val screenshot = deviceController.takeScreenshot()
            if (screenshot.base64 == null) {
                log("❌ 截图失败")
                return StepResult(false, true, null, "", "截图失败")
            }

            // 使用新的应用检测器（不使用 ADB）
            val currentApp = deviceController.getCurrentApp()

            // 如果是包名且不在已知应用列表中，添加提示
            val appDisplay = when {
                currentApp == "Unknown" -> "$currentApp (无法检测)"
                currentApp == "System" -> "$currentApp (系统应用)"
                currentApp == "Home" -> "$currentApp (桌面)"
                currentApp.contains(".") && !currentApp.startsWith("com.android") -> {
                    // 尝试从包名映射中查找应用名
                    val appName = try {
                        AppPackages.packages.entries.find { it.value == currentApp }?.key
                    } catch (e: Exception) {
                        null
                    }
                    appName ?: "$currentApp (未识别)"
                }
                else -> currentApp
            }
            log("📱 当前应用: $appDisplay")

            // 构建消息
            if (isFirst) {
                conversationContext.add(MessageBuilder.createSystemMessage(Prompts.getSystemPrompt(agentConfig.lang)))

                val screenInfo = MessageBuilder.buildScreenInfo(currentApp)
                val textContent = "$userPrompt\n\n$screenInfo"
                conversationContext.add(MessageBuilder.createUserMessage(textContent, screenshot.base64))
            } else {
                val screenInfo = MessageBuilder.buildScreenInfo(currentApp)
                val textContent = "** Screen Info **\n\n$screenInfo"
                conversationContext.add(MessageBuilder.createUserMessage(textContent, screenshot.base64))
            }

            // 调用模型
            _state.value = AgentState.Thinking("思考中...")
            log("💭 思考中...")

            val response = modelClient.request(conversationContext)

//            log("💭 resp: ${response}...")
            log("💭 思考: ${response.thinking.take(200)}...")
            val thinkingContent = response.thinking.take(200)

            mainScope.launch {
                _state.value = AgentState.ThinkingMsg(thinkingContent)
            }

            // 解析动作
            val action = try {
                ActionParser.parse(response.action)
            } catch (e: Exception) {
                log("⚠️ 解析动作失败: ${e.message}")
                return StepResult(false, true, null, "", "解析动作失败: ${e.message}")
            }

            val actionStr = action.toString()
            log("🎯 动作: $actionStr")
            _state.value = AgentState.Executing(actionStr)

            // 移除上下文中的图片以节省空间
            if (conversationContext.isNotEmpty()) {
                conversationContext[conversationContext.lastIndex] = MessageBuilder.removeImagesFromMessage(conversationContext.last())
            }

            // 执行动作
            val result = actionHandler.execute(action, screenshot.width, screenshot.height)

            // 添加助手响应到上下文
            conversationContext.add(
                MessageBuilder.createAssistantMessage(
                    "<think>${response.thinking}</think><answer>${response.action}</answer>"
                )
            )

            val finished = action["_metadata"] == "finish" || result.shouldFinish

            if (finished) {
                val msg = result.message ?: (action["message"] as? String) ?: "完成"
                log("✅ 任务完成: $msg")
            }

            return StepResult(
                success = result.success,
                finished = finished,
                action = action,
                thinking = response.thinking,
                message = result.message ?: (action["message"] as? String)
            )

        } catch (e: Exception) {
            Log.e(TAG, "Step failed", e)
            log("❌ 步骤失败: ${e.message}")
            return StepResult(false, true, null, "", "错误: ${e.message}")
        }
    }

    /**
     * 停止运行
     */
    fun stop() {
        _state.value = AgentState.Idle
        log("⏹️ 已停止")
        // 恢复原有输入法
        mainScope.launch {
            try {
                restoreInputMethod()
            } catch (e: Exception) {
                log("⚠️ 停止时恢复输入法失败: ${e.message}")
            }
        }
        // 停止时清理截图文件
        deviceController.cleanupScreenshots()
    }

    /**
     * 重置
     */
    fun reset() {
        conversationContext.clear()
        stepCount = 0
        _state.value = AgentState.Idle
        _logs.value = emptyList()
        // 重置时清理截图文件
        deviceController.cleanupScreenshots()
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        cleanupPortMonitoring()
        deviceController.cleanup()
    }

    /**
     * 任务成功完成时清理截图
     */
    private fun cleanupScreenshotsOnSuccess() {
        try {
            deviceController.cleanupScreenshots()
            log("🧹 已清理临时截图文件")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup screenshots on success", e)
            log("⚠️ 清理截图文件失败: ${e.message}")
        }
    }

    /**
     * 任务出错时清理截图
     */
    private fun cleanupScreenshotsOnError() {
        try {
            deviceController.cleanupScreenshots()
            log("🧹 已清理临时截图文件")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup screenshots on error", e)
            // 错误时不向用户显示清理失败的消息，避免混淆
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        _logs.value = _logs.value + message
    }

    /**
     * 恢复原有输入法
     */
    private suspend fun restoreInputMethod() {
        try {
            log("⌨️ 恢复原有输入法...")
            val restored = deviceController.restoreOriginalInputMethod()
            if (restored) {
                log("✅ 已恢复原有输入法")
            } else {
                log("⚠️ 恢复输入法失败")
            }
        } catch (e: Exception) {
            log("⚠️ 恢复输入法时发生异常: ${e.message}")
        }
    }

    // ========================================
    // ADB 端口监听相关方法
    // ========================================

    /**
     * 启动 ADB 端口监听
     */
    private fun startAdbPortMonitoring() {
        if (isMonitoringPorts) {
            Log.w(TAG, "Port monitoring already started")
            return
        }

        try {
            // 启动 DNS 服务发现
            val scanResult = dnsDiscoveryManager.scanAdbPorts()
            if (scanResult.success) {
                Log.i(TAG, "Started ADB port discovery: ${scanResult.message}")
                log("🔍 开始监听 ADB 端口...")
            } else {
                Log.w(TAG, "Failed to start port discovery: ${scanResult.message}")
                return
            }

            // 启动定时检查
            isMonitoringPorts = true
            portMonitorTimer = Timer().apply {
                scheduleAtFixedRate(0, 2000) { // 每 2 秒检查一次
                    checkPortChanges()
                }
            }
            Log.d(TAG, "Port monitoring timer started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start port monitoring", e)
            log("❌ 启动端口监听失败: ${e.message}")
        }
    }

    /**
     * 停止 ADB 端口监听
     */
    private fun stopAdbPortMonitoring() {
        if (!isMonitoringPorts) {
            return
        }

        try {
            portMonitorTimer?.cancel()
            portMonitorTimer = null
            isMonitoringPorts = false

            dnsDiscoveryManager.stopScan()
            Log.d(TAG, "Stopped ADB port monitoring")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop port monitoring", e)
        }
    }

    /**
     * 检查端口变化
     */
    private fun checkPortChanges() {
        try {
            // 获取所有端口信息
            val allPorts = dnsDiscoveryManager.getAllDiscoveredPorts()
            val currentPorts = allPorts["all"]?.toSet() ?: emptySet()

            // 检查是否有新端口或端口消失
            if (currentPorts != lastKnownPorts) {
                val addedPorts = currentPorts - lastKnownPorts
                val removedPorts = lastKnownPorts - currentPorts

                if (addedPorts.isNotEmpty()) {
                    Log.i(TAG, "新发现端口: $addedPorts")
                    log("📡 发现 ADB 端口: $addedPorts")
                }

                if (removedPorts.isNotEmpty()) {
                    Log.i(TAG, "端口消失: $removedPorts")
                    log("📡 ADB 端口消失: $removedPorts")
                }

                // 更新最后已知端口
                lastKnownPorts = currentPorts

                // 通知回调（如果提供了）
                onAdbPortDiscovered?.let { callback ->
                    callback(allPorts)
                }
            }

            // 定期输出当前端口状态（调试用）
            if (currentPorts.isNotEmpty() && agentConfig.verbose) {
                val pairingPorts = allPorts["pairing"] ?: emptyList()
                val connectPorts = allPorts["connect"] ?: emptyList()

                if (pairingPorts.isNotEmpty()) {
                    Log.d(TAG, "配对端口: $pairingPorts")
                }
                if (connectPorts.isNotEmpty()) {
                    Log.d(TAG, "连接端口: $connectPorts")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking port changes", e)
        }
    }

    /**
     * 获取当前发现的 ADB 端口列表
     */
    fun getDiscoveredAdbPorts(): Map<String, List<Int>> {
        return try {
            dnsDiscoveryManager.getAllDiscoveredPorts()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get discovered ports", e)
            emptyMap()
        }
    }

    /**
     * 获取最佳 ADB 端口
     */
    fun getBestAdbPort(): Int? {
        return try {
            dnsDiscoveryManager.getBestPort()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get best port", e)
            null
        }
    }

    /**
     * 手动触发端口重新扫描
     */
    fun rescanAdbPorts(): Boolean {
        return try {
            Log.d(TAG, "Manual rescan of ADB ports")
            log("🔄 重新扫描 ADB 端口...")
            dnsDiscoveryManager.clearPorts()
            val result = dnsDiscoveryManager.scanAdbPorts()
            result.success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rescan ports", e)
            false
        }
    }

    /**
     * 检查端口监听是否正在运行
     */
    fun isMonitoringAdbPorts(): Boolean = isMonitoringPorts

    /**
     * 清理资源时停止端口监听
     */
    private fun cleanupPortMonitoring() {
        stopAdbPortMonitoring()
        lastKnownPorts = emptySet()
    }
}

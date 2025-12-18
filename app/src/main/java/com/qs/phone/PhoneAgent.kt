package com.qs.phone

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import com.qs.phone.action.ActionHandler
import com.qs.phone.action.ActionParser
import com.qs.phone.config.AppPackages
import com.qs.phone.config.Prompts
import com.qs.phone.controller.DeviceController
import com.qs.phone.model.MessageBuilder
import com.qs.phone.model.ModelClient
import com.qs.phone.model.ModelConfig
import com.qs.phone.service.FloatingWindowService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    private val onTakeover: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "PhoneAgent"
    }

    val deviceController: DeviceController = DeviceController(context)
    private val modelClient = ModelClient(modelConfig)
    private val actionHandler = ActionHandler(deviceController, onConfirmation, onTakeover)

    private val conversationContext = mutableListOf<Map<String, Any>>()
    private var stepCount = 0

    val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    /**
     * 初始化 Agent
     */
    suspend fun initialize(): Boolean {
        Log.d(TAG, "Initializing PhoneAgent...")
        val success = deviceController.initialize()
        if (success) {
            log("✅ 设备控制器初始化成功")
            if (deviceController.isLadbAvailable()) {
                log("ℹ️ 使用 LADB 模式（无需 Root）")
            } else {
                log("⚠️ 使用传统模式（需要 Root 或 Shizuku）")
            }

            val devices = deviceController.getDevices()
            if (devices.isNotEmpty()) {
                log("📱 检测到设备: $devices")
            } else {
                log("⚠️ 未检测到 ADB 设备")
            }
        } else {
            log("❌ 设备控制器初始化失败")
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
                    return message
                }
            }

            val message = "达到最大步数限制"
            _state.value = AgentState.Error(message)
            cleanupScreenshotsOnError()
            return message
        } catch (e: Exception) {
            Log.e(TAG, "Task execution failed", e)
            val message = "任务执行异常: ${e.message}"
            _state.value = AgentState.Error(message)
            cleanupScreenshotsOnError()
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

            // 使用无障碍服务检测当前应用
            val currentApp = try {
                // 通过无障碍服务获取当前前台应用
                val service = FloatingWindowService.instance
                if (service != null) {
                    // 使用无障碍服务获取当前窗口信息
                    val packageName = try {
                        // 通过 AccessibilityService 获取当前焦点窗口
                        val hasFocusedApp = service.windows?.any { window ->
                            window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                            window.isFocused
                        } == true

                        if (hasFocusedApp) {
                            // 获取当前活动窗口的根节点
                            val rootNode = service.rootInActiveWindow
                            val pkgName = rootNode?.packageName?.toString()
                            // 在新版本中不需要手动 recycle
                            pkgName
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to get window info via accessibility service", e)
                        null
                    }

                    if (!packageName.isNullOrEmpty()) {
                        // 检查是否是已知的系统应用
                        when {
                            packageName.contains("launcher") -> "Home"
                            packageName.contains("system") -> "System"
                            else -> packageName
                        }
                    } else {
                        // 回退到传统的 ADB 方法
                        deviceController.getCurrentApp()
                    }
                } else {
                    // 如果无障碍服务未运行，使用 ADB 方法
                    deviceController.getCurrentApp()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get current app via accessibility service", e)
                // 回退到 ADB 方法
                deviceController.getCurrentApp()
            }

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

            log("💭 resp: ${response}...")
            log("💭 思考: ${response.thinking.take(200)}...")
            _state.value = AgentState.Thinking(response.thinking)

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
}

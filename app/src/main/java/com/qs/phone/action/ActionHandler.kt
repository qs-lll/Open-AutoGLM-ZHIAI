package com.qs.phone.action

import com.qs.phone.controller.DeviceController
import kotlinx.coroutines.delay
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * 动作结果
 */
data class ActionResult(
    val success: Boolean,
    val shouldFinish: Boolean,
    val message: String? = null
)

/**
 * 动作解析器
 */
object ActionParser {
    /**
     * 解析模型响应中的动作
     */
    fun parse(response: String): Map<String, Any> {
        val trimmed = response.trim()

        // Type action 特殊处理
        if (trimmed.startsWith("do(action=\"Type\"") || trimmed.startsWith("do(action=\"Type_Name\"")) {
            val textMatch = Regex("""text="([^"]*)"?""").find(trimmed)
            val text = textMatch?.groupValues?.get(1) ?: ""
            return mapOf("_metadata" to "do", "action" to "Type", "text" to text)
        }

        // do action 解析
        if (trimmed.startsWith("do(")) {
            return parseDoAction(trimmed)
        }

        // finish action 解析
        if (trimmed.startsWith("finish(")) {
            val messageMatch = Regex("""message="([^"]*)"?""").find(trimmed)
            val message = messageMatch?.groupValues?.get(1) ?: trimmed.removePrefix("finish(message=").dropLast(1).trim('"')
            return mapOf("_metadata" to "finish", "message" to message)
        }

        throw IllegalArgumentException("Failed to parse action: $trimmed")
    }

    private fun parseDoAction(response: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>("_metadata" to "do")

        // 提取 action 类型
        val actionMatch = Regex("""action="([^"]+)"""").find(response)
        result["action"] = actionMatch?.groupValues?.get(1) ?: ""

        // 提取 app 参数
        val appMatch = Regex("""app="([^"]+)"""").find(response)
        if (appMatch != null) {
            result["app"] = appMatch.groupValues[1]
        }

        // 提取 element 参数 (坐标数组)
        val elementMatch = Regex("""element=\[(\d+),\s*(\d+)]""").find(response)
        if (elementMatch != null) {
            result["element"] = listOf(
                elementMatch.groupValues[1].toInt(),
                elementMatch.groupValues[2].toInt()
            )
        }

        // 提取 start 参数
        val startMatch = Regex("""start=\[(\d+),\s*(\d+)]""").find(response)
        if (startMatch != null) {
            result["start"] = listOf(
                startMatch.groupValues[1].toInt(),
                startMatch.groupValues[2].toInt()
            )
        }

        // 提取 end 参数
        val endMatch = Regex("""end=\[(\d+),\s*(\d+)]""").find(response)
        if (endMatch != null) {
            result["end"] = listOf(
                endMatch.groupValues[1].toInt(),
                endMatch.groupValues[2].toInt()
            )
        }

        // 提取 text 参数
        val textMatch = Regex("""text="([^"]*)"?""").find(response)
        if (textMatch != null) {
            result["text"] = textMatch.groupValues[1]
        }

        // 提取 duration 参数
        val durationMatch = Regex("""duration="?([^",)]+)"?""").find(response)
        if (durationMatch != null) {
            result["duration"] = durationMatch.groupValues[1]
        }

        // 提取 message 参数
        val messageMatch = Regex("""message="([^"]*)"?""").find(response)
        if (messageMatch != null) {
            result["message"] = messageMatch.groupValues[1]
        }

        return result
    }
}

/**
 * 动作处理器 - 执行从 AI 模型输出的动作
 */
class ActionHandler(
    private val deviceController: DeviceController,
    private val onConfirmation: ((String) -> Boolean)? = null,
    private val onTakeover: ((String) -> Unit)? = null
) {
    /**
     * 执行动作（同步方式）
     */
    suspend fun execute(action: Map<String, Any>, screenWidth: Int, screenHeight: Int): ActionResult {
        val metadata = action["_metadata"] as? String

        if (metadata == "finish") {
            return ActionResult(
                success = true,
                shouldFinish = true,
                message = action["message"] as? String
            )
        }

        if (metadata != "do") {
            return ActionResult(
                success = false,
                shouldFinish = true,
                message = "Unknown action type: $metadata"
            )
        }

        val actionName = action["action"] as? String
        return when (actionName) {
            "Launch" -> handleLaunch(action)
            "Tap" -> handleTap(action, screenWidth, screenHeight)
            "Type", "Type_Name" -> handleType(action)
            "Swipe" -> handleSwipe(action, screenWidth, screenHeight)
            "Back" -> handleBack()
            "Home" -> handleHome()
            "Double Tap" -> handleDoubleTap(action, screenWidth, screenHeight)
            "Long Press" -> handleLongPress(action, screenWidth, screenHeight)
            "Wait" -> handleWait(action)
            "Take_over" -> handleTakeover(action)
            else -> ActionResult(success = false, shouldFinish = false, message = "Unknown action: $actionName")
        }
    }

    private fun convertRelativeToAbsolute(element: List<Int>, screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        val x = (element[0] / 1000.0 * screenWidth).toInt()
        val y = (element[1] / 1000.0 * screenHeight).toInt()
        return Pair(x, y)
    }

    private suspend fun handleLaunch(action: Map<String, Any>): ActionResult {
        val appName = action["app"] as? String ?: return ActionResult(false, false, "No app name")
        val success = deviceController.launchApp(appName)
        return if (success) {
            ActionResult(true, false)
        } else {
            ActionResult(false, false, "App not found: $appName")
        }
    }

    private suspend fun handleTap(action: Map<String, Any>, screenWidth: Int, screenHeight: Int): ActionResult {
        @Suppress("UNCHECKED_CAST")
        val element = action["element"] as? List<Int> ?: return ActionResult(false, false, "No element coordinates")

        // 敏感操作确认
        val message = action["message"] as? String
        if (message != null && onConfirmation != null) {
            if (!onConfirmation.invoke(message)) {
                return ActionResult(false, true, "User cancelled")
            }
        }

        val (x, y) = convertRelativeToAbsolute(element, screenWidth, screenHeight)
        deviceController.tap(x, y)
        return ActionResult(true, false)
    }

    private suspend fun handleType(action: Map<String, Any>): ActionResult {
        val text = action["text"] as? String ?: ""
        deviceController.clearText()
        delay(200)
        deviceController.typeText(text)
        return ActionResult(true, false)
    }

    private suspend fun handleSwipe(action: Map<String, Any>, screenWidth: Int, screenHeight: Int): ActionResult {
        @Suppress("UNCHECKED_CAST")
        val start = action["start"] as? List<Int> ?: return ActionResult(false, false, "Missing start coordinates")
        @Suppress("UNCHECKED_CAST")
        val end = action["end"] as? List<Int> ?: return ActionResult(false, false, "Missing end coordinates")

        val (startX, startY) = convertRelativeToAbsolute(start, screenWidth, screenHeight)
        val (endX, endY) = convertRelativeToAbsolute(end, screenWidth, screenHeight)

        deviceController.swipe(startX, startY, endX, endY)
        return ActionResult(true, false)
    }

    private suspend fun handleBack(): ActionResult {
        deviceController.back()
        return ActionResult(true, false)
    }

    private suspend fun handleHome(): ActionResult {
        deviceController.home()
        return ActionResult(true, false)
    }

    private suspend fun handleDoubleTap(action: Map<String, Any>, screenWidth: Int, screenHeight: Int): ActionResult {
        @Suppress("UNCHECKED_CAST")
        val element = action["element"] as? List<Int> ?: return ActionResult(false, false, "No element coordinates")

        val (x, y) = convertRelativeToAbsolute(element, screenWidth, screenHeight)
        deviceController.doubleTap(x, y)
        return ActionResult(true, false)
    }

    private suspend fun handleLongPress(action: Map<String, Any>, screenWidth: Int, screenHeight: Int): ActionResult {
        @Suppress("UNCHECKED_CAST")
        val element = action["element"] as? List<Int> ?: return ActionResult(false, false, "No element coordinates")

        val (x, y) = convertRelativeToAbsolute(element, screenWidth, screenHeight)
        deviceController.longPress(x, y)
        return ActionResult(true, false)
    }

    private suspend fun handleWait(action: Map<String, Any>): ActionResult {
        val durationStr = action["duration"] as? String ?: "1 seconds"
        val duration = durationStr.replace("seconds", "").trim().toFloatOrNull() ?: 1f
        delay((duration * 1000).toLong())
        return ActionResult(true, false)
    }

    private fun handleTakeover(action: Map<String, Any>): ActionResult {
        val message = action["message"] as? String ?: "需要人工介入"
        onTakeover?.invoke(message)
        return ActionResult(true, false)
    }

    /**
     * 异步执行动作 - 不等待结果，立即返回
     * 适用于需要快速响应的场景
     */
    fun executeAsync(
        action: Map<String, Any>,
        screenWidth: Int,
        screenHeight: Int,
        callback: ((ActionResult) -> Unit)? = null
    ) {
        val metadata = action["_metadata"] as? String

        if (metadata == "finish") {
            val result = ActionResult(
                success = true,
                shouldFinish = true,
                message = action["message"] as? String
            )
            callback?.invoke(result)
            return
        }

        if (metadata != "do") {
            val result = ActionResult(
                success = false,
                shouldFinish = true,
                message = "Unknown action type: $metadata"
            )
            callback?.invoke(result)
            return
        }

        val actionName = action["action"] as? String
        when (actionName) {
            "Launch" -> handleLaunchAsync(action, callback)
            "Tap" -> handleTapAsync(action, screenWidth, screenHeight, callback)
            "Type", "Type_Name" -> handleTypeAsync(action, callback)
            "Swipe" -> handleSwipeAsync(action, screenWidth, screenHeight, callback)
            "Back" -> handleBackAsync(callback)
            "Home" -> handleHomeAsync(callback)
            "Double Tap" -> handleDoubleTapAsync(action, screenWidth, screenHeight, callback)
            "Long Press" -> handleLongPressAsync(action, screenWidth, screenHeight, callback)
            "Wait" -> handleWaitAsync(action, callback)
            "Take_over" -> {
                val result = handleTakeover(action)
                callback?.invoke(result)
            }
            else -> {
                val result = ActionResult(success = false, shouldFinish = false, message = "Unknown action: $actionName")
                callback?.invoke(result)
            }
        }
    }

    private fun handleLaunchAsync(action: Map<String, Any>, callback: ((ActionResult) -> Unit)?) {
        val appName = action["app"] as? String
        if (appName == null) {
            callback?.invoke(ActionResult(false, false, "No app name"))
            return
        }

        // Launch 操作需要等待应用启动，使用同步方法
        GlobalScope.launch {
            val success = deviceController.launchApp(appName)
            val result = if (success) {
                ActionResult(true, false)
            } else {
                ActionResult(false, false, "App not found: $appName")
            }
            callback?.invoke(result)
        }
    }

    private fun handleTapAsync(action: Map<String, Any>, screenWidth: Int, screenHeight: Int, callback: ((ActionResult) -> Unit)?) {
        @Suppress("UNCHECKED_CAST")
        val element = action["element"] as? List<Int>
        if (element == null) {
            callback?.invoke(ActionResult(false, false, "No element coordinates"))
            return
        }

        // 敏感操作确认
        val message = action["message"] as? String
        if (message != null && onConfirmation != null) {
            if (!onConfirmation.invoke(message)) {
                callback?.invoke(ActionResult(false, true, "User cancelled"))
                return
            }
        }

        val (x, y) = convertRelativeToAbsolute(element, screenWidth, screenHeight)
        deviceController.tapAsync(x, y) { success ->
            callback?.invoke(ActionResult(success, false))
        }
    }

    private fun handleTypeAsync(action: Map<String, Any>, callback: ((ActionResult) -> Unit)?) {
        val text = action["text"] as? String ?: ""

        // 先清空文本，再输入新文本
        GlobalScope.launch {
            deviceController.clearText()
            delay(200)
            deviceController.typeTextAsync(text) { success ->
                callback?.invoke(ActionResult(success, false))
            }
        }
    }

    private fun handleSwipeAsync(action: Map<String, Any>, screenWidth: Int, screenHeight: Int, callback: ((ActionResult) -> Unit)?) {
        @Suppress("UNCHECKED_CAST")
        val start = action["start"] as? List<Int>
        @Suppress("UNCHECKED_CAST")
        val end = action["end"] as? List<Int>

        if (start == null || end == null) {
            callback?.invoke(ActionResult(false, false, "Missing coordinates"))
            return
        }

        val (startX, startY) = convertRelativeToAbsolute(start, screenWidth, screenHeight)
        val (endX, endY) = convertRelativeToAbsolute(end, screenWidth, screenHeight)

        deviceController.swipeAsync(startX, startY, endX, endY) { success ->
            callback?.invoke(ActionResult(success, false))
        }
    }

    private fun handleBackAsync(callback: ((ActionResult) -> Unit)?) {
        deviceController.backAsync { success ->
            callback?.invoke(ActionResult(success, false))
        }
    }

    private fun handleHomeAsync(callback: ((ActionResult) -> Unit)?) {
        deviceController.homeAsync { success ->
            callback?.invoke(ActionResult(success, false))
        }
    }

    private fun handleDoubleTapAsync(action: Map<String, Any>, screenWidth: Int, screenHeight: Int, callback: ((ActionResult) -> Unit)?) {
        @Suppress("UNCHECKED_CAST")
        val element = action["element"] as? List<Int>
        if (element == null) {
            callback?.invoke(ActionResult(false, false, "No element coordinates"))
            return
        }

        val (x, y) = convertRelativeToAbsolute(element, screenWidth, screenHeight)
        deviceController.doubleTapAsync(x, y) { success ->
            callback?.invoke(ActionResult(success, false))
        }
    }

    private fun handleLongPressAsync(action: Map<String, Any>, screenWidth: Int, screenHeight: Int, callback: ((ActionResult) -> Unit)?) {
        @Suppress("UNCHECKED_CAST")
        val element = action["element"] as? List<Int>
        if (element == null) {
            callback?.invoke(ActionResult(false, false, "No element coordinates"))
            return
        }

        val (x, y) = convertRelativeToAbsolute(element, screenWidth, screenHeight)
        deviceController.longPressAsync(x, y) { success ->
            callback?.invoke(ActionResult(success, false))
        }
    }

    private fun handleWaitAsync(action: Map<String, Any>, callback: ((ActionResult) -> Unit)?) {
        val durationStr = action["duration"] as? String ?: "1 seconds"
        val duration = durationStr.replace("seconds", "").trim().toFloatOrNull() ?: 1f

        GlobalScope.launch {
            delay((duration * 1000).toLong())
            callback?.invoke(ActionResult(true, false))
        }
    }
}

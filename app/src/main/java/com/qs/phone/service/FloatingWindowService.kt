package com.qs.phone.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.qs.phone.AgentConfig
import com.qs.phone.AgentState
import com.qs.phone.PhoneAgent
import com.qs.phone.MainActivity
import com.qs.phone.model.ModelConfig
import com.qs.phone.util.NativeLibraryLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.ViewConfiguration
import android.app.ActionBar
import android.view.ViewGroup
import android.animation.ObjectAnimator
import android.view.animation.LinearInterpolator
import android.view.WindowInsets
import android.os.Build
import android.view.WindowMetrics

/**
 * 无障碍服务 - 用于显示浮窗和控制 Agent
 */
class FloatingWindowService : AccessibilityService() {

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val CHANNEL_ID = "zhi_ai_takeover_channel"
        private const val NOTIFICATION_ID = 1001

        var instance: FloatingWindowService? = null
            private set

        // 配置
        var baseUrl = "https://open.bigmodel.cn/api/paas/v4"
        var apiKey = "EMPTY"
        var modelName = "autoglm-phone"
    }

    // 保存最后一个无障碍事件
    private var lastAccessibilityEvent: AccessibilityEvent? = null

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var widgetView: View? = null
    private var marqueeView: View? = null
    private var isExpanded = true

    private var agent: PhoneAgent? = null
    private var agentJob: Job? = null
    private var stateCollectionJob: Job? = null
    private var logCollectionJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // UI 组件 - 主界面
    private var logTextView: TextView? = null
    private var scrollView: ScrollView? = null
    private var inputEditText: EditText? = null
    private var startButton: Button? = null
    private var stopButton: Button? = null
    private var toggleButton: Button? = null
    private var clearButton: Button? = null

    // UI 组件 - 小悬浮窗
    private var logoImageView: android.widget.ImageView? = null
    private var statusIndicator: View? = null

    // 窗口参数
    private var mainParams: WindowManager.LayoutParams? = null
    private var widgetParams: WindowManager.LayoutParams? = null
    private var marqueeParams: WindowManager.LayoutParams? = null

    // 跑马灯动画
    private var borderAnimator: ValueAnimator? = null
    private var cornerAnimator: ValueAnimator? = null
    private var typewriterJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        instance = this

        // 加载原生库
        NativeLibraryLoader.loadLibraries(this)

        // 创建通知渠道
        createNotificationChannel()

        createFloatingWindow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 保存最后一个无障碍事件，用于应用检测
        event?.let {
            // 只关注应用程序类型的事件
            if (it.packageName != null && it.eventType in listOf(
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    AccessibilityEvent.TYPE_VIEW_FOCUSED
                )) {
                lastAccessibilityEvent = it
//                Log.d(TAG, "Saved accessibility event for package: ${it.packageName}")
            }
        }
    }

    override fun onInterrupt() {
        // 服务中断
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        instance = null

        // 取消所有协程
        agentJob?.cancel()
        logCollectionJob?.cancel()
        typewriterJob?.cancel()

        // 只有在服务销毁时才取消状态监听
        stateCollectionJob?.cancel()
        serviceScope.cancel()

        hideMarqueeEffect()
        removeFloatingWindow()
    }

    private fun createFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 获取屏幕尺寸
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val inflater = LayoutInflater.from(this)

        // 创建主界面
        val mainLayoutId = resources.getIdentifier("layout_floating_window", "layout", packageName)
        floatingView = inflater.inflate(mainLayoutId, null)

        val keyboardHeight = (screenHeight * 2) / 5 // 屏幕高度的2/5
        mainParams = WindowManager.LayoutParams(
            screenWidth, // 宽度全屏
            keyboardHeight, // 高度为屏幕的2/5
            layoutType,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START // 位置在屏幕下方
            x = 0
            y = 0
        }

        // 创建小悬浮窗
        val widgetLayoutId =
            resources.getIdentifier("layout_floating_widget", "layout", packageName)
        widgetView = inflater.inflate(widgetLayoutId, null)

        // 设置小悬浮窗位置：初始化时先计算目标位置
        val widgetWidth = 40 // dp
        val density = displayMetrics.density
        val widgetWidthPx = (widgetWidth * density).toInt()

        // 默认吸附到右边缘
        val widgetX = screenWidth - widgetWidthPx
        val widgetY = screenHeight * 4 / 5 // 屏幕高度的4/5处

        widgetParams = WindowManager.LayoutParams(
            ActionBar.LayoutParams.WRAP_CONTENT, // 40dp宽度，匹配布局
            ActionBar.LayoutParams.WRAP_CONTENT, // 40dp高度，匹配布局
            layoutType,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = widgetX
            y = widgetY
        }

        // 创建跑马灯视图
        createMarqueeView()

        setupViews()
        setupWidgetViews()
        setupDragListener()
        setupWidgetDragListener()

        // 默认显示主界面
        windowManager?.addView(floatingView, mainParams)
        appendLog("🤝 你好,我是你的AI助手.\n你可以让我执行一些简单的操作哦!\n"+"🎉比如点杯奶茶,自己刷会抖音,微信回复XX信息.\n    任务开始窗口会自动隐藏\n    运行中不要打开本窗口\n    否则会阻塞程序正常执行")

    }

    private fun setupViews() {
        floatingView?.let { view ->
            logTextView =
                view.findViewById(resources.getIdentifier("logTextView", "id", packageName))
            scrollView = view.findViewById(resources.getIdentifier("scrollView", "id", packageName))
            inputEditText =
                view.findViewById(resources.getIdentifier("inputEditText", "id", packageName))
            startButton =
                view.findViewById(resources.getIdentifier("startButton", "id", packageName))
            stopButton = view.findViewById(resources.getIdentifier("stopButton", "id", packageName))
            toggleButton =
                view.findViewById(resources.getIdentifier("toggleButton", "id", packageName))
            clearButton =
                view.findViewById(resources.getIdentifier("clearButton", "id", packageName))

            val expandedContent = view.findViewById<View>(
                resources.getIdentifier(
                    "expandedContent",
                    "id",
                    packageName
                )
            )

            // 清空日志按钮
            clearButton?.setOnClickListener {
                clearLogs()
            }

            // 切换最小化/展开
            toggleButton?.setOnClickListener {
                showWidgetInterface()
            }


            // 开始按钮
            startButton?.setOnClickListener {
                val task = inputEditText?.text?.toString()
                if (!task.isNullOrBlank()) {
                    startAgent(task)
                }
                // 清空输入框
                inputEditText?.text?.clear()
            }

            // 停止按钮
            stopButton?.setOnClickListener {
                stopAgent()
            }
        }
    }

    private fun setupWidgetViews() {
        widgetView?.let { view ->
            logoImageView =
                view.findViewById(resources.getIdentifier("logoImageView", "id", packageName))
            statusIndicator =
                view.findViewById(resources.getIdentifier("statusIndicator", "id", packageName))
            // 移除OnClickListener，直接在OnTouchListener中处理点击
        }
    }

    /**
     * 创建跑马灯视图
     */
    private fun createMarqueeView() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val inflater = LayoutInflater.from(this)
        val marqueeLayoutId = resources.getIdentifier("layout_tech_marquee", "layout", packageName)
        marqueeView = inflater.inflate(marqueeLayoutId, null)

        marqueeParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
            WindowManager.LayoutParams.FLAG_LAYOUT_ATTACHED_IN_DECOR,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0

            // 获取真实屏幕尺寸，包括系统栏
            val displayMetrics = resources.displayMetrics
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics = windowManager.currentWindowMetrics
                val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
                width = windowMetrics.bounds.width()
                height = windowMetrics.bounds.height()
            } else {
                @Suppress("DEPRECATION")
                width = windowManager.defaultDisplay.width
                @Suppress("DEPRECATION")
                height = windowManager.defaultDisplay.height
            }
        }
    }

    /**
     * 显示跑马灯效果
     */
    private fun showMarqueeEffect() {
        marqueeView?.let { view ->
            try {
                if (view.parent == null) {
                    windowManager?.addView(view, marqueeParams)
                }
                startMarqueeAnimations()
                Log.d(TAG, "Marquee effect shown")
            } catch (e: Exception) {
                Log.e(TAG, "Error showing marquee effect", e)
            }
        }
    }

    /**
     * 隐藏跑马灯效果
     */
    private fun hideMarqueeEffect() {
        stopMarqueeAnimations()
        marqueeView?.let { view ->
            try {
                if (view.parent != null) {
                    windowManager?.removeView(view)
                }
                Log.d(TAG, "Marquee effect hidden")
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding marquee effect", e)
            }
        }
    }

    /**
     * 启动跑马灯动画
     */
    private fun startMarqueeAnimations() {
        marqueeView?.let { view ->
            // 边框渐变动画
            borderAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    val value = animation.animatedValue as Float
                    val alpha = (Math.sin(value * Math.PI * 2) * 0.5 + 0.5).toFloat()

                    view.findViewById<View>(
                        resources.getIdentifier("topBorder", "id", packageName)
                    )?.alpha = alpha
                    view.findViewById<View>(
                        resources.getIdentifier("bottomBorder", "id", packageName)
                    )?.alpha = alpha
                    view.findViewById<View>(
                        resources.getIdentifier("leftBorder", "id", packageName)
                    )?.alpha = alpha
                    view.findViewById<View>(
                        resources.getIdentifier("rightBorder", "id", packageName)
                    )?.alpha = alpha

                }
            }

            // 四角闪烁动画
            cornerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1200
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { animation ->
                    val value = animation.animatedValue as Float
                    val alpha = (Math.sin(value * Math.PI * 2) * 0.5 + 0.5).toFloat()

                    view.findViewById<View>(
                        resources.getIdentifier("topLeftCorner", "id", packageName)
                    )?.alpha = alpha
                    view.findViewById<View>(
                        resources.getIdentifier("topRightCorner", "id", packageName)
                    )?.alpha = alpha
                    view.findViewById<View>(
                        resources.getIdentifier("bottomLeftCorner", "id", packageName)
                    )?.alpha = alpha
                    view.findViewById<View>(
                        resources.getIdentifier("bottomRightCorner", "id", packageName)
                    )?.alpha = alpha
                }
            }

            // 启动所有动画
            borderAnimator?.start()
            cornerAnimator?.start()
        }
    }

    /**
     * 停止跑马灯动画
     */
    private fun stopMarqueeAnimations() {
        borderAnimator?.cancel()
        cornerAnimator?.cancel()

        borderAnimator = null
        cornerAnimator = null
    }

    /**
     * 隐藏跑马灯状态指示器
     */
    fun hideMarqueeStatusIndicator() {
        serviceScope.launch(Dispatchers.Main) {
            marqueeView?.let { view ->
                val statusLayout = view.findViewById<View>(
                    resources.getIdentifier("statusIndicatorLayout", "id", packageName)
                )
                statusLayout?.visibility = View.GONE
                Log.d(TAG, "Marquee status indicator hidden")
            }
        }
    }

    /**
     * 显示跑马灯状态指示器
     */
    fun showMarqueeStatusIndicator() {
        serviceScope.launch(Dispatchers.Main) {
            marqueeView?.let { view ->
                val statusLayout = view.findViewById<View>(
                    resources.getIdentifier("statusIndicatorLayout", "id", packageName)
                )
                statusLayout?.visibility = View.VISIBLE
                Log.d(TAG, "Marquee status indicator shown")
            }
        }
    }

    /**
     * 打字机效果
     */
    private fun startTypewriterEffect(textView: android.widget.TextView, text: String) {
        // 取消之前的打字机动画
        typewriterJob?.cancel()

        typewriterJob = serviceScope.launch(Dispatchers.Main) {
            textView.text = ""
            val delay = 30L // 每个字符之间的延迟（毫秒）

            for (i in text.indices) {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive != true) break // 检查协程是否被取消

                textView.text = text.take(i + 1)
                kotlinx.coroutines.delay(delay)
            }
        }
    }

    /**
     * 更新跑马灯上的思考文本
     */
    fun updateMarqueeThinkingText(thinkingText: String) {
        serviceScope.launch(Dispatchers.Main) {
            marqueeView?.let { view ->
                // 尝试找到专门的思考文本视图
                val thinkingTextView = view.findViewById<android.widget.TextView>(
                    resources.getIdentifier("thinkingText", "id", packageName)
                )

                // 检查跑马灯是否可见
                if (view.visibility != View.VISIBLE) {
                    // 如果跑马灯不可见，直接返回，不执行打字机效果
                    return@launch
                }

                thinkingTextView?.let { textView ->
                    val displayText = if (thinkingText.length > 100) {
                        thinkingText.take(97) + "..."
                    } else {
                        thinkingText
                    }
                    startTypewriterEffect(textView, displayText)
                    return@launch
                }

                // 如果找不到思考文本视图，尝试更新状态文本
                val statusLayout = view.findViewById<View>(
                    resources.getIdentifier("statusIndicatorLayout", "id", packageName)
                )
                if (statusLayout is android.widget.LinearLayout) {
                    val textView = statusLayout.getChildAt(1) as? android.widget.TextView
                    textView?.let {
                        val displayText = if (thinkingText.length > 30) {
                            "🤖 ${thinkingText.take(27)}..."
                        } else {
                            "🤖 $thinkingText"
                        }
                        startTypewriterEffect(it, displayText)
                    }
                }
            }
        }
    }

    private fun showMainInterface() {
        Log.d(TAG, "showMainInterface called, isExpanded=$isExpanded")
        if (!isExpanded) {
            try {
                Log.d(TAG, "Removing widget view and adding floating view")
                windowManager?.removeView(widgetView)
                windowManager?.addView(floatingView, mainParams)
                isExpanded = true
                Log.d(TAG, "Main interface shown successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error showing main interface", e)
            }
        } else {
            Log.d(TAG, "Already expanded, not showing main interface")
        }
    }

    private fun showWidgetInterface() {
        if (isExpanded) {
            try {
                windowManager?.removeView(floatingView)
                windowManager?.addView(widgetView, widgetParams)
                isExpanded = false
            } catch (e: Exception) {
                Log.e(TAG, "Error showing widget interface", e)
            }
        }
    }

    private fun setupDragListener() {
        // 主界面不需要拖动功能，因为它固定在底部
    }

    private fun setupWidgetDragListener() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var hasMoved = false

        widgetView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.d(TAG, "ACTION_DOWN received")
                    initialX = widgetParams?.x ?: 0
                    initialY = widgetParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    hasMoved = false
                    true // 消费此事件
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = Math.abs(event.rawX - initialTouchX)
                    val deltaY = Math.abs(event.rawY - initialTouchY)

                    if (deltaX > 10 || deltaY > 10) { // 使用固定阈值10像素
                        hasMoved = true
                        Log.d(TAG, "Dragging detected, hasMoved=true")

                        widgetParams?.let { params ->
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()

                            // 确保在屏幕范围内
                            constrainToScreenBounds(params)

                            windowManager?.updateViewLayout(widgetView, params)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    Log.d(TAG, "ACTION_UP received, hasMoved=$hasMoved")
                    if (hasMoved) {
                        // 这是拖动操作，吸附到边缘
                        widgetParams?.let { params ->
                            animateSnapToEdge(params)
                        }
                    } else {
                        // 这是点击操作
                        Log.d(TAG, "Widget clicked! Calling showMainInterface()")
                        showMainInterface()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun startAgent(task: String) {
        Log.d(TAG, "Starting agent with task: $task")

        // 清理之前的协程
        stopAgent()

        val modelConfig = ModelConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelName = modelName
        )
        val agentConfig = AgentConfig(verbose = true)

        agent = PhoneAgent(
            context = this@FloatingWindowService,
            modelConfig = modelConfig,
            agentConfig = agentConfig,
            onTakeover = { message ->
                appendLog("⚠️ 需要人工介入: $message")
                // 发送通知提醒用户
                sendTakeoverNotification(message)
            }
        )

        // 初始化 Agent
        serviceScope.launch {
            try {
                val initialized = agent?.initialize()
                Log.e(TAG, "startAgent: " + initialized)

                if (initialized == true) {
                    appendLog("✅ Agent 初始化成功")

                    // 检查 LADB 可用性
                    if (agent?.deviceController?.isLadbAvailable() == true) {
                        appendLog("ℹ️ 使用 LADB 模式（无需 Root）")
                    } else {
                        appendLog("⚠️ LADB 不可用，需要 Root 权限或安装 LADB")
                    }

                    // 检查设备连接
                    val devices = agent?.deviceController?.getDevices()
                    if (!devices.isNullOrEmpty()) {
                        appendLog("📱 检测到设备: $devices")
                    } else {
                        appendLog("⚠️ 未检测到 ADB 设备，请检查调试设置")
                    }
                    Log.e(TAG, "Agent state: " + agent?.state)
                    // 收集日志 - 创建 Job 引用以便管理
                    logCollectionJob = serviceScope.launch {
                        agent?.logs?.collectLatest { logs ->
                            logTextView?.text = logs.joinToString("\n")
                            scrollView?.post {
                                scrollView?.fullScroll(View.FOCUS_DOWN)
                            }
                        }
                    }
                    Log.e(TAG, "Agent state2: " + agent?.state)

                    // 收集状态 - 创建 Job 引用以便管理
                    stateCollectionJob = serviceScope.launch {
                        agent?.state?.collect { state ->
                            Log.d(TAG, "State changed to: ${state::class.simpleName}")
                            when (state) {
                                is AgentState.Running -> {
                                    showWidgetInterface()
                                    startButton?.isEnabled = true
                                    stopButton?.isEnabled = true
                                    updateStatusIndicator(true)
                                    showMarqueeEffect()
                                }

                                is AgentState.Thinking -> {
                                    updateMarqueeThinkingText(state.content)
                                }
                                is AgentState.ThinkingMsg -> {
                                    updateMarqueeThinkingText(state.content)
                                }

                                is AgentState.Completed -> {
                                    Log.d(TAG, "Handling Completed state: ${state.message}")
                                    typewriterJob?.cancel() // 立即取消打字机效果
                                    showMainInterface()
                                    startButton?.isEnabled = true
                                    stopButton?.isEnabled = false
                                    updateStatusIndicator(false)
                                    hideMarqueeEffect()
                                    appendLog("✅ ${state.message}")
                                }

                                is AgentState.Error -> {
                                    typewriterJob?.cancel() // 立即取消打字机效果
                                    showMainInterface()
                                    startButton?.isEnabled = true
                                    stopButton?.isEnabled = false
                                    updateStatusIndicator(false)
                                    hideMarqueeEffect()
                                    appendLog("❌ ${state.message}")
                                }

                                is AgentState.Idle -> {
                                    typewriterJob?.cancel() // 立即取消打字机效果
                                    showMainInterface()
                                    startButton?.isEnabled = true
                                    stopButton?.isEnabled = false
                                    updateStatusIndicator(false)
                                    hideMarqueeEffect()
                                }

                                else -> {}
                            }
                        }
                    }
                    Log.e(TAG, "Agent state: " + agent?.state)

                    // 运行 Agent
                    agentJob = serviceScope.launch(Dispatchers.IO) {
                        try {
                            agent?.run(task)
                        } catch (e: Exception) {
                            appendLog("❌ 错误: ${e.message}")
                        } finally {
                            Log.d(TAG, "Agent job finished, ensuring main interface is shown")
                            agent?.cleanup()

                            // 确保在主线程中切换界面
                            serviceScope.launch(Dispatchers.Main) {
                                showMainInterface()
                                startButton?.isEnabled = true
                                stopButton?.isEnabled = false
                                updateStatusIndicator(false)
                                hideMarqueeEffect()
                                typewriterJob?.cancel()
                            }
                        }
                    }
                } else {
                    appendLog("❌ Agent 初始化失败")
                    appendLog("💡 请确保：")
                    appendLog("   • 已安装 LADB 应用")
                    appendLog("   • 或已获取 Root 权限")
                    appendLog("   • 已在开发者选项中启用调试")
                }
            } catch (e: Exception) {
                appendLog("❌ 初始化错误: ${e.message}")
                Log.e(TAG, "Agent initialization error", e)
            }
        }

        appendLog("🚀 开始任务: $task")
    }

    private fun stopAgent() {
        agent?._state?.value = AgentState.Thinking("思考中...")
        Log.d(TAG, "Stopping agent")

        // 先停止 Agent，让状态变化被处理
        agent?.stop()
        agent?.cleanup()

        typewriterJob?.cancel() // 取消打字机效果
        hideMarqueeEffect()

        // 最后才取消协程
        agentJob?.cancel()
        logCollectionJob?.cancel()
        // 注意：不要在这里取消 stateCollectionJob，否则无法接收后续的状态变化

        appendLog("⏹️ 已停止")
    }

    private fun appendLog(message: String) {
        serviceScope.launch(Dispatchers.Main) {
            val current = logTextView?.text?.toString() ?: ""
            logTextView?.text = if (current.isEmpty()) message else "$current\n$message"
            scrollView?.post {
                scrollView?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun removeFloatingWindow() {
        try {
            floatingView?.let {
                if (it.parent != null) {
                    windowManager?.removeView(it)
                }
                floatingView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing floating view", e)
        }

        try {
            widgetView?.let {
                if (it.parent != null) {
                    windowManager?.removeView(it)
                }
                widgetView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing widget view", e)
        }
    }

    fun clearLogs() {
        logTextView?.text = ""
        appendLog("🤝 你好,我是你的AI助手.\n你可以让我执行一些简单的操作哦!\n"+"🎉比如点杯奶茶,自己刷会抖音,微信回复XX信息.\n    任务开始窗口会自动隐藏\n    运行中不要打开本窗口\n    否则会阻塞程序正常执行")
    }

    private fun updateStatusIndicator(isRunning: Boolean) {
        statusIndicator?.background = if (isRunning) {
            resources.getDrawable(
                resources.getIdentifier(
                    "status_indicator_on",
                    "drawable",
                    packageName
                ), null
            )
        } else {
            resources.getDrawable(
                resources.getIdentifier(
                    "status_indicator_off",
                    "drawable",
                    packageName
                ), null
            )
        }
    }

    /**
     * 确保悬浮窗在屏幕范围内
     */
    private fun constrainToScreenBounds(params: WindowManager.LayoutParams) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val widgetWidth = 40 // dp
        val widgetHeight = 40 // dp

        // 转换为像素
        val density = displayMetrics.density
        val widgetWidthPx = (widgetWidth * density).toInt()
        val widgetHeightPx = (widgetHeight * density).toInt()

        // 限制X坐标范围
        params.x = params.x.coerceIn(0, screenWidth - widgetWidthPx)

        // 限制Y坐标范围
        params.y = params.y.coerceIn(0, screenHeight - widgetHeightPx)
    }

    /**
     * 自动吸附到最近的屏幕边缘（无动画版本）
     */
    private fun snapToEdge(params: WindowManager.LayoutParams) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val widgetWidth = 40 // dp

        // 转换为像素
        val density = displayMetrics.density
        val widgetWidthPx = (widgetWidth * density).toInt()

        // 计算屏幕中心线
        val centerX = screenWidth / 2

        // 判断更接近左边缘还是右边缘
        val widgetCenterX = params.x + widgetWidthPx / 2
        val snapToRight = widgetCenterX > centerX

        // 吸附到最近的边缘
        if (snapToRight) {
            // 吸附到右边缘
            params.x = screenWidth - widgetWidthPx
        } else {
            // 吸附到左边缘
            params.x = 0
        }
    }

    /**
     * 带动画效果自动吸附到最近的屏幕边缘
     */
    private fun animateSnapToEdge(params: WindowManager.LayoutParams) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val widgetWidth = 40 // dp

        // 转换为像素
        val density = displayMetrics.density
        val widgetWidthPx = (widgetWidth * density).toInt()

        // 计算屏幕中心线
        val centerX = screenWidth / 2

        // 判断更接近左边缘还是右边缘
        val widgetCenterX = params.x + widgetWidthPx / 2
        val snapToRight = widgetCenterX > centerX

        // 计算目标位置
        val targetX = if (snapToRight) {
            screenWidth - widgetWidthPx
        } else {
            0
        }

        // 如果已经在目标位置，不需要动画
        if (params.x == targetX) {
            return
        }

        // 创建值动画器
        val animator = ValueAnimator.ofInt(params.x, targetX)
        animator.duration = 200 // 200毫秒动画
        animator.addUpdateListener { animation ->
            val currentX = animation.animatedValue as Int
            params.x = currentX
            windowManager?.updateViewLayout(widgetView, params)
        }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // 确保最终位置正确
                params.x = targetX
                windowManager?.updateViewLayout(widgetView, params)
            }
        })

        animator.start()
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "智AI 人工介入提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "当 AI 需要人工介入时发送通知提醒"
                enableVibration(true)
                enableLights(true)
            }

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 发送人工介入通知
     */
    private fun sendTakeoverNotification(message: String) {
        try {
            val intent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("🤖 智AI 需要人工介入")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .addAction(
                    android.R.drawable.ic_menu_view,
                    "查看",
                    pendingIntent
                )
                .build()

            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)

            Log.d(TAG, "Takeover notification sent: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send takeover notification", e)
        }
    }

    /**
     * 获取最后一个无障碍事件
     */
    fun getLastAccessibilityEvent(): AccessibilityEvent? = lastAccessibilityEvent

}

package com.qs.phone.controller

import android.content.Context
import android.util.Log

/**
 * 测试 AppDetector 的工具类
 */
class AppDetectionTest(private val context: Context) {

    companion object {
        private const val TAG = "AppDetectionTest"
    }

    /**
     * 测试应用检测功能
     */
    fun testAppDetection() {
        Log.d(TAG, "开始测试应用检测功能...")

        val appDetector = AppDetector(context)

        // 测试获取当前应用
        val currentApp = appDetector.getCurrentApp()
        Log.d(TAG, "当前检测到的应用: $currentApp")

        // 显示检测方法状态
        val info = appDetector.getDetectionMethodsInfo()
        Log.d(TAG, "\n$info")

        Log.d(TAG, "应用检测测试完成")
    }
}
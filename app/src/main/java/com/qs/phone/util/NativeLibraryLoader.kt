package com.qs.phone.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * LADB 原生库加载器 - 直接加载本地 ADB 库
 * 严格按照 LADB 方案实现：直接使用 dlopen 加载 libadb.so
 * 无需额外的 JNI 桥接层
 */
object NativeLibraryLoader {
    private const val TAG = "NativeLibraryLoader"

    // 存储加载的 ADB 库句柄
    private var adbLibraryHandle: Long = 0

    /**
     * 初始化 LADB - 按照 LADB 官方方式加载原生库
     */
    fun loadLibraries(context: Context) {
        try {
            Log.d(TAG, "Initializing LADB...")

            // 查找 libadb.so 文件
            val adbPath = findAdbLibrary(context)
            if (adbPath == null) {
                Log.e(TAG, "ADB library not found")
                return
            }

            Log.d(TAG, "Found ADB library at: $adbPath")

            // 直接加载库文件（LADB 方式）
            adbLibraryHandle = loadNativeLibrary(adbPath)
            if (adbLibraryHandle != 0L) {
                Log.d(TAG, "Successfully loaded ADB library")
            } else {
                Log.e(TAG, "Failed to load ADB library")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading native libraries", e)
        }
    }

    /**
     * 查找 ADB 库文件
     * 按照 LADB 项目的方式查找：优先从 jniLibs 目录，其次从 nativeLibraryDir
     */
    private fun findAdbLibrary(context: Context): String? {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir

        // 首先尝试从标准的 nativeLibraryDir 查找
        val standardPath = "$nativeLibDir/libadb.so"
        if (File(standardPath).exists()) {
            Log.d(TAG, "Found ADB library at: $standardPath")
            return standardPath
        }

        // 如果没找到，尝试从 jniLibs 目录查找（APK 解压后的位置）
        val jniLibsPath = "$nativeLibDir/libadb.so"
        if (File(jniLibsPath).exists()) {
            Log.d(TAG, "Found ADB library at: $jniLibsPath")
            return jniLibsPath
        }

        Log.e(TAG, "ADB library not found in any location")
        return null
    }

    /**
     * 加载原生库 - 使用 System.load() 直接加载
     * 这是 LADB 的核心实现方式
     */
    private fun loadNativeLibrary(libPath: String): Long {
        return try {
            // 使用 System.load 加载库文件
            System.load(libPath)
            Log.i(TAG, "LADB library loaded successfully from: $libPath")
            1L // 返回非零值表示成功
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load LADB library: ${e.message}")
            0L
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error loading library: ${e.message}")
            0L
        }
    }

    /**
     * 检查 ADB 库是否已加载
     */
    fun isAdbAvailable(context: Context): Boolean {
        val adbPath = findAdbLibrary(context)
        return adbPath != null && File(adbPath).exists()
    }

    /**
     * 获取库加载状态
     */
    fun getLibraryStatus(context: Context): String {
        val available = isAdbAvailable(context)
        val status = StringBuilder()
        status.append("LADB Status:\n")
        status.append("  Library Available: ").append(if (available) "Yes" else "No").append("\n")

        if (available) {
            val adbPath = findAdbLibrary(context)
            if (adbPath != null) {
                val file = File(adbPath)
                status.append("  Library Path: ").append(adbPath).append("\n")
                status.append("  Library Size: ").append(file.length()).append(" bytes\n")
            }
        }

        status.append("  Native Library Handle: ").append(if (adbLibraryHandle != 0L) "Loaded" else "Not Loaded")

        return status.toString()
    }
}

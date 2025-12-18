package com.qs.phone.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限管理器 - 处理文件读写权限申请
 */
object PermissionManager {
    private const val TAG = "PermissionManager"
    private const val PERMISSION_REQUEST_CODE = 1001

    // 用于存储回调的临时引用
    private var permissionCallback: ((Boolean) -> Unit)? = null

    /**
     * 检查是否具有文件读写权限
     */
    fun hasStoragePermissions(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+ 使用 READ_MEDIA_IMAGES
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11-12
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
            else -> {
                // Android 10 及以下
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    /**
     * 检查是否具有所有文件访问权限 (Android 11+)
     */
    fun hasAllFilesAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 需要手动在设置中授予 MANAGE_EXTERNAL_STORAGE 权限
            // 无法通过代码自动申请
            try {
                val intent = android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                intent?.let { true } ?: false
            } catch (e: Exception) {
                false
            }
        } else {
            true
        }
    }

    /**
     * 请求文件读写权限
     */
    fun requestStoragePermissions(activity: Activity, callback: ((Boolean) -> Unit)? = null) {
        val permissionsToRequest = mutableListOf<String>()

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11-12
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            else -> {
                // Android 10 及以下
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        // 检查是否已经拥有权限
        val permissionsToRequestFiltered = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequestFiltered.isEmpty()) {
            Log.d(TAG, "All storage permissions already granted")
            callback?.invoke(true)
            return
        }

        // 保存回调引用
        permissionCallback = callback

        Log.d(TAG, "Requesting storage permissions: ${permissionsToRequestFiltered.joinToString()}")

        // 请求权限
        ActivityCompat.requestPermissions(
            activity,
            permissionsToRequestFiltered,
            PERMISSION_REQUEST_CODE
        )
    }

    /**
     * 处理权限请求结果
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        return when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                val allGranted = grantResults.isNotEmpty() &&
                        grantResults.all { it == PackageManager.PERMISSION_GRANTED }

                if (allGranted) {
                    Log.d(TAG, "Storage permissions granted")
                } else {
                    Log.e(TAG, "Storage permissions denied")
                }

                // 调用回调
                permissionCallback?.invoke(allGranted)
                permissionCallback = null

                allGranted
            }
            else -> false
        }
    }

    /**
     * 获取权限状态描述
     */
    fun getPermissionStatus(context: Context): String {
        val sb = StringBuilder()
        sb.append("=== Permission Status ===\n\n")

        // 存储权限
        val hasStorage = hasStoragePermissions(context)
        sb.append("Storage Permissions: ${if (hasStorage) "✅ Granted" else "❌ Denied"}\n")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sb.append("All Files Access: ${if (hasAllFilesAccess(context)) "✅ Granted" else "⚠️ Manual grant required"}\n")
            sb.append("Note: For Android 11+, enable 'All files access' in:\n")
            sb.append("  Settings > Apps > ${context.packageName} > Permissions\n")
        }

        sb.append("\nRequired Permissions:\n")
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                sb.append("  - READ_MEDIA_IMAGES\n")
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                sb.append("  - READ_EXTERNAL_STORAGE\n")
            }
            else -> {
                sb.append("  - WRITE_EXTERNAL_STORAGE\n")
                sb.append("  - READ_EXTERNAL_STORAGE\n")
            }
        }

        return sb.toString()
    }
}

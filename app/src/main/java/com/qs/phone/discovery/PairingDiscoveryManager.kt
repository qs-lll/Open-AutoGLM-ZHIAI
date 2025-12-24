package com.qs.phone.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 无线调试配对服务发现管理器
 * 参考 WirelessAdbPairingService 实现 mDNS 服务发现
 */
class PairingDiscoveryManager(
    private val context: Context,
    private val nsdManager: NsdManager
) {
    companion object {
        private const val TAG = "PairingDiscovery"
        private const val SERVICE_TYPE = "_adb-tls-pairing._tcp"
    }

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val discoveredServices = mutableListOf<NsdServiceInfo>()
    private var isDiscovering = false

    /**
     * 配对服务发现回调
     */
    interface PairingCallback {
        fun onServiceFound(serviceInfo: NsdServiceInfo, port: Int)
        fun onServiceLost(serviceName: String)
        fun onDiscoveryFailed(errorCode: Int)
    }

    private var callback: PairingCallback? = null

    /**
     * 启动配对服务发现
     */
    fun startPairingDiscovery(callback: PairingCallback): Boolean {
        if (isDiscovering) {
            Log.w(TAG, "Discovery already in progress")
            return false
        }

        this.callback = callback

        try {
            discoveryListener = createDiscoveryListener()
            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
            isDiscovering = true
            Log.d(TAG, "Started pairing service discovery")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            isDiscovering = false
            return false
        }
    }

    /**
     * 停止配对服务发现
     */
    fun stopPairingDiscovery() {
        if (!isDiscovering) return

        try {
            discoveryListener?.let {
                nsdManager.stopServiceDiscovery(it)
            }
            isDiscovering = false
            discoveredServices.clear()
            Log.d(TAG, "Stopped pairing service discovery")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop discovery", e)
        }
    }

    /**
     * 获取本地 IP 地址
     */
    fun getLocalIpAddress(): String? {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val addresses = networkInterface.inetAddresses

                    while (addresses.hasMoreElements()) {
                        val inetAddress = addresses.nextElement()
                        if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                            return inetAddress.hostAddress
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get local IP", e)
            }
        }

        return null
    }

    /**
     * 检查服务是否是本地服务
     */
    fun isLocalService(serviceInfo: NsdServiceInfo): Boolean {
        val localIp = getLocalIpAddress() ?: return false
        val serviceIp = serviceInfo.host?.hostAddress ?: return false
        return localIp == serviceIp
    }

    /**
     * 创建服务发现监听器
     */
    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Discovery started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName}")

                // 解析服务以获取完整信息
                resolveService(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
                discoveredServices.removeAll { it.serviceName == service.serviceName }
                callback?.onServiceLost(service.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped: $serviceType")
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: $serviceType, error: $errorCode")
                isDiscovering = false
                callback?.onDiscoveryFailed(errorCode)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $serviceType, error: $errorCode")
                isDiscovering = false
            }
        }
    }

    /**
     * 解析服务
     */
    private fun resolveService(service: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: ${serviceInfo.serviceName}, error: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service resolved: ${serviceInfo.serviceName}, port: ${serviceInfo.port}")

                // 只处理本地服务
                    val port = serviceInfo.port
                    if (port > 0) {
                        discoveredServices.add(serviceInfo)
                        callback?.onServiceFound(serviceInfo, port)
                    }
            }
        }

        try {
            nsdManager.resolveService(service, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve service", e)
        }
    }

    /**
     * 检查端口是否在使用
     */
    fun isPortInUse(port: Int): Boolean {
        return discoveredServices.any { it.port == port }
    }
}

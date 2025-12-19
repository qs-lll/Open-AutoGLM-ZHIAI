package com.qs.phone.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DNS 服务发现管理器
 * 专门负责通过 DNS-SD 协议发现 ADB 端口
 */
class DnsDiscoveryManager(
    private val context: Context,
    private val nsdManager: NsdManager
) {
    companion object {
        private const val TAG = "DnsDiscoveryManager"
        private const val SERVICE_TYPE = "_adb-tls-connect._tcp"
        private val pendingResolves = AtomicBoolean(false)
    }

    private var started = false
    private var bestExpirationTime: Long? = null
    private var bestServiceName: String? = null

    private var pendingServices: MutableList<NsdServiceInfo> =
        Collections.synchronizedList(ArrayList())

    /**
     * 扫描 ADB 端口
     */
    fun scanAdbPorts(): ScanResult {
        clearPorts()
        if (started) {
            Log.w(TAG, "DNS scan already started")
            return ScanResult(false, emptyList(), "Scan already in progress")
        }

        started = true
        startTime = System.currentTimeMillis()

        try {
            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
            Log.d(TAG, "Started DNS service discovery")
            return ScanResult(true, emptyList(), "Scan started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DNS discovery", e)
            started = false
            return ScanResult(false, emptyList(), e.message ?: "Unknown error")
        }
    }

    /**
     * 停止扫描
     */
    fun stopScan() {
        if (!started) return

        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
            started = false
            Log.d(TAG, "Stopped DNS service discovery")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop DNS discovery", e)
        }
    }

    /**
     * 获取扫描到的端口列表
     */
    fun getDiscoveredPorts(): List<Int> {
        return ports.toList()
    }

    /**
     * 获取最佳端口
     */
    fun getBestPort(): Int? = bestPort

    /**
     * 清除端口列表
     */
    fun clearPorts() {
        ports.clear()
        bestPort = null
        Log.d(TAG, "Cleared port list")
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

    // 私有成员和方法
    private val ports: MutableList<Int> = mutableListOf()
    private var bestPort: Int? = null
    private var startTime: Long? = null

    private fun updateIfNewest(serviceInfo: NsdServiceInfo) {
        val port = serviceInfo.port
        val expirationTime = parseExpirationTime(serviceInfo.toString())
        val serviceName = serviceInfo.serviceName

        fun getHighestNumberedString(strings: List<String>): String {
            return strings.maxByOrNull {
                """\((\d+)\)""".toRegex().find(it)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            } ?: strings.first()
        }

        fun update() {
            bestPort = port
            bestExpirationTime = expirationTime
            bestServiceName = serviceName
            Log.d(TAG, "Updated best ADB port: $bestPort")
        }

        if (bestPort == null) {
            update()
            return
        }

        if (expirationTime != null) {
            if (bestExpirationTime == null) {
                update()
                return
            }

            if (expirationTime > bestExpirationTime!!) {
                update()
                return
            }
        }

        if (serviceName == getHighestNumberedString(listOf(bestServiceName ?: "", serviceName))) {
            update()
            return
        }
    }

    private fun parseExpirationTime(rawString: String): Long? {
        val regex = """expirationTime: (\S+)""".toRegex()
        val expirationTimeStr = regex.find(rawString)?.groupValues?.get(1)

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")

        return try {
            dateFormat.parse(expirationTimeStr ?: "")?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun handleResolvedService(serviceInfo: NsdServiceInfo) {
        Log.d(TAG, "Resolve successful: $serviceInfo")

        val ipAddress = getLocalIpAddress()
        val discoveredAddress = serviceInfo.host?.hostAddress

        if (ipAddress != null && discoveredAddress != null && discoveredAddress != ipAddress) {
            Log.d(TAG, "IP does not match device")
            return
        }

        if (serviceInfo.port == 0) {
            Log.d(TAG, "Port is zero, skipping...")
            return
        }

        ports.add(serviceInfo.port)
        updateIfNewest(serviceInfo)
    }

    private fun resolveService(service: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: $errorCode: $serviceInfo")

                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    resolveService(serviceInfo)
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                handleResolvedService(serviceInfo)
                pendingServices.removeAll { it.serviceName == serviceInfo.serviceName }

                if (pendingServices.isEmpty()) {
                    pendingResolves.set(false)
                }

                Log.d(TAG, "Service resolved, pending: ${pendingServices.size}")
            }
        }

        try {
            nsdManager.resolveService(service, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve service", e)
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "Service discovery started: $regType")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d(TAG, "Service found: ${service.serviceName}, port: ${service.port}")

            pendingServices.add(service)
            pendingResolves.set(true)

            resolveService(service)
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.e(TAG, "Service lost: ${service.serviceName}")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(TAG, "Discovery stopped: $serviceType")
            started = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery failed: Error code: $errorCode")
            nsdManager.stopServiceDiscovery(this)
            started = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Stop discovery failed: Error code: $errorCode")
            nsdManager.stopServiceDiscovery(this)
        }
    }
}

/**
 * 扫描结果
 */
data class ScanResult(
    val success: Boolean,
    val ports: List<Int>,
    val message: String
)
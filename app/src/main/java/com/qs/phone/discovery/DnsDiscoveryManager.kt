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
        private const val CONNECT_SERVICE_TYPE = "_adb-tls-connect._tcp"      // 连接服务
        private const val PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp"      // 配对服务
        private val pendingResolves = AtomicBoolean(false)
    }

    private var started = false
    private var bestExpirationTime: Long? = null
    private var bestServiceName: String? = null

    private var pendingServices: MutableList<NsdServiceInfo> =
        Collections.synchronizedList(ArrayList())

    // 新增：配对服务列表
    private val pairingServices: MutableList<NsdServiceInfo> =
        Collections.synchronizedList(ArrayList())

    /**
     * 扫描 ADB 连接端口
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
            // 只监听连接服务类型
            nsdManager.discoverServices(
                CONNECT_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
            Log.d(TAG, "Started DNS service discovery for connect services")
            return ScanResult(true, emptyList(), "Scan started for connect services")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DNS discovery", e)
            started = false
            return ScanResult(false, emptyList(), e.message ?: "Unknown error")
        }
    }

    /**
     * 扫描 ADB 配对端口
     */
    fun scanPairingPorts(): ScanResult {
        clearPorts()
        if (started) {
            Log.w(TAG, "DNS scan already started")
            return ScanResult(false, emptyList(), "Scan already in progress")
        }

        started = true
        startTime = System.currentTimeMillis()

        try {
            // 监听配对服务类型，并解析服务（如同scanAdbPorts）
            nsdManager.discoverServices(
                PAIRING_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
            Log.d(TAG, "Started DNS service discovery for pairing services")
            return ScanResult(true, emptyList(), "Scan started for pairing services")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start pairing discovery", e)
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
            // 停止服务发现监听器
            nsdManager.stopServiceDiscovery(discoveryListener)
            started = false
            Log.d(TAG, "Stopped DNS service discovery")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop DNS discovery", e)
        }
    }

    /**
     * 停止配对服务扫描
     */
    fun stopPairingScan() {
        if (!started) return

        try {
            // 停止配对服务发现监听器
            nsdManager.stopServiceDiscovery(pairingDiscoveryListener)
            started = false
            Log.d(TAG, "Stopped pairing DNS service discovery")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop pairing DNS discovery", e)
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
        pairingServices.clear()
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
            Log.d(TAG, "Connect service discovery started: $regType")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d(TAG, "Connect service found: ${service.serviceName}, type: ${service.serviceType}, port: ${service.port}")

            // 只处理连接服务类型
            pendingServices.add(service)
            pendingResolves.set(true)
            resolveService(service)
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.e(TAG, "Connect service lost: ${service.serviceName}")
            pendingServices.removeAll { it.serviceName == service.serviceName }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(TAG, "Connect discovery stopped: $serviceType")
            started = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Connect discovery failed for $serviceType: Error code: $errorCode")
            nsdManager.stopServiceDiscovery(this)
            started = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Stop connect discovery failed for $serviceType: Error code: $errorCode")
            nsdManager.stopServiceDiscovery(this)
        }
    }

    /**
     * 配对服务发现监听器
     */
    private val pairingDiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "Pairing service discovery started: $regType")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d(TAG, "Pairing service found: ${service.serviceName}, type: ${service.serviceType}, port: ${service.port}")

            // 配对服务直接添加到列表
            if (service.port > 0) {
                pairingServices.add(service)
                Log.i(TAG, "Added pairing service: ${service.serviceName} at port ${service.port}")
            } else {
                Log.w(TAG, "Ignoring pairing service with invalid port: ${service.port}")
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.e(TAG, "Pairing service lost: ${service.serviceName}")
            pairingServices.removeAll { it.serviceName == service.serviceName }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(TAG, "Pairing discovery stopped: $serviceType")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Pairing discovery failed for $serviceType: Error code: $errorCode")
            nsdManager.stopServiceDiscovery(this)
            started = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Stop pairing discovery failed for $serviceType: Error code: $errorCode")
            nsdManager.stopServiceDiscovery(this)
        }
    }

    /**
     * 获取发现的所有端口（含配对和连接）
     */
    fun getAllDiscoveredPorts(): Map<String, List<Int>> {
        return mapOf(
            "pairing" to pairingServices.mapNotNull { it.port }.filter { it > 0 },
            "connect" to ports.toList(),
            "all" to (ports + pairingServices.mapNotNull { it.port })
                .filter { it > 0 }
        )
    }

    /**
     * 获取配对端口列表
     */
    fun getPairingPorts(): List<Int> {
        return pairingServices.mapNotNull { it.port }.filter { it > 0 }
    }

    /**
     * 获取连接端口列表
     */
    fun getConnectPorts(): List<Int> {
        return ports.toList()
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
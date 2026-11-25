package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.data.local.ConnectionLogEntity
import com.example.data.local.HopChainEntity
import com.example.data.local.HopShieldDao
import com.example.data.local.RelayNodeEntity
import com.example.data.local.SplitTunnelMode
import com.example.data.local.TunnelPreferencesRepository
import com.example.data.local.TunnelSettings
import com.example.data.model.EgressDiagnostics
import com.example.data.model.HopChainResolved
import com.example.data.model.InstalledAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class VpnRepository(
    private val context: Context,
    private val dao: HopShieldDao,
    private val prefsRepo: TunnelPreferencesRepository
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    val nodesFlow: Flow<List<RelayNodeEntity>> = dao.observeNodes()
    val logsFlow: Flow<List<ConnectionLogEntity>> = dao.observeLogs()
    val settingsFlow: Flow<TunnelSettings> = prefsRepo.settingsFlow

    val resolvedChainsFlow: Flow<List<HopChainResolved>> = combine(
        dao.observeChains(),
        dao.observeNodes()
    ) { chains, nodes ->
        val nodeMap = nodes.associateBy { it.id }
        chains.mapNotNull { chain ->
            val entry = nodeMap[chain.entryNodeId] ?: return@mapNotNull null
            val exit = nodeMap[chain.exitNodeId] ?: return@mapNotNull null
            val middle = chain.middleNodeId?.let { nodeMap[it] }
            HopChainResolved(
                chain = chain,
                entryNode = entry,
                middleNode = middle,
                exitNode = exit
            )
        }
    }

    suspend fun addOrUpdateNode(node: RelayNodeEntity): Long = dao.insertNode(node)

    suspend fun deleteNode(id: Long) = dao.deleteNodeById(id)

    suspend fun createChain(
        name: String,
        entryId: Long,
        middleId: Long?,
        exitId: Long,
        cipherSuite: String
    ): Long {
        val chainId = dao.insertChain(
            HopChainEntity(
                name = name,
                entryNodeId = entryId,
                middleNodeId = middleId,
                exitNodeId = exitId,
                cipherSuite = cipherSuite
            )
        )
        prefsRepo.setActiveChainId(chainId)
        return chainId
    }

    suspend fun deleteChain(id: Long) = dao.deleteChainById(id)

    suspend fun clearLogs() = dao.clearLogs()

    suspend fun setActiveChain(chainId: Long) = prefsRepo.setActiveChainId(chainId)

    suspend fun setDnsServers(primary: String, secondary: String) =
        prefsRepo.setDnsServers(primary, secondary)

    suspend fun setMtu(mtu: Int) = prefsRepo.setMtu(mtu)

    suspend fun setSplitTunnelMode(mode: SplitTunnelMode) = prefsRepo.setSplitTunnelMode(mode)

    suspend fun toggleSplitApp(packageName: String) = prefsRepo.togglePackageSelection(packageName)

    suspend fun setBlockLanTraffic(enabled: Boolean) = prefsRepo.setBlockLanTraffic(enabled)

    suspend fun setIpv6LeakProtection(enabled: Boolean) = prefsRepo.setIpv6LeakProtection(enabled)

    suspend fun setStealthDarkTheme(enabled: Boolean) = prefsRepo.setStealthDarkTheme(enabled)

    /**
     * Performs a real TCP socket reachability and RTT latency probe against a configured relay node.
     */
    suspend fun probeNodeLatency(node: RelayNodeEntity): RelayNodeEntity = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var latency: Int? = null
        var reachable = false
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(node.host, node.port), 3000)
                latency = (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
                reachable = true
            }
        } catch (_: Exception) {
            reachable = false
        }
        val updated = node.copy(
            lastLatencyMs = latency,
            lastCheckedAt = System.currentTimeMillis(),
            isReachable = reachable
        )
        dao.updateNode(updated)
        updated
    }

    /**
     * Queries live public egress IP and edge datacenter trace via Cloudflare trace endpoint.
     */
    suspend fun checkPublicEgress(): EgressDiagnostics = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("https://1.1.1.1/cdn-cgi/trace")
                .header("User-Agent", "HopShield-Android/1.0")
                .build()
            httpClient.newCall(request).execute().use { response ->
                val rtt = System.currentTimeMillis() - start
                if (!response.isSuccessful) {
                    return@withContext EgressDiagnostics(
                        publicIp = "Unavailable",
                        colo = "—",
                        countryCode = "—",
                        protocol = "HTTP ${response.code}",
                        roundTripMs = rtt,
                        error = "HTTP ${response.code}"
                    )
                }
                val body = response.body?.string().orEmpty()
                val map = body.lineSequence()
                    .mapNotNull { line ->
                        val idx = line.indexOf('=')
                        if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
                    }
                    .toMap()
                EgressDiagnostics(
                    publicIp = map["ip"] ?: "Unknown",
                    colo = map["colo"] ?: "—",
                    countryCode = map["loc"] ?: "—",
                    protocol = map["http"] ?: "HTTPS",
                    roundTripMs = rtt
                )
            }
        } catch (e: Exception) {
            EgressDiagnostics(
                publicIp = "Offline",
                colo = "—",
                countryCode = "—",
                protocol = "—",
                roundTripMs = System.currentTimeMillis() - start,
                error = e.message ?: "Network error"
            )
        }
    }

    /**
     * Loads real launcher-capable applications installed on the device for per-app split tunneling.
     */
    suspend fun loadInstalledLauncherApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        resolved
            .mapNotNull { info ->
                val appInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
                val pkg = appInfo.packageName
                if (pkg == context.packageName) return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                InstalledAppInfo(
                    packageName = pkg,
                    appName = label,
                    isSystemApp = isSystem
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy<InstalledAppInfo> { it.isSystemApp }.thenBy { it.appName.lowercase() })
    }
}

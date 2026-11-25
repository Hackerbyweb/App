package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.ConnectionLogEntity
import com.example.data.local.HopShieldDatabase
import com.example.data.local.RelayNodeEntity
import com.example.data.local.RelayProtocol
import com.example.data.local.SplitTunnelMode
import com.example.data.local.TunnelPreferencesRepository
import com.example.data.local.TunnelSettings
import com.example.data.model.HopChainResolved
import com.example.data.model.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class MultiHopVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunnelInterface: ParcelFileDescriptor? = null
    private var packetLoopJob: Job? = null
    private var tickerJob: Job? = null
    private var activeLogId: Long = -1L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_DISCONNECT) {
            stopTunnel(statusLabel = "Disconnected by user", details = "Clean session termination")
            return START_NOT_STICKY
        }

        val chainId = intent?.getLongExtra(EXTRA_CHAIN_ID, -1L) ?: -1L
        val localInspectionFallback = intent?.getBooleanExtra(EXTRA_LOCAL_INSPECTION, false) ?: false

        // Promote to foreground immediately as required by Android 8+ / 14+
        val initialNotification = buildNotification(
            title = getString(R.string.vpn_notification_title),
            text = "Establishing multi-hop circuit..."
        )
        startForegroundCompat(initialNotification)

        serviceScope.launch {
            startTunnel(chainId, localInspectionFallback)
        }

        return START_STICKY
    }

    private suspend fun startTunnel(requestedChainId: Long, localInspectionFallback: Boolean) {
        stopTunnelResourcesOnly()

        val db = HopShieldDatabase.getInstance(applicationContext)
        val dao = db.hopShieldDao()
        val prefs = TunnelPreferencesRepository(applicationContext).getSnapshot()

        val resolvedChain = resolveChain(
            requestedChainId = if (requestedChainId > 0) requestedChainId else prefs.activeChainId,
            dao = dao
        )

        val hops: List<RelayNodeEntity> = resolvedChain?.hops.orEmpty()
        val isLocalMode = localInspectionFallback ||
            resolvedChain?.chain?.isLocalTunInspection == true ||
            hops.isEmpty()

        val chainTitle = resolvedChain?.chain?.name ?: "Local TUN Inspector"
        val hopSummary = resolvedChain?.summaryLabel ?: "Device TUN (Local Loop)"

        var relayVerified = false
        var statusNote = ""

        if (!isLocalMode && hops.isNotEmpty()) {
            val allTcpRelay = hops.all {
                it.protocolEnum() == RelayProtocol.SOCKS5 || it.protocolEnum() == RelayProtocol.HTTP_CONNECT
            }
            if (allTcpRelay) {
                VpnRuntimeBus.updateState(
                    TunnelState.Handshaking(
                        stage = "Dialing Entry ${hops.first().name}...",
                        currentHopIndex = 0,
                        totalHops = hops.size
                    ),
                    activeChainId = resolvedChain?.chain?.id ?: -1L
                )
                val probe = MultiHopRelayConnector.verifyChainConnectivity(
                    vpnService = this,
                    hops = hops,
                    probeHost = prefs.primaryDns,
                    probePort = 53
                ) { idx, hop ->
                    VpnRuntimeBus.updateState(
                        TunnelState.Handshaking(
                            stage = "Verifying Hop ${idx + 1}/${hops.size}: ${hop.name}",
                            currentHopIndex = idx,
                            totalHops = hops.size
                        ),
                        activeChainId = resolvedChain?.chain?.id ?: -1L
                    )
                }

                if (probe.isSuccess) {
                    relayVerified = true
                    statusNote = "Verified ${hops.size}-hop circuit (${probe.getOrNull()} ms RTT)"
                } else {
                    val errMsg = probe.exceptionOrNull()?.message ?: "Relay handshake failed"
                    dao.insertLog(
                        ConnectionLogEntity(
                            chainName = chainTitle,
                            hopSummary = hopSummary,
                            startedAt = System.currentTimeMillis(),
                            endedAt = System.currentTimeMillis(),
                            status = "FAILED",
                            details = errMsg
                        )
                    )
                    VpnRuntimeBus.updateState(
                        TunnelState.Error("Multi-hop handshake failed: $errMsg"),
                        activeChainId = resolvedChain?.chain?.id ?: -1L
                    )
                    stopForegroundCompat()
                    stopSelf()
                    return
                }
            } else {
                // WireGuard / mixed protocol route: establish TUN interface with explicit status note
                relayVerified = false
                statusNote = "TUN interface active (WireGuard kernel tunnel requires root/wg-quick; inspecting local packets)"
            }
        } else {
            statusNote = "Local TUN capture active — DNS & IPv4 telemetry live"
        }

        // Build real Android VPN TUN interface
        val tunFd = runCatching { buildTunInterface(prefs, chainTitle) }.getOrNull()
        if (tunFd == null) {
            VpnRuntimeBus.updateState(TunnelState.Error("Failed to establish Android VpnService TUN interface"))
            stopForegroundCompat()
            stopSelf()
            return
        }
        tunnelInterface = tunFd

        activeLogId = dao.insertLog(
            ConnectionLogEntity(
                chainName = chainTitle,
                hopSummary = hopSummary,
                startedAt = System.currentTimeMillis(),
                status = if (relayVerified) "CONNECTED" else "TUN_ACTIVE",
                details = statusNote
            )
        )

        VpnRuntimeBus.updateState(
            TunnelState.Connected(
                chainName = chainTitle,
                hopSummary = hopSummary,
                hopsCount = hops.size.coerceAtLeast(1),
                localTunIp = TUN_IPV4,
                isRelayVerified = relayVerified,
                statusNote = statusNote
            ),
            activeChainId = resolvedChain?.chain?.id ?: -1L
        )

        updateNotification(
            title = "HopShield • $chainTitle",
            text = "$hopSummary • $statusNote"
        )

        startThroughputTicker(chainTitle, hopSummary)
        startPacketLoop(
            tunFd = tunFd,
            hops = if (relayVerified) hops else emptyList(),
            primaryDns = prefs.primaryDns
        )
    }

    private suspend fun resolveChain(
        requestedChainId: Long,
        dao: com.example.data.local.HopShieldDao
    ): HopChainResolved? {
        val chain = if (requestedChainId > 0) dao.getChainById(requestedChainId) else dao.getAllChains().firstOrNull()
        chain ?: return null
        val entry = dao.getNodeById(chain.entryNodeId) ?: return null
        val exit = dao.getNodeById(chain.exitNodeId) ?: return null
        val middle = chain.middleNodeId?.let { dao.getNodeById(it) }
        return HopChainResolved(
            chain = chain,
            entryNode = entry,
            middleNode = middle,
            exitNode = exit
        )
    }

    private fun buildTunInterface(prefs: TunnelSettings, sessionName: String): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("HopShield: $sessionName")
            .setMtu(prefs.mtu)
            .addAddress(TUN_IPV4, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(prefs.primaryDns)

        if (prefs.secondaryDns.isNotBlank() && prefs.secondaryDns != prefs.primaryDns) {
            runCatching { builder.addDnsServer(prefs.secondaryDns) }
        }

        if (prefs.ipv6LeakProtection) {
            runCatching {
                builder.addAddress("fd00:88::2", 128)
                builder.addRoute("::", 0)
            }
        }

        when (prefs.splitTunnelMode) {
            SplitTunnelMode.ALL_APPS -> {
                runCatching { builder.addDisallowedApplication(packageName) }
            }
            SplitTunnelMode.BYPASS_SELECTED -> {
                runCatching { builder.addDisallowedApplication(packageName) }
                prefs.selectedPackages.forEach { pkg ->
                    if (isAppInstalled(pkg)) {
                        runCatching { builder.addDisallowedApplication(pkg) }
                    }
                }
            }
            SplitTunnelMode.ALLOW_ONLY -> {
                val allowed = prefs.selectedPackages.filter { it != packageName && isAppInstalled(it) }
                if (allowed.isNotEmpty()) {
                    allowed.forEach { pkg ->
                        runCatching { builder.addAllowedApplication(pkg) }
                    }
                } else {
                    // Fallback: cannot have empty allowed list if intending tunnel
                    runCatching { builder.addDisallowedApplication(packageName) }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        return builder.establish()
    }

    private fun isAppInstalled(pkg: String): Boolean {
        return try {
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun startPacketLoop(
        tunFd: ParcelFileDescriptor,
        hops: List<RelayNodeEntity>,
        primaryDns: String
    ) {
        packetLoopJob = serviceScope.launch {
            val input = FileInputStream(tunFd.fileDescriptor)
            val output = FileOutputStream(tunFd.fileDescriptor)
            val buffer = ByteBuffer.allocate(32767)

            while (isActive) {
                val length = runCatching { input.read(buffer.array()) }.getOrElse { -1 }
                if (length < 0) break
                if (length == 0) {
                    delay(10)
                    continue
                }

                val rawBytes = buffer.array()
                val parsed = PacketParser.parseIpv4(rawBytes, length)
                if (parsed != null) {
                    val srcLabel = if (parsed.srcPort > 0) "${parsed.srcIp}:${parsed.srcPort}" else parsed.srcIp
                    val dstLabel = if (parsed.dstPort > 0) "${parsed.dstIp}:${parsed.dstPort}" else parsed.dstIp

                    var rxInjected = 0
                    var relayedDns = false
                    var action = if (hops.isNotEmpty()) "RELAY" else "INSPECT"

                    // If this is a UDP DNS query (port 53) and we have a verified multi-hop relay chain,
                    // forward the real DNS query over TCP through the multi-hop circuit and inject response!
                    if (parsed.protocolNumber == 17 && parsed.dstPort == 53 && parsed.payloadLength > 12 && hops.isNotEmpty()) {
                        val dnsQueryPayload = rawBytes.copyOfRange(
                            parsed.payloadOffset,
                            parsed.payloadOffset + parsed.payloadLength
                        )
                        val dnsResponse = MultiHopRelayConnector.forwardDnsOverTcp(
                            vpnService = this@MultiHopVpnService,
                            hops = hops,
                            dnsServer = primaryDns,
                            dnsUdpPayload = dnsQueryPayload
                        )
                        if (dnsResponse != null && dnsResponse.isNotEmpty()) {
                            val replyPacket = PacketParser.buildIpv4UdpResponse(
                                srcIpBytes = parsed.dstIpBytes,
                                dstIpBytes = parsed.srcIpBytes,
                                srcPort = parsed.dstPort,
                                dstPort = parsed.srcPort,
                                udpPayload = dnsResponse
                            )
                            synchronized(output) {
                                runCatching { output.write(replyPacket) }
                            }
                            rxInjected = replyPacket.size
                            relayedDns = true
                            action = "DNS-HOP"
                        }
                    }

                    VpnRuntimeBus.recordPacket(
                        protocol = parsed.protocolName,
                        sourceEndpoint = srcLabel,
                        destinationEndpoint = dstLabel,
                        txDelta = length,
                        rxDelta = rxInjected,
                        dnsQuery = parsed.dnsQueryName,
                        actionLabel = action,
                        relayedDns = relayedDns
                    )
                }
                buffer.clear()
            }
        }
    }

    private fun startThroughputTicker(chainTitle: String, hopSummary: String) {
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                VpnRuntimeBus.sampleThroughputTick()
                val snap = VpnRuntimeBus.snapshot.value
                val rxFmt = formatCompactBytes(snap.rxBytes)
                val txFmt = formatCompactBytes(snap.txBytes)
                updateNotification(
                    title = "HopShield • $chainTitle",
                    text = "$hopSummary • ↓$rxFmt  ↑$txFmt"
                )
            }
        }
    }

    override fun onRevoke() {
        stopTunnel(statusLabel = "REVOKED", details = "System revoked VPN permission")
        super.onRevoke()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopTunnel(statusLabel: String, details: String) {
        val snap = VpnRuntimeBus.snapshot.value
        val logId = activeLogId
        activeLogId = -1L
        if (logId > 0) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    HopShieldDatabase.getInstance(applicationContext).hopShieldDao().finishLog(
                        id = logId,
                        endedAt = System.currentTimeMillis(),
                        rxBytes = snap.rxBytes,
                        txBytes = snap.txBytes,
                        status = statusLabel,
                        details = details
                    )
                }
            }
        }
        stopTunnelResourcesOnly()
        VpnRuntimeBus.updateState(TunnelState.Disconnected)
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopTunnelResourcesOnly() {
        tickerJob?.cancel()
        tickerJob = null
        packetLoopJob?.cancel()
        packetLoopJob = null
        runCatching { tunnelInterface?.close() }
        tunnelInterface = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_channel_description)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MultiHopVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.action_disconnect), disconnectIntent)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun formatCompactBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
        }
    }

    companion object {
        private const val CHANNEL_ID = "hopshield_vpn_channel"
        private const val NOTIFICATION_ID = 1042
        private const val TUN_IPV4 = "10.88.0.2"

        const val ACTION_CONNECT = "com.example.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpn.ACTION_DISCONNECT"
        const val EXTRA_CHAIN_ID = "extra_chain_id"
        const val EXTRA_LOCAL_INSPECTION = "extra_local_inspection"

        fun startConnect(context: Context, chainId: Long, localInspection: Boolean = false) {
            val intent = Intent(context, MultiHopVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_CHAIN_ID, chainId)
                putExtra(EXTRA_LOCAL_INSPECTION, localInspection)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startDisconnect(context: Context) {
            val intent = Intent(context, MultiHopVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }
}

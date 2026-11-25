package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.ConnectionLogEntity
import com.example.data.local.HopShieldDatabase
import com.example.data.local.RelayNodeEntity
import com.example.data.local.RelayProtocol
import com.example.data.local.SplitTunnelMode
import com.example.data.local.TunnelPreferencesRepository
import com.example.data.local.TunnelSettings
import com.example.data.model.ConfigParser
import com.example.data.model.EgressDiagnostics
import com.example.data.model.HopChainResolved
import com.example.data.model.InstalledAppInfo
import com.example.data.model.TunnelState
import com.example.data.model.VpnTelemetrySnapshot
import com.example.data.repository.VpnRepository
import com.example.vpn.MultiHopVpnService
import com.example.vpn.VpnRuntimeBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class VpnUiState(
    val telemetry: VpnTelemetrySnapshot = VpnTelemetrySnapshot(),
    val nodes: List<RelayNodeEntity> = emptyList(),
    val chains: List<HopChainResolved> = emptyList(),
    val logs: List<ConnectionLogEntity> = emptyList(),
    val settings: TunnelSettings = TunnelSettings(),
    val activeChain: HopChainResolved? = null,
    val egressDiagnostics: EgressDiagnostics? = null,
    val isCheckingEgress: Boolean = false,
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val isLoadingApps: Boolean = false,
    val probingNodeIds: Set<Long> = emptySet(),
    val elapsedSeconds: Long = 0L,
    val feedbackMessage: String? = null
)

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VpnRepository(
        context = application,
        dao = HopShieldDatabase.getInstance(application).hopShieldDao(),
        prefsRepo = TunnelPreferencesRepository(application)
    )

    private val _egress = MutableStateFlow<EgressDiagnostics?>(null)
    private val _isCheckingEgress = MutableStateFlow(false)
    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val _isLoadingApps = MutableStateFlow(false)
    private val _probingIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _elapsedSeconds = MutableStateFlow(0L)
    private val _feedback = MutableStateFlow<String?>(null)

    private val coreDataFlow = combine(
        VpnRuntimeBus.snapshot,
        repository.nodesFlow,
        repository.resolvedChainsFlow,
        repository.logsFlow,
        repository.settingsFlow
    ) { telemetry, nodes, chains, logs, settings ->
        CoreBundle(telemetry, nodes, chains, logs, settings)
    }

    private val auxDataFlow = combine(
        _egress,
        _isCheckingEgress,
        _installedApps,
        _isLoadingApps,
        _probingIds
    ) { egress, checkingEgress, apps, loadingApps, probingIds ->
        AuxBundle(egress, checkingEgress, apps, loadingApps, probingIds)
    }

    val uiState: StateFlow<VpnUiState> = combine(
        coreDataFlow,
        auxDataFlow,
        _elapsedSeconds,
        _feedback
    ) { core, aux, elapsed, feedback ->
        val selectedChain = core.chains.firstOrNull { it.chain.id == core.settings.activeChainId }
            ?: core.chains.firstOrNull()
        VpnUiState(
            telemetry = core.telemetry,
            nodes = core.nodes,
            chains = core.chains,
            logs = core.logs,
            settings = core.settings,
            activeChain = selectedChain,
            egressDiagnostics = aux.egress,
            isCheckingEgress = aux.checkingEgress,
            installedApps = aux.apps,
            isLoadingApps = aux.loadingApps,
            probingNodeIds = aux.probingIds,
            elapsedSeconds = elapsed,
            feedbackMessage = feedback
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VpnUiState()
    )

    init {
        viewModelScope.launch {
            while (isActive) {
                val since = VpnRuntimeBus.snapshot.value.connectedSinceMillis
                _elapsedSeconds.value = if (since != null) {
                    ((System.currentTimeMillis() - since) / 1000L).coerceAtLeast(0L)
                } else {
                    0L
                }
                delay(1000L)
            }
        }
        refreshEgressDiagnostics()
        loadInstalledApps()
    }

    fun prepareVpnIntent(): Intent? = VpnService.prepare(getApplication())

    fun startTunnel(localInspectionFallback: Boolean = false) {
        val state = uiState.value
        val chainId = state.activeChain?.chain?.id ?: -1L
        val useLocal = localInspectionFallback || chainId <= 0L
        MultiHopVpnService.startConnect(
            context = getApplication(),
            chainId = chainId,
            localInspection = useLocal
        )
    }

    fun stopTunnel() {
        MultiHopVpnService.startDisconnect(getApplication())
    }

    fun selectChain(chainId: Long) {
        viewModelScope.launch {
            repository.setActiveChain(chainId)
            val currentState = VpnRuntimeBus.snapshot.value.state
            if (currentState is TunnelState.Connected) {
                MultiHopVpnService.startConnect(getApplication(), chainId, localInspection = false)
            }
        }
    }

    fun addRelayNode(
        name: String,
        regionCode: String,
        city: String,
        host: String,
        port: Int,
        protocol: RelayProtocol,
        username: String,
        password: String
    ) {
        viewModelScope.launch {
            val cleanHost = host.trim()
            if (cleanHost.isEmpty() || port !in 1..65535) {
                _feedback.value = "Enter a valid relay host and port (1-65535)"
                return@launch
            }
            val node = RelayNodeEntity(
                name = name.trim().ifBlank { "$cleanHost:$port" },
                regionCode = regionCode.trim().uppercase().take(4).ifBlank { "NET" },
                city = city.trim().ifBlank { cleanHost },
                host = cleanHost,
                port = port,
                protocol = protocol.name,
                username = username.trim(),
                password = password
            )
            val insertedId = repository.addOrUpdateNode(node)
            _feedback.value = "Relay node '${node.name}' saved"
            probeNode(node.copy(id = insertedId))
        }
    }

    fun importRelayConfig(rawInput: String) {
        viewModelScope.launch {
            ConfigParser.parseRelayConfig(rawInput)
                .onSuccess { parsed ->
                    val id = repository.addOrUpdateNode(parsed)
                    _feedback.value = "Imported '${parsed.name}' (${parsed.protocolEnum().displayName})"
                    probeNode(parsed.copy(id = id))
                }
                .onFailure { err ->
                    _feedback.value = err.message ?: "Could not parse relay config"
                }
        }
    }

    fun deleteNode(nodeId: Long) {
        viewModelScope.launch {
            repository.deleteNode(nodeId)
            _feedback.value = "Relay node removed"
        }
    }

    fun probeNode(node: RelayNodeEntity) {
        viewModelScope.launch {
            _probingIds.update { it + node.id }
            val result = repository.probeNodeLatency(node)
            _probingIds.update { it - node.id }
            _feedback.value = if (result.isReachable) {
                "${result.name}: reachable in ${result.lastLatencyMs} ms"
            } else {
                "${result.name}: unreachable (TCP timeout)"
            }
        }
    }

    fun probeAllNodes() {
        viewModelScope.launch {
            val nodes = uiState.value.nodes
            nodes.forEach { node ->
                launch {
                    _probingIds.update { it + node.id }
                    repository.probeNodeLatency(node)
                    _probingIds.update { it - node.id }
                }
            }
        }
    }

    fun createHopChain(
        name: String,
        entryId: Long,
        middleId: Long?,
        exitId: Long,
        cipherSuite: String
    ) {
        viewModelScope.launch {
            if (entryId <= 0 || exitId <= 0) {
                _feedback.value = "Select both an Entry and Exit node"
                return@launch
            }
            if (entryId == exitId || (middleId != null && (middleId == entryId || middleId == exitId))) {
                _feedback.value = "Each hop in a multi-hop chain must use a distinct relay node"
                return@launch
            }
            val cleanName = name.trim().ifBlank {
                if (middleId != null) "3-Hop Stealth Route" else "2-Hop Double Route"
            }
            repository.createChain(
                name = cleanName,
                entryId = entryId,
                middleId = middleId,
                exitId = exitId,
                cipherSuite = cipherSuite
            )
            _feedback.value = "Multi-hop chain '$cleanName' activated"
        }
    }

    fun deleteChain(chainId: Long) {
        viewModelScope.launch {
            repository.deleteChain(chainId)
            _feedback.value = "Chain deleted"
        }
    }

    fun refreshEgressDiagnostics() {
        viewModelScope.launch {
            _isCheckingEgress.value = true
            _egress.value = repository.checkPublicEgress()
            _isCheckingEgress.value = false
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            _installedApps.value = repository.loadInstalledLauncherApps()
            _isLoadingApps.value = false
        }
    }

    fun toggleSplitApp(packageName: String) {
        viewModelScope.launch { repository.toggleSplitApp(packageName) }
    }

    fun setSplitTunnelMode(mode: SplitTunnelMode) {
        viewModelScope.launch { repository.setSplitTunnelMode(mode) }
    }

    fun setDnsServers(primary: String, secondary: String) {
        viewModelScope.launch {
            repository.setDnsServers(primary.trim(), secondary.trim())
            _feedback.value = "DNS updated to $primary"
        }
    }

    fun setMtu(mtu: Int) {
        viewModelScope.launch { repository.setMtu(mtu) }
    }

    fun setBlockLanTraffic(enabled: Boolean) {
        viewModelScope.launch { repository.setBlockLanTraffic(enabled) }
    }

    fun setIpv6LeakProtection(enabled: Boolean) {
        viewModelScope.launch { repository.setIpv6LeakProtection(enabled) }
    }

    fun setStealthDarkTheme(enabled: Boolean) {
        viewModelScope.launch { repository.setStealthDarkTheme(enabled) }
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearLogs()
            _feedback.value = "Session logs cleared"
        }
    }

    fun clearFeedback() {
        _feedback.value = null
    }

    private data class CoreBundle(
        val telemetry: VpnTelemetrySnapshot,
        val nodes: List<RelayNodeEntity>,
        val chains: List<HopChainResolved>,
        val logs: List<ConnectionLogEntity>,
        val settings: TunnelSettings
    )

    private data class AuxBundle(
        val egress: EgressDiagnostics?,
        val checkingEgress: Boolean,
        val apps: List<InstalledAppInfo>,
        val loadingApps: Boolean,
        val probingIds: Set<Long>
    )
}

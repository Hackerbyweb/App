package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.RelayNodeEntity
import com.example.data.local.RelayProtocol
import com.example.ui.VpnUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChainsScreen(
    uiState: VpnUiState,
    onSelectChain: (Long) -> Unit,
    onCreateChain: (name: String, entryId: Long, middleId: Long?, exitId: Long, cipherSuite: String) -> Unit,
    onDeleteChain: (Long) -> Unit,
    onAddNode: (name: String, region: String, city: String, host: String, port: Int, protocol: RelayProtocol, user: String, pass: String) -> Unit,
    onImportConfig: (String) -> Unit,
    onDeleteNode: (Long) -> Unit,
    onProbeNode: (RelayNodeEntity) -> Unit,
    onProbeAllNodes: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0 = Chains, 1 = Relay Nodes
    var showAddNodeDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val content = runCatching {
                context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
            }.getOrNull()
            if (!content.isNullOrBlank()) {
                onImportConfig(content)
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 680.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Multi-Hop Routing",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Chain 2 or 3 relay nodes in sequence",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { showImportDialog = true },
                        modifier = Modifier.testTag("import_config_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FileUpload,
                            contentDescription = "Import Config",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import")
                    }
                    Button(
                        onClick = { showAddNodeDialog = true },
                        modifier = Modifier.testTag("add_node_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Add Relay Node",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Node")
                    }
                }
            }
        }

        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { Icon(Icons.Rounded.Route, contentDescription = null, modifier = Modifier.size(18.dp)) }
                ) {
                    Text("Hop Chains (${uiState.chains.size})")
                }
                SegmentedButton(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { Icon(Icons.Rounded.Router, contentDescription = null, modifier = Modifier.size(18.dp)) }
                ) {
                    Text("Relay Nodes (${uiState.nodes.size})")
                }
            }
        }

        if (selectedTab == 0) {
            item {
                ChainBuilderCard(
                    nodes = uiState.nodes,
                    onCreateChain = onCreateChain,
                    onOpenAddNode = { showAddNodeDialog = true }
                )
            }

            if (uiState.chains.isEmpty()) {
                item {
                    EmptyTopologyStateCard(
                        title = "No Multi-Hop Chains Configured",
                        subtitle = if (uiState.nodes.size < 2) {
                            "Add at least 2 distinct relay nodes (Entry + Exit) to build your first multi-hop VPN chain."
                        } else {
                            "Use the Multi-Hop Chain Builder above to link your Entry and Exit relay nodes."
                        },
                        actionLabel = if (uiState.nodes.size < 2) "Add Relay Node" else null,
                        onAction = { showAddNodeDialog = true }
                    )
                }
            } else {
                items(uiState.chains, key = { it.chain.id }) { resolved ->
                    val isSelected = uiState.activeChain?.chain?.id == resolved.chain.id
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            }
                        ),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Hub,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = resolved.chain.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = resolved.summaryLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                                ) {
                                    Text(
                                        text = "${resolved.hopCount}-HOP",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            resolved.hops.forEachIndexed { idx, hop ->
                                val role = when {
                                    idx == 0 -> "1. ENTRY"
                                    idx == resolved.hops.lastIndex -> "${idx + 1}. EXIT"
                                    else -> "${idx + 1}. MID"
                                }
                                Text(
                                    text = "$role  ${hop.name} (${hop.host}:${hop.port} • ${hop.protocolEnum().displayName})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = resolved.chain.cipherSuite,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconButton(onClick = { onDeleteChain(resolved.chain.id) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "Delete Chain",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    Button(
                                        onClick = { onSelectChain(resolved.chain.id) },
                                        enabled = !isSelected
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Rounded.CheckCircle,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Active")
                                        } else {
                                            Text("Activate")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Relay Nodes Tab
            if (uiState.nodes.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CONFIGURED RELAY ENDPOINTS",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = onProbeAllNodes) {
                            Icon(
                                imageVector = Icons.Rounded.Speed,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Probe All")
                        }
                    }
                }
            }

            if (uiState.nodes.isEmpty()) {
                item {
                    EmptyTopologyStateCard(
                        title = "No Relay Nodes Added",
                        subtitle = "Add your SOCKS5, HTTP Tunnel, or WireGuard .conf relay endpoints. Every node can be TCP-probed for real latency before chaining.",
                        actionLabel = "Add First Relay Node",
                        onAction = { showAddNodeDialog = true }
                    )
                }
            } else {
                items(uiState.nodes, key = { it.id }) { node ->
                    val isProbing = uiState.probingNodeIds.contains(node.id)
                    RelayNodeCard(
                        node = node,
                        isProbing = isProbing,
                        onProbe = { onProbeNode(node) },
                        onDelete = { onDeleteNode(node.id) }
                    )
                }
            }
        }
    }

    if (showAddNodeDialog) {
        AddRelayNodeDialog(
            onDismiss = { showAddNodeDialog = false },
            onConfirm = { name, region, city, host, port, proto, user, pass ->
                onAddNode(name, region, city, host, port, proto, user, pass)
                showAddNodeDialog = false
            }
        )
    }

    if (showImportDialog) {
        ImportConfigDialog(
            onDismiss = { showImportDialog = false },
            onPickFile = {
                showImportDialog = false
                filePickerLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
            },
            onImportText = { raw ->
                onImportConfig(raw)
                showImportDialog = false
            }
        )
    }
}

@Composable
private fun ChainBuilderCard(
    nodes: List<RelayNodeEntity>,
    onCreateChain: (name: String, entryId: Long, middleId: Long?, exitId: Long, cipherSuite: String) -> Unit,
    onOpenAddNode: () -> Unit
) {
    var chainName by rememberSaveable { mutableStateOf("") }
    var useThreeHops by rememberSaveable { mutableStateOf(false) }
    var selectedCipher by rememberSaveable { mutableStateOf("ChaCha20-Poly1305") }

    var entryNodeId by rememberSaveable(nodes) { mutableStateOf(nodes.firstOrNull()?.id ?: -1L) }
    var middleNodeId by rememberSaveable(nodes) { mutableStateOf(nodes.getOrNull(1)?.id ?: -1L) }
    var exitNodeId by rememberSaveable(nodes) { mutableStateOf(nodes.lastOrNull()?.id ?: -1L) }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CHAIN BUILDER",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (useThreeHops) "3-Hop Mode" else "2-Hop Mode",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = useThreeHops,
                        onCheckedChange = { useThreeHops = it }
                    )
                }
            }

            if (nodes.size < 2) {
                Text(
                    text = "Requires at least 2 relay nodes (${nodes.size}/2 configured).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onOpenAddNode, modifier = Modifier.fillMaxWidth()) {
                    Text("Add Relay Nodes")
                }
            } else {
                OutlinedTextField(
                    value = chainName,
                    onValueChange = { chainName = it },
                    label = { Text("Chain Name (optional)") },
                    placeholder = { Text(if (useThreeHops) "e.g., Triple Privacy Cascade" else "e.g., Double-Hop Shield") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                NodeSelectorDropdown(
                    label = "Hop 1 — Entry Node (Guard)",
                    nodes = nodes,
                    selectedId = entryNodeId,
                    onSelect = { entryNodeId = it }
                )

                if (useThreeHops && nodes.size >= 3) {
                    NodeSelectorDropdown(
                        label = "Hop 2 — Intermediate Relay",
                        nodes = nodes,
                        selectedId = middleNodeId,
                        onSelect = { middleNodeId = it }
                    )
                } else if (useThreeHops) {
                    Text(
                        text = "Add a 3rd relay node to unlock 3-Hop intermediate routing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                NodeSelectorDropdown(
                    label = if (useThreeHops && nodes.size >= 3) "Hop 3 — Exit Node" else "Hop 2 — Exit Node",
                    nodes = nodes,
                    selectedId = exitNodeId,
                    onSelect = { exitNodeId = it }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ChaCha20-Poly1305", "AES-256-GCM").forEach { cipher ->
                        FilterChip(
                            selected = selectedCipher == cipher,
                            onClick = { selectedCipher = cipher },
                            label = { Text(cipher, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Button(
                    onClick = {
                        val mid = if (useThreeHops && nodes.size >= 3) middleNodeId else null
                        onCreateChain(chainName, entryNodeId, mid, exitNodeId, selectedCipher)
                        chainName = ""
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("save_chain_button")
                ) {
                    Text("Save & Activate Multi-Hop Chain")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeSelectorDropdown(
    label: String,
    nodes: List<RelayNodeEntity>,
    selectedId: Long,
    onSelect: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = nodes.firstOrNull { it.id == selectedId } ?: nodes.firstOrNull()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected?.let { "${it.regionCode} • ${it.name} (${it.host}:${it.port})" } ?: "Select node",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            nodes.forEach { node ->
                DropdownMenuItem(
                    text = {
                        Text("${node.regionCode} • ${node.name} (${node.protocolEnum().displayName})")
                    },
                    onClick = {
                        onSelect(node.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun RelayNodeCard(
    node: RelayNodeEntity,
    isProbing: Boolean,
    onProbe: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = node.regionCode.take(3),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = node.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${node.host}:${node.port} • ${node.protocolEnum().displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val latencyText = when {
                        isProbing -> "Probing TCP RTT..."
                        node.lastCheckedAt == null -> "Not probed yet"
                        node.isReachable -> "Reachable • ${node.lastLatencyMs} ms RTT"
                        else -> "Unreachable (TCP timeout)"
                    }
                    Text(
                        text = latencyText,
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            isProbing -> MaterialTheme.colorScheme.primary
                            node.isReachable -> MaterialTheme.colorScheme.secondary
                            node.lastCheckedAt != null -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onProbe, enabled = !isProbing) {
                    if (isProbing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.NetworkCheck,
                            contentDescription = "Probe Node Latency",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Delete Node",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTopologyStateCard(
    title: String,
    subtitle: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_mesh_backdrop_1795646481787),
                contentDescription = "Multi-Hop Network Topology",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .alpha(0.85f)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionLabel != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun AddRelayNodeDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, region: String, city: String, host: String, port: Int, protocol: RelayProtocol, user: String, pass: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("US") }
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("1080") }
    var protocol by remember { mutableStateOf(RelayProtocol.SOCKS5) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Relay Node") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(RelayProtocol.SOCKS5, RelayProtocol.HTTP_CONNECT, RelayProtocol.WIREGUARD).forEach { p ->
                        FilterChip(
                            selected = protocol == p,
                            onClick = {
                                protocol = p
                                portText = p.defaultPort.toString()
                            },
                            label = { Text(p.displayName, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Label") },
                        placeholder = { Text("Zurich Guard") },
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = region,
                        onValueChange = { region = it.take(4) },
                        label = { Text("Code") },
                        placeholder = { Text("CH") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host / IP") },
                        placeholder = { Text("198.51.100.10") },
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (protocol != RelayProtocol.WIREGUARD) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password (optional)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = portText.toIntOrNull() ?: protocol.defaultPort
                    onConfirm(name, region, name, host, port, protocol, username, password)
                },
                enabled = host.isNotBlank()
            ) {
                Text("Save & Probe")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ImportConfigDialog(
    onDismiss: () -> Unit,
    onPickFile: () -> Unit,
    onImportText: (String) -> Unit
) {
    var rawText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Relay Configuration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Paste a WireGuard .conf snippet, socks5://user:pass@host:port#Label URI, http://host:port#Label, or host:port.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onPickFile,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pick .conf / .txt File (SAF)")
                }
                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    label = { Text("Config / URI") },
                    placeholder = { Text("socks5://user:pass@relay.example.com:1080#NL-Entry") },
                    minLines = 4,
                    maxLines = 7,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onImportText(rawText) },
                enabled = rawText.isNotBlank()
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

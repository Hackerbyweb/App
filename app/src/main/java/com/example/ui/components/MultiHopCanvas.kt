package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.HopChainResolved
import com.example.data.model.ThroughputSample
import com.example.data.model.TunnelState

data class TopologyNodeDisplay(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accent: Color,
    val isActive: Boolean
)

@Composable
fun MultiHopTopologyDiagram(
    activeChain: HopChainResolved?,
    tunnelState: TunnelState,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline
    val error = MaterialTheme.colorScheme.error

    val isConnected = tunnelState is TunnelState.Connected
    val isHandshaking = tunnelState is TunnelState.Handshaking
    val isError = tunnelState is TunnelState.Error

    val nodes: List<TopologyNodeDisplay> = buildList {
        add(
            TopologyNodeDisplay(
                title = "Device",
                subtitle = "TUN 10.88.0.2",
                icon = Icons.Rounded.Smartphone,
                accent = primary,
                isActive = isConnected || isHandshaking
            )
        )
        if (activeChain != null) {
            activeChain.hops.forEachIndexed { idx, hop ->
                val hopColor = when {
                    isError -> error
                    idx == 0 -> primary
                    idx == activeChain.hops.lastIndex -> secondary
                    else -> tertiary
                }
                val role = when {
                    idx == 0 -> "Entry"
                    idx == activeChain.hops.lastIndex -> "Exit"
                    else -> "Relay"
                }
                add(
                    TopologyNodeDisplay(
                        title = hop.regionCode.ifBlank { role },
                        subtitle = hop.lastLatencyMs?.let { "$role • ${it}ms" } ?: role,
                        icon = Icons.Rounded.Router,
                        accent = hopColor,
                        isActive = isConnected || (isHandshaking && (tunnelState as TunnelState.Handshaking).currentHopIndex >= idx)
                    )
                )
            }
        } else {
            add(
                TopologyNodeDisplay(
                    title = "Local TUN",
                    subtitle = "No Relay Set",
                    icon = Icons.Rounded.Dns,
                    accent = tertiary,
                    isActive = isConnected
                )
            )
        }
        add(
            TopologyNodeDisplay(
                title = "Egress",
                subtitle = if (isConnected) "Protected" else "Standby",
                icon = Icons.Rounded.Public,
                accent = secondary,
                isActive = isConnected
            )
        )
    }

    val infiniteTransition = rememberInfiniteTransition(label = "hop_pulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "packet_flow"
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(126.dp)
    ) {
        val nodeCount = nodes.size
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            if (nodeCount < 2) return@Canvas
            val segCount = nodeCount - 1
            val y = size.height * 0.35f
            val stepX = size.width / segCount

            for (i in 0 until segCount) {
                val startX = i * stepX
                val endX = (i + 1) * stepX
                val midX = (startX + endX) / 2f
                val arcLift = if (i % 2 == 0) -22f else 22f

                val path = Path().apply {
                    moveTo(startX, y)
                    quadraticTo(midX, y + arcLift, endX, y)
                }

                val segColor = when {
                    isError -> error.copy(alpha = 0.7f)
                    isConnected -> secondary
                    isHandshaking -> tertiary
                    else -> outline.copy(alpha = 0.65f)
                }

                drawPath(
                    path = path,
                    color = segColor.copy(alpha = if (isConnected) 0.45f else 0.35f),
                    style = Stroke(
                        width = if (isConnected) 4f else 2.5f,
                        cap = StrokeCap.Round,
                        pathEffect = if (isConnected) null else PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                    )
                )

                if (isConnected || isHandshaking) {
                    val t = ((pulseProgress * segCount) - i).coerceIn(0f, 1f)
                    val activeWindow = ((pulseProgress * segCount) >= i && (pulseProgress * segCount) <= i + 1.15f)
                    if (activeWindow) {
                        val inv = 1f - t
                        val px = inv * inv * startX + 2 * inv * t * midX + t * t * endX
                        val py = inv * inv * y + 2 * inv * t * (y + arcLift) + t * t * y
                        drawCircle(
                            color = primary.copy(alpha = 0.35f),
                            radius = 12f,
                            center = Offset(px, py)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 5f,
                            center = Offset(px, py)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            nodes.forEach { item ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (item.isActive) item.accent.copy(alpha = 0.22f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (item.isActive) 1.5.dp else 1.dp,
                            color = if (item.isActive) item.accent else outline.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                tint = if (item.isActive) item.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.isActive) item.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun ThroughputSparkline(
    samples: List<ThroughputSample>,
    modifier: Modifier = Modifier
) {
    val rxColor = MaterialTheme.colorScheme.primary
    val txColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        // Subtle horizontal baseline
        drawLine(
            color = gridColor,
            start = Offset(0f, size.height * 0.5f),
            end = Offset(size.width, size.height * 0.5f),
            strokeWidth = 1.5f
        )

        if (samples.size < 2) return@Canvas
        val maxVal = samples.maxOf { maxOf(it.rxBytesPerSec, it.txBytesPerSec) }.coerceAtLeast(1024L).toFloat()
        val stepX = size.width / (samples.size - 1).coerceAtLeast(1)

        val rxPath = Path()
        val rxFill = Path()
        val txPath = Path()

        samples.forEachIndexed { idx, s ->
            val x = idx * stepX
            val rxY = size.height - (s.rxBytesPerSec / maxVal) * (size.height - 8f) - 4f
            val txY = size.height - (s.txBytesPerSec / maxVal) * (size.height - 8f) - 4f

            if (idx == 0) {
                rxPath.moveTo(x, rxY)
                rxFill.moveTo(x, size.height)
                rxFill.lineTo(x, rxY)
                txPath.moveTo(x, txY)
            } else {
                rxPath.lineTo(x, rxY)
                rxFill.lineTo(x, rxY)
                txPath.lineTo(x, txY)
            }
        }
        rxFill.lineTo(size.width, size.height)
        rxFill.close()

        drawPath(
            path = rxFill,
            brush = Brush.verticalGradient(
                colors = listOf(rxColor.copy(alpha = 0.25f), Color.Transparent)
            )
        )
        drawPath(
            path = rxPath,
            color = rxColor,
            style = Stroke(width = 3f, cap = StrokeCap.Round)
        )
        drawPath(
            path = txPath,
            color = txColor,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
        )
    }
}

package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.displayName
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Connected Viewers Card - Shows connected viewers with visual balance progress bar
 * Displays remaining balance as percentage of 1 ALGO deposit
 */
@Composable
internal fun ConnectedViewersCard(
    sessionId: String,
    balanceAlgos: Double,
    connectionType: IceConnectionType,
    currentBlockNumber: Long? = null,
) {
    val balancePercentage = (balanceAlgos / 1.0).coerceIn(0.0, 1.0)
    val percentageInt = kotlin.math.round(balancePercentage * 100).toInt()

    // Format balance text
    val balanceText = (kotlin.math.round(balanceAlgos * 100) / 100).toString().takeIf { it.length <= 4 } ?: balanceAlgos.toString().take(4)

    // Color based on remaining balance
    val balanceColor =
        when {
            balanceAlgos > 0.5 -> Color(0xFF4CAF50) // Green - plenty
            balanceAlgos > 0.2 -> Color(0xFFFFC107) // Yellow - getting low
            else -> Color(0xFFF44336) // Red - almost empty
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = AlgoKitTheme.colors.layerGray,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header row with viewer info and live indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: Viewer info
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Connection indicator
                    Box(
                        modifier =
                            Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0xFF4CAF50)),
                    )

                    Column {
                        Text(
                            text = "Viewer ${sessionId.take(8)}...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AlgoKitTheme.colors.textMain,
                        )
                        Text(
                            text = "via ${connectionType.displayName()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AlgoKitTheme.colors.textGray,
                        )
                    }
                }

                // Right: Live badge
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = Color(0xFFF44336).copy(alpha = 0.2f),
                        ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFFF44336)),
                        )
                        Text(
                            text = "LIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFF44336),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // Big visual progress bar section
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Percentage label row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = "$percentageInt%",
                        style = MaterialTheme.typography.headlineLarge,
                        color = balanceColor,
                        fontWeight = FontWeight.Bold,
                    )

                    Text(
                        text = "${balanceText}A / 1A",
                        style = MaterialTheme.typography.titleMedium,
                        color = AlgoKitTheme.colors.textMain,
                    )
                }

                // Thick progress bar
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AlgoKitTheme.colors.background),
                ) {
                    // Filled portion
                    Box(
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(balancePercentage.toFloat())
                                .background(balanceColor),
                    )

                    // Percentage text centered in bar
                    Text(
                        text = "$percentageInt%",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Labels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "0%",
                        style = MaterialTheme.typography.labelSmall,
                        color = AlgoKitTheme.colors.textGray,
                    )
                    Text(
                        text = "Remaining Balance",
                        style = MaterialTheme.typography.labelSmall,
                        color = AlgoKitTheme.colors.textGray,
                    )
                    Text(
                        text = "100%",
                        style = MaterialTheme.typography.labelSmall,
                        color = AlgoKitTheme.colors.textGray,
                    )
                }
            }

            // Stats row
            val usedAlgos = 1.0 - balanceAlgos
            val usedText = (kotlin.math.round(usedAlgos * 100) / 100).toString().takeIf { it.length <= 4 } ?: usedAlgos.toString().take(4)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatItem(
                    label = "Used",
                    value = "${usedText}A",
                    color = AlgoKitTheme.colors.textGray,
                )
                StatItem(
                    label = "Remaining",
                    value = "${balanceText}A",
                    color = balanceColor,
                )
                StatItem(
                    label = "Total Deposit",
                    value = "1A",
                    color = AlgoKitTheme.colors.textMain,
                )
            }
            // Blockchain info - Algorand block number
            if (currentBlockNumber != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = Color(0xFF6200EE).copy(alpha = 0.1f), // Purple tint
                        ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // Chain link icon
                            Text(
                                text = "⛓️",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Algorand Block",
                                style = MaterialTheme.typography.labelMedium,
                                color = AlgoKitTheme.colors.textMain,
                            )
                        }

                        Text(
                            text = "#$currentBlockNumber",
                            style = MaterialTheme.typography.labelMedium,
                            color = AlgoKitTheme.colors.linkPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Simple stat item for the stats row
 */
@Composable
internal fun StatItem(
    label: String,
    value: String,
    color: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AlgoKitTheme.colors.textGray,
        )
    }
}
 
@Preview
@Composable
private fun ConnectedViewersCardWithBlockPreview() {
    AlgoKitTheme {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(AlgoKitTheme.colors.background)
                    .padding(vertical = 16.dp),
        ) {
            ConnectedViewersCard(
                sessionId = "session-1234567890",
                balanceAlgos = 0.6,
                connectionType = IceConnectionType.STUN,
                currentBlockNumber = 45123459L,
            )
        }
    }
}
 
@Preview
@Composable
private fun ConnectedViewersCardWithoutBlockPreview() {
    AlgoKitTheme {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(AlgoKitTheme.colors.background)
                    .padding(vertical = 16.dp),
        ) {
            ConnectedViewersCard(
                sessionId = "session-1234567890",
                balanceAlgos = 0.2,
                connectionType = IceConnectionType.RELAY,
                currentBlockNumber = null,
            )
        }
    }
}

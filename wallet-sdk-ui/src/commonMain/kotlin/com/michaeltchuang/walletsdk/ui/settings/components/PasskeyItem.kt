package com.michaeltchuang.walletsdk.ui.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme

@Composable
fun PasskeyItem(
    title: String,
    domain: String,
    lastUsed: String,
    username: String,
    onDelete: () -> Unit = {}
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 0.dp),
        shape = RoundedCornerShape(18.dp),
        color = AlgoKitTheme.colors.layerGrayLightest,
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Simple user (account) icon
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            color = AlgoKitTheme.colors.layerGray,
                            shape = RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete, // Replace with AccountCircle/Key icon if available
                        contentDescription = null,
                        tint = AlgoKitTheme.colors.textMain,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = AlgoKitTheme.typography.body.regular.sansMedium,
                        color = AlgoKitTheme.colors.textMain
                    )
                    Text(
                        text = domain,
                        style = AlgoKitTheme.typography.footnote.sans,
                        color = AlgoKitTheme.colors.textGray
                    )
                }
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = AlgoKitTheme.colors.textGray,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Last used",
                        style = AlgoKitTheme.typography.footnote.sans,
                        color = AlgoKitTheme.colors.textGray
                    )
                    Text(
                        text = lastUsed,
                        style = AlgoKitTheme.typography.body.regular.sans,
                        color = AlgoKitTheme.colors.textMain
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "User name",
                        style = AlgoKitTheme.typography.footnote.sans,
                        color = AlgoKitTheme.colors.textGray
                    )
                    Text(
                        text = username,
                        style = AlgoKitTheme.typography.body.regular.sans,
                        color = AlgoKitTheme.colors.textMain
                    )
                }
            }
        }
    }
}

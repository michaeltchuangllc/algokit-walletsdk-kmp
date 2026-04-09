package com.michaeltchuang.walletsdk.ui.liquidAuth.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource
import kotlin.math.roundToInt

data class SegmentedTabItem(
    val id: String,
    val icon: DrawableResource,
    val title: String,
    val subtitle: String? = null,
)

@Composable
fun LiquidSegmentedTabs(
    tabs: List<SegmentedTabItem>,
    selectedTabId: String,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color(0xFFF7FBFC),
    borderColor: Color = Color(0xFFB8E5E9),
    selectedTabColor: Color = Color(0xFF2E34F7),
    selectedContentColor: Color = Color.White,
    unselectedIconColor: Color = Color(0xFFAEE8E7),
    unselectedTitleColor: Color = Color(0xFF0B2239),
    unselectedSubtitleColor: Color = Color(0xFF5A7186),
    selectedSubtitleColor: Color = Color(0xFFD1D8FF),
    selectedTitleWeight: FontWeight = FontWeight.Bold,
    unselectedTitleWeight: FontWeight = FontWeight.Normal,
    selectedSubtitleWeight: FontWeight = FontWeight.Normal,
    unselectedSubtitleWeight: FontWeight = FontWeight.Normal,
    subtitleFontSize: TextUnit = 12.sp,
    subtitleLineHeight: TextUnit = TextUnit.Unspecified,
    usePerTabSelectedBackground: Boolean = false,
    iconSize: Dp = 14.dp,
    tabCornerRadius: Dp = 14.dp,
    containerCornerRadius: Dp = 18.dp,
    tabSpacing: Dp = 8.dp,
    contentPadding: PaddingValues = PaddingValues(vertical = 12.dp),
) {
    if (tabs.isEmpty()) return

    val density = LocalDensity.current
    val selectedIndex = tabs.indexOfFirst { it.id == selectedTabId }.takeIf { it >= 0 } ?: 0

    var tabsRowWidthPx by remember(tabs) { mutableStateOf(0f) }
    var tabsRowHeightPx by remember(tabs) { mutableStateOf(0f) }
    val spacingPx = with(density) { tabSpacing.toPx() }
    val tabCount = tabs.size
    val totalSpacingPx = spacingPx * (tabCount - 1)
    val tabWidthPx =
        if (tabsRowWidthPx > totalSpacingPx && tabCount > 0) {
            (tabsRowWidthPx - totalSpacingPx) / tabCount
        } else {
            0f
        }
    val stepPx = tabWidthPx + spacingPx
    val selectedOffsetPx = if (tabWidthPx > 0f) selectedIndex * stepPx else 0f

    val animatedSelectedOffsetPx by animateFloatAsState(
        targetValue = selectedOffsetPx,
        animationSpec = tween(durationMillis = 280),
        label = "segmentedTabIndicatorOffset",
    )

    val indicatorOffsetPx = animatedSelectedOffsetPx

    Surface(
        shape = RoundedCornerShape(containerCornerRadius),
        color = containerColor,
        modifier =
            modifier.border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(containerCornerRadius),
            ),
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .onSizeChanged {
                        tabsRowWidthPx = it.width.toFloat()
                        tabsRowHeightPx = it.height.toFloat()
                    },
        ) {
            if (!usePerTabSelectedBackground && tabsRowHeightPx > 0f && tabWidthPx > 0f) {
                Box(
                    modifier =
                        Modifier
                            .offset { IntOffset(x = indicatorOffsetPx.roundToInt(), y = 0) }
                            .width(with(density) { tabWidthPx.toDp() })
                            .height(with(density) { tabsRowHeightPx.toDp() })
                            .clip(RoundedCornerShape(tabCornerRadius))
                            .background(selectedTabColor),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(tabSpacing),
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == selectedIndex
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(tabCornerRadius))
                                .background(
                                    if (usePerTabSelectedBackground && selected) selectedTabColor else Color.Transparent,
                                )
                                .clickable { onTabSelected(tab.id) }
                                .padding(contentPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = vectorResource(tab.icon),
                                contentDescription = null,
                                tint = if (selected) selectedContentColor else unselectedIconColor,
                                modifier = Modifier.size(iconSize),
                            )
                            Text(
                                text = tab.title,
                                color = if (selected) selectedContentColor else unselectedTitleColor,
                                fontSize = 14.sp,
                                fontWeight = if (selected) selectedTitleWeight else unselectedTitleWeight,
                            )
                        }

                        tab.subtitle?.let { subtitle ->
                            Text(
                                text = subtitle,
                                fontSize = subtitleFontSize,
                                lineHeight = subtitleLineHeight,
                                color = if (selected) selectedSubtitleColor else unselectedSubtitleColor,
                                fontWeight = if (selected) selectedSubtitleWeight else unselectedSubtitleWeight,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

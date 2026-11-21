package com.beeregg2001.komorebi.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class TabItemBounds(
    val leftPx: Float,
    val widthPx: Float,
)

private val TAB_BAR_HEIGHT = 44.dp
private val TAB_PILL_CORNER = 10.dp

/**
 * Animated top-bar tab strip for the launcher.
 *
 * Preserves Komorebi's clean horizontal underlined tab look while giving immediate, physics-driven
 * motion: a spring-gliding underline indicator, a focus-aware soft pill halo, and subtle per-tab
 * scale and color transitions without layout reflow.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun LauncherTabBar(
    controller: LauncherTabController,
    isEpgJumping: Boolean,
    leftEdgeFocusRequester: FocusRequester,
    rightEdgeFocusRequester: FocusRequester = FocusRequester.Default,
    onTabFocused: (LauncherTab) -> Unit,
    onUpKey: (() -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    val density = LocalDensity.current
    val visibleTabs = controller.visibleTabs
    val selectedTab = controller.selectedTab

    var doesTabBarHaveFocus by remember { mutableStateOf(false) }
    val tabBounds = remember { mutableStateMapOf<LauncherTab, TabItemBounds>() }

    // Prune stale bounds when tab visibility changes (e.g. hideAppsTab toggled).
    LaunchedEffect(visibleTabs) {
        val visibleSet = visibleTabs.toSet()
        tabBounds.keys.filter { it !in visibleSet }.forEach { tabBounds.remove(it) }
    }

    val targetBounds = tabBounds[selectedTab]
    val indicatorLeftPx = remember { Animatable(0f) }
    val indicatorWidthPx = remember { Animatable(0f) }
    var hasInitializedIndicator by remember { mutableStateOf(false) }

    LaunchedEffect(targetBounds) {
        val bounds = targetBounds ?: return@LaunchedEffect
        if (!hasInitializedIndicator) {
            indicatorLeftPx.snapTo(bounds.leftPx)
            indicatorWidthPx.snapTo(bounds.widthPx)
            hasInitializedIndicator = true
        } else {
            val springSpec = spring<Float>(
                dampingRatio = 0.82f,
                stiffness = Spring.StiffnessMediumLow,
            )
            launch { indicatorLeftPx.animateTo(bounds.leftPx, springSpec) }
            launch { indicatorWidthPx.animateTo(bounds.widthPx, springSpec) }
        }
    }

    val focusPillAlpha by animateFloatAsState(
        targetValue = if (doesTabBarHaveFocus && hasInitializedIndicator) 1f else 0f,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "tab_focus_pill_alpha",
    )
    val indicatorHeight by animateDpAsState(
        targetValue = if (doesTabBarHaveFocus) 3.dp else 2.5.dp,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "tab_indicator_height",
    )
    val indicatorInset by animateDpAsState(
        targetValue = if (doesTabBarHaveFocus) 5.dp else 8.dp,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "tab_indicator_inset",
    )
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (doesTabBarHaveFocus) 1f else 0.72f,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "tab_indicator_alpha",
    )

    Box(
        modifier = modifier
            .height(TAB_BAR_HEIGHT)
            .onFocusChanged { doesTabBarHaveFocus = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionUp &&
                    onUpKey != null
                ) {
                    onUpKey()
                } else {
                    false
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (hasInitializedIndicator && indicatorWidthPx.value > 0f) {
            val pillWidthDp = with(density) { indicatorWidthPx.value.toDp() }
            val insetPx = with(density) { indicatorInset.toPx() }
            val underlineWidthPx = (indicatorWidthPx.value - insetPx * 2f).coerceAtLeast(0f)
            val underlineWidthDp = with(density) { underlineWidthPx.toDp() }

            // Soft sliding focus halo behind the active tab when the tab bar holds focus.
            if (focusPillAlpha > 0.005f) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(indicatorLeftPx.value.roundToInt(), 0) }
                        .width(pillWidthDp)
                        .height(36.dp)
                        .graphicsLayer { alpha = focusPillAlpha }
                        .background(
                            color = colors.textPrimary.copy(alpha = if (colors.isDark) 0.10f else 0.08f),
                            shape = RoundedCornerShape(TAB_PILL_CORNER),
                        ),
                )
            }

            // Spring-animated accent underline anchored to the bottom of the tab strip.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset {
                        IntOffset((indicatorLeftPx.value + insetPx).roundToInt(), 0)
                    }
                    .width(underlineWidthDp)
                    .height(indicatorHeight)
                    .graphicsLayer { alpha = indicatorAlpha }
                    .background(
                        color = colors.accent,
                        shape = RoundedCornerShape(percent = 50),
                    ),
            )
        }

        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            visibleTabs.forEachIndexed { index, tab ->
                val isSelected = selectedTab == tab
                val canFocusTab = !(tab == LauncherTab.EPG && isEpgJumping)
                var isItemFocused by remember(tab) { mutableStateOf(false) }

                val textColor by animateColorAsState(
                    targetValue = when {
                        isItemFocused || isSelected -> colors.textPrimary
                        else -> colors.textSecondary
                    },
                    animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                    label = "tab_text_color_${tab.name}",
                )
                val itemScale by animateFloatAsState(
                    targetValue = if (isItemFocused) 1.05f else 1.0f,
                    animationSpec = spring(
                        dampingRatio = 0.78f,
                        stiffness = Spring.StiffnessMedium,
                    ),
                    label = "tab_item_scale_${tab.name}",
                )

                val interactionSource = remember(tab) { MutableInteractionSource() }
                val prevTab = visibleTabs.getOrNull(index - 1)
                val nextTab = visibleTabs.getOrNull(index + 1)

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .testTag("launcher-tab-${tab.name.lowercase()}")
                        .onGloballyPositioned { coords ->
                            val left = coords.positionInParent().x
                            val width = coords.size.width.toFloat()
                            val current = tabBounds[tab]
                            if (current == null || current.leftPx != left || current.widthPx != width) {
                                tabBounds[tab] = TabItemBounds(left, width)
                            }
                        }
                        .focusRequester(controller.tabRequester(tab))
                        .focusProperties {
                            down = if (isSelected && controller.isContentReadyFor(tab)) {
                                controller.contentRequester(tab)
                            } else {
                                FocusRequester.Default
                            }
                            up = FocusRequester.Cancel
                            left = prevTab?.let { controller.tabRequester(it) }
                                ?: leftEdgeFocusRequester
                            right = nextTab?.let { controller.tabRequester(it) }
                                ?: rightEdgeFocusRequester
                            canFocus = canFocusTab
                        }
                        .onFocusChanged { focusState ->
                            isItemFocused = focusState.isFocused
                            if (focusState.isFocused) {
                                onTabFocused(tab)
                            }
                        }
                        .selectable(
                            selected = isSelected,
                            enabled = canFocusTab,
                            role = Role.Tab,
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onTabFocused(tab) },
                        )
                        .graphicsLayer {
                            scaleX = itemScale
                            scaleY = itemScale
                        }
                        .padding(horizontal = 9.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tab.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isSelected || isItemFocused) FontWeight.SemiBold else FontWeight.Normal,
                        color = textColor,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

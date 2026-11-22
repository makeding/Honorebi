package com.beeregg2001.komorebi.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FiberSmartRecord
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.R
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.TvCardFocusBorderWidth
import com.beeregg2001.komorebi.ui.theme.TvCardRadiusListRow
import com.beeregg2001.komorebi.ui.theme.TvRailCollapsedWidth
import com.beeregg2001.komorebi.ui.theme.TvRailExpandedWidth
import com.beeregg2001.komorebi.ui.theme.TvRailSelectedIndicatorVerticalInset
import com.beeregg2001.komorebi.ui.theme.TvRailSelectedIndicatorWidth
import kotlinx.coroutines.delay

private val RAIL_ITEM_HEIGHT = 48.dp
private val RAIL_ICON_SIZE = 24.dp
private val RAIL_ITEM_HORIZONTAL_INSET = 8.dp
private val RAIL_ITEM_VERTICAL_INSET = 2.dp
private const val RAIL_WIDTH_ANIM_MS = 140
private const val RAIL_LABEL_ANIM_MS = 120
private const val RAIL_AUTO_COLLAPSE_MS = 15_000L

private fun railIcon(tab: LauncherTab): ImageVector = when (tab) {
    LauncherTab.HOME -> Icons.Default.Home
    LauncherTab.LIVE -> Icons.Default.LiveTv
    LauncherTab.APPS -> Icons.Default.Apps
    LauncherTab.VIDEO -> Icons.Default.VideoLibrary
    LauncherTab.EPG -> Icons.Default.DateRange
    LauncherTab.RESERVE -> Icons.Default.FiberSmartRecord
}

/**
 * Left-hand vertical navigation rail for the launcher.
 *
 * Expands from [TvRailCollapsedWidth] to [TvRailExpandedWidth] as an opaque/high-opacity overlay
 * over the content stage without reflowing tab content, applying blur, or adding a scrim.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
internal fun LauncherNavRail(
    controller: LauncherTabController,
    isEpgJumping: Boolean,
    upEdgeFocusRequester: FocusRequester,
    settingsItemFocusRequester: FocusRequester,
    settingsCanFocus: Boolean,
    onTabFocused: (LauncherTab) -> Unit,
    onTabActivated: (LauncherTab) -> Unit = onTabFocused,
    onOpenSettings: () -> Unit,
    onSettingsFocused: () -> Unit = {},
    onPrepareEnterContent: () -> Unit = {},
    onHasFocusChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    startCollapsed: Boolean = false,
    onStartCollapsedConsumed: () -> Unit = {},
    onSettingsButtonBoundsChanged: (Rect) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    val focusManager = LocalFocusManager.current
    val visibleTabs = controller.visibleTabs
    val selectedTab = controller.selectedTab
    val focusableTabs = remember(visibleTabs, isEpgJumping) {
        visibleTabs.filter { !(it == LauncherTab.EPG && isEpgJumping) }
    }

    var railHasFocus by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var isAutoCollapsed by remember { mutableStateOf(false) }
    var suppressInitialExpand by remember(startCollapsed) { mutableStateOf(startCollapsed) }

    LaunchedEffect(railHasFocus, suppressInitialExpand, interactionTick) {
        isAutoCollapsed = false
        if (railHasFocus && !suppressInitialExpand) {
            delay(RAIL_AUTO_COLLAPSE_MS)
            isAutoCollapsed = true
        }
    }

    val isExpanded = railHasFocus && !isAutoCollapsed && !suppressInitialExpand

    val railWidth by animateDpAsState(
        targetValue = if (isExpanded) TvRailExpandedWidth else TvRailCollapsedWidth,
        animationSpec = tween(durationMillis = RAIL_WIDTH_ANIM_MS, easing = FastOutSlowInEasing),
        label = "launcher_rail_width",
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(durationMillis = RAIL_LABEL_ANIM_MS, easing = FastOutSlowInEasing),
        label = "launcher_rail_label_alpha",
    )
    val railSurfaceColor by animateColorAsState(
        targetValue = if (isExpanded) colors.surface1.copy(alpha = 0.88f) else colors.surface1,
        animationSpec = tween(durationMillis = RAIL_LABEL_ANIM_MS, easing = FastOutSlowInEasing),
        label = "launcher_rail_surface",
    )

    Column(
        modifier = modifier
            .testTag("launcher-nav-rail")
            .width(railWidth)
            .fillMaxHeight()
            .clipToBounds()
            .drawBehind {
                drawRect(color = railSurfaceColor)
                val borderPx = 1.dp.toPx()
                drawRect(
                    color = colors.borderSubtle,
                    topLeft = Offset(size.width - borderPx, 0f),
                    size = Size(borderPx, size.height),
                )
            }
            .onFocusChanged { focusState ->
                val hadFocus = railHasFocus
                railHasFocus = focusState.hasFocus
                if (hadFocus && !focusState.hasFocus && suppressInitialExpand) {
                    suppressInitialExpand = false
                    onStartCollapsedConsumed()
                }
                if (focusState.hasFocus && !suppressInitialExpand) {
                    interactionTick++
                }
                onHasFocusChanged(focusState.hasFocus)
            }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    if ((event.key == Key.DirectionUp || event.key == Key.DirectionDown) && suppressInitialExpand) {
                        suppressInitialExpand = false
                        onStartCollapsedConsumed()
                    }
                    if (event.key != Key.DirectionRight &&
                        event.key != Key.DirectionCenter &&
                        event.key != Key.Enter &&
                        event.key != Key.NumPadEnter &&
                        event.key != Key.Escape &&
                        event.key != Key.Back
                    ) {
                        interactionTick++
                    }
                    if (event.key == Key.Escape) {
                        onBack()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .focusProperties {
                down = FocusRequester.Cancel
                left = FocusRequester.Cancel
                enter = {
                    val target = if (controller.selectedTab == LauncherTab.EPG && isEpgJumping) {
                        focusableTabs.firstOrNull() ?: controller.selectedTab
                    } else {
                        controller.selectedTab
                    }
                    controller.tabRequester(target)
                }
            }
            .focusGroup()
            .padding(top = 8.dp, bottom = 16.dp),
    ) {
        // Non-focusable brand badge aligned with the top utility strip.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(RAIL_ITEM_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(TvRailCollapsedWidth)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.honorebi_mark),
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                )
            }
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .graphicsLayer { alpha = labelAlpha },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        visibleTabs.forEach { tab ->
            val isSelected = selectedTab == tab
            val canFocusTab = !(tab == LauncherTab.EPG && isEpgJumping)
            var isItemFocused by remember(tab) { mutableStateOf(false) }
            val interactionSource = remember(tab) { MutableInteractionSource() }
            val focusableIdx = focusableTabs.indexOf(tab)
            val prevTab = if (focusableIdx > 0) focusableTabs[focusableIdx - 1] else null
            val nextTab = if (focusableIdx >= 0 && focusableIdx < focusableTabs.lastIndex) {
                focusableTabs[focusableIdx + 1]
            } else {
                null
            }

            val contentColor = when {
                isItemFocused -> colors.textPrimary
                isSelected -> colors.accent
                else -> colors.textSecondary
            }

            RailItemSurface(
                isSelected = isSelected,
                isFocused = isItemFocused,
                icon = railIcon(tab),
                label = tab.title,
                contentColor = contentColor,
                labelAlpha = labelAlpha,
                modifier = Modifier
                    .testTag("launcher-tab-${tab.name.lowercase()}")
                    .focusRequester(controller.tabRequester(tab))
                    .focusProperties {
                        up = prevTab?.let { controller.tabRequester(it) } ?: upEdgeFocusRequester
                        down = nextTab?.let { controller.tabRequester(it) } ?: settingsItemFocusRequester
                        right = FocusRequester.Cancel
                        left = FocusRequester.Cancel
                        canFocus = canFocusTab
                    }
                    .onFocusChanged { focusState ->
                        isItemFocused = focusState.isFocused
                        if (focusState.isFocused) {
                            if (!suppressInitialExpand) interactionTick++
                            onTabFocused(tab)
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionRight &&
                            canFocusTab
                        ) {
                            if (isSelected && controller.isContentReadyFor(tab)) {
                                onPrepareEnterContent()
                                if (!controller.contentRequester(tab).safeRequestFocus("RailRight_${tab.name}")) {
                                    focusManager.moveFocus(FocusDirection.Right)
                                }
                            } else {
                                onTabActivated(tab)
                            }
                            true
                        } else {
                            false
                        }
                    }
                    .selectable(
                        selected = isSelected,
                        enabled = canFocusTab,
                        role = Role.Tab,
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onTabActivated(tab) },
                    ),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .height(1.dp)
                .background(colors.borderSubtle),
        )

        var isSettingsFocused by remember { mutableStateOf(false) }
        val settingsInteractionSource = remember { MutableInteractionSource() }
        val lastVisibleTab = focusableTabs.lastOrNull()

        RailItemSurface(
            isSelected = false,
            isFocused = isSettingsFocused,
            icon = Icons.Default.Tune,
            label = "アプリ設定",
            contentColor = if (isSettingsFocused) colors.textPrimary else colors.textSecondary,
            labelAlpha = labelAlpha,
            modifier = Modifier
                .testTag("launcher-rail-settings")
                .onGloballyPositioned { onSettingsButtonBoundsChanged(it.boundsInRoot()) }
                .focusRequester(settingsItemFocusRequester)
                .focusProperties {
                    up = lastVisibleTab?.let { controller.tabRequester(it) } ?: upEdgeFocusRequester
                    down = FocusRequester.Cancel
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                    canFocus = settingsCanFocus
                }
                .onFocusChanged { focusState ->
                    isSettingsFocused = focusState.isFocused
                    if (focusState.isFocused) {
                        if (!suppressInitialExpand) interactionTick++
                        onSettingsFocused()
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionRight &&
                        controller.isActiveContentReady
                    ) {
                        val activeTab = controller.selectedTab
                        onPrepareEnterContent()
                        if (!controller.contentRequester(activeTab).safeRequestFocus("RailSettingsRight")) {
                            focusManager.moveFocus(FocusDirection.Right)
                        }
                        true
                    } else {
                        false
                    }
                }
                .clickable(
                    enabled = settingsCanFocus,
                    interactionSource = settingsInteractionSource,
                    indication = null,
                    onClick = onOpenSettings,
                ),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RailItemSurface(
    isSelected: Boolean,
    isFocused: Boolean,
    icon: ImageVector,
    label: String,
    contentColor: Color,
    labelAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(RAIL_ITEM_HEIGHT)
            .drawBehind {
                val insetX = RAIL_ITEM_HORIZONTAL_INSET.toPx()
                val insetY = RAIL_ITEM_VERTICAL_INSET.toPx()
                val cornerPx = TvCardRadiusListRow.toPx()
                val bgTopLeft = Offset(insetX, insetY)
                val bgSize = Size(
                    width = (size.width - insetX * 2f).coerceAtLeast(0f),
                    height = (size.height - insetY * 2f).coerceAtLeast(0f),
                )

                if (isFocused) {
                    drawRoundRect(
                        color = colors.surface2,
                        topLeft = bgTopLeft,
                        size = bgSize,
                        cornerRadius = CornerRadius(cornerPx, cornerPx),
                    )
                    drawRoundRect(
                        color = colors.accent,
                        topLeft = bgTopLeft,
                        size = bgSize,
                        cornerRadius = CornerRadius(cornerPx, cornerPx),
                        style = Stroke(width = TvCardFocusBorderWidth.toPx()),
                    )
                } else if (isSelected) {
                    drawRoundRect(
                        color = colors.accentSoft,
                        topLeft = bgTopLeft,
                        size = bgSize,
                        cornerRadius = CornerRadius(cornerPx, cornerPx),
                    )
                }

                if (isSelected) {
                    val pillW = TvRailSelectedIndicatorWidth.toPx()
                    val pillInsetY = TvRailSelectedIndicatorVerticalInset.toPx()
                    drawRoundRect(
                        color = colors.accent,
                        topLeft = Offset(0f, pillInsetY),
                        size = Size(pillW, (size.height - pillInsetY * 2f).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(pillW / 2f, pillW / 2f),
                    )
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(TvRailCollapsedWidth)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(RAIL_ICON_SIZE),
            )
        }

        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .padding(end = 12.dp)
                .graphicsLayer { alpha = labelAlpha },
        )
    }
}

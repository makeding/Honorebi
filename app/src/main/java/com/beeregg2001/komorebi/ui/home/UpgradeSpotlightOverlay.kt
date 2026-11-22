package com.beeregg2001.komorebi.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.TvCardFocusBorderWidth
import com.beeregg2001.komorebi.ui.theme.TvCardRadiusGrid
import kotlin.math.roundToInt

class UpgradeSpotlightTargets {
    var navRailBounds by mutableStateOf(Rect.Zero)
    var systemStripBounds by mutableStateOf(Rect.Zero)
    var contentStageBounds by mutableStateOf(Rect.Zero)
    var deepTvSettingBounds by mutableStateOf(Rect.Zero)
    var commentEngineSettingBounds by mutableStateOf(Rect.Zero)

    fun boundsFor(targetId: UpgradeTourTargetId): Rect = when (targetId) {
        UpgradeTourTargetId.NAV_RAIL -> navRailBounds
        UpgradeTourTargetId.SYSTEM_STRIP -> systemStripBounds
        UpgradeTourTargetId.CONTENT_STAGE -> contentStageBounds
        UpgradeTourTargetId.DEEP_TV_SETTING -> deepTvSettingBounds
        UpgradeTourTargetId.COMMENT_ENGINE_SETTING -> commentEngineSettingBounds
    }
}

@Composable
fun rememberUpgradeSpotlightTargets(): UpgradeSpotlightTargets =
    remember { UpgradeSpotlightTargets() }

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun UpgradeSpotlightOverlay(
    steps: List<UpgradeTourStep>,
    targets: UpgradeSpotlightTargets,
    onStepChanged: (Int) -> Unit = {},
    onStepAction: (UpgradeTourAction) -> Unit = {},
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (steps.isEmpty()) return

    val colors = KomorebiTheme.colors
    val density = LocalDensity.current
    var stepIndex by remember { mutableIntStateOf(0) }
    val currentStep = steps[stepIndex.coerceIn(0, steps.lastIndex)]
    val isFirstStep = stepIndex == 0
    val isLastStep = stepIndex == steps.lastIndex

    val primaryButtonFocusRequester = remember { FocusRequester() }
    val stepActionButtonFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val skipButtonFocusRequester = remember { FocusRequester() }
    var calloutHasFocus by remember { mutableStateOf(false) }
    val hasStepAction = currentStep.actionLabel != null && currentStep.actionTarget != null

    BackHandler(enabled = true) {
        onFinish()
    }

    LaunchedEffect(stepIndex) {
        onStepChanged(stepIndex)
        androidx.compose.runtime.withFrameNanos { }
        primaryButtonFocusRequester.safeRequestFocusWithRetry("UpgradeSpotlight_Primary_$stepIndex")
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("upgrade-spotlight-overlay")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Back || event.key == Key.Escape)
                ) {
                    onFinish()
                    return@onPreviewKeyEvent true
                }
                if (!calloutHasFocus) {
                    val isNavOrConfirmKey = event.key == Key.DirectionCenter ||
                        event.key == Key.Enter ||
                        event.key == Key.NumPadEnter ||
                        event.key == Key.DirectionUp ||
                        event.key == Key.DirectionDown ||
                        event.key == Key.DirectionLeft ||
                        event.key == Key.DirectionRight
                    if (isNavOrConfirmKey) {
                        if (event.type == KeyEventType.KeyDown) {
                            primaryButtonFocusRequester.safeRequestFocus("UpgradeSpotlight_RecoverFocus")
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                false
            },
    ) {
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }

        val rawTargetRect = targets.boundsFor(currentStep.targetId).let { measured ->
            when {
                measured != Rect.Zero && currentStep.targetId == UpgradeTourTargetId.CONTENT_STAGE -> {
                    // Focus on a single card-sized region directly to the right of the sidebar.
                    val cardLeft = measured.left + with(density) { 28.dp.toPx() }
                    val cardTop = measured.top + with(density) { 64.dp.toPx() }
                    val cardWidth = with(density) { 296.dp.toPx() }.coerceAtMost((measured.width - with(density) { 56.dp.toPx() }).coerceAtLeast(160f))
                    val cardHeight = with(density) { 168.dp.toPx() }.coerceAtMost((measured.height * 0.36f).coerceAtLeast(120f))
                    Rect(
                        left = cardLeft,
                        top = cardTop,
                        right = cardLeft + cardWidth,
                        bottom = cardTop + cardHeight,
                    )
                }
                measured != Rect.Zero -> measured
                else -> Rect(
                    left = screenWidthPx * 0.15f,
                    top = screenHeightPx * 0.15f,
                    right = screenWidthPx * 0.45f,
                    bottom = screenHeightPx * 0.45f,
                )
            }
        }

        val animatedRect by animateRectAsState(
            targetValue = rawTargetRect,
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            label = "spotlight_cutout_rect",
        )

        val cutoutPaddingPx = with(density) {
            when (currentStep.shape) {
                UpgradeSpotlightShape.CIRCLE -> 10.dp.toPx()
                UpgradeSpotlightShape.ROUNDED_RECT -> 8.dp.toPx()
            }
        }
        val cutoutCornerPx = with(density) { 22.dp.toPx() }
        val borderWidthPx = with(density) { 2.5.dp.toPx() }
        val haloWidthPx = with(density) { 9.dp.toPx() }

        // Dimmed scrim with transparent spotlight cutout
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            drawRect(color = Color(0xFF070A10).copy(alpha = 0.78f))

            if (animatedRect.width > 0f && animatedRect.height > 0f) {
                when (currentStep.shape) {
                    UpgradeSpotlightShape.CIRCLE -> {
                        val radius = (maxOf(animatedRect.width, animatedRect.height) / 2f) + cutoutPaddingPx
                        val center = animatedRect.center
                        drawCircle(
                            color = Color.Transparent,
                            radius = radius,
                            center = center,
                            blendMode = BlendMode.Clear,
                        )
                        drawCircle(
                            color = colors.accent.copy(alpha = 0.22f),
                            radius = radius + haloWidthPx / 2f,
                            center = center,
                            style = Stroke(width = haloWidthPx),
                        )
                        drawCircle(
                            color = colors.accent.copy(alpha = 0.92f),
                            radius = radius + borderWidthPx / 2f,
                            center = center,
                            style = Stroke(width = borderWidthPx),
                        )
                    }
                    UpgradeSpotlightShape.ROUNDED_RECT -> {
                        val left = (animatedRect.left - cutoutPaddingPx).coerceAtLeast(0f)
                        val top = (animatedRect.top - cutoutPaddingPx).coerceAtLeast(0f)
                        val right = (animatedRect.right + cutoutPaddingPx).coerceAtMost(size.width)
                        val bottom = (animatedRect.bottom + cutoutPaddingPx).coerceAtMost(size.height)
                        val cutoutSize = Size(
                            width = (right - left).coerceAtLeast(0f),
                            height = (bottom - top).coerceAtLeast(0f),
                        )
                        val topLeft = Offset(left, top)
                        val corner = CornerRadius(cutoutCornerPx, cutoutCornerPx)

                        drawRoundRect(
                            color = Color.Transparent,
                            topLeft = topLeft,
                            size = cutoutSize,
                            cornerRadius = corner,
                            blendMode = BlendMode.Clear,
                        )
                        drawRoundRect(
                            color = colors.accent.copy(alpha = 0.22f),
                            topLeft = topLeft,
                            size = cutoutSize,
                            cornerRadius = corner,
                            style = Stroke(width = haloWidthPx),
                        )
                        drawRoundRect(
                            color = colors.accent.copy(alpha = 0.92f),
                            topLeft = topLeft,
                            size = cutoutSize,
                            cornerRadius = corner,
                            style = Stroke(width = borderWidthPx),
                        )
                    }
                }
            }
        }

        // Callout card placed in the remaining blank space (余白)
        val calloutWidth = 460.dp
        val calloutModifier = when (currentStep.placement) {
            UpgradeCalloutPlacement.RIGHT_OF_TARGET -> {
                val startOffsetPx = (animatedRect.right + with(density) { 36.dp.toPx() })
                    .coerceAtMost(screenWidthPx - with(density) { 490.dp.toPx() })
                    .roundToInt()
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(x = startOffsetPx.coerceAtLeast(0), y = 0) }
            }
            UpgradeCalloutPlacement.BELOW_RIGHT -> {
                if (currentStep.targetId == UpgradeTourTargetId.COMMENT_ENGINE_SETTING &&
                    animatedRect != Rect.Zero &&
                    animatedRect.center.y > screenHeightPx * 0.52f
                ) {
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 40.dp, end = 48.dp)
                } else {
                    val topOffsetPx = (animatedRect.bottom + with(density) { 28.dp.toPx() })
                        .coerceAtMost(screenHeightPx * 0.55f)
                        .roundToInt()
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 36.dp)
                        .offset { IntOffset(x = 0, y = topOffsetPx.coerceAtLeast(0)) }
                }
            }
            UpgradeCalloutPlacement.BOTTOM_CENTER -> {
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
            }
            UpgradeCalloutPlacement.BOTTOM_LEFT_BESIDE_RAIL -> {
                val startOffsetPx = (animatedRect.right + with(density) { 40.dp.toPx() })
                    .coerceAtMost(screenWidthPx - with(density) { 490.dp.toPx() })
                    .roundToInt()
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 28.dp)
                    .offset { IntOffset(x = startOffsetPx.coerceAtLeast(0), y = 0) }
            }
        }

        Box(
            modifier = calloutModifier
                .widthIn(max = calloutWidth)
                .clip(RoundedCornerShape(TvCardRadiusGrid + 4.dp))
                .background(colors.surface1.copy(alpha = 0.96f))
                .border(
                    width = 1.dp,
                    color = colors.borderSubtle,
                    shape = RoundedCornerShape(TvCardRadiusGrid + 4.dp),
                )
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .onFocusChanged { calloutHasFocus = it.hasFocus }
                .focusGroup(),
        ) {
            Column {
                AnimatedContent(
                    targetState = stepIndex,
                    transitionSpec = {
                        fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                    },
                    label = "upgrade_callout_step",
                ) { idx ->
                    val step = steps[idx.coerceIn(0, steps.lastIndex)]
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(colors.accentSoft)
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = step.badge,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.accent,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                text = "${idx + 1} / ${steps.size}",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.textSecondary,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = step.description,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                            color = colors.textSecondary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Step progress dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    steps.indices.forEach { idx ->
                        val active = idx == stepIndex
                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .width(if (active) 24.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) colors.accent else colors.textSecondary.copy(alpha = 0.35f),
                                ),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SpotlightActionButton(
                        label = "スキップ",
                        icon = Icons.Default.Close,
                        isPrimary = false,
                        onClick = onFinish,
                        modifier = Modifier
                            .focusRequester(skipButtonFocusRequester)
                            .focusProperties {
                                up = FocusRequester.Cancel
                                down = FocusRequester.Cancel
                                left = FocusRequester.Cancel
                                right = when {
                                    !isFirstStep -> backButtonFocusRequester
                                    hasStepAction -> stepActionButtonFocusRequester
                                    else -> primaryButtonFocusRequester
                                }
                            },
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!isFirstStep) {
                            SpotlightActionButton(
                                label = "戻る",
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                isPrimary = false,
                                onClick = { stepIndex = (stepIndex - 1).coerceAtLeast(0) },
                                modifier = Modifier
                                    .focusRequester(backButtonFocusRequester)
                                    .focusProperties {
                                        up = FocusRequester.Cancel
                                        down = FocusRequester.Cancel
                                        left = skipButtonFocusRequester
                                        right = if (hasStepAction) stepActionButtonFocusRequester else primaryButtonFocusRequester
                                    },
                            )
                        }

                        val actionLabel = currentStep.actionLabel
                        val actionTarget = currentStep.actionTarget
                        if (actionLabel != null && actionTarget != null) {
                            SpotlightActionButton(
                                label = actionLabel,
                                icon = Icons.Default.Tune,
                                isPrimary = false,
                                onClick = { onStepAction(actionTarget) },
                                modifier = Modifier
                                    .focusRequester(stepActionButtonFocusRequester)
                                    .focusProperties {
                                        up = FocusRequester.Cancel
                                        down = FocusRequester.Cancel
                                        left = if (!isFirstStep) backButtonFocusRequester else skipButtonFocusRequester
                                        right = primaryButtonFocusRequester
                                    },
                            )
                        }

                        SpotlightActionButton(
                            label = if (isLastStep) "はじめる" else "次へ",
                            icon = if (isLastStep) Icons.Default.Check else Icons.AutoMirrored.Filled.ArrowForward,
                            isPrimary = true,
                            onClick = {
                                if (isLastStep) {
                                    onFinish()
                                } else {
                                    stepIndex = (stepIndex + 1).coerceAtMost(steps.lastIndex)
                                }
                            },
                            modifier = Modifier
                                .focusRequester(primaryButtonFocusRequester)
                                .focusProperties {
                                    up = FocusRequester.Cancel
                                    down = FocusRequester.Cancel
                                    left = when {
                                        hasStepAction -> stepActionButtonFocusRequester
                                        !isFirstStep -> backButtonFocusRequester
                                        else -> skipButtonFocusRequester
                                    }
                                    right = FocusRequester.Cancel
                                },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SpotlightActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isPrimary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isPrimary) colors.accent else colors.surface2,
            focusedContainerColor = if (isPrimary) colors.accent else colors.surface2,
            contentColor = if (isPrimary) colors.onAccent else colors.textPrimary,
            focusedContentColor = if (isPrimary) colors.onAccent else colors.textPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isPrimary) Color.Transparent else colors.borderSubtle,
                ),
                shape = RoundedCornerShape(10.dp),
            ),
            focusedBorder = Border(
                border = BorderStroke(
                    width = TvCardFocusBorderWidth,
                    color = if (isPrimary) colors.textPrimary else colors.accent,
                ),
                shape = RoundedCornerShape(10.dp),
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isPrimary || isFocused) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

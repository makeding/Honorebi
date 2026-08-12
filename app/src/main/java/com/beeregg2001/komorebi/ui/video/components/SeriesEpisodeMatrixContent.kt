package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.rememberChannelLogoImageLoader
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.ExpandedSeriesState

data class SeriesEpisodeSlot(
    val key: String,
    val label: String,
    val sortOrder: Double,
)

data class SeriesEpisodeChannelRow(
    val channelId: String?,
    val channelName: String,
    val programCount: Int,
    val programs: List<RecordedProgram?>,
)

data class SeriesEpisodeMatrix(
    val slots: List<SeriesEpisodeSlot>,
    val rows: List<SeriesEpisodeChannelRow>,
)

private fun getEpisodeSlots(program: RecordedProgram): List<SeriesEpisodeSlot> {
    val episodeNumber = program.episodeNumber
    if (!episodeNumber.isNullOrBlank()) {
        val range = Regex("^(\\d+)-(\\d+)$").matchEntire(episodeNumber)
        val numbers = if (range != null) {
            val start = range.groupValues[1].toInt()
            val end = range.groupValues[2].toInt()
            if (end >= start) (start..end).map(Int::toString) else listOf(episodeNumber)
        } else {
            episodeNumber.split('・')
        }
        return numbers.map { number ->
            SeriesEpisodeSlot("episode:$number", "第${number}話", number.toDoubleOrNull() ?: Double.MAX_VALUE)
        }
    }

    val date = program.startTime.substringBefore('T')
    return listOf(SeriesEpisodeSlot("date:$date", date, Double.MAX_VALUE))
}

fun buildSeriesEpisodeMatrix(programs: List<RecordedProgram>): SeriesEpisodeMatrix {
    val slots = programs
        .flatMap(::getEpisodeSlots)
        .distinctBy(SeriesEpisodeSlot::key)
        .sortedWith(compareBy<SeriesEpisodeSlot>({ it.sortOrder }, { it.key }))
    val rows = programs.groupBy { it.channel?.id ?: "unknown" }.values.map { channelPrograms ->
        val programsBySlot = mutableMapOf<String, RecordedProgram>()
        channelPrograms.forEach { program ->
            getEpisodeSlots(program).forEach { slot -> programsBySlot.putIfAbsent(slot.key, program) }
        }
        SeriesEpisodeChannelRow(
            channelId = channelPrograms.first().channel?.id,
            channelName = channelPrograms.first().channel?.name ?: "チャンネル情報なし",
            programCount = channelPrograms.size,
            programs = slots.map { programsBySlot[it.key] },
        )
    }
    return SeriesEpisodeMatrix(slots, rows)
}

@Composable
fun SeriesEpisodeMatrixContent(
    title: String,
    state: ExpandedSeriesState,
    konomiIp: String,
    konomiPort: String,
    onProgramClick: (RecordedProgram, Double?) -> Unit,
    onOpenNavPane: () -> Unit,
    onBackPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    val matrix = remember(state.programs) { buildSeriesEpisodeMatrix(state.programs) }
    val horizontalScrollState = rememberScrollState()
    val channelWidth = 148.dp
    val episodeWidth = 180.dp
    val rowHeight = 102.dp

    Surface(
        modifier = modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(containerColor = colors.surface),
        shape = RoundedCornerShape(12.dp),
        border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.14f))),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = colors.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${state.programs.size}話",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(14.dp))

            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxWidth().height(128.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = colors.accent) }

                state.errorMessage != null -> Box(
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(state.errorMessage, color = colors.textSecondary) }

                matrix.slots.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("録画番組がありません", color = colors.textSecondary) }

                else -> Row {
                    Column(modifier = Modifier.width(channelWidth)) {
                        MatrixHeader("放送局", Modifier.height(28.dp), colors.textSecondary)
                        matrix.rows.forEach { row ->
                            ChannelHeader(
                                row = row,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                modifier = Modifier.height(rowHeight),
                            )
                        }
                    }
                    Column(modifier = Modifier.horizontalScroll(horizontalScrollState)) {
                        Row(modifier = Modifier.height(28.dp)) {
                            matrix.slots.forEach { slot ->
                                MatrixHeader(slot.label, Modifier.width(episodeWidth), colors.textSecondary)
                            }
                        }
                        matrix.rows.forEach { row ->
                            val firstProgramIndex = row.programs.indexOfFirst { it != null }
                            Row(
                                modifier = Modifier.height(rowHeight),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                row.programs.forEachIndexed { index, program ->
                                    if (program == null) {
                                        Spacer(
                                            modifier = Modifier
                                                .width(episodeWidth)
                                                .fillMaxHeight()
                                                .padding(vertical = 6.dp)
                                                .background(
                                                    colors.textPrimary.copy(alpha = 0.035f),
                                                    RoundedCornerShape(7.dp),
                                                ),
                                        )
                                    } else {
                                        EpisodeCell(
                                            program = program,
                                            onClick = { onProgramClick(program, null) },
                                            onOpenNavPane = onOpenNavPane,
                                            onBackPress = onBackPress,
                                            isFirstProgram = index == firstProgramIndex,
                                            modifier = Modifier.width(episodeWidth).fillMaxHeight().padding(vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatrixHeader(text: String, modifier: Modifier, color: Color) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        Text(text = text, color = color, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun ChannelHeader(
    row: SeriesEpisodeChannelRow,
    konomiIp: String,
    konomiPort: String,
    modifier: Modifier,
) {
    val colors = KomorebiTheme.colors
    val imageLoader = rememberChannelLogoImageLoader()
    Row(modifier = modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (row.channelId != null) {
            AsyncImage(
                model = UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, row.channelId),
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 48.dp, height = 30.dp)
                    .background(Color.White, RoundedCornerShape(5.dp)),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.channelName,
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = "${row.programCount}話", color = colors.textSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun EpisodeCell(
    program: RecordedProgram,
    onClick: () -> Unit,
    onOpenNavPane: () -> Unit,
    onBackPress: () -> Unit,
    isFirstProgram: Boolean,
    modifier: Modifier,
) {
    val colors = KomorebiTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier
            .focusProperties { if (isFirstProgram) left = FocusRequester.Cancel }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    isFirstProgram && event.key == Key.DirectionLeft -> {
                        onOpenNavPane()
                        true
                    }
                    event.key == Key.Back || event.key == Key.Escape -> {
                        onBackPress()
                        true
                    }
                    else -> false
                }
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(containerColor = colors.surface),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(7.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, colors.accent)),
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = program.apiThumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))),
                ),
            )
            Text(
                text = program.subtitle?.takeIf(String::isNotBlank)
                    ?: program.episodeNumber?.let { "第${it}話" }
                    ?: program.title,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

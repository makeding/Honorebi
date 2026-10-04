package com.beeregg2001.komorebi.ui.onair

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.common.ProgramTimeFormatter
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** coil に短い crossfade を効せて画像差し替えのちらつきを抑える。 */
@Composable
internal fun CrossfadeImage(url: String, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val context = LocalContext.current
    AsyncImage(
        remember(url) { ImageRequest.Builder(context).data(url).crossfade(180).build() },
        contentDescription,
        modifier,
        contentScale = ContentScale.Crop,
    )
}

/** 話数セルの中身（サムネイル + 下部キャプション + 部分録画バッジ）。フォーカスは呼び出し側が担う。 */
@Composable
internal fun EpisodeCellContent(
    program: RecordedProgram,
    ip: String,
    port: String,
    showPartialWarning: Boolean,
    timeFormat: String = "24H",
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surface)
    ) {
        CrossfadeImage(
            UrlBuilder.getThumbnailUrl("KONOMITV", ip, port, program.id.toString()),
            Modifier.matchParentSize(),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.86f),
                )
            )
        )
        if (showPartialWarning) {
            Text(
                "⚠ 一部のみ録画",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        Text(
            episodeCaption(program, timeFormat),
            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .85f), Offset(0f, 1f), 3f)),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

/** HonomiTV と同じくサブタイトルを優先し、無ければ開始日時を表記する。 */
internal fun episodeCaption(program: RecordedProgram, timeFormat: String = "24H"): String =
    program.subtitle?.takeIf(String::isNotBlank) ?: runCatching {
        val start = OffsetDateTime.parse(program.startTime).toZonedDateTime()
        ProgramTimeFormatter.formatDateTime(start, timeFormat, "yyyy/M/d (E)")
    }.getOrDefault(program.title)

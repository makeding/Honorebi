package com.beeregg2001.komorebi.ui.video.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.api.interceptor.CloudflareAccessConfiguration
import com.beeregg2001.komorebi.data.api.interceptor.CloudflareAccessUrlConnection
import com.beeregg2001.komorebi.util.PlaybackHttpEntryPoint
import dagger.hilt.android.EntryPointAccessors
import com.beeregg2001.komorebi.common.safeRequestFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.floor

private const val TAG = "SceneSearchOverlay"

internal data class TileBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal fun tileBoundsFor(
    col: Int,
    row: Int,
    tileWidth: Int,
    tileHeight: Int,
    sheetWidth: Int,
    sheetHeight: Int,
): TileBounds? {
    if (col < 0 || row < 0 || tileWidth <= 0 || tileHeight <= 0) return null
    val left = col.toLong() * tileWidth
    val top = row.toLong() * tileHeight
    if (left + tileWidth > sheetWidth || top + tileHeight > sheetHeight) return null
    return TileBounds(left.toInt(), top.toInt(), tileWidth, tileHeight)
}

internal data class TileSheetRegion(
    val sheet: Bitmap,
    val bounds: TileBounds,
)

class TileSheetLoader(
    private val context: Context,
    private val cloudflareAccessConfiguration: () -> CloudflareAccessConfiguration = {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PlaybackHttpEntryPoint::class.java,
        ).settingsRepository().cloudflareAccessConfiguration.value
    },
) {
    private var isReleased = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(2)
    private var fullSheetBitmap: Bitmap? = null
    private val sheetLoadingMutex = Mutex()

    fun release() {
        isReleased = true
        fullSheetBitmap?.recycle()
        fullSheetBitmap = null
    }

    internal suspend fun loadRegion(
        url: String,
        col: Int,
        row: Int,
        tileW: Int,
        tileH: Int,
    ): TileSheetRegion? {
        if (isReleased) return null

        return withContext(decodeDispatcher) {
            if (!isActive || isReleased) return@withContext null
            try {
                val sheet = getOrLoadFullSheet(url) ?: run {
                    Log.w(TAG, "[TileLoader] Failed to get or load full sheet!")
                    return@withContext null
                }

                val bounds = tileBoundsFor(col, row, tileW, tileH, sheet.width, sheet.height)
                if (bounds == null) {
                    Log.e(
                        TAG,
                        "[TileLoader] Out of bounds! Request: col=$col, row=$row, " +
                            "w=$tileW, h=$tileH / Sheet Size: ${sheet.width}x${sheet.height}"
                    )
                    return@withContext null
                }
                TileSheetRegion(sheet, bounds)
            } catch (e: Exception) {
                Log.e(TAG, "[TileLoader] Error loading tile region: col=$col, row=$row", e)
                null
            }
        }
    }

    private suspend fun getOrLoadFullSheet(url: String): Bitmap? {
        if (fullSheetBitmap != null && !fullSheetBitmap!!.isRecycled) return fullSheetBitmap
        return sheetLoadingMutex.withLock {
            if (fullSheetBitmap != null && !fullSheetBitmap!!.isRecycled) return@withLock fullSheetBitmap
            if (isReleased) return@withLock null
            try {
                Log.i(TAG, "[TileLoader] Start loading full sheet from: $url")

                val fileName = hashString(url) + ".webp"
                val file = File(context.cacheDir, fileName)

                if (!file.exists() || file.length() == 0L) {
                    Log.i(
                        TAG,
                        "[TileLoader] Downloading sheet to local cache file: ${file.absolutePath}"
                    )
                    withContext(Dispatchers.IO) {
                        CloudflareAccessUrlConnection.open(
                            initialUrl = url,
                            configuration = cloudflareAccessConfiguration,
                        ).inputStream.use { input ->
                            FileOutputStream(file).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    Log.i(TAG, "[TileLoader] Download complete. File size: ${file.length()} bytes")
                } else {
                    Log.i(
                        TAG,
                        "[TileLoader] Found sheet in local cache file. Size: ${file.length()} bytes"
                    )
                }

                val options = BitmapFactory.Options()
                    .apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)

                if (bitmap != null) {
                    fullSheetBitmap = bitmap
                    Log.i(
                        TAG,
                        "[TileLoader] Successfully decoded sheet. Size: ${bitmap.width}x${bitmap.height}"
                    )
                } else {
                    Log.e(
                        TAG,
                        "[TileLoader] Failed to decode image file! (BitmapFactory returned null)"
                    )
                }

                bitmap
            } catch (e: Exception) {
                Log.e(TAG, "[TileLoader] Exception during sheet download or decoding", e)
                null
            }
        }
    }

    private fun hashString(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

@Composable
internal fun TileSheetRegionImage(
    region: TileSheetRegion,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val image = remember(region.sheet) { region.sheet.asImageBitmap() }
    val description = contentDescription
    val accessibleModifier = if (description == null) modifier else {
        modifier.semantics { this.contentDescription = description }
    }
    Canvas(modifier = accessibleModifier) {
        drawImage(
            image = image,
            srcOffset = IntOffset(region.bounds.left, region.bounds.top),
            srcSize = IntSize(region.bounds.width, region.bounds.height),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SceneSearchOverlay(
    program: RecordedProgram,
    tiledThumbnailUrl: String?, // ★ ViewModelから受け取るように変更
    currentPositionMs: Long,
    onSeekRequested: (Long) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val loader = remember { TileSheetLoader(context) }

    DisposableEffect(Unit) { onDispose { loader.release() } }

    val tileInfo = program.recordedVideo.thumbnailInfo?.tile

    // ★ ログ仕込み: UI層で認識しているタイル情報
    LaunchedEffect(tileInfo) {
        Log.i(TAG, "[SceneSearch] Overlay opened. TileInfo: $tileInfo, URL: $tiledThumbnailUrl")
    }

    val tileColumns = tileInfo?.columnCount ?: 1
    val tileInterval = tileInfo?.intervalSec ?: 10.0
    val tileWidth = tileInfo?.tileWidth ?: 320
    val tileHeight = tileInfo?.tileHeight ?: 180

    val intervals = VideoPlayerConstants.SEARCH_INTERVALS
    var intervalIndex by remember { mutableIntStateOf(1) }
    val currentInterval = intervals[intervalIndex]

    val durationMs = (program.recordedVideo.duration * 1000).toLong()

    var focusedTime by remember { mutableLongStateOf(currentPositionMs / 1000) }

    val timePoints = remember(currentInterval, durationMs) {
        val totalSec = durationMs / 1000
        (0..totalSec step currentInterval.toLong()).toList()
    }

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    val targetIndex = remember(currentInterval) {
        timePoints.indexOfFirst { it >= focusedTime }.coerceAtLeast(0)
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val itemWidthPx = with(density) { 224.dp.toPx() }
    val centerOffset = (-(screenWidthPx / 2) + (itemWidthPx / 2)).toInt()

    LaunchedEffect(targetIndex) {
        listState.scrollToItem(targetIndex, centerOffset)
        delay(150)
        focusRequester.safeRequestFocus(TAG)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.85f))))
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (intervalIndex < intervals.lastIndex) intervalIndex++; true
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (intervalIndex > 0) intervalIndex--; true
                    }

                    KeyEvent.KEYCODE_BACK -> {
                        onClose(); true
                    }

                    else -> false
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (currentInterval < 60) "${currentInterval}秒間隔" else "${currentInterval / 60}分間隔",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(126.dp)
            ) {
                itemsIndexed(timePoints) { index, time ->
                    TiledThumbnailItem(
                        time = time,
                        imageUrl = tiledThumbnailUrl ?: "", // ★ 変更
                        loader = loader,
                        tileColumns = tileColumns,
                        tileInterval = tileInterval,
                        tileWidth = tileWidth,
                        tileHeight = tileHeight,
                        onClick = { onSeekRequested(time * 1000) },
                        onFocused = { focusedTime = time },
                        modifier = if (index == targetIndex) Modifier.focusRequester(focusRequester) else Modifier
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 48.dp, end = 48.dp)
            ) {
                val screenWidth = LocalConfiguration.current.screenWidthDp.dp
                Row(
                    modifier = Modifier
                        .width(screenWidth / 3)
                        .align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "00:00",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(0.7f)
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                    ) {
                        val progress =
                            if (durationMs > 0) focusedTime.toFloat() / (durationMs / 1000).toFloat() else 0f
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(Color.White, RoundedCornerShape(2.dp))
                        )
                    }

                    Text(
                        text = formatSecondsToTime(durationMs / 1000),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(0.7f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TiledThumbnailItem(
    time: Long,
    imageUrl: String,
    loader: TileSheetLoader,
    tileColumns: Int,
    tileInterval: Double,
    tileWidth: Int,
    tileHeight: Int,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    itemWidth: androidx.compose.ui.unit.Dp = 224.dp,
    itemHeight: androidx.compose.ui.unit.Dp = 126.dp,
    imageTimeOffsetSec: Long = 0L,
    overlayContent: @Composable BoxScope.() -> Unit = {}
) {
    var region by remember { mutableStateOf<TileSheetRegion?>(null) }

    val fetchTime = time + imageTimeOffsetSec
    val tileIndex = floor(fetchTime / tileInterval).toInt()
    val col = tileIndex % tileColumns
    val row = tileIndex / tileColumns

    LaunchedEffect(imageUrl, col, row) {
        if (imageUrl.isBlank()) {
            Log.w(TAG, "[TiledItem] Image URL is blank. Cannot load thumbnail for time: $fetchTime")
            return@LaunchedEffect
        }
        delay(50)
        if (isActive) {
            val result = loader.loadRegion(imageUrl, col, row, tileWidth, tileHeight)
            if (result != null && isActive) {
                region = result
            } else {
                Log.w(
                    TAG,
                    "[TiledItem] loadTile returned null for time: $fetchTime (col=$col, row=$row)"
                )
            }
        }
    }

    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(0.1f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        ),
        modifier = modifier
            .width(itemWidth)
            .height(itemHeight)
            .onFocusChanged { if (it.isFocused) onFocused() }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (region != null) {
                TileSheetRegionImage(
                    region = region!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // サムネイルがない場合のフォールバック表示 (グレー背景)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.DarkGray)
                )
            }

            overlayContent()

            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .background(Color.Black.copy(0.7f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = formatSecondsToTime(time),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KeyframeGridOverlay(
    program: RecordedProgram,
    tiledThumbnailUrl: String?,
    currentPositionMs: Long,
    onSeekRequested: (Long) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val loader = remember { TileSheetLoader(context) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) { onDispose { loader.release() } }

    val tileInfo = program.recordedVideo.thumbnailInfo?.tile
    val tileColumns = tileInfo?.columnCount ?: 1
    val tileInterval = tileInfo?.intervalSec ?: 10.0
    val tileWidth = tileInfo?.tileWidth ?: 320
    val tileHeight = tileInfo?.tileHeight ?: 180

    val totalSec = program.recordedVideo.duration.toLong().coerceAtLeast(0L)
    val itemCount = remember(totalSec, tileInterval) {
        if (totalSec <= 0L || tileInterval <= 0.0) 1
        else (floor(totalSec / tileInterval).toInt() + 1).coerceAtLeast(1)
    }
    val initialIndex = remember(currentPositionMs, tileInterval, itemCount) {
        if (tileInterval <= 0.0) 0
        else floor((currentPositionMs / 1000.0) / tileInterval)
            .toInt()
            .coerceIn(0, itemCount - 1)
    }

    val gridState = rememberLazyGridState()
    val focusRequester = remember { FocusRequester() }
    var requestedFocusIndex by remember { mutableIntStateOf(initialIndex) }
    var focusedIndex by remember { mutableIntStateOf(initialIndex) }
    val focusedTime = remember(focusedIndex, tileInterval, totalSec) {
        floor(focusedIndex * tileInterval).toLong().coerceIn(0L, totalSec)
    }

    fun requestGridFocus(index: Int) {
        val target = index.coerceIn(0, itemCount - 1)
        requestedFocusIndex = target
        focusedIndex = target
        scope.launch {
            gridState.animateScrollToItem((target - 4).coerceAtLeast(0))
            delay(80)
            focusRequester.safeRequestFocus(TAG)
        }
    }

    LaunchedEffect(Unit) {
        gridState.scrollToItem((initialIndex - 4).coerceAtLeast(0))
        delay(150)
        focusRequester.safeRequestFocus(TAG)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.96f))
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        onClose()
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (focusedIndex < 4) {
                            onClose()
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        requestGridFocus(focusedIndex + 16)
                        true
                    }

                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        requestGridFocus(focusedIndex - 16)
                        true
                    }

                    else -> false
                }
            }
    ) {
        val columns = 4
        val horizontalPadding = 80.dp
        val spacing = 12.dp
        val itemWidth = (maxWidth - horizontalPadding * 2 - spacing * (columns - 1)) / columns
        val itemHeight = itemWidth * 9f / 16f
        val gridHeight = itemHeight * 4 + spacing * 3

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = "${formatSecondsToTime(focusedTime)} / ${formatSecondsToTime(totalSec)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Bold
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalArrangement = Arrangement.spacedBy(spacing),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight)
            ) {
                repeat(itemCount) { index ->
                    item(key = index) {
                        val time = floor(index * tileInterval).toLong().coerceIn(0L, totalSec)
                        TiledThumbnailItem(
                            time = time,
                            imageUrl = tiledThumbnailUrl ?: "",
                            loader = loader,
                            tileColumns = tileColumns,
                            tileInterval = tileInterval,
                            tileWidth = tileWidth,
                            tileHeight = tileHeight,
                            itemWidth = itemWidth,
                            itemHeight = itemHeight,
                            onClick = { onSeekRequested(time * 1000) },
                            onFocused = { focusedIndex = index },
                            modifier = if (index == requestedFocusIndex) {
                                Modifier.focusRequester(focusRequester)
                            } else {
                                Modifier
                            }
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(2.dp))
            ) {
                val progress = if (totalSec > 0L) focusedTime.toFloat() / totalSec.toFloat() else 0f
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color.White, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

private fun formatSecondsToTime(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChapterListOverlay(
    program: RecordedProgram,
    chapters: List<ChapterInfo>, // ★ ViewModelから受け取るように変更
    tiledThumbnailUrl: String?,  // ★ ViewModelから受け取るように変更
    currentPositionMs: Long,
    onSeekRequested: (Long) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val loader = remember { TileSheetLoader(context) }

    DisposableEffect(Unit) { onDispose { loader.release() } }

    val tileInfo = program.recordedVideo.thumbnailInfo?.tile

    // ★ ログ仕込み: UI層で認識しているタイル情報
    LaunchedEffect(tileInfo) {
        Log.i(TAG, "[ChapterList] Overlay opened. TileInfo: $tileInfo, URL: $tiledThumbnailUrl")
    }

    val tileColumns = tileInfo?.columnCount ?: 1
    val tileInterval = tileInfo?.intervalSec ?: 10.0
    val tileWidth = tileInfo?.tileWidth ?: 320
    val tileHeight = tileInfo?.tileHeight ?: 180

    val durationMs = (program.recordedVideo.duration * 1000).toLong()

    var focusedTime by remember { mutableLongStateOf(currentPositionMs / 1000) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    val targetIndex = remember(chapters) {
        val idx =
            chapters.indexOfFirst { it.startTimeMs <= currentPositionMs && currentPositionMs < it.endTimeMs }
        if (idx != -1) idx else 0
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val itemWidthPx = with(density) { 224.dp.toPx() }
    val centerOffset = (-(screenWidthPx / 2) + (itemWidthPx / 2)).toInt()

    LaunchedEffect(targetIndex) {
        if (chapters.isNotEmpty()) {
            listState.scrollToItem(targetIndex, centerOffset)
            delay(150)
            focusRequester.safeRequestFocus(TAG)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.85f))))
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP -> {
                        onClose()
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        true
                    }

                    else -> false
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "チャプター一覧",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(126.dp)
            ) {
                itemsIndexed(chapters) { index, chapter ->
                    val tagColor = if (chapter.isCm) Color(0xFFE53935) else Color(0xFF1E88E5)
                    val tagText = if (chapter.isCm) "CM" else "本編"

                    val lengthSec = (chapter.endTimeMs - chapter.startTimeMs) / 1000
                    val m = lengthSec / 60
                    val s = lengthSec % 60
                    val lengthText = if (m > 0) "${m}分${s}秒" else "${s}秒"

                    val offsetSec = minOf(5L, maxOf(0L, lengthSec / 2))

                    Box(
                        modifier = if (index == targetIndex) Modifier.focusRequester(focusRequester) else Modifier
                    ) {
                        TiledThumbnailItem(
                            time = chapter.startTimeMs / 1000,
                            imageUrl = tiledThumbnailUrl ?: "", // ★ 変更
                            loader = loader,
                            tileColumns = tileColumns,
                            tileInterval = tileInterval,
                            tileWidth = tileWidth,
                            tileHeight = tileHeight,
                            imageTimeOffsetSec = offsetSec,
                            onClick = { onSeekRequested(chapter.startTimeMs) },
                            onFocused = { focusedTime = chapter.startTimeMs / 1000 },
                            overlayContent = {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .background(tagColor, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = tagText,
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                Color.Black.copy(0.7f),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = lengthText,
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 48.dp, end = 48.dp)
            ) {
                val screenWidth = LocalConfiguration.current.screenWidthDp.dp
                Row(
                    modifier = Modifier
                        .width(screenWidth / 3)
                        .align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "00:00",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(0.7f)
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                    ) {
                        val progress =
                            if (durationMs > 0) focusedTime.toFloat() / (durationMs / 1000).toFloat() else 0f
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(Color.White, RoundedCornerShape(2.dp))
                        )
                    }

                    Text(
                        text = formatSecondsToTime(durationMs / 1000),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(0.7f)
                    )
                }
            }
        }
    }
}

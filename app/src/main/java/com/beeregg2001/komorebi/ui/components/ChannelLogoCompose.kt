package com.beeregg2001.komorebi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.data.repository.ChannelLogoCache
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChannelLogoCacheEntryPoint {
    fun channelLogoCache(): ChannelLogoCache
}

/**
 * Hilt の singleton [ChannelLogoCache] を Composable から参照する。
 * Hilt が無い環境 (Compose 単体テスト等) では null を返し、呼び出し側は従来動作へフォールバックする。
 */
@Composable
fun rememberChannelLogoCache(): ChannelLogoCache? {
    val applicationContext = LocalContext.current.applicationContext
    return remember(applicationContext) {
        runCatching {
            EntryPointAccessors
                .fromApplication(applicationContext, ChannelLogoCacheEntryPoint::class.java)
                .channelLogoCache()
        }.getOrNull()
    }
}

/**
 * 局ロゴ URL を返す。キャッシュ済みなら初回フレームから同期的に返し、未解決なら [getLogoUrl] で解決する。
 *
 * `"" -> URL` の2段階再コンポーズ（ロゴが一瞬消えてから出る「ちらつき」）を避けるためのヘルパー。
 */
@Composable
fun rememberChannelLogoUrl(
    channelId: String,
    getLogoUrl: suspend (String) -> String,
): String {
    val cache = rememberChannelLogoCache()
    if (cache != null) {
        return rememberCachedChannelLogoUrl(cache, channelId, getLogoUrl)
    }

    // Hilt が無い環境向けフォールバック: 従来どおり非同期取得のみ
    var logoUrl by remember(channelId) { mutableStateOf("") }
    LaunchedEffect(channelId) {
        val resolved = getLogoUrl(channelId)
        if (resolved.isNotBlank()) logoUrl = resolved
    }
    return logoUrl
}

@Composable
private fun rememberCachedChannelLogoUrl(
    cache: ChannelLogoCache,
    channelId: String,
    getLogoUrl: suspend (String) -> String,
): String {
    val logoUrls by cache.channelLogoUrls.collectAsState()
    var logoUrl by remember(channelId) { mutableStateOf(cache.peek(channelId).orEmpty()) }

    LaunchedEffect(channelId, logoUrls) {
        val cached = logoUrls[channelId]
        when {
            !cached.isNullOrBlank() -> logoUrl = cached
            logoUrl.isBlank() -> {
                val resolved = getLogoUrl(channelId)
                if (resolved.isNotBlank()) logoUrl = resolved
            }
        }
    }
    return logoUrl
}

/**
 * チャンネルロゴのバッジ表示を統一する共有コンポーザブル。
 * EPG 検索行 / キーワード条件カード / 予約カードの 3 か所の描き分けを 1 つにまとめる。
 *
 * - 背景: ハードコード白ではなくテーマ由来の薄色 (textPrimary の 10%)
 * - ContentScale: 16:9 スロットに正方形ロゴ (KonomiTV 系で生成) を収める場合は Crop。
 *   透過ロゴ (Mirakurun 系) を全体表示したい場合は呼び出し側から crop = false を渡す。
 * - フォールバック: URL 未取得のときは Tv アイコン
 *
 * サイズはスロットごとに呼び出し側が指定する (スタイルだけ統一)。
 */
@Composable
fun ChannelLogoBadge(
    logoUrl: String,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    crop: Boolean = true,
    model: Any? = logoUrl,
) {
    val colors = KomorebiTheme.colors
    val imageLoader = rememberChannelLogoImageLoader()
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .clipToBounds()
            .background(colors.textPrimary.copy(alpha = 0.1f), RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (logoUrl.isNotBlank()) {
            AsyncImage(
                imageLoader = imageLoader,
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit
            )
        } else {
            Icon(
                imageVector = Icons.Default.Tv,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

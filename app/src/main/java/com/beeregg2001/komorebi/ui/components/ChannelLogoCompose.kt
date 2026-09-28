package com.beeregg2001.komorebi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.beeregg2001.komorebi.data.repository.ChannelLogoCache
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

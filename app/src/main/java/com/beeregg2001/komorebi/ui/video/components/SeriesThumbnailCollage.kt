package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.viewmodel.SeriesInfo

@Composable
fun SeriesThumbnailCollage(
    series: SeriesInfo,
    backendType: String,
    konomiIp: String,
    konomiPort: String,
    modifier: Modifier = Modifier,
    imageAlpha: Float = 1f,
    allowNetworkImages: Boolean = true,
) {
    val urls = remember(series, backendType, konomiIp, konomiPort) {
        if (series.thumbnailVideoIds.isNotEmpty()) {
            series.thumbnailVideoIds.take(3).map {
                UrlBuilder.getThumbnailUrl(backendType, konomiIp, konomiPort, it.toString())
            }
        } else {
            listOf(
                series.directThumbnailUrl
                    ?: series.apiThumbnailUrl
                    ?: UrlBuilder.getThumbnailUrl(
                        backendType,
                        konomiIp,
                        konomiPort,
                        series.representativeVideoId.toString(),
                    )
            )
        }
    }
    val context = LocalContext.current

    Row(modifier = modifier) {
        urls.forEach { url ->
            val request = remember(url, allowNetworkImages) {
                ImageRequest.Builder(context)
                    .data(url)
                    .memoryCacheKey(url)
                    .diskCacheKey(url)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(
                        if (allowNetworkImages) CachePolicy.ENABLED else CachePolicy.DISABLED
                    )
                    .crossfade(true)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .alpha(imageAlpha),
            )
        }
    }
}

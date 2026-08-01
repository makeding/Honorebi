package com.beeregg2001.komorebi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.Coil
import coil.ImageLoader
import com.beeregg2001.komorebi.MainApplication

@Composable
fun rememberChannelLogoImageLoader(): ImageLoader {
    val applicationContext = LocalContext.current.applicationContext
    return remember(applicationContext) {
        (applicationContext as? MainApplication)?.channelLogoImageLoader
            ?: Coil.imageLoader(applicationContext)
    }
}

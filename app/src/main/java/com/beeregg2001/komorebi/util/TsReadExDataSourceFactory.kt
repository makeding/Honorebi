package com.beeregg2001.komorebi.util

import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.NativeLib
import com.beeregg2001.komorebi.data.api.interceptor.CloudflareAccessConfiguration

@UnstableApi
class TsReadExDataSourceFactory(
    private val nativeLib: NativeLib,
    initialArgs: Array<String> // 名前を変更

) : androidx.media3.datasource.DataSource.Factory {

    // 外部から書き換え可能なように var にする
    var tsArgs: Array<String> = initialArgs
    var cloudflareAccessConfiguration: () -> CloudflareAccessConfiguration = { CloudflareAccessConfiguration() }

    override fun createDataSource(): androidx.media3.datasource.DataSource {
        return TsReadExDataSource(
            nativeLib = nativeLib,
            tsArgs = tsArgs,
            cloudflareAccessConfiguration = cloudflareAccessConfiguration,
        )
    }
}

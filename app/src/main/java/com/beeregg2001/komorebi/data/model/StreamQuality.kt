package com.beeregg2001.komorebi.data.model

/**
 * プロジェクト全体で共通の画質定義（動的リスト対応のためData Classに変更）
 */
data class StreamQuality(
    val label: String,
    val value: String,
    val isRawTs: Boolean = false, // 生TS(TS-Live!)かどうかを判定するフラグ
    val isRawMmts: Boolean = false,
    val videoPacketId: Int? = null
) {
    companion object {
        const val RAW_MMTS_PRIMARY_VALUE = "raw-mmts"
        const val RAW_MMTS_SECONDARY_VALUE = "raw-mmts-secondary"
        const val ORIGINAL_MPEG_TS_VALUE = "original-mpegts-hwdi"

        fun originalMpegTsHardwareDi(): StreamQuality = StreamQuality(
            label = "オリジナル（ハードウェア DI）",
            value = ORIGINAL_MPEG_TS_VALUE,
            isRawTs = true
        )

        fun recordedRawMmts(): StreamQuality = StreamQuality(
            label = "TLV パススルー",
            value = RAW_MMTS_PRIMARY_VALUE,
            isRawMmts = true
        )

        // KonomiTVなどのバックエンド用のデフォルト（固定）リスト
        val DEFAULT_QUALITIES = listOf(
            StreamQuality("1080p (60fps)", "1080p-60fps"),
            StreamQuality("1080p", "1080p"),
            StreamQuality("810p", "810p"),
            StreamQuality("720p", "720p"),
            StreamQuality("540p", "540p"),
            StreamQuality("480p", "480p"),
            StreamQuality("360p", "360p"),
            StreamQuality("240p", "240p")
        )

        fun rawMmtsQualities(channel: Channel): List<StreamQuality> {
            val isBs8k = channel.networkId == 11L && channel.serviceId == 102L
            val primary = StreamQuality(
                label = "TLV パススルー",
                value = RAW_MMTS_PRIMARY_VALUE,
                isRawMmts = true,
                videoPacketId = if (isBs8k) 0xf100 else null
            )
            val hasRainService = channel.networkId == 11L && channel.serviceId in listOf(101L, 102L)
            if (!hasRainService) return listOf(primary)

            return listOf(
                primary,
                StreamQuality(
                    label = "TLV パススルー（降雨放送）",
                    value = RAW_MMTS_SECONDARY_VALUE,
                    isRawMmts = true,
                    videoPacketId = if (isBs8k) 0xf101 else 0xf301
                )
            )
        }

        /**
         * 文字列から画質型を取得する（利用可能なリストから検索）
         */
        fun fromValue(
            value: String,
            availableList: List<StreamQuality> = DEFAULT_QUALITIES
        ): StreamQuality {
            return availableList.find { it.value == value }
                ?: availableList.firstOrNull()
                ?: DEFAULT_QUALITIES.first()
        }
    }
}

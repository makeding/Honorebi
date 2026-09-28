package com.beeregg2001.komorebi.ui.subtitle

import android.content.Context

/**
 * 字幕デコード (libaribcaption) に使う同梱フォント。
 *
 * アセットから noBackupFilesDir へ初回のみコピーし、native 側にはその絶対パスを渡す。
 * 設定値 (SubtitleFontFiles.FontFile.id) と 1:1 で対応する。
 */
object SubtitleFontFiles {
    const val DEFAULT_ID = "default"
    const val ARIB_ID = "arib"

    data class FontFile(
        val id: String,
        val assetPath: String,
        val installedFileName: String,
        val sizeBytes: Long,
    )

    val KosugiMaru = FontFile(
        id = DEFAULT_ID,
        assetPath = "fonts/kosugi_maru_regular.ttf",
        installedFileName = "caption-fonts/kosugi_maru_regular_4_002.ttf",
        sizeBytes = 3_565_692L,
    )

    // 丸ゴシック・ARIB 外字対応 (自家製 Rounded M+ 1m for ARIB)
    val RoundedMPlusArib = FontFile(
        id = ARIB_ID,
        assetPath = "fonts/rounded-mplus-1m-arib.ttf",
        installedFileName = "caption-fonts/rounded-mplus-1m-arib.ttf",
        sizeBytes = 5_484_340L,
    )

    val all: List<FontFile> = listOf(KosugiMaru, RoundedMPlusArib)

    fun fromId(id: String?): FontFile = all.firstOrNull { it.id == id } ?: KosugiMaru

    private val installLock = Any()

    /** 指定フォントを (初回のみ) ローカルへ展開し、native に渡せる絶対パスを返す。 */
    fun installFont(context: Context, id: String?): String = synchronized(installLock) {
        val font = fromId(id)
        val fontFile = context.applicationContext.noBackupFilesDir.resolve(font.installedFileName)
        if (fontFile.length() != font.sizeBytes) {
            val fontDirectory = checkNotNull(fontFile.parentFile)
            check(fontDirectory.isDirectory || fontDirectory.mkdirs()) {
                "Failed to create the caption font directory"
            }
            context.applicationContext.assets.open(font.assetPath).use { source ->
                fontFile.outputStream().use(source::copyTo)
            }
            check(fontFile.length() == font.sizeBytes) {
                "Failed to install the caption font"
            }
        }
        fontFile.absolutePath
    }
}

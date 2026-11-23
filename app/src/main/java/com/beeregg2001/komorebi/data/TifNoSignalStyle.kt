package com.beeregg2001.komorebi.data

enum class TifNoSignalStyle(
    val label: String,
    val description: String,
) {
    BANNER("標準バナー", "中央にシンプルなステータスバナーを表示"),
    DVD_BOUNCE("DVD風バウンス", "懐かしのロゴが画面端で跳ね返る演出"),
    HONOREBI_BOUNCE("Honorebi バウンス", "Honorebi ロゴが光りながらバウンス"),
    SCREENSAVER("スクリーンセーバー", "設定済みのスクリーンセーバーを表示"),
    ARIB_COLOR_BARS("ARIB カラーバー", "地デジ試験電波風の静止カラーバー"),
    ARIB_COLOR_BARS_SCROLL("ARIB スクロールバー", "深夜放送の試験電波風スクロールカラーバー"),
    ANALOG_STATIC("アナログ砂嵐", "レトロなCRTブラウン管の砂嵐とOSD"),
    SIGNAL_WAVE("シグナルウェーブ", "信号待機を示すオシロスコープ波形"),
    MINIMAL_CLOCK("ミニマル時計", "焼き付き防止シフト付きの省電力時計"),
    AURORA_AMBIENT("オーロラ", "深く穏やかに揺らぐアンビエント光彩"),
    DOT_MATRIX("ドットマトリクス", "LEDドット表示と呼吸ステータスランプ"),
    STARFIELD("スターフィールド", "漆黒の宇宙を漂うスターフィールド"),
    CRAYON_TITLE("クレヨンタイトル", "ポップな手描き風タイトルカード演出");

    companion object {
        fun fromPreference(value: String?): TifNoSignalStyle =
            entries.firstOrNull { it.name == value } ?: BANNER
    }
}

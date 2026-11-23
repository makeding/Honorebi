package com.beeregg2001.komorebi.data

enum class TifNoSignalStyle(
    val label: String,
    val description: String,
) {
    BANNER("標準バナー", "中央にシンプルなステータスバナーを表示"),
    DVD_BOUNCE("DVD風バウンス", "懐かしのロゴが画面端で跳ね返る演出"),
    HONOREBI_BOUNCE("Honorebi バウンス", "Honorebi ロゴが光りながらバウンス"),
    SCREENSAVER("スクリーンセーバー", "設定済みのスクリーンセーバーを表示"),
    ARIB_COLOR_BARS("ARIB カラーバー", "放送局テスト信号風の75%カラーバー"),
    ANALOG_STATIC("アナログ砂嵐", "レトロなCRTブラウン管の砂嵐とOSD"),
    SIGNAL_WAVE("シグナルウェーブ", "信号待機を示すオシロスコープ波形"),
    MINIMAL_CLOCK("ミニマル時計", "焼き付き防止シフト付きの省電力時計"),
    AURORA_AMBIENT("オーロラ", "深く穏やかに揺らぐアンビエント光彩"),
    DOT_MATRIX("ドットマトリクス", "LEDドット表示と呼吸ステータスランプ"),
    STARFIELD("スターフィールド", "漆黒の宇宙を漂うスターフィールド"),
    SIGNAL_RADAR("シグナルレーダー", "入力信号を探索する同心円レーダー");

    companion object {
        fun fromPreference(value: String?): TifNoSignalStyle =
            entries.firstOrNull { it.name == value } ?: BANNER
    }
}

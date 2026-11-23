package com.beeregg2001.komorebi.data

enum class TifNoSignalStyle(
    val label: String,
    val description: String,
) {
    BANNER("標準バナー", "中央にシンプルなステータスバナーを表示"),
    DVD_BOUNCE("DVD風バウンス", "懐かしのロゴが画面端で跳ね返る演出"),
    HONOREBI_BOUNCE("Honorebi バウンス", "Honorebi ロゴが光りながらバウンス"),
    SCREENSAVER("スクリーンセーバー", "設定済みのスクリーンセーバーを表示"),
    NOK_CALLSIGN("N〇K 風", "放送局コールサイン風（▲▼でカラー変更）"),
    ARIB_COLOR_BARS("ARIB カラーバー", "地デジ試験電波風の静止カラーバー"),
    ARIB_COLOR_BARS_SCROLL("ARIB スクロールバー", "深夜放送の試験電波風スクロールカラーバー"),
    ANALOG_STATIC("アナログ砂嵐", "レトロなCRTブラウン管の砂嵐とOSD"),
    SIGNAL_WAVE("シグナルウェーブ", "信号待機を示すオシロスコープ波形"),
    MINIMAL_CLOCK("ミニマル時計", "焼き付き防止シフト付きの省電力時計"),
    AURORA_AMBIENT("オーロラ", "深く穏やかに揺らぐアンビエント光彩"),
    DOT_MATRIX("ドットマトリクス", "LEDドット表示と呼吸ステータスランプ"),
    STARFIELD("スターフィールド", "漆黒の宇宙を漂うスターフィールド"),
    CRAYON_TITLE("クレヨンタイトル", "ポップな手描き風タイトルカード演出"),
    PRECURE_SPARKLE("プリキュア風", "キラキラ輝く魔法少女タイトルカード");

    companion object {
        fun fromPreference(value: String?): TifNoSignalStyle =
            entries.firstOrNull { it.name == value } ?: BANNER
    }
}

enum class TifNoSignalAccentColor(
    val label: String,
    val deepArgb: Long,
    val mainArgb: Long,
    val ribbonArgb: Long,
) {
    RANDOM("ランダム", 0xFFB51B0B, 0xFFC94121, 0xFFF29A86),
    CRIMSON("G（紅）", 0xFFB51B0B, 0xFFC94121, 0xFFF29A86),
    EMERALD("E（翠）", 0xFF0E6B47, 0xFF1A8A5E, 0xFF86DFB8),
    COBALT("BS（藍）", 0xFF0F3B82, 0xFF1D56B3, 0xFF8CB6F5),
    AMBER("山吹", 0xFF9C5206, 0xFFC97312, 0xFFF7C47C),
    VIOLET("藤", 0xFF4C1D7A, 0xFF6D2FA8, 0xFFC8A2F0),
    SLATE("墨", 0xFF181C24, 0xFF282F3D, 0xFF7D889E);

    companion object {
        val concreteEntries: List<TifNoSignalAccentColor> = entries.filter { it != RANDOM }

        fun fromPreference(value: String?): TifNoSignalAccentColor =
            entries.firstOrNull { it.name == value } ?: RANDOM
    }
}

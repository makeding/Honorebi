package com.beeregg2001.komorebi.data

enum class DataBroadcastingVirtualRemoteMode(
    val preferenceValue: String,
    val label: String,
) {
    AUTO("AUTO", "自動（未対応リモコン時に表示）"),
    ALWAYS("ALWAYS", "常に表示"),
    MANUAL("MANUAL", "手動のみ（戻る長押しで表示）");

    fun next(): DataBroadcastingVirtualRemoteMode = when (this) {
        AUTO -> ALWAYS
        ALWAYS -> MANUAL
        MANUAL -> AUTO
    }

    companion object {
        fun fromPreference(value: String?): DataBroadcastingVirtualRemoteMode = when (value) {
            ALWAYS.preferenceValue -> ALWAYS
            MANUAL.preferenceValue -> MANUAL
            else -> AUTO
        }
    }
}

data class DataBroadcastingPrefecture(
    val code: Int,
    val regionCode: Int,
    val label: String,
) {
    val preferenceValue: String get() = code.toString()
}

data class DataBroadcastingSettings(
    val allowExternalNetwork: Boolean = false,
    val zipcode: String = "",
    val prefectureCode: Int? = null,
    val virtualRemoteMode: DataBroadcastingVirtualRemoteMode = DataBroadcastingVirtualRemoteMode.AUTO,
    val preloadB24InBackground: Boolean = false,
) {
    val prefecture: DataBroadcastingPrefecture?
        get() = prefectureCode?.let { DataBroadcastingPrefectures.findByCode(it) }

    val regionCode: Int?
        get() = prefecture?.regionCode

    val formattedZipcode: String
        get() = if (zipcode.length == 7) "${zipcode.substring(0, 3)}-${zipcode.substring(3)}" else ""

    val displayZipcode: String
        get() = formattedZipcode.ifEmpty { "未設定" }

    val displayPrefecture: String
        get() = prefecture?.label ?: "未設定"

    companion object {
        private val SEVEN_DIGITS = Regex("^\\d{7}$")

        /**
         * Normalizes user-entered Japanese postal codes.
         * Accepts blank (clears setting), `000-0000`, `0000000`, full-width digits/hyphens.
         * Returns null when the input is invalid.
         */
        fun normalizeZipcode(raw: String): String? {
            val normalized = buildString {
                for (ch in raw.trim()) {
                    when {
                        ch in '0'..'9' -> append(ch)
                        ch in '０'..'９' -> append(('0'.code + (ch.code - '０'.code)).toChar())
                        ch == '-' || ch == 'ー' || ch == '－' || ch == '‐' || ch.isWhitespace() -> Unit
                        else -> return null
                    }
                }
            }
            if (normalized.isEmpty()) return ""
            return if (SEVEN_DIGITS.matches(normalized)) normalized else null
        }
    }
}

/**
 * ARIB STD-B24 / STD-B62 prefecture and 12-bit region code table.
 */
object DataBroadcastingPrefectures {
    const val UNSET_PREFERENCE_VALUE = ""

    val ALL: List<DataBroadcastingPrefecture> = listOf(
        DataBroadcastingPrefecture(2, 0b000101101011, "西北海道"),
        DataBroadcastingPrefecture(1, 0b000101101011, "東北海道"),
        DataBroadcastingPrefecture(3, 0b010001100111, "青森県"),
        DataBroadcastingPrefecture(4, 0b010111010100, "岩手県"),
        DataBroadcastingPrefecture(5, 0b011101011000, "宮城県"),
        DataBroadcastingPrefecture(6, 0b101011000110, "秋田県"),
        DataBroadcastingPrefecture(7, 0b111001001100, "山形県"),
        DataBroadcastingPrefecture(8, 0b000110101110, "福島県"),
        DataBroadcastingPrefecture(9, 0b110001101001, "茨城県"),
        DataBroadcastingPrefecture(10, 0b111000111000, "栃木県"),
        DataBroadcastingPrefecture(11, 0b100110001011, "群馬県"),
        DataBroadcastingPrefecture(12, 0b011001001011, "埼玉県"),
        DataBroadcastingPrefecture(13, 0b000111000111, "千葉県"),
        DataBroadcastingPrefecture(14, 0b101010101100, "東京都 (島部を除く)"),
        DataBroadcastingPrefecture(49, 0b101010101100, "東京都島部 (伊豆・小笠原諸島)"),
        DataBroadcastingPrefecture(15, 0b010101101100, "神奈川県"),
        DataBroadcastingPrefecture(16, 0b010011001110, "新潟県"),
        DataBroadcastingPrefecture(17, 0b010100111001, "富山県"),
        DataBroadcastingPrefecture(18, 0b011010100110, "石川県"),
        DataBroadcastingPrefecture(19, 0b100100101101, "福井県"),
        DataBroadcastingPrefecture(20, 0b110101001010, "山梨県"),
        DataBroadcastingPrefecture(21, 0b100111010010, "長野県"),
        DataBroadcastingPrefecture(22, 0b101001100101, "岐阜県"),
        DataBroadcastingPrefecture(23, 0b101001011010, "静岡県"),
        DataBroadcastingPrefecture(24, 0b100101100110, "愛知県"),
        DataBroadcastingPrefecture(25, 0b001011011100, "三重県"),
        DataBroadcastingPrefecture(26, 0b110011100100, "滋賀県"),
        DataBroadcastingPrefecture(27, 0b010110011010, "京都府"),
        DataBroadcastingPrefecture(28, 0b110010110010, "大阪府"),
        DataBroadcastingPrefecture(29, 0b011001110100, "兵庫県"),
        DataBroadcastingPrefecture(30, 0b101010010011, "奈良県"),
        DataBroadcastingPrefecture(31, 0b001110010110, "和歌山県"),
        DataBroadcastingPrefecture(32, 0b110100100011, "鳥取県"),
        DataBroadcastingPrefecture(33, 0b001100011011, "島根県"),
        DataBroadcastingPrefecture(34, 0b001010110101, "岡山県"),
        DataBroadcastingPrefecture(35, 0b101100110001, "広島県"),
        DataBroadcastingPrefecture(36, 0b101110011000, "山口県"),
        DataBroadcastingPrefecture(37, 0b111001100010, "徳島県"),
        DataBroadcastingPrefecture(38, 0b100110110100, "香川県"),
        DataBroadcastingPrefecture(39, 0b000110011101, "愛媛県"),
        DataBroadcastingPrefecture(40, 0b001011100011, "高知県"),
        DataBroadcastingPrefecture(41, 0b011000101101, "福岡県"),
        DataBroadcastingPrefecture(42, 0b100101011001, "佐賀県"),
        DataBroadcastingPrefecture(43, 0b101000101011, "長崎県"),
        DataBroadcastingPrefecture(44, 0b100010100111, "熊本県"),
        DataBroadcastingPrefecture(45, 0b110010001101, "大分県"),
        DataBroadcastingPrefecture(46, 0b110100011100, "宮崎県"),
        DataBroadcastingPrefecture(47, 0b110101000101, "鹿児島県 (南西諸島を除く)"),
        DataBroadcastingPrefecture(50, 0b110101000101, "鹿児島県島部 (南西諸島の鹿児島県域)"),
        DataBroadcastingPrefecture(48, 0b001101110010, "沖縄県"),
    )

    private val byCode: Map<Int, DataBroadcastingPrefecture> = ALL.associateBy { it.code }

    fun findByCode(code: Int): DataBroadcastingPrefecture? = byCode[code]

    fun selectionOptions(): List<Pair<String, String>> =
        listOf("未設定" to UNSET_PREFERENCE_VALUE) + ALL.map { it.label to it.preferenceValue }
}

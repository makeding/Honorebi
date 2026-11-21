package com.beeregg2001.komorebi.ui.theme

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import com.beeregg2001.komorebi.common.AppStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalTime

// ★ 時間帯の定義
enum class TimeZone { MORNING, DAY, EVENING, NIGHT }

@RequiresApi(Build.VERSION_CODES.O)
fun getTimeZone(time: LocalTime): TimeZone {
    val hour = time.hour
    return when {
        hour in 5..8 -> TimeZone.MORNING  // 5時〜8時: 朝
        hour in 9..15 -> TimeZone.DAY     // 9時〜15時: 昼
        hour in 16..18 -> TimeZone.EVENING // 16時〜18時: 夕方
        else -> TimeZone.NIGHT            // 19時〜4時: 夜
    }
}

@RequiresApi(Build.VERSION_CODES.O)
fun getDynamicThemeLabel(theme: AppTheme, time: LocalTime = LocalTime.now()): String {
    val timeZone = getTimeZone(time)
    return when (theme) {
        AppTheme.KOMOREBI -> when (timeZone) {
            TimeZone.MORNING -> AppStrings.THEME_NAME_MORNING_KOMOREBI
            TimeZone.DAY -> AppStrings.THEME_NAME_DAY_KOMOREBI
            TimeZone.EVENING -> AppStrings.THEME_NAME_EVENING_KOMOREBI
            TimeZone.NIGHT -> AppStrings.THEME_NAME_NIGHT_KOMOREBI
        }
        AppTheme.KYLE -> when (timeZone) {
            TimeZone.MORNING -> AppStrings.THEME_NAME_MORNING_KYLE
            TimeZone.DAY -> AppStrings.THEME_NAME_DAY_KYLE
            TimeZone.EVENING -> AppStrings.THEME_NAME_EVENING_KYLE
            TimeZone.NIGHT -> AppStrings.THEME_NAME_NIGHT_KYLE
        }
        else -> theme.label
    }
}

// 1. テーマの種類を定義
enum class AppTheme(val label: String) {
    MONOTONE("モノトーン"),
    HIGHTONE("ハイトーン"),
    WINTER_DARK("冬 (聖夜) - Winter Dark"),
    WINTER_LIGHT("冬 (祝祭) - Winter Light"),
    SPRING("春 (夜桜) - Spring Dark"),
    SPRING_LIGHT("春 (昼桜) - Spring Light"),
    SUMMER("夏 (夜海) - Summer Dark"),
    SUMMER_LIGHT("夏 (青空) - Summer Light"),
    AUTUMN("秋 (夜長) - Autumn Dark"),
    AUTUMN_LIGHT("秋 (紅葉) - Autumn Light"),

    // EPGStation 風の青系テーマ
    EPG_BLUE("ブルー (EPGStation風) - Blue Dark"),
    EPG_BLUE_LIGHT("ブルー (EPGStation風) - Blue Light"),

    // ★ 統合された時間連動テーマ
    KOMOREBI("時間連動 (木漏れ日)"),
    KYLE("時間連動 (海辺のカイル)"),

    // (互換性維持用)
    KOMOREBI_DAY("昼 (木漏れ日)"), KOMOREBI_NIGHT("夜 (月光)"),
    KYLE_DAY("昼 (海辺のカイル)"), KYLE_NIGHT("夜 (深海のカイル)")
}

private val DefaultDarkOnAccentInk = Color(0xFF0B0E14)

private fun wcagContrastRatio(foreground: Color, background: Color): Float {
    val lighter = maxOf(foreground.luminance(), background.luminance())
    val darker = minOf(foreground.luminance(), background.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun computeOnAccent(accent: Color): Color =
    if (wcagContrastRatio(DefaultDarkOnAccentInk, accent) >= wcagContrastRatio(Color.White, accent)) {
        DefaultDarkOnAccentInk
    } else {
        Color.White
    }

private fun defaultSurface2(surface: Color, isDark: Boolean): Color =
    if (isDark) {
        Color(
            red = (surface.red + 0.045f).coerceAtMost(1f),
            green = (surface.green + 0.048f).coerceAtMost(1f),
            blue = (surface.blue + 0.055f).coerceAtMost(1f),
            alpha = surface.alpha,
        )
    } else {
        Color(0xFFE8EEF5)
    }

private fun defaultOverlay(isDark: Boolean): Color =
    if (isDark) Color(0xEB0E1118) else Color(0xF5FFFFFF)

private fun defaultTextMuted(textSecondary: Color, background: Color): Color {
    val candidate = lerp(background, textSecondary, 0.72f)
    if (wcagContrastRatio(candidate, background) >= 3.5f) return candidate
    val stepped = lerp(background, textSecondary, 0.86f)
    return if (wcagContrastRatio(stepped, background) >= 3.5f) stepped else textSecondary
}

data class KomorebiColors(
    val background: Color,
    val surface: Color,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val isDark: Boolean = true,
    val surface2: Color = defaultSurface2(surface, isDark),
    val overlay: Color = defaultOverlay(isDark),
    val accentSoft: Color = accent.copy(alpha = if (isDark) 0.16f else 0.14f),
    val onAccent: Color = computeOnAccent(accent),
    val textMuted: Color = defaultTextMuted(textSecondary, background),
    val borderSubtle: Color = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.10f),
    val danger: Color = if (isDark) Color(0xFFF87171) else Color(0xFFD32F2F),
    val recording: Color = StatusRecordingColor,
    val cmRed: Color = Color(0xFFE11D48),
) {
    /** L1 flat surface alias */
    val surface1: Color get() = surface

    /** L2 elevated / focused surface alias */
    val surfaceElevated: Color get() = surface2

    /** L3 high-opacity flat overlay surface alias */
    val surfaceOverlay: Color get() = overlay
}

// --- 既存パレット (4段フラットサーフェス対応) ---
val MonotonePalette = KomorebiColors(
    background = Color(0xFF0C0E12),
    surface = Color(0xFF151821),
    surface2 = Color(0xFF1E2330),
    accent = Color.White,
    textPrimary = Color.White,
    textSecondary = Color(0xFF9AA4B2),
    textMuted = Color(0xFF687387),
    isDark = true
)
val HightonePalette = KomorebiColors(
    background = Color(0xFFF0F3F5),
    surface = Color(0xFFFFFFFF),
    accent = Color(0xFF00A0E9),
    textPrimary = Color(0xFF222222),
    textSecondary = Color(0xFF666666),
    isDark = false
)
val WinterDarkPalette = KomorebiColors(
    background = Color(0xFF0F2016),
    surface = Color(0xFF1A2B20),
    accent = Color(0xFFCF3C3C),
    textPrimary = Color(0xFFF0F4F1),
    textSecondary = Color(0xFF8A9AB0),
    isDark = true
)
val WinterLightPalette = KomorebiColors(
    background = Color(0xFFFAF7F2),
    surface = Color(0xFFFFFFFF),
    accent = Color(0xFFD32F2F),
    textPrimary = Color(0xFF2B1F1F),
    textSecondary = Color(0xFF7A6868),
    isDark = false
)
val SpringDarkPalette = KomorebiColors(
    background = Color(0xFF1F1216),
    surface = Color(0xFF2E1C22),
    accent = Color(0xFFF48FB1),
    textPrimary = Color(0xFFFFF0F5),
    textSecondary = Color(0xFFBCAAA4),
    isDark = true
)
val SpringLightPalette = KomorebiColors(
    background = Color(0xFFFCE4EC),
    surface = Color(0xFFFFFFFF),
    accent = Color(0xFFD81B60),
    textPrimary = Color(0xFF4E342E),
    textSecondary = Color(0xFF8D6E63),
    isDark = false
)
val SummerDarkPalette = KomorebiColors(
    background = Color(0xFF0B132B),
    surface = Color(0xFF1C2541),
    accent = Color(0xFF00E5FF),
    textPrimary = Color(0xFFF0F4FF),
    textSecondary = Color(0xFF8E9EBD),
    isDark = true
)
val SummerLightPalette = KomorebiColors(
    background = Color(0xFFE1F5FE),
    surface = Color(0xFFFFFFFF),
    accent = Color(0xFF0288D1),
    textPrimary = Color(0xFF011A27),
    textSecondary = Color(0xFF546E7A),
    isDark = false
)
val AutumnDarkPalette = KomorebiColors(
    background = Color(0xFF2C1E16),
    surface = Color(0xFF3E2A20),
    accent = Color(0xFFFF7043),
    textPrimary = Color(0xFFFFF3E0),
    textSecondary = Color(0xFFBCAAA4),
    isDark = true
)
val AutumnLightPalette = KomorebiColors(
    background = Color(0xFFEBE0D8),
    surface = Color(0xFFFFFFFF),
    accent = Color(0xFFD84315),
    textPrimary = Color(0xFF3E2723),
    textSecondary = Color(0xFF8D6E63),
    isDark = false
)

// EPGStation 風の青系パレット (アクセントは EPGStation のアプリバーに近いブルー)
val BlueDarkPalette = KomorebiColors(
    background = Color(0xFF0B1118),
    surface = Color(0xFF131D28),
    surface2 = Color(0xFF1B2838),
    accent = Color(0xFF2196F3),
    textPrimary = Color(0xFFE8EFF7),
    textSecondary = Color(0xFF8A99A8),
    isDark = true
)
val BlueLightPalette = KomorebiColors(
    background = Color(0xFFEAF1F8),
    surface = Color(0xFFFFFFFF),
    accent = Color(0xFF1976D2),
    textPrimary = Color(0xFF1F2933),
    textSecondary = Color(0xFF5F6B78),
    isDark = false
)

// --- ★ 昼夜のパレット (Komorebi / Kyle — 高コントラスト正規化) ---
val KomorebiDayPalette = KomorebiColors(
    background = Color(0xFFF4F1EA),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFEBE6DC),
    accent = Color(0xFF5A7247),
    textPrimary = Color(0xFF1C1A17),
    textSecondary = Color(0xFF545047),
    isDark = false
)
val KomorebiNightPalette = KomorebiColors(
    background = Color(0xFF101214),
    surface = Color(0xFF191C1F),
    surface2 = Color(0xFF24282D),
    accent = Color(0xFFDDE7F5),
    textPrimary = Color(0xFFEAF1FB),
    textSecondary = Color(0xFF8A9AB0),
    isDark = true
)
val KyleDayPalette = KomorebiColors(
    background = Color(0xFFEAF5F3),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFDCECE9),
    accent = Color(0xFF00796B),
    textPrimary = Color(0xFF0A2421),
    textSecondary = Color(0xFF3A5C57),
    isDark = false
)
val KyleNightPalette = KomorebiColors(
    background = Color(0xFF05171F),
    surface = Color(0xFF0A2433),
    surface2 = Color(0xFF123245),
    accent = Color(0xFF4DB6AC),
    textPrimary = Color(0xFFDDF0F7),
    textSecondary = Color(0xFF6B8AAB),
    isDark = true
)

// --- ★ 朝夕のパレット (深いダークベースで視認性を確保) ---
val KomorebiMorningPalette = KomorebiColors(
    background = Color(0xFF161512),
    surface = Color(0xFF211F1B),
    surface2 = Color(0xFF2D2A24),
    accent = Color(0xFFFFD599),
    textPrimary = Color(0xFFFFFBE8),
    textSecondary = Color(0xFFB3AA96),
    isDark = true
)
val KomorebiEveningPalette = KomorebiColors(
    background = Color(0xFF1A1518),
    surface = Color(0xFF261F23),
    surface2 = Color(0xFF332A2F),
    accent = Color(0xFFFFB280),
    textPrimary = Color(0xFFFFF0E6),
    textSecondary = Color(0xFFB8A6B3),
    isDark = true
)
val KyleMorningPalette = KomorebiColors(
    background = Color(0xFF0D1B1E),
    surface = Color(0xFF15272B),
    surface2 = Color(0xFF1E353B),
    accent = Color(0xFFAEE8F2),
    textPrimary = Color(0xFFEAF9FA),
    textSecondary = Color(0xFF8AB5BF),
    isDark = true
)
val KyleEveningPalette = KomorebiColors(
    background = Color(0xFF1B131A),
    surface = Color(0xFF271C26),
    surface2 = Color(0xFF342633),
    accent = Color(0xFFFFAB66),
    textPrimary = Color(0xFFFFF3E0),
    textSecondary = Color(0xFFBFA3B8),
    isDark = true
)

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun getSeasonalBackgroundBrush(theme: AppTheme, currentTime: LocalTime): Brush {
    val colors = KomorebiTheme.colors
    val tintColor = when (theme) {
        AppTheme.SPRING, AppTheme.SPRING_LIGHT -> Color(0xFFF48FB1)
        AppTheme.SUMMER, AppTheme.SUMMER_LIGHT -> Color(0xFF00E5FF)
        AppTheme.AUTUMN, AppTheme.AUTUMN_LIGHT -> Color(0xFFFF7043)
        AppTheme.WINTER_DARK, AppTheme.WINTER_LIGHT -> Color(0xFFCF3C3C)
        AppTheme.KOMOREBI, AppTheme.KOMOREBI_DAY, AppTheme.KOMOREBI_NIGHT,
        AppTheme.KYLE, AppTheme.KYLE_DAY, AppTheme.KYLE_NIGHT -> colors.accent
        else -> return Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    val alpha = if (colors.isDark) 0.10f else 0.08f
    return Brush.radialGradient(
        colors = listOf(tintColor.copy(alpha = alpha), Color.Transparent),
        center = Offset(1920f, 0f),
        radius = 1200f,
    )
}

fun getSeasonalIcon(theme: AppTheme): String {
    return when (theme) {
        AppTheme.SPRING, AppTheme.SPRING_LIGHT -> "🌸"
        AppTheme.SUMMER, AppTheme.SUMMER_LIGHT -> "🌻"
        AppTheme.AUTUMN, AppTheme.AUTUMN_LIGHT -> "🍁"
        AppTheme.WINTER_DARK, AppTheme.WINTER_LIGHT -> "❄️"
        AppTheme.KOMOREBI, AppTheme.KOMOREBI_DAY, AppTheme.KOMOREBI_NIGHT -> "🍃"
        AppTheme.KYLE, AppTheme.KYLE_DAY, AppTheme.KYLE_NIGHT -> "🐬"
        else -> ""
    }
}

val LocalKomorebiColors = staticCompositionLocalOf { MonotonePalette }
val LocalAppTheme = staticCompositionLocalOf { AppTheme.MONOTONE }

object KomorebiTheme {
    val colors: KomorebiColors @Composable @ReadOnlyComposable get() = LocalKomorebiColors.current
    val theme: AppTheme @Composable @ReadOnlyComposable get() = LocalAppTheme.current
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KomorebiTheme(theme: AppTheme = AppTheme.MONOTONE, content: @Composable () -> Unit) {
    // ★ 1分ごとに現在時刻を更新して時間連動を実現
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(60000); currentTime = LocalTime.now()
        }
    }
    val timeZone = getTimeZone(currentTime)

    val komorebiColors = when (theme) {
        AppTheme.HIGHTONE -> HightonePalette
        AppTheme.WINTER_DARK -> WinterDarkPalette; AppTheme.WINTER_LIGHT -> WinterLightPalette
        AppTheme.SPRING -> SpringDarkPalette; AppTheme.SPRING_LIGHT -> SpringLightPalette
        AppTheme.SUMMER -> SummerDarkPalette; AppTheme.SUMMER_LIGHT -> SummerLightPalette
        AppTheme.AUTUMN -> AutumnDarkPalette; AppTheme.AUTUMN_LIGHT -> AutumnLightPalette
        AppTheme.EPG_BLUE -> BlueDarkPalette; AppTheme.EPG_BLUE_LIGHT -> BlueLightPalette
        // ★ 時間帯によるパレットの出し分け
        AppTheme.KOMOREBI, AppTheme.KOMOREBI_DAY, AppTheme.KOMOREBI_NIGHT -> {
            when (timeZone) {
                TimeZone.MORNING -> KomorebiMorningPalette
                TimeZone.DAY -> KomorebiDayPalette
                TimeZone.EVENING -> KomorebiEveningPalette
                TimeZone.NIGHT -> KomorebiNightPalette
            }
        }

        AppTheme.KYLE, AppTheme.KYLE_DAY, AppTheme.KYLE_NIGHT -> {
            when (timeZone) {
                TimeZone.MORNING -> KyleMorningPalette
                TimeZone.DAY -> KyleDayPalette
                TimeZone.EVENING -> KyleEveningPalette
                TimeZone.NIGHT -> KyleNightPalette
            }
        }

        else -> MonotonePalette
    }

    val onAccent = komorebiColors.onAccent

    val materialColorScheme = if (komorebiColors.isDark) {
        darkColorScheme(
            primary = komorebiColors.accent,
            onPrimary = onAccent,
            secondary = komorebiColors.accent,
            onSecondary = onAccent,
            tertiary = komorebiColors.accent,
            onTertiary = onAccent,
            background = komorebiColors.background,
            onBackground = komorebiColors.textPrimary,
            surface = komorebiColors.surface,
            onSurface = komorebiColors.textPrimary,
            surfaceVariant = komorebiColors.surface2,
            onSurfaceVariant = komorebiColors.textSecondary,
            error = komorebiColors.danger,
        )
    } else {
        lightColorScheme(
            primary = komorebiColors.accent,
            onPrimary = onAccent,
            secondary = komorebiColors.accent,
            onSecondary = onAccent,
            tertiary = komorebiColors.accent,
            onTertiary = onAccent,
            background = komorebiColors.background,
            onBackground = komorebiColors.textPrimary,
            surface = komorebiColors.surface,
            onSurface = komorebiColors.textPrimary,
            surfaceVariant = komorebiColors.surface2,
            onSurfaceVariant = komorebiColors.textSecondary,
            error = komorebiColors.danger,
        )
    }

    CompositionLocalProvider(
        LocalKomorebiColors provides komorebiColors,
        LocalAppTheme provides theme
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = AppTypography,
            content = content
        )
    }
}

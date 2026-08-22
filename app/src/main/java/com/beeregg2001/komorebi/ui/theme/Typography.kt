package com.beeregg2001.komorebi.ui.theme

import androidx.tv.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AppTypography = Typography(
    // Google TVのような外観にするため、各スタイルにフォントを指定
    displayLarge = TextStyle(fontFamily = SystemFontFamily, fontWeight = FontWeight.Normal),
    displayMedium = TextStyle(fontFamily = SystemFontFamily, fontWeight = FontWeight.Normal),
    displaySmall = TextStyle(fontFamily = SystemFontFamily, fontWeight = FontWeight.Normal),

    headlineLarge = TextStyle(fontFamily = SystemFontFamily, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = SystemFontFamily, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontFamily = SystemFontFamily, fontWeight = FontWeight.Bold),

    titleLarge = TextStyle(fontFamily = SystemFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = SystemFontFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = SystemFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),

    bodyLarge = TextStyle(fontFamily = SystemFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = SystemFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = SystemFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp),

    labelLarge = TextStyle(fontFamily = SystemFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = SystemFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = SystemFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp)
)
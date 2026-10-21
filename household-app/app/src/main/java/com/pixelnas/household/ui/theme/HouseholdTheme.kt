package com.pixelnas.household.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HouseholdDarkColors = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF115E59),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF0C4A6E),
    onSecondaryContainer = Color(0xFFE0F2FE),
    background = Color(0xFF07191A),
    onBackground = Color(0xFFE6FFFA),
    surface = Color(0xFF0D2628),
    onSurface = Color(0xFFE6FFFA),
    surfaceVariant = Color(0xFF133538),
    onSurfaceVariant = Color(0xFF99F6E4)
)

@Composable
fun HouseholdTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HouseholdDarkColors,
        content = content
    )
}

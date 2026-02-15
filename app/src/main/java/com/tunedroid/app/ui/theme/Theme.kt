package com.tunedroid.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val ElectricOrange = Color(0xFFFF6D00)
val ElectricOrangeLight = Color(0xFFFF9E40)
val ElectricOrangeDark = Color(0xFFC43E00)

private val DarkColorScheme = darkColorScheme(
    primary = ElectricOrange,
    onPrimary = Color.White,
    primaryContainer = ElectricOrangeDark,
    onPrimaryContainer = Color.White,
    secondary = ElectricOrangeLight,
    onSecondary = Color.Black,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onBackground = Color.White,
    onSurface = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = ElectricOrange,
    onPrimary = Color.White,
    primaryContainer = ElectricOrangeLight,
    onPrimaryContainer = Color.Black,
    secondary = ElectricOrangeDark,
    onSecondary = Color.White,
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
)

@Composable
fun TuneDroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

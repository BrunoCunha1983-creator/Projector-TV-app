package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = SleekAccent,
    onPrimary = SleekBackground,
    secondary = SleekPanel,
    onSecondary = SleekText,
    tertiary = SleekSurface,
    background = SleekBackground,
    onBackground = SleekText,
    surface = SleekSurface,
    onSurface = SleekText,
    surfaceVariant = SleekPanel,
    onSurfaceVariant = SleekTextSecondary
  )

private val LightColorScheme =
  lightColorScheme(
    primary = SleekAccentDark,
    secondary = SleekPanel,
    tertiary = SleekSurface,
    background = SleekBackground,
    surface = SleekSurface,
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = SleekText,
    onSurface = SleekText,
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

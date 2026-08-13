package app.uperf.manager

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat

data class UColors(
    val bg: Color, val surface: Color, val surface2: Color,
    val text: Color, val text2: Color, val text3: Color, val outline: Color
)

val LightU = UColors(
    bg = Color(0xFFFFFFFF), surface = Color(0xFFF4F4F5), surface2 = Color(0xFFECECEE),
    text = Color(0xFF111113), text2 = Color(0xFF6B6B70), text3 = Color(0xFFA1A1A8),
    outline = Color(0xFFE6E6E9)
)
val DarkU = UColors(
    bg = Color(0xFF0E0E10), surface = Color(0xFF1A1A1D), surface2 = Color(0xFF26262A),
    text = Color(0xFFF2F2F4), text2 = Color(0xFF9C9CA4), text3 = Color(0xFF5F5F66),
    outline = Color(0xFF2C2C31)
)

val LocalU = compositionLocalOf { LightU }
val Danger = Color(0xFFD93A3E)

@Composable
fun AppTheme(themeMode: String, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val u = if (dark) DarkU else LightU
    val scheme = if (dark) darkColorScheme(
        background = u.bg, surface = u.surface, surfaceVariant = u.surface2,
        onSurface = u.text, onSurfaceVariant = u.text2, outline = u.outline,
        primary = u.text, onPrimary = u.bg, primaryContainer = u.surface2,
        onPrimaryContainer = u.text, secondaryContainer = u.surface2
    ) else lightColorScheme(
        background = u.bg, surface = u.surface, surfaceVariant = u.surface2,
        onSurface = u.text, onSurfaceVariant = u.text2, outline = u.outline,
        primary = u.text, onPrimary = u.bg, primaryContainer = u.surface2,
        onPrimaryContainer = u.text, secondaryContainer = u.surface2
    )

    // 沉浸式状态栏：图标颜色跟随主题
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = !dark
            WindowInsetsControllerCompat(window, view).isAppearanceLightNavigationBars = !dark
        }
    }

    CompositionLocalProvider(LocalU provides u) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(14.dp),
                large = RoundedCornerShape(14.dp),
                extraLarge = RoundedCornerShape(20.dp)
            ),
            content = content
        )
    }
}

package pl.simpay.android.demo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Single light scheme in the SimPay colours.
 *
 * Dynamic colour is deliberately off: the checkout sheet draws its own brand palette,
 * so a wallpaper-derived theme would make the shop and the sheet disagree.
 */
private val SimPayColorScheme = lightColorScheme(
    primary = SimPayGreen,
    onPrimary = Color.White,
    primaryContainer = SimPayGreenContainer,
    onPrimaryContainer = SimPayInk
)

@Composable
fun SimPayAndroidSDKTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SimPayColorScheme,
        typography = Typography,
        content = content
    )
}
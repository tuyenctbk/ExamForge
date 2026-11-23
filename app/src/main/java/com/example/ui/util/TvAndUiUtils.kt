package com.example.ui.util

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Detects whether the current device is an Android TV / Google TV / Leanback television surface.
 */
fun isAndroidTv(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
        return true
    }
    val pm = context.packageManager
    return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
        !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
}

/**
 * Checks whether the device exposes at least one physical camera suitable for scanning notes.
 * Returns false on Android TV / Leanback surfaces even when an emulator advertises a virtual camera.
 */
fun hasCameraHardware(context: Context): Boolean {
    if (isAndroidTv(context)) return false
    return context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
}

@Composable
fun rememberIsAndroidTv(): Boolean {
    val context = LocalContext.current
    return remember(context) { isAndroidTv(context) }
}

@Composable
fun rememberHasCameraHardware(): Boolean {
    val context = LocalContext.current
    return remember(context) { hasCameraHardware(context) }
}

/**
 * Returns WCAG-AA high-contrast foreground text color for any deck accent button background.
 */
fun Color.accessibleContentColor(): Color {
    return if (this.luminance() > 0.36f) Color(0xFF111827) else Color.White
}

/**
 * Adds crisp D-pad / keyboard focus ring and gentle spring lift for Android TV & large-screen navigation
 * without disturbing touch interactions.
 */
fun Modifier.tvFocusHighlight(
    shape: Shape = RoundedCornerShape(18.dp),
    focusedScale: Float = 1.02f,
    focusedColor: Color? = null
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    val ringColor = focusedColor ?: MaterialTheme.colorScheme.primary
    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) focusedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "tv_focus_scale"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) ringColor else Color.Transparent,
        label = "tv_focus_border"
    )

    this
        .onFocusChanged { isFocused = it.isFocused }
        .graphicsLayer {
            scaleX = animatedScale
            scaleY = animatedScale
        }
        .then(
            if (isFocused) {
                Modifier.border(width = 2.5.dp, color = borderColor, shape = shape)
            } else {
                Modifier
            }
        )
}

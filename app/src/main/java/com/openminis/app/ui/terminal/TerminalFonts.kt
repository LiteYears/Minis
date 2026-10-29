package com.openminis.app.ui.terminal

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat
import com.openminis.app.R

/**
 * JetBrains Mono — bundled TTF, used wherever the terminal renders code-
 * style text. Replaces `FontFamily.Monospace` / `Typeface.MONOSPACE`,
 * which the platform aliases to whatever ships on the device (Droid
 * Sans Mono on AOSP, Roboto Mono on Pixel, vendor-custom on Xiaomi /
 * Samsung etc.) — different glyph widths, different baselines, never
 * the same picture across phones (T138).
 *
 * Two equivalent surfaces:
 *  • `JetBrainsMonoFontFamily` — Compose Text, the keyboard-accessory
 *    bar and topbar inside `TerminalScreen`.
 *  • `rememberJetBrainsMonoTypeface()` — `android.graphics.Typeface`
 *    for `TerminalCanvasView`'s low-level Paint, which doesn't go
 *    through Compose. Loaded once via `ResourcesCompat.getFont` and
 *    cached.
 */
val JetBrainsMonoFontFamily: FontFamily = FontFamily.Monospace

fun jetBrainsMonoTypeface(context: Context, bold: Boolean = false): Typeface {
    return if (bold) Typeface.defaultFromStyle(Typeface.BOLD) else Typeface.MONOSPACE
}

@Composable
fun rememberJetBrainsMonoTypeface(bold: Boolean = false): Typeface {
    val context = LocalContext.current
    return remember(bold) { jetBrainsMonoTypeface(context, bold) }
}

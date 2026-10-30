package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.R
import com.openminis.app.i18n.uppercaseForDisplay
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.launch

/**
 * App-wide confirmation dialog. Tighter than the Material 3 default
 * (16 dp radius, titleLarge instead of headlineSmall) so small
 * confirmation prompts don't read as oversized cards. The destructive
 * variant tints the confirm button with `colorScheme.error`, matching
 * the pattern iOS uses for `UIAlertActionStyle.destructive`.
 */
@Composable
fun MinisAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    text: String? = null,
    dismissText: String = stringResource(R.string.cancel),
    isDestructive: Boolean = false,
    onDismiss: () -> Unit = onDismissRequest,
    /**
     * Optional third action, rendered between dismiss and confirm. When set,
     * the buttons stack vertically instead of sitting in a row: three labels
     * of real length (the pre-send compact prompt's "Compact & Enable
     * Auto-Compact" is 29 chars, and its zh translations are similar) do not
     * fit side by side on a phone, and a Row would either clip them or shrink
     * the text. Callers that pass nothing keep the original two-button row.
     */
    neutralText: String? = null,
    onNeutral: (() -> Unit)? = null,
    /**
     * The neutral action's color; null keeps the confirm button's. Lets a
     * dialog put a safe primary choice beside a destructive confirm
     * ([T-stop-choice]: "Stop Current Tool Only" next to a red "Stop All").
     */
    neutralColor: Color? = null,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
                )
                if (text != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                val confirmColor = if (isDestructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
                if (neutralText != null && onNeutral != null) {
                    // Stacked layout. Order runs least-committal first
                    // (dismiss) down to the most specific action, so the
                    // primary choice sits closest to the thumb.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        MinisTextButton(onClick = onDismiss) {
                            Text(dismissText)
                        }
                        MinisTextButton(onClick = onConfirm) {
                            Text(text = confirmText, color = confirmColor)
                        }
                        MinisTextButton(onClick = onNeutral) {
                            Text(text = neutralText, color = neutralColor ?: confirmColor)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        MinisTextButton(onClick = onDismiss) {
                            Text(dismissText)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        MinisTextButton(onClick = onConfirm) {
                            Text(text = confirmText, color = confirmColor)
                        }
                    }
                }
            }
        }
    }
}

// ─── Merged from ReopenOnResume.kt ──────────────────────────────────────────────────

/**
 * [T-android-picker-provider-edit] Reopen a sheet after the screen it handed
 * off to is popped.
 *
 * iOS opens a provider's settings (or Model Groups) as a sheet over the model
 * picker, so closing them lands back in the picker. Android pushes a route
 * instead, and the picker must close first (a bottom sheet left open lingers
 * behind the pushed screen). Call the returned function right before
 * navigating; [onReopen] then runs on the first ON_RESUME after it. The flag is
 * saveable because the host screen leaves composition while the pushed route
 * is on top.
 */
@Composable
fun rememberReopenOnResume(onReopen: () -> Unit): () -> Unit {
    var pending by rememberSaveable { mutableStateOf(false) }
    val latestOnReopen by rememberUpdatedState(onReopen)
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pending) {
                pending = false
                // Wait until the returning screen has drawn two frames before
                // composing the sheet. Opening it in the same frame as the
                // screen coming back put both compositions (the picker with
                // hundreds of models, the chat behind it) into one main-thread
                // burst; on a Pixel 4a under memory pressure that burst held
                // the window's FocusEvent past 5 s and raised an ANR. Two frames
                // let the focus change and the first draw go through first.
                scope.launch {
                    androidx.compose.runtime.withFrameNanos { }
                    androidx.compose.runtime.withFrameNanos { }
                    latestOnReopen()
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return remember { { pending = true } }
}

// ─── Merged from SettingsSection.kt ──────────────────────────────────────────────────

/**
 * iOS Settings-style "inset grouped" section. Renders an uppercase
 * teal header above a 16dp rounded surface card containing the
 * caller's [content] rows. Pair with [SettingsRowDivider] between
 * rows so the dividers stop short of the card's curved edges.
 *
 * Apply this to any settings detail screen that wants the
 * SF-Symbols Settings.app look — initially adopted by
 * RootfsManagementScreen (T168).
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = title.uppercaseForDisplay(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Thin divider for rows inside a [SettingsSection]. Pads in 16dp on
 * either side so the line stops short of the rounded card edge,
 * matching iOS Settings' inset divider style.
 */
@Composable
fun SettingsRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    )
}

// ─── Merged from MenuItemIcon.kt ──────────────────────────────────────────────────

/**
 * [T-android-menu-icons-ios] Leading icon for a menu row.
 *
 * iOS menus draw every item with an SF Symbol of one style and one weight, so
 * the column of glyphs reads as a set. The Android menus mixed filled and
 * outlined Material icons at the default 24 dp, and the filled ones (trash,
 * document, wrench, puzzle piece) looked heavier and larger than the outlined
 * ones beside them. Every row goes through this so they share one size;
 * callers pass Outlined icons only.
 */
@Composable
fun MenuItemIcon(icon: ImageVector, tint: Color = LocalContentColor.current) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(MENU_ICON_SIZE))
}

private val MENU_ICON_SIZE = 20.dp

/** Glyphs the Material set has no counterpart for. */
object MinisIcons {
    /**
     * SF Symbol `square.and.pencil` (iOS "New Chat"): a rounded square open at
     * its top-right corner with a pencil across the gap. Stroked on the
     * Material 24-unit grid at the outlined set's 2-unit weight.
     */
    val SquareAndPencil: ImageVector by lazy {
        ImageVector.Builder(
            name = "SquareAndPencil",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            val stroke = SolidColor(Color.Black)
            // Square, open where the pencil crosses it.
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(11f, 4f)
                horizontalLineTo(6f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 4f, y1 = 6f)
                verticalLineTo(18f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 6f, y1 = 20f)
                horizontalLineTo(18f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 20f, y1 = 18f)
                verticalLineTo(13f)
            }
            // Pencil.
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(18.5f, 2.5f)
                arcTo(2.12f, 2.12f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 21.5f, y1 = 5.5f)
                lineTo(12f, 15f)
                lineTo(8f, 16f)
                lineTo(9f, 12f)
                close()
            }
        }.build()
    }
}

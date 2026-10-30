package com.openminis.app.ui.components

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.annotation.MainThread
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.openminis.app.data.SpeedTier
import com.openminis.app.ui.preview.WebPreviewBottomSheet
import com.openminis.app.ui.preview.rememberWebViewHolder
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * [T-android-badge-clip] An [IconButton] with a badge that is never clipped.
 *
 * Material3's IconButton clips its content to its own shape: the modifier
 * chain is `minimumInteractiveComponentSize().size(40dp).clip(CircleShape)`
 * (confirmed in material3 1.3.2's IconButtonKt). A [BadgedBox] placed INSIDE
 * the button anchors the badge at the 24dp icon's top-end corner and pushes
 * it out from there, i.e. into the corner of the button where the 40dp circle
 * has already curved away - so a count badge ("18", "99+") lost its top-right
 * corner to the circle (user report, home toolbar scheduled-tasks clock).
 *
 * Here the BadgedBox wraps the button instead, so nothing above the badge
 * clips it. Its anchor is then the 48dp button rather than the 24dp icon,
 * whose top-end corner sits [ANCHOR_INSET] further right and up; the badge is
 * offset back by exactly that, so it lands where it always did. The icon and
 * the 48dp touch target are unchanged.
 *
 * [badge] null = a plain IconButton.
 */
@Composable
fun BadgedIconButton(
    onClick: () -> Unit,
    badge: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    if (badge == null) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled) { icon() }
        return
    }
    BadgedBox(
        badge = { Box(Modifier.offset(x = -ANCHOR_INSET, y = ANCHOR_INSET)) { badge() } },
        modifier = modifier,
    ) {
        IconButton(onClick = onClick, enabled = enabled) { icon() }
    }
}

/** (48dp minimum touch target - 24dp icon) / 2: how far the icon sits inside the button. */
internal val ANCHOR_INSET: Dp = 12.dp

// ─── Merged from DecorativeAnimation.kt ──────────────────────────────────────────────────

/**
 * [T-android-decorative-anim-perf] Shared machinery for decorative, always-on
 * animations: spinners, shimmers, breathing dots, pulsing glows.
 *
 * Why this file exists. Profiled on a Pixel 6 (90 Hz) with one tool running:
 * the 15 dp indeterminate spinner in the floating tool bar cost 193 `drawArc`
 * samples — and dragged 893 samples of text / image / rect re-rasterisation
 * with it, because nothing put a layer boundary between the spinner and its
 * siblings. Compose walks a draw invalidation UP to the nearest layer; with
 * no layer in between, that is the whole bar, so every frame re-recorded the
 * tile's twelve lines of monospace text to move a ring a few degrees.
 *
 * Four rules, each with a helper here so call sites cannot drift:
 *
 *  1. ISOLATE — give the animated node its own layer ([animationLayer], or a
 *     `graphicsLayer { }` lambda, which is one already) so its invalidation
 *     stops there and siblings are composited from cache.
 *  2. DEFER — read the animated value inside the `graphicsLayer { }` / draw
 *     lambda, never in composition. A composition-phase read recomposes the
 *     node every frame; a layer-phase read touches nothing but the layer.
 *  3. NO PER-FRAME ALLOCATION — brushes, colours and strokes are hoisted or
 *     cached; the frame path only writes a float.
 *  4. ONE THROTTLED CLOCK — every decorative animation derives its phase from
 *     [DecorativeClock], a single ~30 fps tick shared by all readers. A 2.8 s
 *     shimmer or a 1 s spin does not earn the panel's full refresh rate, and
 *     N independent `rememberInfiniteTransition`s meant N invalidation
 *     sources per frame. The clock runs only while something is reading it.
 *
 * Content the user tracks (scroll, drag, transitions) is NOT decorative and
 * must not be routed through this clock.
 */

/**
 * A layer boundary for an animated node.
 *
 * `graphicsLayer()` with no lambda: purely a RenderNode boundary so that draw
 * invalidations inside do not re-record anything outside. Use it around
 * Material's `CircularProgressIndicator` and any other component whose
 * animation you do not control; use the `graphicsLayer { }` lambda form
 * directly when you are also animating a layer property.
 */
fun Modifier.animationLayer(): Modifier = graphicsLayer()

/**
 * The single shared tick for decorative animations.
 *
 * Reference-counted: the frame loop starts when the first reader appears and
 * is cancelled when the last one leaves, so an idle screen costs nothing.
 * [nanos] updates at most every [INTERVAL_NS]; readers derive phase from it
 * with [decorativePhase] inside a layer / draw lambda.
 */
object DecorativeClock {
    /** ~30 fps. A third of a 90 Hz panel's frames, visually indistinguishable for decoration. */
    const val INTERVAL_NS = 33_333_333L

    private val _nanos = mutableLongStateOf(0L)
    val nanos: State<Long> get() = _nanos

    // AndroidUiDispatcher.Main carries the Choreographer-backed frame clock
    // that withFrameNanos needs; a plain Dispatchers.Main scope would not.
    private val scope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob())
    private var job: Job? = null
    private var readers = 0

    /** True while at least one reader holds the clock. Exposed for tests. */
    val isRunning: Boolean get() = job != null

    @MainThread
    fun acquire() {
        if (readers++ == 0) start()
    }

    @MainThread
    fun release() {
        readers = (readers - 1).coerceAtLeast(0)
        if (readers == 0) {
            job?.cancel()
            job = null
        }
    }

    private fun start() {
        job = scope.launch {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (now - last >= INTERVAL_NS) {
                        last = now
                        _nanos.longValue = now
                    }
                }
            }
        }
    }
}

/**
 * Subscribe this composable to [DecorativeClock] for as long as it is composed.
 * Read the returned state's `.value` INSIDE a `graphicsLayer { }` or draw
 * lambda (rule 2), not in composition.
 */
@Composable
fun rememberDecorativeTick(): State<Long> {
    // [FIX-261025-14] Held only while this reader's lifecycle is STARTED. The
    // clock runs on AndroidUiDispatcher's frame clock, which - unlike
    // Compose's own - is not paused when the screen stops, so a reader left
    // composed in the background (a group tile's comet or a row's ring while
    // a long turn runs with Minis in the background) woke the main thread
    // every vsync with nothing drawn. A screen behind another one in the
    // back stack (CREATED) releases it too. The floating tool capsule is
    // plain Views and does not use this clock.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        var held = false
        fun hold() { if (!held) { held = true; DecorativeClock.acquire() } }
        fun drop() { if (held) { held = false; DecorativeClock.release() } }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> hold()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> drop()
                else -> {}
            }
        }
        // Replays ON_START when the owner is already started.
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            drop()
        }
    }
    return DecorativeClock.nanos
}

/**
 * Position within a repeating cycle, 0f (inclusive) to 1f (exclusive).
 * [offsetMs] staggers siblings (the classic three-dot delay).
 *
 * The last few nanoseconds of a period round to exactly 1f in single
 * precision; because phase is cyclic (1f and 0f are the same instant to every
 * consumer — 360° is 0°, ping-pong floors it, the shimmer wraps anyway) that
 * hit is folded onto 0f so the exclusive bound holds.
 */
fun decorativePhase(nanos: Long, periodMs: Int, offsetMs: Int = 0): Float {
    if (periodMs <= 0) return 0f
    val periodNs = periodMs * 1_000_000L
    val shifted = nanos - offsetMs * 1_000_000L
    val m = shifted % periodNs
    val pos = if (m < 0) m + periodNs else m
    val r = pos.toFloat() / periodNs
    return if (r >= 1f) 0f else r
}

/** 0 → 1 → 0 over one phase cycle: the `RepeatMode.Reverse` shape. */
fun decorativePingPong(phase: Float): Float {
    val p = phase - floor(phase)
    return if (p < 0.5f) p * 2f else (1f - p) * 2f
}

/**
 * [T-android-decorative-anim-perf] A drop-in replacement for Material's
 * indeterminate [androidx.compose.material3.CircularProgressIndicator] that
 * costs a layer property per tick instead of a frame-rate animation.
 *
 * Material's spinner owns a private `InfiniteTransition` running at the
 * panel's refresh rate, and nothing outside it can throttle that — measured
 * on a 90 Hz Pixel 6, wrapping it in a layer cut the *collateral* re-recording
 * of sibling text but the screen still produced 87 fps because the spinner
 * kept asking for every frame. It also drags a Material semantics node
 * (ProgressBarRangeInfo) along, and the semantics tree walk was itself
 * hundreds of samples per capture.
 *
 * This draws the same visual — a rotating arc — from the shared ~30 fps
 * [DecorativeClock], records the arc exactly once, and animates only
 * `rotationZ` on its own layer. No semantics, no per-frame recomposition, no
 * allocation on the frame path.
 *
 * Use it for decorative "работа идёт" spinners. Anything conveying real
 * determinate progress should stay a Material indicator so it keeps its
 * accessibility semantics.
 */
@Composable
fun DecorativeSpinner(
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
    /** Fraction of the circle the sweep covers. Material's is ~0.75 at its widest. */
    sweepFraction: Float = 0.75f,
    periodMs: Int = 900,
) {
    val tick = rememberDecorativeTick()
    Canvas(
        modifier = modifier.graphicsLayer {
            rotationZ = decorativePhase(tick.value, periodMs) * 360f
        },
    ) {
        val px = strokeWidth.toPx()
        val inset = px / 2f
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 360f * sweepFraction,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - px, size.height - px),
            style = Stroke(width = px, cap = StrokeCap.Round),
        )
    }
}

// ─── Merged from DialogTextField.kt ──────────────────────────────────────────────────

/**
 * Outlined text input for use inside a Dialog / ModalBottomSheet /
 * full-screen editor. Sister composable of [SectionTextField] — same
 * BasicTextField + OutlinedTextFieldDefaults.DecorationBox pattern so we
 * can override contentPadding, but keeps Material3's outlined border +
 * container visible (dialog backgrounds use surfaceContainerHigh /
 * surfaceContainerHighest, where SectionTextField's transparent chrome
 * would leave the input invisible).
 *
 * Layout contract: caller renders the label outside the field
 * (`Text(label) + Spacer(6dp) + DialogTextField(...)`) — the inner
 * BasicTextField does NOT use a floating label slot.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    fieldModifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    // Tighten Material's default 16dp/16dp contentPadding so the min-height
    // comfortably fits a single line of text + cursor.
    // [T-android-search-height] 8.dp of vertical padding plus the text's own
    // line box exceeded the 42.dp target on its own, so the field would have
    // ignored heightIn and stayed tall. 6.dp leaves the single-line case
    // comfortably inside 42 while keeping the text off the border.
    val contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
    val mergedTextStyle = LocalTextStyle.current.merge(textStyle)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            // [T-android-search-height] Unified search/input height.
            .heightIn(min = 42.dp)
            .then(fieldModifier),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = mergedTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interactionSource,
        singleLine = singleLine,
        maxLines = maxLines,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                visualTransformation = visualTransformation,
                innerTextField = innerTextField,
                placeholder = placeholder?.let { { Text(it) } },
                label = null,
                trailingIcon = trailingIcon,
                singleLine = singleLine,
                enabled = enabled,
                isError = isError,
                interactionSource = interactionSource,
                colors = colors,
                contentPadding = contentPadding,
            )
        },
    )
}

// ─── Merged from FitOrWrapLine.kt ──────────────────────────────────────────────────

/**
 * [T-model-row-premature-wrap] One line of a model row: a text [lead] (model
 * name, or quota hint + model id) followed by fixed-size [tags] (quota pill,
 * Active badge, modality chips). Port of the iOS `ViewThatFits(in: .horizontal)`
 * pair in UnifiedModelPicker (b86aabda4, 74025d909):
 *  - when lead + tags fit the width, they share ONE line, the lead unwrapped;
 *  - otherwise the lead keeps the FULL width (and wraps only if it is longer
 *    than the whole line) and the tags move, together, to their own line below.
 *
 * A plain Row with `weight(1f, fill = false)` on the name (or a FlowRow) did
 * the opposite: the tags kept their width and the name wrapped into what was
 * left ("Claude Sonnet / 5 [14% used] [Active]"). A wrapped Text is only as
 * wide as its longest line, so the leftover showed up as empty space before
 * the quick-test button.
 *
 * [separator] (the "·" before modality chips) is drawn only in the one-line
 * form and only when there are tags, like iOS. No tags → just the lead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FitOrWrapLine(
    modifier: Modifier = Modifier,
    spacing: Dp = 4.dp,
    lineSpacing: Dp = 2.dp,
    separator: (@Composable () -> Unit)? = null,
    lead: @Composable RowScope.() -> Unit,
    tags: @Composable () -> Unit,
) {
    val leadContent: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
            content = lead,
        )
    }
    val separatorContent: @Composable () -> Unit = { separator?.invoke() }
    // A FlowRow, so that in the wrapped form an unusually long set of tags
    // (a model with all seven modality chips on a narrow screen) continues on
    // a further line instead of clipping the last chip. Its max intrinsic
    // width is the single-line width, which is what the fit check uses.
    val tagsContent: @Composable () -> Unit = {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(lineSpacing),
            itemVerticalAlignment = Alignment.CenterVertically,
            content = { tags() },
        )
    }
    Layout(
        contents = listOf(leadContent, separatorContent, tagsContent),
        modifier = modifier,
    ) { (leadSlot, sepSlot, tagsSlot), constraints ->
        val lead = leadSlot.single()
        val sep = sepSlot.firstOrNull()
        val tagRow = tagsSlot.single()
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)

        val tagsW = tagRow.maxIntrinsicWidth(Constraints.Infinity)
        val leadW = lead.maxIntrinsicWidth(Constraints.Infinity)
        val sepW = if (tagsW > 0) sep?.maxIntrinsicWidth(Constraints.Infinity) ?: 0 else 0
        val oneLine = FitOrWrapRules.fitsOnOneLine(
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else null,
            leadWidth = leadW,
            separatorWidth = if (sep != null && tagsW > 0) sepW else null,
            tagsWidth = tagsW,
            gap = gap,
        )

        if (tagsW == 0) {
            val lp = lead.measure(loose)
            return@Layout layout(constraints.constrainWidth(lp.width), constraints.constrainHeight(lp.height)) {
                lp.placeRelative(0, 0)
            }
        }

        if (oneLine) {
            val lp = lead.measure(loose.copy(maxWidth = leadW))
            val sp = if (sepW > 0) sep?.measure(Constraints()) else null
            val tp = tagRow.measure(Constraints(maxWidth = tagsW))
            val h = maxOf(lp.height, sp?.height ?: 0, tp.height)
            val w = lp.width + gap + (if (sp != null) sp.width + gap else 0) + tp.width
            layout(constraints.constrainWidth(w), constraints.constrainHeight(h)) {
                var x = 0
                lp.placeRelative(x, (h - lp.height) / 2)
                x += lp.width + gap
                if (sp != null) {
                    sp.placeRelative(x, (h - sp.height) / 2)
                    x += sp.width + gap
                }
                tp.placeRelative(x, (h - tp.height) / 2)
            }
        } else {
            val lp = lead.measure(loose)
            val tp = tagRow.measure(loose)
            val h = lp.height + lineSpacing.roundToPx() + tp.height
            layout(constraints.constrainWidth(maxOf(lp.width, tp.width)), constraints.constrainHeight(h)) {
                lp.placeRelative(0, 0)
                tp.placeRelative(0, lp.height + lineSpacing.roundToPx())
            }
        }
    }
}

/** The fit decision behind [FitOrWrapLine], kept pure for unit tests. */
internal object FitOrWrapRules {
    /**
     * True when lead, optional separator and tags fit side by side (with [gap]
     * between each) in [maxWidth]; an unbounded width always fits. No tags → one
     * line trivially (the lead alone wraps, if it must, across the full width).
     */
    fun fitsOnOneLine(maxWidth: Int?, leadWidth: Int, separatorWidth: Int?, tagsWidth: Int, gap: Int): Boolean {
        if (tagsWidth <= 0 || maxWidth == null) return true
        val needed = leadWidth + gap + (separatorWidth?.let { it + gap } ?: 0) + tagsWidth
        return needed <= maxWidth
    }
}

// ─── Merged from HardwareKeyboard.kt ──────────────────────────────────────────────────

/**
 * [T-android-hwkeyboard-virtual-false-positive] "Is a real, external
 * hardware keyboard attached?" — the question the composer and the terminal
 * both ask before deciding whether to keep focus / skip dismissing the IME.
 *
 * The previous answer was `Configuration.keyboard == KEYBOARD_QWERTY &&
 * hardKeyboardHidden == HARDKEYBOARDHIDDEN_NO`. That is what Android
 * computes from the set of attached input devices, and it counts ANY device
 * that presents an alphabetic key layout — including virtual ones. Measured
 * on a Pixel 4a with no keyboard in sight: a `scrcpy --keyboard=uhid`
 * session registers a uinput device (`bus=0x0006`, `Classes: KEYBOARD |
 * ALPHAKEY`, `Generic.kcm`), the Configuration flips to `qwerty/v/v`, and
 * from then on sending a message no longer dismissed the on-screen keyboard.
 * The hardware-keyboard commit (cdf61e8ff) had verified `nokeys` on a Pixel
 * 6 — one that happened not to be mirrored at the time.
 *
 * The user-visible contract of that feature is "your hands are on physical
 * keys, so keep the caret in the box". A virtual HID device from a screen
 * mirror, a game controller's alpha map, or a TV remote satisfies the
 * Configuration check without satisfying the contract. So the decision is
 * made from the devices themselves:
 *
 *   - [InputDevice.isVirtual] excludes the framework's own virtual keyboard
 *     (`uinput-fpc … built-in keyboard`, `-1: Virtual`).
 *   - [InputDevice.isExternal] (API 29) excludes built-in button devices
 *     (`gpio-keys`, `qpnp_pon`, headset jacks) that also carry KEYBOARD
 *     class bits.
 *   - KEYBOARD_TYPE_ALPHABETIC excludes DPAD-only remotes.
 *
 * A scrcpy UHID keyboard is `bus=0x0006` (virtual bus) and, being created by
 * the UHID driver rather than a physical transport, is NOT reported as
 * external — so it drops out here while a real Bluetooth / USB keyboard,
 * which IS external, still qualifies.
 *
 * Below API 29 `isExternal` does not exist, so the Configuration check is
 * kept as the fallback: it is the old behaviour, not a regression, on the
 * handful of API 26–28 devices.
 *
 * [rememberHasHardwareKeyboard] re-evaluates on device add/remove (so a
 * Bluetooth keyboard connecting mid-session is picked up, like the
 * Configuration approach was) and also when Configuration changes, which
 * covers a folding cover flipping `hardKeyboardHidden`.
 */
object HardwareKeyboard {

    /** Pure predicate over a device's reported properties; unit-testable. */
    fun qualifies(
        keyboardType: Int,
        isVirtual: Boolean,
        isExternal: Boolean,
        sourcesIncludeKeyboard: Boolean,
    ): Boolean =
        sourcesIncludeKeyboard &&
            keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
            !isVirtual &&
            isExternal

    /**
     * Evaluate against the live device list. On API < 29 falls back to the
     * Configuration flags (the previous behaviour).
     */
    fun isAttached(context: Context, configuration: Configuration): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
                configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        }
        // A keyboard that is attached but folded shut (slider / cover) is not
        // one the user is typing on. Configuration is the only place that
        // state lives, so keep honouring it.
        if (configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES) return false
        val ids = InputDevice.getDeviceIds()
        for (id in ids) {
            val d = InputDevice.getDevice(id) ?: continue
            val hasKeyboardSource = (d.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
            if (qualifies(d.keyboardType, d.isVirtual, d.isExternal, hasKeyboardSource)) return true
        }
        return false
    }
}

/**
 * Composable state for [HardwareKeyboard.isAttached] that recomposes when a
 * device is added/removed/changed or when the Configuration changes.
 */
@Composable
fun rememberHasHardwareKeyboard(): Boolean {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var attached by remember { mutableStateOf(HardwareKeyboard.isAttached(context, configuration)) }
    // Re-evaluate on configuration changes (fold/unfold, keyboardHidden).
    androidx.compose.runtime.LaunchedEffect(configuration) {
        attached = HardwareKeyboard.isAttached(context, configuration)
    }
    DisposableEffect(context) {
        val im = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { attached = HardwareKeyboard.isAttached(context, configuration) }
            override fun onInputDeviceRemoved(deviceId: Int) { attached = HardwareKeyboard.isAttached(context, configuration) }
            override fun onInputDeviceChanged(deviceId: Int) { attached = HardwareKeyboard.isAttached(context, configuration) }
        }
        im?.registerInputDeviceListener(listener, null)
        onDispose { im?.unregisterInputDeviceListener(listener) }
    }
    return attached
}

// ─── Merged from InAppBrowserLauncher.kt ──────────────────────────────────────────────────

/**
 * A composition-wide callback for opening a URL inside the app's WebView sheet.
 *
 * - For http/https links, callers should invoke this to trigger [UrlPreviewSheet]
 *   and keep the user in-app (iOS-style preview).
 * - For other schemes (mailto:, tel:, geo:, minis://, etc.) fall back to
 *   [openExternalUrl] which dispatches a normal system Intent.
 *
 * The root [InAppBrowserHost] provides this and renders the sheet when invoked.
 * If a screen reads the ambient outside a host (e.g. during tests), the default
 * implementation falls back to the external Intent so nothing silently no-ops.
 */
val LocalInAppBrowserLauncher = compositionLocalOf<(String) -> Unit> {
    // Fallback: if no host is installed above, bail to an external intent so
    // links at least open somewhere. Hosts must override this.
    { _ -> }
}

/**
 * Fire a system ACTION_VIEW intent for schemes we don't preview in-app
 * (mailto, tel, geo, sms, etc.) or when we explicitly want the external handler.
 */
fun openExternalUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/**
 * Host composable: provides [LocalInAppBrowserLauncher] and renders
 * [UrlPreviewSheet] when a URL is requested. Place this once near the top of
 * the app (around the NavHost) so any screen can open links in-app.
 *
 * Only http/https URLs are routed through the in-app sheet. Anything else
 * is delegated to a normal system Intent so mailto:/tel:/maps:/minis:// keep
 * working.
 */
@Composable
fun InAppBrowserHost(
    context: Context,
    content: @Composable () -> Unit,
) {
    var previewUrl by remember { mutableStateOf<String?>(null) }

    val launcher = remember(context) {
        { url: String ->
            val lower = url.trim().lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                previewUrl = url
            } else {
                openExternalUrl(context, url)
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalInAppBrowserLauncher provides launcher,
    ) {
        content()
    }

    previewUrl?.let { url ->
        UrlPreviewSheet(
            url = url,
            onDismiss = { previewUrl = null },
        )
    }
}

// ─── Merged from SectionDesign.kt ──────────────────────────────────────────────────

/**
 * Single source of truth for Settings section-card visual rhythm.
 * Mirror of iOS SwiftUI Form / List grouped style; the values below are
 * derived from the iOS reference screenshot and its pixel measurements.
 *
 * All Settings screens (ProviderDetail / MountedFolders / MountDetail /
 * ModelGroups / EnvironmentVariables / etc.) MUST consume from this
 * object rather than hard-coding values inline. When a value changes
 * here every screen updates atomically.
 */
object SectionDesign {
    /** Outer column padding from screen edge. iOS UIKit insets.left/.right = 16. */
    val ScreenHorizontalPadding = 16.dp

    /** Card corner radius. iOS UITableViewCell rounded section uses ~10pt. */
    val CardShape = RoundedCornerShape(12.dp)

    /** Distance from screen top → first section header. */
    val FirstSectionTopGap = 16.dp

    /** Distance from previous section's footer → next section header. */
    val SectionTopGap = 24.dp

    /** Distance from a section header → its card. */
    val HeaderToCardGap = 8.dp

    /** Distance from card → its footer caption. */
    val CardToFooterGap = 8.dp

    /** Distance between adjacent rows / cards inside the same section
     *  (e.g. mount list rows). iOS uses no gap with internal dividers
     *  but Android's surfaceContainer color contrast is weaker so keep
     *  a tight 8dp visual break. */
    val InterRowGap = 8.dp

    /** Standard row height inside a card. iOS row ≈ 44pt. */
    val RowMinHeight = 44.dp

    /** [T-android-settings-ui-md3] #3 Single-line text-field min height. MD3
     *  text fields are 56dp; the section rows were normalized to the same 56dp
     *  so an input field lines up with the toggle/value rows around it instead
     *  of reading as the old ~44/24dp mix. Kept separate from [RowMinHeight] so
     *  dropdowns / plain rows that intentionally sit tighter are unaffected. */
    val TextFieldMinHeight = 56.dp

    /** Horizontal padding inside the card (from card-inner-edge to row content). */
    val RowHorizontalPadding = 16.dp

    /** Vertical padding inside a row (top + bottom each). */
    val RowVerticalPadding = 10.dp

    /** Vertical padding wrapping the inner Column of a [SectionCard], so the
     *  first/last row don't hug the card's top/bottom edge. iOS Settings
     *  reserves a small gap here even though row dividers stay flush. */
    val CardInnerVerticalPadding = 6.dp

    /** Inner-card divider thickness. */
    val DividerThickness = 0.5.dp

    /** Inner-card divider left inset (so it tucks under content text rather
     *  than touching the card edge — matches iOS). */
    val DividerStartInset = 16.dp

    /** Card background — match iOS pure surface (white in light, near-black in dark).
     *  T313 follow-up: The app theme deliberately overrides Material3 semantics —
     *  `surface` is the page-level grouped background (gray / black) while
     *  `surfaceContainerLow` is the brighter card foreground. Using
     *  `surfaceContainerLow` here gives us the card color iOS Settings expects. */
    @Composable
    @ReadOnlyComposable
    fun cardColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow

    /** Section background (the gray behind the cards) — match iOS systemGroupedBackground.
     *  Pairs with [cardColor]: this token is the dimmer page-level surface
     *  (`background` = light gray / near-black), so cards painted in
     *  `surfaceContainerLow` stand out against it. */
    @Composable
    @ReadOnlyComposable
    fun screenBackgroundColor(): Color = MaterialTheme.colorScheme.background

    /** Inner-card divider color — match iOS systemFill α 0.3. */
    @Composable
    @ReadOnlyComposable
    fun dividerColor(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

    /** Footer caption color (gray text below cards). */
    @Composable
    @ReadOnlyComposable
    fun footerColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Section title above a card. iOS reference uses 17pt SemiBold sentence-case
 * onSurface — bigger and more present than Material's labelMedium ALL CAPS
 * onSurfaceVariant which fades into the background.
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(
            start = SectionDesign.ScreenHorizontalPadding,
            end = SectionDesign.ScreenHorizontalPadding,
            bottom = SectionDesign.HeaderToCardGap,
        ),
    )
}

/**
 * Footer caption below a card. iOS reference uses 13pt secondary onSurfaceVariant.
 */
@Composable
fun SectionFooter(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = SectionDesign.footerColor(),
        modifier = modifier.padding(
            start = SectionDesign.ScreenHorizontalPadding,
            end = SectionDesign.ScreenHorizontalPadding,
            top = SectionDesign.CardToFooterGap,
        ),
    )
}

/**
 * Container for one or more rows in a settings section. Replaces ad-hoc
 * Surface(surfaceContainerLow, RoundedCornerShape(12.dp)) call sites that
 * each pick their own color / shape / padding. Pass child rows; they get
 * the card background + corner clipping and divide themselves with
 * [SectionDivider] when stacked.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = SectionDesign.cardColor(),
        shape = SectionDesign.CardShape,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SectionDesign.ScreenHorizontalPadding),
    ) {
        Column(
            modifier = Modifier.padding(vertical = SectionDesign.CardInnerVerticalPadding),
            content = content,
        )
    }
}

/** Divider between rows inside a [SectionCard]. */
@Composable
fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = SectionDesign.DividerStartInset),
        thickness = SectionDesign.DividerThickness,
        color = SectionDesign.dividerColor(),
    )
}

/**
 * Inline label placed directly above a [SectionTextField] (or
 * [SectionDropdown]) when several labelled fields share a section but
 * each row needs its own short caption — too small a scope for a full
 * [SectionHeader]. Used by ModelEntryDetail / AddCustomModel /
 * AddProvider for fields like "Model ID" / "Display Name" / "Context Window".
 *
 * The previous `label =` slot on [SectionTextField] used Material3's
 * floating-label DecorationBox, but the row's tight `RowVerticalPadding`
 * (10dp) leaves no headroom — input glyphs overlap the floating label.
 * iOS Form puts every label outside its cell, so we follow the same
 * pattern: render the caption with this composable, then drop the
 * field below it.
 *
 * Horizontal padding is 0: callsites place this inside [SettingsCardBlock]
 * (or a manually padded Column) which already adds the 16dp inset. Adding
 * our own 16dp on top would double-indent the label relative to sibling
 * SectionHeader / description text / radio rows — see T353. Mirrors
 * SectionTextField's contentPadding=0 from T352.
 */
@Composable
fun RowLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 6.dp),
    )
}

// ─── Merged from SectionTextField.kt ──────────────────────────────────────────────────

/**
 * Text input for use inside a [SectionCard]. Wraps a BasicTextField in
 * OutlinedTextFieldDefaults.DecorationBox so we can override contentPadding
 * (the default OutlinedTextField overload doesn't expose it) — needed to
 * shrink the row to [SectionDesign.RowMinHeight] (~44dp) and align the
 * inner glyphs with sibling section content.
 *
 * Borders + container fills are transparent so the surrounding
 * [SectionCard] surface is the only visual boundary. Error state keeps a
 * red border as a recoverable affordance.
 *
 * Horizontal contentPadding is 0: every callsite places this field inside
 * a parent ([SettingsCardBlock] or a manually padded Column) that already
 * adds the 16dp horizontal inset. Adding our own 16dp on top would
 * double-indent the text glyphs relative to sibling section headers /
 * description text / radio rows — see T352.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SectionTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    fieldModifier: Modifier = Modifier,
    /**
     * Fill behind the field. The default matches settings cards — but inside
     * a ModalBottomSheet that default is INVISIBLE: M3 sheets use the same
     * surfaceContainerLow as their container, so the field renders as bare
     * borderless text. Sheet callers pass a contrasting fill (typically
     * [SectionDesign.screenBackgroundColor]) to restore the filled-card
     * look the settings screens have.
     */
    containerColor: Color? = null,
    /**
     * [T-android-sheet-textfield-inset] Horizontal inset for the glyphs inside
     * the field.
     *
     * Defaults to 0 because on a SETTINGS screen the field is a full-bleed row
     * inside a section card whose parent already applies the 16dp inset — any
     * padding here would double it and break alignment with sibling rows (T352).
     *
     * That assumption does not hold in a bottom sheet / dialog, where the field
     * is a standalone rounded card supplying its own background: with 0 the text
     * and placeholder sit flush against the card's left edge. Those callers pass
     * a real inset instead of the component changing its default, so no settings
     * screen shifts.
     */
    contentHorizontalPadding: Dp = 0.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color.Transparent,
        unfocusedBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
        errorBorderColor = MaterialTheme.colorScheme.error,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
    )
    // Horizontal defaults to 0 so glyphs align with sibling section content
    // (parent already adds the 16dp horizontal inset). See T352. Sheet/dialog
    // callers override via [contentHorizontalPadding].
    val contentPadding = PaddingValues(
        horizontal = contentHorizontalPadding,
        vertical = SectionDesign.RowVerticalPadding,
    )
    val mergedTextStyle = LocalTextStyle.current.merge(textStyle)

    // [T-android-model-id-input-no-hscroll]
    // The legacy `BasicTextField(value: String, ...)` overload reconstructs
    // its internal TextFieldValue from `value` on every recomposition,
    // which forces selection back to the end of the string each time the
    // parent emits a fresh recompose. On a long string in a singleLine
    // field, that means: user taps to move the caret → caret moves
    // briefly → next recompose snaps caret to value.length → the
    // bring-cursor-into-view path never gets to "scroll content sideways
    // so the trailing edge of the text becomes visible" because the
    // caret is always at the END of the (clipped) layout. Net effect: a
    // 60-char model id like "huihui-qwen3.6-35b-…-mtp" has its last
    // characters permanently off-screen and the user can't reach them.
    //
    // Switch to the TextFieldValue overload — selection is owned locally
    // and survives recomposition. The outer caller still passes a plain
    // String; we wrap it, drive onValueChange with the new text only
    // when text actually changed, and re-seed cursor-at-end only when
    // the parent replaces the value out from under us (e.g. screen
    // reset / hydration / a different entry loaded).
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }
    if (fieldValue.text != value) {
        // External value changed (parent assigned a fresh string).
        // Re-seed with cursor at end — matches the legacy String
        // overload's behavior on the same transition.
        fieldValue = TextFieldValue(value, TextRange(value.length))
    }
    Surface(
        color = containerColor ?: SectionDesign.cardColor(),
        shape = SectionDesign.CardShape,
        modifier = modifier.fillMaxWidth(),
    ) {
        BasicTextField(
            value = fieldValue,
            onValueChange = { newValue ->
                fieldValue = newValue
                if (newValue.text != value) onValueChange(newValue.text)
            },
            modifier = Modifier
                .fillMaxWidth()
                // [T-android-settings-ui-md3] #3 standard MD3 single-line text
                // field height (56dp) so input rows match the toggle/value rows.
                .heightIn(min = SectionDesign.TextFieldMinHeight)
                .then(fieldModifier),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = mergedTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interactionSource,
            singleLine = singleLine,
            maxLines = maxLines,
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = fieldValue.text,
                    visualTransformation = visualTransformation,
                    innerTextField = innerTextField,
                    placeholder = placeholder?.let { { Text(it) } },
                    label = null,
                    trailingIcon = trailingIcon,
                    singleLine = singleLine,
                    enabled = enabled,
                    isError = isError,
                    interactionSource = interactionSource,
                    colors = colors,
                    contentPadding = contentPadding,
                    container = {
                        OutlinedTextFieldDefaults.ContainerBox(
                            enabled = enabled,
                            isError = isError,
                            interactionSource = interactionSource,
                            colors = colors,
                            shape = SectionDesign.CardShape,
                        )
                    },
                )
            },
        )
    }
}

// ─── Merged from SheetScrollGuard.kt ──────────────────────────────────────────────────

/**
 * [T-android-picker-scroll-guard] Stops a FLING inside a bottom sheet's list
 * from continuing into the sheet and dismissing it, without taking away the
 * deliberate drag-down-to-close gesture.
 *
 * The problem: a `LazyColumn` inside a `ModalBottomSheet` hands its leftover
 * scroll to the sheet. Flick the model list upward, hit the top, and the
 * remaining fling velocity becomes a downward drag on the sheet — the picker
 * closes while the user was only scrolling.
 *
 * The first fix for this consumed EVERY unconsumed downward delta, which cured
 * the flick but also disabled drag-to-dismiss: once the list was at the top,
 * pulling the sheet down with a finger did nothing, leaving the scrim tap and
 * the back gesture as the only ways out. That trades a rare accident for a
 * permanent regression of the platform-standard gesture.
 *
 * So the guard discriminates by SOURCE rather than by direction alone:
 *
 *  - [NestedScrollSource.SideEffect] — momentum from a fling the user has
 *    already let go of. They are no longer steering, so carrying it into a
 *    dismissal is never what they asked for. Consumed.
 *  - [NestedScrollSource.UserInput] — the finger is still down and the user is
 *    actively dragging. Passed through, so drag-to-dismiss works exactly as the
 *    platform intends.
 *
 * Only downward (`available.y > 0`) deltas are considered at all; upward
 * leftovers belong to the sheet's own expand behaviour.
 */
@Composable
fun rememberSheetScrollGuard(): NestedScrollConnection = remember {
    object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            val isFlingMomentum = source == NestedScrollSource.SideEffect
            return if (available.y > 0f && isFlingMomentum) {
                Offset(0f, available.y)
            } else {
                Offset.Zero
            }
        }
    }
}

// ─── Merged from SpeedTierIcons.kt ──────────────────────────────────────────────────

/**
 * [T-speed-tier-icons] Monochrome speed-tier glyphs, the Android counterparts
 * of the iOS SF Symbols: Standard `speedometer`, Fast `bolt` (outline),
 * Ultrafast `bolt.fill` (solid).
 *
 * Why local vectors for the bolt: in material-icons-extended, `Outlined.Bolt`
 * and `Filled.Bolt` are the same closed solid shape (identical path data), and
 * so are the two `FlashOn` variants — an "outlined" bolt from the library is
 * visually solid and cannot tell Fast from Ultrafast. Both bolts below are
 * built from ONE polygon, stroked for Fast and filled for Ultrafast, so the
 * only difference the eye sees is outline vs fill. The paint color is a
 * placeholder: Icon's tint recolors fill and stroke alike.
 */
object SpeedTierIcons {
    private fun bolt(name: String, filled: Boolean): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                // Thin for the outline, a hairline for the fill so its edge
                // lines up with the outline version's silhouette.
                strokeLineWidth = if (filled) 0.8f else 1.8f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(13.5f, 2.5f)
                lineTo(4.5f, 13.5f)
                lineTo(11f, 13.5f)
                lineTo(10.5f, 21.5f)
                lineTo(19.5f, 10.5f)
                lineTo(13f, 10.5f)
                close()
            }
        }.build()

    /**
     * [T-android-ultrafast-align] Share of the 24-unit box the bolt's ink fills
     * vertically: the path spans y 2.5..21.5, plus half the outline's 1.8 stroke
     * at each end. Used to size an Icon so the bolt itself, not its box, is a
     * given height.
     */
    const val BOLT_INK_FRACTION = (21.5f - 2.5f + 1.8f) / 24f

    /** The Icon box size whose bolt ink is [inkHeightPx] tall. */
    fun boltBoxForInk(inkHeightPx: Float): Float = inkHeightPx / BOLT_INK_FRACTION

    /** Fast: outline bolt (SF `bolt`). */
    val BoltOutline: ImageVector by lazy { bolt("SpeedBoltOutline", filled = false) }

    /** Ultrafast: solid bolt (SF `bolt.fill`). */
    val BoltFill: ImageVector by lazy { bolt("SpeedBoltFill", filled = true) }

    /** Standard and the "Speed" entry: speedometer (SF `speedometer`). */
    val Speedometer: ImageVector get() = Icons.Outlined.Speed

    fun forTier(tier: SpeedTier): ImageVector = when (tier) {
        SpeedTier.STANDARD -> Speedometer
        SpeedTier.FAST -> BoltOutline
        SpeedTier.ULTRAFAST -> BoltFill
    }
}

// ─── Merged from UrlPreviewSheet.kt ──────────────────────────────────────────────────

/**
 * Bottom-sheet web preview for a URL tapped inside chat markdown.
 *
 * Thin wrapper around [WebPreviewBottomSheet]: callers that don't manage
 * their own [com.openminis.app.ui.preview.WebViewHolder] (most chat-side
 * link taps) get the same toolbar / sheet UX as the in-chat HTML preview
 * by going through this helper.
 *
 * Fullscreen expand is intentionally not exposed here — chat URL preview
 * was a single-sheet flow on iOS too.
 */
@Composable
fun UrlPreviewSheet(
    url: String,
    onDismiss: () -> Unit,
    initialFullscreen: Boolean = false,
) {
    val holder = rememberWebViewHolder(url)
    var isFullscreen by remember(url) { mutableStateOf(initialFullscreen) }
    val onFullDismiss = {
        holder.destroy()
        onDismiss()
    }
    if (isFullscreen) {
        com.openminis.app.ui.preview.WebPreviewFullscreenScreen(
            holder = holder,
            onCollapseToSheet = {
                holder.detach()
                isFullscreen = false
            },
            onDismiss = onFullDismiss,
        )
    } else {
        WebPreviewBottomSheet(
            holder = holder,
            onExpandFullscreen = {
                holder.detach()
                isFullscreen = true
            },
            onDismiss = onFullDismiss,
        )
    }
}

// ─── Merged from MinisButton.kt ──────────────────────────────────────────────────

// Material3 ButtonDefaults.MinHeight = 40dp; tuned to 48dp for touch
// ergonomics on phones. IconButton family is unaffected (icon-sized).
val MinisButtonHeight = 48.dp

// Compact button height for actions embedded inside section cards
// (e.g. "Sign out" inside a credentials card, "Set Bearer Token" inside
// a token section). Visually subordinate to MinisButtonHeight (48dp)
// which remains the size for primary screen actions ("Add Custom Model",
// TopAppBar Save, AlertDialog confirm).
val MinisSmallButtonHeight = 32.dp

private val SmallButtonContentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)

@Composable
fun MinisButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = MinisButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

@Composable
fun MinisOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled = enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = MinisButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

@Composable
fun MinisTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = MinisButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

// defaultMinSize is also pinned at MinisSmallButtonHeight so Material3's
// internal 40dp floor (ButtonDefaults.MinHeight) doesn't override the
// heightIn modifier and keep the button at 40dp.
@Composable
fun MinisSmallButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = MinisSmallButtonHeight)
            .defaultMinSize(minHeight = MinisSmallButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

@Composable
fun MinisSmallOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled = enabled),
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = MinisSmallButtonHeight)
            .defaultMinSize(minHeight = MinisSmallButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

@Composable
fun MinisSmallTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = MinisSmallButtonHeight)
            .defaultMinSize(minHeight = MinisSmallButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

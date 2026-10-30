package com.openminis.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Velocity
import com.openminis.app.data.model.ModelEntry
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * iOS-style spring-bounce overscroll for vertical scroll containers.
 *
 * Default Android overscroll is either an edge glow (API <31) or a brief
 * stretch (API 31+); neither matches the rubber-band drag and bouncy
 * spring-back UIScrollView gives you on iOS. This effect:
 *
 *   - Translates the content by a *rubber-banded* fraction of the
 *     overscroll delta (longer drags compress harder, asymptoting), so the
 *     page visibly follows the finger past the top/bottom edge.
 *   - On release / fling, animates the offset back to 0 with
 *     [Spring.DampingRatioMediumBouncy] and [Spring.StiffnessLow] — the
 *     ratio iOS uses for bounce-back of overscrolled tables and scroll
 *     views (≈ 0.5 damping, slow restoring force, visible overshoot).
 *
 * Wire up with `Modifier.verticalScroll(state).overscroll(effect)`. The
 * `Modifier.overscroll` modifier registers a NestedScrollConnection so the
 * outer-edge component of every drag flows into [applyToScroll] and
 * post-fling momentum flows into [applyToFling], even though the inner
 * `verticalScroll` is the one that actually consumes scroll deltas. Use
 * [rememberIosBounceOverscrollEffect] to keep the same Animatable across
 * recompositions.
 */
@OptIn(ExperimentalFoundationApi::class)
class IosBounceOverscrollEffect(
    private val scope: CoroutineScope,
    /** Maximum visual stretch in pixels at "infinite" pull. iOS' UIScrollView
     *  caps around the viewport size; 600px feels right on Pixel-class
     *  densities without letting a stray fling rip the page off screen. */
    private val maxOverscrollPx: Float = 600f,
) : OverscrollEffect {

    private val offset = Animatable(0f)

    override val isInProgress: Boolean
        get() = offset.value != 0f

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        // If we're already past an edge (offset != 0) and the user starts
        // pulling back toward 0, consume the matching component of the
        // gesture to unwind the rubber band before the inner scrollable
        // sees any of it. Mirrors UIScrollView's "release the bounce
        // before resuming scroll" behavior.
        val curOffset = offset.value
        val sameSign = curOffset != 0f && sign(delta.y) != sign(curOffset)
        val preConsumed = if (sameSign) {
            val correction = (-curOffset).coerceAtMost(abs(delta.y)) * sign(delta.y)
            scope.launch { offset.snapTo(curOffset + correction) }
            Offset(0f, correction)
        } else {
            Offset.Zero
        }

        val afterPre = delta - preConsumed
        val consumedByInner = performScroll(afterPre)
        val leftover = afterPre - consumedByInner

        if (leftover.y != 0f) {
            // User is dragging past an edge — apply the rubber band.
            val newOffset = curOffset + preConsumed.y + rubberBand(leftover.y, curOffset + preConsumed.y)
            scope.launch { offset.snapTo(newOffset) }
        }

        return preConsumed + consumedByInner
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        val leftover = performFling(velocity)
        // Spring back with iOS-bouncy parameters — mediumBouncy + stiffnessLow
        // gives ~0.5 damping with a slow restoring force, producing the
        // visible overshoot iOS users expect.
        offset.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
            initialVelocity = leftover.y,
        )
    }

    /** UIScrollView-style rubber band: overscroll = delta * (1 - |currentOffset|/max).
     *  Exponent 0.55 matches the curve UIKit's `_UIRubberBandClampedDistance` uses. */
    private fun rubberBand(delta: Float, currentOffset: Float): Float {
        val pull = abs(currentOffset).coerceAtMost(maxOverscrollPx)
        val resistance = (1f - (pull / maxOverscrollPx)).coerceAtLeast(0f).pow(0.55f)
        return delta * resistance
    }

    /** Translate the content by the current overscroll offset. Used by
     *  `Modifier.overscroll(effect)` to render the rubber band — we move the
     *  laid-out content vertically without resizing it, which preserves the
     *  inner scrollable's measured size. */
    override val effectModifier: Modifier = Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, offset.value.toInt())
        }
    }
}

/**
 * Composable factory that remembers an [IosBounceOverscrollEffect] tied to
 * the local [rememberCoroutineScope]. The returned instance is stable for
 * the composition's lifetime so callers can pass it to
 * `Modifier.overscroll(effect)`.
 */
@Composable
fun rememberIosBounceOverscrollEffect(): IosBounceOverscrollEffect {
    val scope = rememberCoroutineScope()
    return remember(scope) { IosBounceOverscrollEffect(scope) }
}

// ─── Merged from ModelSearch.kt ──────────────────────────────────────────────────

/**
 * [T-picker-search-relevance] [T-picker-search-debounce] [T-picker-search-cap]
 * Model-picker search: relevance scoring, a debounced query and a result cap.
 * iOS parity: ModelSearchScorer + UnifiedModelPicker (30fb01839, 0dcb55a0b;
 * GH#271, GH#272).
 *
 * Android filtered with a Bool match (`contains` / subsequence) on every
 * keystroke, kept the provider's order — so a loose subsequence hit could
 * bury an exact one — and put every match on screen. With an aggregator
 * holding 7,000+ models each keystroke re-ran the whole pass and a short query
 * mounted thousands of rows.
 *
 * Pure except [rememberDebouncedQuery], so the ranking is unit-tested
 * directly (ModelSearchTest).
 */
object ModelSearch {

    /** Tiers as on iOS, spread wide so no bonus lifts a lower tier over a higher one. */
    const val EXACT = 1000
    const val PREFIX = 900
    const val TOKEN_PREFIX = 800
    const val SUBSTRING = 700
    const val SUBSEQUENCE = 100

    /** Typing must settle this long before the list re-filters (iOS: 120 ms). */
    const val DEBOUNCE_MS = 120L

    /**
     * Rows shown for one search, counted ACROSS provider sections: one
     * aggregator with 7,000 models is the reported case, and a per-section
     * cap would not bound it. iOS: 150.
     */
    const val MAX_RESULTS = 150

    /**
     * Score [text] against [query]; 0 = no match. [query] must already be
     * lowercased and trimmed — scoring runs per candidate per search, so the
     * caller lowercases the query once, outside the loop.
     */
    fun score(text: String, query: String): Int {
        if (query.isEmpty()) return EXACT
        val target = text.lowercase()
        if (target == query) return EXACT
        if (target.startsWith(query)) return PREFIX + lengthBonus(target)
        if (tokenHasPrefix(target, query)) return TOKEN_PREFIX + lengthBonus(target)
        if (target.contains(query)) return SUBSTRING + lengthBonus(target)
        if (isSubsequence(query, target)) return SUBSEQUENCE
        return 0
    }

    /** Best score across fields (display name, id): an exact id hit ranks as exact. */
    fun bestScore(texts: List<String>, query: String): Int {
        var best = 0
        for (t in texts) {
            val s = score(t, query)
            if (s > best) best = s
            if (best >= EXACT) break
        }
        return best
    }

    /** Normalize what the user typed into the form [score] expects. */
    fun normalize(raw: String): String = raw.trim().lowercase()

    /** Up to 99 points, shorter first, never reaching the next tier. */
    private fun lengthBonus(target: String): Int = maxOf(0, 99 - minOf(99, target.length))

    /**
     * True when any word of [target] starts with [query]. Model ids are
     * separated by `- / . _ :` far more often than by spaces, so all count as
     * boundaries — otherwise "sonnet" would not token-match `claude-3-5-sonnet`.
     */
    private fun tokenHasPrefix(target: String, query: String): Boolean {
        var atBoundary = true
        for (i in target.indices) {
            if (atBoundary && target.startsWith(query, i)) return true
            val ch = target[i]
            atBoundary = ch == '-' || ch == '/' || ch == '.' || ch == '_' || ch == ':' || ch == ' '
        }
        return false
    }

    /** The old loose match, kept as the lowest tier so nothing findable becomes unfindable. */
    private fun isSubsequence(query: String, target: String): Boolean {
        var idx = 0
        for (ch in query) {
            val found = target.indexOf(ch, idx)
            if (found < 0) return false
            idx = found + 1
        }
        return true
    }

    /**
     * Keep the entries of one section that match [query], most relevant
     * first. Ties keep the incoming order — callers pass sections already in
     * release-rank order, which is how the list reads unsearched, so equal
     * relevance keeps the familiar newest-first order.
     */
    fun rank(entries: List<ModelEntry>, query: String): List<ModelEntry> {
        if (query.isEmpty()) return entries
        val scored = ArrayList<Pair<ModelEntry, Int>>()
        for (e in entries) {
            val s = bestScore(listOf(e.model.displayName, e.model.id), query)
            if (s > 0) scored += e to s
        }
        // sortedByDescending is stable, so ties keep release-rank order.
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    /** One provider section's search result. */
    data class Section<K>(val key: K, val entries: List<ModelEntry>)

    data class Result<K>(
        val sections: List<Section<K>>,
        /** Matches before the cap — drives the "showing N of M" footer. */
        val totalMatches: Int,
    ) {
        val shown: Int get() = sections.sumOf { it.entries.size }
        val truncated: Boolean get() = totalMatches > shown
    }

    /**
     * Search sections of entries: a section whose [label] matches shows ALL
     * its entries (a self-hosted provider's label is often its only memorable
     * name — iOS T-picker-search-provider-name); otherwise its matching
     * entries, ranked. Empty sections drop out. Then the cap is applied across
     * sections, in section order, so what survives is the most relevant part
     * of each section rather than an arbitrary slice. An empty [query] returns
     * the sections unchanged and uncapped.
     */
    fun <K> search(
        sections: List<Pair<K, List<ModelEntry>>>,
        label: (K) -> String,
        query: String,
        maxResults: Int = MAX_RESULTS,
    ): Result<K> {
        if (query.isEmpty()) {
            val all = sections.filter { it.second.isNotEmpty() }.map { Section(it.first, it.second) }
            return Result(all, all.sumOf { it.entries.size })
        }
        val matched = sections.mapNotNull { (key, entries) ->
            val hit = if (score(label(key), query) > 0) entries else rank(entries, query)
            if (hit.isEmpty()) null else Section(key, hit)
        }
        val total = matched.sumOf { it.entries.size }
        var remaining = maxResults
        val capped = ArrayList<Section<K>>()
        for (s in matched) {
            if (remaining <= 0) break
            if (s.entries.size <= remaining) {
                capped += s
                remaining -= s.entries.size
            } else {
                capped += Section(s.key, s.entries.take(remaining))
                remaining = 0
            }
        }
        return Result(capped, total)
    }
}

/**
 * [T-picker-search-debounce] The search text, trailing the field by
 * [ModelSearch.DEBOUNCE_MS] and normalized, so a burst of typing runs the
 * search once. Clearing applies immediately: waiting to restore the full list
 * reads as lag, and the unsearched list costs nothing.
 */
@Composable
fun rememberDebouncedQuery(raw: String, delayMs: Long = ModelSearch.DEBOUNCE_MS): String {
    val normalized = ModelSearch.normalize(raw)
    var debounced by remember { mutableStateOf(normalized) }
    LaunchedEffect(normalized) {
        if (normalized.isNotEmpty()) delay(delayMs)
        debounced = normalized
    }
    return if (normalized.isEmpty()) "" else debounced
}

// ─── Merged from SectionDropdown.kt ──────────────────────────────────────────────────

/**
 * Dropdown selector for use inside a [SectionCard]. Visual twin of
 * [SectionTextField] — same card color/shape, same row min-height
 * (44dp), same horizontal glyph inset (16dp). Trailing icon is
 * always Icons.Default.ArrowDropDown to signal the menu affordance.
 *
 * [items] is the list of selectable values; [itemLabel] maps each
 * value to its menu/textfield display string. The current selection
 * is shown read-only in the field; tapping anywhere on the field
 * toggles the menu.
 *
 * Callers must NOT add their own `Modifier.padding(horizontal=16.dp)` — the
 * dropdown draws itself with [SectionDesign.RowHorizontalPadding] inside
 * its own card surface (matches iOS form-cell behaviour).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SectionDropdown(
    selected: T,
    items: List<T>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    itemLabel: (T) -> String = { it.toString() },
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color.Transparent,
        unfocusedBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
    )
    val contentPadding = PaddingValues(
        horizontal = SectionDesign.RowHorizontalPadding,
        vertical = SectionDesign.RowVerticalPadding,
    )
    val displayText = itemLabel(selected)
    val mergedTextStyle = LocalTextStyle.current.merge(textStyle)
    Surface(
        color = SectionDesign.cardColor(),
        shape = SectionDesign.CardShape,
        modifier = modifier.fillMaxWidth(),
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            BasicTextField(
                value = displayText,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
                    .heightIn(min = SectionDesign.RowMinHeight),
                textStyle = mergedTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                interactionSource = interactionSource,
                decorationBox = { innerTextField ->
                    OutlinedTextFieldDefaults.DecorationBox(
                        value = displayText,
                        innerTextField = innerTextField,
                        enabled = enabled,
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                        interactionSource = interactionSource,
                        colors = colors,
                        contentPadding = contentPadding,
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                            )
                        },
                        container = {
                            OutlinedTextFieldDefaults.ContainerBox(
                                enabled = enabled,
                                isError = false,
                                interactionSource = interactionSource,
                                colors = colors,
                                shape = SectionDesign.CardShape,
                            )
                        },
                    )
                },
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                items.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(itemLabel(item)) },
                        onClick = {
                            expanded = false
                            onSelect(item)
                        },
                    )
                }
            }
        }
    }
}

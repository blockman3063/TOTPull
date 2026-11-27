package io.blockman.totpull.ui

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import io.blockman.totpull.R
import io.blockman.totpull.TotpEntry
import io.blockman.totpull.TotpGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.util.Locale

// ---------------------------------------------------------------------------
// One row in the codes list: either a source-app group header or one entry card.
// ---------------------------------------------------------------------------

private sealed class CodesRow

/** One source-app bucket. Tapping folds it, long-pressing opens the group editor. */
private class HeaderRow(val key: String, val title: String, val count: Int, val collapsed: Boolean) : CodesRow()

/** One code card. */
private class EntryRow(val entry: TotpEntry) : CodesRow()

/**
 * One uniform card tone for every card: a neutral, slightly cool surface that exists
 * only to lift the cards off the page background - light theme reads as clean white
 * cards on grey, dark theme as charcoal-blue cards on near-black.
 */
private fun cardTint(dark: Boolean): Color {
    return Color.hsl(215f, if (dark) 0.14f else 0.30f, if (dark) 0.16f else 0.98f)
}

/**
 * Visual state of an active drag, shared between the handle (writes, per pointer event) and
 * the dragged card (reads, per frame). The follow offset accumulates the finger's movement
 * relative to the handle itself: since the handle travels with the card, a slot change cancels
 * out of the delta automatically - no cross-generation coordinate conversion left to jitter.
 */
private fun rowKey(row: CodesRow): String = when (row) {
    is HeaderRow -> "h:" + row.key
    is EntryRow -> "e:" + row.entry.id
}

// ---------------------------------------------------------------------------
// Theme
// ---------------------------------------------------------------------------

@Composable
fun TOTPullTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MiuixTheme(
        colors = if (dark) darkColorScheme() else lightColorScheme(),
        content = content
    )
}

// ---------------------------------------------------------------------------
// Root: one page at a time over a two-tab bottom bar.
// ---------------------------------------------------------------------------

@Composable
fun MainScreen() {
    val tab = UiBridge.tab.intValue
    // Java owns the side effects of a tab change (first-run setup accepted, info refresh).
    LaunchedEffect(tab) {
        UiBridge.host?.onTabChanged(tab)
    }

    // The long-press drag gesture lives on the ROOT, not on the LazyColumn: hit-testing
    // ends at the list's bounds, so a finger that wanders onto the bottom bar or past
    // the viewport used to starve the gesture and drop the card mid-drag. From the root,
    // every touch anywhere lands in the hit chain and the drag survives.
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        // The root gesture exists on every tab; only the codes page owns
                        // a drag handle, so it must never start on the info page.
                        if (UiBridge.tab.intValue == 0) {
                            UiBridge.drag.probe(offset.x, offset.y)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        UiBridge.drag.moved(dragAmount.y, change.position.y)
                    },
                    onDragEnd = { UiBridge.drag.finish() },
                    onDragCancel = { UiBridge.drag.finish() }
                )
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background)
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab == 0) {
                    CodesPage()
                } else {
                    InfoPageHost()
                }
            }
            BottomNav(selected = tab) { UiBridge.showTab(it) }
        }

        // The dragged card floats here - above the bottom bar and outside the list's
        // clip, so it stays visible wherever the finger goes.
        DragOverlay()

        // Above the overlay on purpose: the floating card passes UNDER the + button.
        // The bar is 1dp hairline + 58dp row, hence the 79dp bottom inset.
        if (tab == 0) {
            AddFab(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 79.dp)
            )
        }
    }
}

@Composable
private fun DragOverlay() {
    val drag = UiBridge.drag
    val entry = drag.overlayEntry ?: return

    // Own countdown clock: the overlay lives outside CodesPage's ticker and only
    // exists for a few seconds per drag.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }

    // Fly-back: on release the card glides from its floating position into the final
    // slot (the slot offset is already settled, since the data move happened during the
    // drag); when it lands it hands over to the in-list twin via settled().
    val flyBack = remember { Animatable(0f) }
    LaunchedEffect(drag.settling) {
        if (drag.settling) {
            val delta = drag.slotOffset() - (drag.baseOffset + drag.dy)
            flyBack.snapTo(0f)
            drag.settleProgress = 0f
            flyBack.animateTo(
                delta,
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) {
                drag.settleProgress = if (delta == 0f) {
                    1f
                } else {
                    (flyBack.value / delta).coerceIn(0f, 1f)
                }
            }
            drag.settled()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .offset {
                IntOffset(
                    0,
                    (drag.containerTopInRoot + drag.baseOffset + drag.dy + flyBack.value)
                        .roundToInt()
                )
            }
            // No pointer modifiers here on purpose: touch passes straight through to
            // the gesture root underneath.
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = drag.overlayTopPad)
        ) {
            EntryCard(entry, now, drag, isFloating = true)
        }
    }
}

// ---------------------------------------------------------------------------
// Codes page
// ---------------------------------------------------------------------------

@Composable
private fun CodesPage() {
    val context = LocalContext.current

    // Subscribing to these makes the page rebuild whenever Java changes the vault,
    // the user types, picks a filter, or folds a group.
    UiBridge.dataVersion.intValue
    val query = UiBridge.query.value.trim().lowercase(Locale.ROOT)
    val sources = UiBridge.filterSources.value
    val types = UiBridge.filterTypes.value
    val sortMode = UiBridge.sortMode.value
    val collapsed = UiBridge.collapsed.toSet()
    // mOTP / Yandex cannot produce codes and are disabled app-wide: never listed, never
    // exported, never offered in the editor. They stay in the vault file untouched.
    val entries = (UiBridge.host?.entries() ?: emptyList()).filter { it.isSupported() }
    val computed = buildRows(context, entries, query, sources, types, sortMode, collapsed)
    val rowsState = rememberUpdatedState(computed)

    // Live countdown: codes and progress bars repaint twice a second.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }

    val listState = rememberLazyListState()
    val dragVisual = UiBridge.drag
    val density = LocalDensity.current
    val handleZone = with(density) { 58.dp.toPx() }

    Column(Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.nav_codes),
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 20.dp, top = 14.dp)
        )
        SearchBar()
        Text(
            text = subtitle(context, entries, computed),
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(start = 20.dp, bottom = 2.dp)
        )

        if (computed.isEmpty()) {
            EmptyView(UiBridge.isFiltering())
        } else {
            val haptics = LocalHapticFeedback.current

            // Swap detection shared by the drag handler and the auto-scroll loop: both
            // feed it a finger Y in container coordinates. Finger Y outside the viewport
            // (on the bottom bar, in the header area) is clamped to the nearest edge, so
            // the drag behaves as if the finger pressed at the edge and never breaks.
            // Hysteresis: swap only once the finger crossed the target's center, so
            // hovering a row boundary cannot flicker back and forth.
            val checkSwap: (Float) -> Unit = checkSwap@{ rawY ->
                // Only the custom (vault-order) mode is drag-adjustable; a sorted view
                // would revert every swap on the next rebuild anyway.
                if (sortMode != UiBridge.SORT_CUSTOM) {
                    return@checkSwap
                }
                val layout = listState.layoutInfo
                val y = rawY.coerceIn(
                    layout.viewportStartOffset.toFloat(),
                    layout.viewportEndOffset.toFloat() - 1f
                )
                val draggedInfo = layout.visibleItemsInfo
                    .firstOrNull { it.key == dragVisual.activeKey }
                    ?: return@checkSwap
                val targetInfo = layout.visibleItemsInfo.firstOrNull {
                    y >= it.offset && y < it.offset + it.size
                } ?: return@checkSwap
                if (targetInfo.key == draggedInfo.key) {
                    return@checkSwap
                }
                val target =
                    (rowsState.value.getOrNull(targetInfo.index) as? EntryRow)
                        ?.entry ?: return@checkSwap
                val draggedEntry =
                    (rowsState.value.getOrNull(draggedInfo.index) as? EntryRow)
                        ?.entry ?: return@checkSwap
                val draggedCenter = draggedInfo.offset + draggedInfo.size / 2f
                val targetCenter = targetInfo.offset + targetInfo.size / 2f
                val crossed = if (targetCenter > draggedCenter) {
                    y >= targetCenter
                } else {
                    y <= targetCenter
                }
                if (crossed &&
                    (target.sourcePackage ?: "") ==
                    (draggedEntry.sourcePackage ?: "")
                ) {
                    // No anchor compensation here on purpose: the visual position is
                    // anchored to the finger (baseOffset + dy) and the overlay draws at
                    // that absolute spot, so a slot jump cancels out. Adding compensation
                    // on top would double-correct, and reading the target's offset
                    // mid-spring would drift.
                    UiBridge.host?.moveEntry(draggedEntry, target)
                }
            }

            // While a drag is active, holding the finger inside an edge band scrolls
            // the list - faster the deeper into the band the finger is - and keeps
            // running swap detection, so the card re-orders while the finger rests.
            LaunchedEffect(dragVisual.activeKey) {
                val edge = with(density) { 56.dp.toPx() }
                val maxSpeed = with(density) { 16.dp.toPx() }
                while (dragVisual.activeKey != null && isActive) {
                    val y = dragVisual.lastFingerY
                    // Sentinel is exactly -1f (no finger); a NEGATIVE y is a real finger
                    // position above the viewport top and must scroll the list upward -
                    // the old "y >= 0f" guard silently killed top-edge auto-scroll.
                    if (y != -1f) {
                        val layout = listState.layoutInfo
                        val top = layout.viewportStartOffset.toFloat()
                        val bottom = layout.viewportEndOffset.toFloat()
                        val delta = when {
                            y < top + edge -> {
                                val depth = ((top + edge - y) / edge).coerceIn(0f, 1f)
                                -maxSpeed * depth
                            }
                            y > bottom - edge -> {
                                val depth = ((y - (bottom - edge)) / edge).coerceIn(0f, 1f)
                                maxSpeed * depth
                            }
                            else -> 0f
                        }
                        if (delta != 0f) {
                            listState.scrollBy(delta)
                            checkSwap(dragVisual.lastFingerY)
                        }
                    }
                    withFrameNanos { }
                }
            }

            // Hand the drag lifecycle to the root-level gesture (MainScreen): it sees
            // events even when the finger leaves the list bounds, which is what keeps
            // the drag alive while the finger rides the bottom bar or screen edges.
            dragVisual.probe = probe@{ x, y ->
                // A fly-back is in progress: ignore new grabs until it lands (a few
                // hundred ms), otherwise two drags would fight over the overlay.
                if (dragVisual.settling) {
                    return@probe false
                }
                // Sorted views cannot reorder: a swap would be reverted on the next
                // rebuild, so the handle does nothing there.
                if (sortMode != UiBridge.SORT_CUSTOM) {
                    return@probe false
                }
                // Double guard against a stale closure: this lambda outlives the codes
                // page's composition, so an info-page long-press could still reach it.
                if (UiBridge.tab.intValue != 0) {
                    return@probe false
                }
                val layout = listState.layoutInfo
                val listY = y - dragVisual.containerTopInRoot
                val info = layout.visibleItemsInfo.firstOrNull {
                    listY >= it.offset && listY < it.offset + it.size
                }
                val row = rowsState.value.getOrNull(info?.index ?: -1) as? EntryRow
                val ok = row != null && x >= layout.viewportSize.width - handleZone
                if (ok) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    dragVisual.activeKey = "e:" + row!!.entry.id
                    dragVisual.dy = 0f
                    dragVisual.baseOffset = info!!.offset.toFloat()
                    dragVisual.lastFingerY = listY
                    dragVisual.overlayEntry = row.entry
                    dragVisual.fabGone = true
                    // Mirror the wrapper's top padding so the fly-back lands exactly
                    // where the in-list card sits (first row gets 4dp, the rest 0).
                    dragVisual.overlayTopPad =
                        if (info.index == 0) 4.dp else 0.dp
                }
                ok
            }
            dragVisual.moved = { d, y ->
                if (dragVisual.activeKey != null) {
                    dragVisual.dy += d
                    dragVisual.lastFingerY = y - dragVisual.containerTopInRoot
                    checkSwap(dragVisual.lastFingerY)
                }
            }
            dragVisual.finish = {
                if (dragVisual.activeKey != null) {
                    // Keep the floating card on screen and let it fly back into the slot
                    // (settling); only then does it hand over to the in-list twin, which
                    // sits in the final slot already (releaseOffset 0 = no jump). The FAB
                    // pop-back starts right here at finger-up, not at the handover.
                    dragVisual.releaseOffset = 0f
                    dragVisual.settling = true
                    dragVisual.lastFingerY = -1f
                    dragVisual.fabGone = false
                    UiBridge.host?.persistOrder()
                }
            }
            dragVisual.slotOffset = {
                val info = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == dragVisual.activeKey }
                if (info != null) info.offset.toFloat() else dragVisual.baseOffset
            }

            LazyColumn(
                state = listState,
                // User scrolling is off while a card is dragged or flying back: the
                // fly-back target was computed at release time, so a second finger
                // scrolling the list meanwhile would land the card somewhere else
                // than its slot. Programmatic edge auto-scroll is unaffected.
                userScrollEnabled = dragVisual.activeKey == null && !dragVisual.settling,
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coords ->
                        dragVisual.containerTopInRoot = coords.positionInRoot().y
                    }
            ) {
                itemsIndexed(computed, key = { _, row -> rowKey(row) }) { index, row ->
                    val itemKey = rowKey(row)
                    val dragging = dragVisual.activeKey == itemKey
                    Box(
                        Modifier
                            .animateItem(
                                // The dragged card floats in the root-level overlay; its
                                // in-list twin is invisible but must keep its slot.
                                placementSpec = if (dragging) {
                                    null
                                } else {
                                    spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                }
                            )
                            .zIndex(if (dragging) 1f else 0f)
                            .padding(
                                start = 14.dp,
                                end = 14.dp,
                                top = if (index == 0) 4.dp else 0.dp,
                                bottom = if (index == computed.lastIndex) 96.dp else 0.dp
                            )
                    ) {
                        when (row) {
                            is HeaderRow -> GroupHeader(row)
                            is EntryRow -> EntryCard(row.entry, now, dragVisual)
                        }
                    }
                }
            }
        }
    }
}

/** Grouped, filtered rows; mirrors the old EntryAdapter.rebuild() ordering rules. */
private fun buildRows(
    context: Context,
    entries: List<TotpEntry>,
    query: String,
    sources: Set<String>,
    types: Set<String>,
    sortMode: String,
    collapsed: Set<String>
): List<CodesRow> {
    val groups = LinkedHashMap<String, MutableList<TotpEntry>>()
    for (entry in entries) {
        if (!matches(entry, query, sources, types)) {
            continue
        }
        val key = entry.sourcePackage ?: ""
        groups.getOrPut(key) { mutableListOf() }.add(entry)
    }

    // Extracted sources first (alphabetical), hand-added entries last.
    val keys = groups.keys.toMutableList()
    keys.sortWith { a, b ->
        when {
            a.isEmpty() && b.isEmpty() -> 0
            a.isEmpty() -> 1
            b.isEmpty() -> -1
            else -> a.compareTo(b, ignoreCase = true)
        }
    }

    val rows = ArrayList<CodesRow>()
    for (key in keys) {
        val bucket = groups.getValue(key)
        // Custom keeps the vault order (and drag reordering works); the other modes sort
        // inside each group, since grouping by source stays the anchor of the page.
        when (sortMode) {
            UiBridge.SORT_NAME -> bucket.sortBy {
                (it.issuer + "\n" + it.name).lowercase(Locale.ROOT)
            }
            UiBridge.SORT_RECENT -> bucket.sortByDescending { it.lastUsedAt }
        }
        val title = if (key.isEmpty()) {
            context.getString(R.string.local_source)
        } else {
            bucket[0].displaySource()
        }
        val isCollapsed = key in collapsed
        rows.add(HeaderRow(key, title, bucket.size, isCollapsed))
        if (!isCollapsed) {
            for (entry in bucket) {
                rows.add(EntryRow(entry))
            }
        }
    }
    return rows
}

private fun matches(entry: TotpEntry, query: String, sources: Set<String>, types: Set<String>): Boolean {
    // Multi-select: every non-empty dimension admits the entry if it hits any choice.
    if (sources.isNotEmpty() && (entry.sourcePackage ?: "") !in sources) {
        return false
    }
    if (types.isNotEmpty() && types.none { typeMatches(entry, it) }) {
        return false
    }
    if (query.isEmpty()) {
        return true
    }
    // Space-separated tokens must all hit somewhere, so "github work" narrows faster
    // than a single blob of text.
    return query.split(Regex("\\s+")).all { token ->
        token.isNotEmpty() && (contains(entry.issuer, token) || contains(entry.name, token) ||
                contains(entry.group, token) || contains(entry.note, token) ||
                contains(entry.sourceLabel, token) || contains(entry.sourcePackage, token) ||
                contains(entry.type, token))
    }
}

private fun contains(value: String?, query: String): Boolean {
    return value != null && value.lowercase(Locale.ROOT).contains(query)
}

private fun typeMatches(entry: TotpEntry, type: String): Boolean = when (type) {
    TotpEntry.TYPE_TOTP -> !entry.isHotp() && !entry.isSteam() && entry.isSupported()
    TotpEntry.TYPE_HOTP -> entry.isHotp()
    TotpEntry.TYPE_STEAM -> entry.isSteam()
    // "other" - unsupported types such as Yandex / MOTP.
    else -> !entry.isSupported()
}

private fun subtitle(context: Context, entries: List<TotpEntry>, rows: List<CodesRow>): String {
    if (entries.isEmpty()) {
        return context.getString(R.string.codes_subtitle_empty)
    }
    if (UiBridge.isFiltering()) {
        val visible = rows.count { it is EntryRow }
        return context.getString(R.string.codes_subtitle_filtered, visible, entries.size)
    }
    val sources = HashSet<String>()
    for (entry in entries) {
        sources.add(entry.sourcePackage ?: "")
    }
    return context.getString(R.string.codes_subtitle, entries.size, sources.size)
}

@Composable
private fun SearchBar() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = UiBridge.query.value,
            onValueChange = { UiBridge.query.value = it },
            label = stringResource(R.string.search_hint),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))

        // Compact pill with the number of active dimensions, so the state stays readable
        // no matter how long the label text would get ("筛选" alone looks inactive).
        val activeCount = UiBridge.filterSources.value.size +
                UiBridge.filterTypes.value.size +
                if (UiBridge.query.value.isNotBlank()) 1 else 0
        val shape = RoundedCornerShape(18.dp)
        val tint = if (activeCount > 0) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        }
        Text(
            text = if (activeCount > 0) {
                stringResource(R.string.filter_chip_active, activeCount)
            } else {
                stringResource(R.string.filter_chip_idle)
            },
            fontSize = 14.sp,
            fontWeight = if (activeCount > 0) FontWeight.Bold else FontWeight.Normal,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .clip(shape)
                .border(1.dp, tint.copy(alpha = 0.5f), shape)
                .clickable { UiBridge.host?.showFilterDialog() }
                .padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun GroupHeader(row: HeaderRow) {
    val countText = stringResource(R.string.entry_count, row.count)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                // Fold/expand and group editing are dead while a card is dragged or
                // flying back - the drag finger must never trigger a header action.
                enabled = UiBridge.drag.activeKey == null && !UiBridge.drag.settling,
                onClick = { toggleCollapse(row.key) },
                onLongClick = { UiBridge.host?.onGroupEdit(row.key) }
            )
            .padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "▶",
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.graphicsLayer(rotationZ = if (row.collapsed) 0f else 90f)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (row.key.isEmpty()) countText else row.key + "  ·  " + countText,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun toggleCollapse(key: String) {
    if (UiBridge.collapsed.contains(key)) {
        UiBridge.collapsed.remove(key)
    } else {
        UiBridge.collapsed.add(key)
    }
}

/**
 * The card body: left column issuer/account/meta, middle column code/countdown, and the
 * three-bar handle as a sibling so body taps never bleed into the handle (and vice versa).
 * Progress bar spans the full card width at the bottom.
 */
@Composable
private fun EntryCard(
    entry: TotpEntry,
    now: Long,
    dragVisual: DragVisualState,
    isFloating: Boolean = false
) {
    val itemKey = "e:" + entry.id
    val dragging = dragVisual.activeKey == itemKey

    // The floating overlay twin renders the card wherever the finger is; the in-list
    // twin goes invisible but keeps its slot. On release the overlay vanishes and the
    // in-list card reappears at releaseOffset (where the overlay was), springing back
    // into its slot - one continuous motion across both layers.
    val settle = remember { Animatable(Float.NaN) }
    // Press feedback: shrink while the finger is down, then over-spring past rest
    // and settle on release. Driven by the interaction source, so it tracks the
    // finger instead of firing after the fact.
    val pressInteraction = remember { MutableInteractionSource() }
    val pressScale = remember { Animatable(1f) }
    // Collect the interaction stream directly: collectIsPressedAsState misses
    // one-frame taps entirely (down and up land in the same compose frame, so the
    // pressed=true recomposition never happens). Per-event collection guarantees
    // every Press runs its shrink and every Release its over-spring; a fast tap
    // simply queues the Release behind the shrink instead of skipping both.
    LaunchedEffect(pressInteraction) {
        pressInteraction.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press ->
                    pressScale.animateTo(0.96f, tween(100))
                is PressInteraction.Release -> {
                    // The natural spring overshoot over such a short travel is
                    // invisible (~0.3%), so the over-swing is explicit: shoot past
                    // rest to 1.03 - bigger than the resting size - then settle.
                    pressScale.animateTo(1.03f, tween(130))
                    pressScale.animateTo(
                        1f,
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                }
                is PressInteraction.Cancel ->
                    pressScale.animateTo(
                        1f,
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    )
            }
        }
    }
    var wasDragging by remember { mutableStateOf(false) }
    val dark = isSystemInDarkTheme()
    LaunchedEffect(dragging) {
        if (wasDragging && !dragging) {
            settle.snapTo(dragVisual.releaseOffset)
            settle.animateTo(
                0f,
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
        wasDragging = dragging
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .alpha(if (dragging && !isFloating) 0f else 1f)
            .graphicsLayer {
                shape = RoundedCornerShape(16.dp)
                if (dragging && isFloating) {
                    // Fly-back progress blends the "lifted" look back to resting.
                    val rest = dragVisual.settleProgress
                    shadowElevation = 24f - 16f * rest
                    val base = 1.03f - 0.03f * rest
                    scaleX = base * pressScale.value
                    scaleY = base * pressScale.value
                } else {
                    shadowElevation = if (dragging) 24f else 8f
                    translationY = if (settle.value.isNaN()) 0f else settle.value
                    scaleX = pressScale.value
                    scaleY = pressScale.value
                }
            }
            .border(
                width = 1.dp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                shape = RoundedCornerShape(16.dp)
            ),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = cardTint(dark))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // Disabled while this card is dragged (or flying back): the finger
                    // holding the handle must never fire tap/long-press on the body -
                    // a stray tap would copy a code or advance a HOTP counter.
                    .combinedClickable(
                        interactionSource = pressInteraction,
                        enabled = !dragging,
                        onClick = { UiBridge.host?.onEntryTap(entry) },
                        onLongClick = { UiBridge.host?.onEntryEdit(entry) }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 12.dp)
                ) {
                    Text(
                        text = entry.displayName(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val account = entry.displayAccount()
                    if (account.isNotEmpty() && account != entry.displayName()) {
                        Text(
                            text = account,
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = metaText(entry),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(
                    Modifier.weight(1.2f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val raw = if (entry.isSupported()) TotpGenerator.generate(entry) else ""
                    val display = if (raw.isEmpty()) "----" else TotpGenerator.format(raw, entry.isSteam())
                    Text(
                        text = display,
                        fontSize = codeFontSize(display.length),
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (entry.isSupported() && !entry.isHotp()) {
                        Text(
                            text = TotpGenerator.secondsLeft(entry.period, now).toString(),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            DragHandle(
                entry = entry,
                modifier = Modifier.width(44.dp).fillMaxHeight()
            )
        }
        if (entry.isSupported() && !entry.isHotp()) {
            val progress = TotpGenerator.progressLeft(entry.period, now) / 1000f
            val fillColor = MiuixTheme.colorScheme.primary
            val trackColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            Canvas(
                Modifier
                    .padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
                    .fillMaxWidth()
                    .height(3.dp)
            ) {
                val radius = CornerRadius(size.height / 2f, size.height / 2f)
                drawRoundRect(trackColor, cornerRadius = radius)
                drawRoundRect(
                    fillColor,
                    size = Size(size.width * progress.coerceIn(0f, 1f), size.height),
                    cornerRadius = radius
                )
            }
        }
    }
}

/** Bigger codes get smaller type; mirrors the old autosize 16..30sp behaviour. */
private fun codeFontSize(length: Int): androidx.compose.ui.unit.TextUnit = when {
    length <= 6 -> 30.sp
    length == 7 -> 26.sp
    length == 8 -> 23.sp
    else -> 19.sp
}

private fun metaText(entry: TotpEntry): String {
    val sb = StringBuilder(entry.type)
    if (!entry.isSteam() && !entry.isHotp()) {
        sb.append(" · ").append(entry.getAlgorithmForMac())
        sb.append(" · ").append(entry.period).append("s")
    }
    if (entry.isHotp()) {
        sb.append(" · #").append(entry.counter)
    }
    if (!entry.group.isNullOrEmpty()) {
        sb.append(" · ").append(entry.group)
    }
    return sb.toString()
}

// ---------------------------------------------------------------------------
// Three-bar handle: tap = share menu. The long-press drag lives on the LIST
// CONTAINER (see CodesPage) because a container never changes coordinates,
// while a handle node moves with every slot swap.
// ---------------------------------------------------------------------------

@Composable
private fun DragHandle(entry: TotpEntry, modifier: Modifier) {
    Box(
        modifier = modifier
            .pointerInput(entry.id) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // While a drag is in flight anywhere in the list, handle taps
                        // are dead: the active pointer belongs to the drag, and events
                        // surfacing here are stale echoes, not real taps.
                        if (UiBridge.drag.activeKey != null) {
                            continue
                        }
                        // Quick tap -> share menu. A long press ends the wait with null and is
                        // owned by the list-level drag handler, so nothing happens here; a
                        // scroll stealing the pointer also ends with null (consumed).
                        val tapped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var up = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                when {
                                    change == null || !change.pressed -> {
                                        up = true
                                        break
                                    }
                                    change.isConsumed -> break
                                }
                            }
                            up
                        }
                        if (tapped == true) {
                            UiBridge.host?.onEntryShareMenu(entry)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) {
                Box(
                    Modifier
                        .size(width = 20.dp, height = 2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Empty state, FAB, bottom navigation
// ---------------------------------------------------------------------------

@Composable
private fun EmptyView(filtering: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(
                if (filtering) R.string.empty_filtered_title else R.string.empty_title
            ),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                if (filtering) R.string.empty_filtered_hint else R.string.empty_hint
            ),
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AddFab(modifier: Modifier) {
    val drag = UiBridge.drag
    // Shrinks into nothing the moment a drag starts, pops back with a little
    // overshoot the moment the finger LIFTS (not when the fly-back lands).
    // Center-anchored scale; the tap stays dead until the handover is done.
    val gone = drag.fabGone
    val locked = drag.activeKey != null || drag.settling
    val scale = remember { Animatable(1f) }
    // Same press feedback as the cards: shrink on hold, shoot past rest on release.
    val fabInteraction = remember { MutableInteractionSource() }
    val pressScale = remember { Animatable(1f) }
    LaunchedEffect(fabInteraction) {
        fabInteraction.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press ->
                    pressScale.animateTo(0.9f, tween(100))
                is PressInteraction.Release -> {
                    pressScale.animateTo(1.06f, tween(130))
                    pressScale.animateTo(
                        1f,
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                }
                is PressInteraction.Cancel ->
                    pressScale.animateTo(
                        1f,
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    )
            }
        }
    }
    LaunchedEffect(gone) {
        when {
            gone && scale.value > 0f ->
                scale.animateTo(0f, tween(150))
            !gone && scale.value < 1f ->
                scale.animateTo(
                    1f,
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
        }
    }
    Box(
        modifier = modifier
            .size(56.dp)
            .graphicsLayer {
                val s = scale.value * pressScale.value
                scaleX = s
                scaleY = s
                alpha = if (s < 0.05f) 0f else 1f
            }
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.primary)
            .clickable(
                interactionSource = fabInteraction,
                indication = LocalIndication.current,
                enabled = !locked
            ) { UiBridge.host?.onAdd() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "+",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
private fun BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    Column {
        // Hairline above the bar separates it from the page background.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .background(MiuixTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NavTab(stringResource(R.string.nav_codes), selected == 0, Modifier.weight(1f)) {
                onSelect(0)
            }
            NavTab(stringResource(R.string.nav_info), selected == 1, Modifier.weight(1f)) {
                onSelect(1)
            }
        }
    }
}

@Composable
private fun NavTab(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(50))
            .background(
                if (active) {
                    MiuixTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Info page: the legacy Java view hosted as-is inside Compose.
// ---------------------------------------------------------------------------

@Composable
private fun InfoPageHost() {
    val context = LocalContext.current
    var infoPage by remember { mutableStateOf<InfoPage?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = LayoutInflater.from(ctx).inflate(R.layout.page_info, null, false)
            infoPage = InfoPage(
                ctx as Activity,
                view,
                object : InfoPage.Listener {
                    override fun onRedetect() {
                        UiBridge.host?.onRedetect()
                    }

                    override fun onOpenRootManager() {
                        UiBridge.host?.onOpenRootManager()
                    }

                    override fun onFastPathChanged(enabled: Boolean) {
                        UiBridge.host?.onFastPathChanged(enabled)
                    }

                    override fun onStart() {
                        UiBridge.host?.onSetupStart()
                    }

                    override fun onExportAll() {
                        UiBridge.host?.onExportAll()
                    }

                    override fun onPickLanguage() {
                        UiBridge.host?.onPickLanguage()
                    }
                }
            )
            view
        },
        update = { page ->
            // Reading infoVersion keeps the legacy page in sync with Java-side re-detection.
            UiBridge.infoVersion.intValue
            infoPage?.bind(UiBridge.moduleReady.value, UiBridge.setupDone.value)
        }
    )
}

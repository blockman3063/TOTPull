package io.blockman.totpull.ui

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.scrollBy
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
private class HeaderRow(val key: String, val title: String, val count: Int, val collapsed: Boolean, val hue: Float) : CodesRow()

/** One code card. */
private class EntryRow(val entry: TotpEntry, val hue: Float) : CodesRow()

// Soft per-source hues: every validator app gets a stable, low-saturation tint so groups
// are distinguishable at a glance without leaving the theme's overall feel.
private val GROUP_HUES = floatArrayOf(212f, 145f, 28f, 262f, 330f, 185f, 95f, 8f, 45f, 240f)

private fun groupHue(key: String): Float {
    return GROUP_HUES[Math.floorMod(key.hashCode(), GROUP_HUES.size)]
}

/** Faint card tint: barely saturated in light theme, a whisper in dark. */
private fun groupTint(hue: Float, dark: Boolean): Color {
    return Color.hsl(hue, if (dark) 0.30f else 0.45f, if (dark) 0.17f else 0.95f)
}

/** Saturated accent for the group header dot. */
private fun groupDot(hue: Float, dark: Boolean): Color {
    return Color.hsl(hue, 0.55f, if (dark) 0.62f else 0.48f)
}

/**
 * Visual state of an active drag, shared between the handle (writes, per pointer event) and
 * the dragged card (reads, per frame). The follow offset accumulates the finger's movement
 * relative to the handle itself: since the handle travels with the card, a slot change cancels
 * out of the delta automatically - no cross-generation coordinate conversion left to jitter.
 */
private class DragVisualState {
    var activeKey by mutableStateOf<String?>(null)

    /** Accumulated finger movement relative to the grab point. */
    var dy by mutableFloatStateOf(0f)

    /**
     * The dragged item's slot offset at grab time. The card's visual position is
     * [baseOffset] + [dy] in list coordinates (grab point plus finger travel); the
     * drawing code converts that to a per-slot translationY by subtracting the item's
     * CURRENT slot offset. This keeps the card glued to the finger no matter how the
     * slots rearrange - including while sibling rows are still animating their spring
     * placement, whose intermediate offsets must never leak into the drag math.
     */
    var baseOffset by mutableFloatStateOf(0f)

    /**
     * Finger Y in list-container coordinates, updated per pointer event and kept
     * between events so the auto-scroll loop can keep swapping rows while the finger
     * rests inside an edge zone. -1 when no drag is active.
     */
    var lastFingerY by mutableFloatStateOf(-1f)
}

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

    Column(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) {
                CodesPage()
                AddFab(Modifier.align(Alignment.BottomEnd).padding(20.dp))
            } else {
                InfoPageHost()
            }
        }
        BottomNav(selected = tab) { UiBridge.showTab(it) }
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
    val source = UiBridge.filterSource.value
    val type = UiBridge.filterType.value
    val collapsed = UiBridge.collapsed.toSet()
    val entries = UiBridge.host?.entries() ?: emptyList()
    val computed = buildRows(context, entries, query, source, type, collapsed)
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
    val dragVisual = remember { DragVisualState() }

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
            // feed it a finger Y in container coordinates. Hysteresis: swap only once
            // the finger crossed the target's center, so hovering a row boundary
            // cannot flicker back and forth.
            val checkSwap: (Float) -> Unit = checkSwap@{ y ->
                val draggedInfo = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == dragVisual.activeKey }
                    ?: return@checkSwap
                val targetInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull {
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
                    // anchored to the finger (baseOffset + dy) and the per-slot
                    // translation subtracts the item's live offset, so a slot jump
                    // cancels out at draw time. Adding compensation on top would
                    // double-correct, and reading the target's offset mid-spring
                    // would drift.
                    UiBridge.host?.moveEntry(draggedEntry, target)
                }
            }

            // While a drag is active, holding the finger inside an edge band scrolls
            // the list - faster the deeper into the band the finger is - and keeps
            // running swap detection, so the card re-orders while the finger rests.
            val density = LocalDensity.current
            LaunchedEffect(dragVisual.activeKey) {
                val edge = with(density) { 56.dp.toPx() }
                val maxSpeed = with(density) { 16.dp.toPx() }
                while (dragVisual.activeKey != null && isActive) {
                    val y = dragVisual.lastFingerY
                    if (y >= 0f) {
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

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // The drag handler lives on the LIST CONTAINER, not on the handle node:
                        // the container never moves, so positionChange is pure finger movement
                        // no matter how the rows swap around underneath. Hanging it on the
                        // handle made the deltas include the item's own relocation - teleporting.
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                // Only the three-bar zone of an entry card starts a drag.
                                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                    offset.y >= it.offset && offset.y < it.offset + it.size
                                }
                                val row =
                                    rowsState.value.getOrNull(info?.index ?: -1) as? EntryRow
                                if (row != null && offset.x >= size.width - 58.dp.toPx()) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragVisual.activeKey = "e:" + row.entry.id
                                    dragVisual.dy = 0f
                                    dragVisual.baseOffset = (info?.offset ?: 0).toFloat()
                                    dragVisual.lastFingerY = offset.y
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (dragVisual.activeKey == null) {
                                    return@detectDragGesturesAfterLongPress
                                }
                                // Follow offset: pure finger movement, no frame lag.
                                dragVisual.dy += dragAmount.y
                                dragVisual.lastFingerY = change.position.y
                                checkSwap(change.position.y)
                            },
                            onDragEnd = {
                                dragVisual.activeKey = null
                                dragVisual.lastFingerY = -1f
                                UiBridge.host?.persistOrder()
                            },
                            onDragCancel = {
                                dragVisual.activeKey = null
                                dragVisual.lastFingerY = -1f
                                UiBridge.host?.persistOrder()
                            }
                        )
                    }
            ) {
                itemsIndexed(computed, key = { _, row -> rowKey(row) }) { index, row ->
                    val itemKey = rowKey(row)
                    val dragging = dragVisual.activeKey == itemKey
                    Box(
                        Modifier
                            .animateItem(
                                // The dragged card is positioned by hand (translation follows
                                // the finger); animating its slot too makes the two fight.
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
                            is EntryRow -> EntryCard(row.entry, now, dragVisual, listState, row.hue)
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
    source: String?,
    type: String?,
    collapsed: Set<String>
): List<CodesRow> {
    val groups = LinkedHashMap<String, MutableList<TotpEntry>>()
    for (entry in entries) {
        if (!matches(entry, query, source, type)) {
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
        val hue = groupHue(key)
        val title = if (key.isEmpty()) {
            context.getString(R.string.local_source)
        } else {
            bucket[0].displaySource()
        }
        val isCollapsed = key in collapsed
        rows.add(HeaderRow(key, title, bucket.size, isCollapsed, hue))
        if (!isCollapsed) {
            for (entry in bucket) {
                rows.add(EntryRow(entry, hue))
            }
        }
    }
    return rows
}

private fun matches(entry: TotpEntry, query: String, source: String?, type: String?): Boolean {
    if (source != null && (entry.sourcePackage ?: "") != source) {
        return false
    }
    if (type != null && !typeMatches(entry, type)) {
        return false
    }
    if (query.isEmpty()) {
        return true
    }
    return contains(entry.issuer, query) || contains(entry.name, query) ||
            contains(entry.group, query) || contains(entry.note, query) ||
            contains(entry.sourceLabel, query) || contains(entry.sourcePackage, query) ||
            contains(entry.type, query)
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
        val active = UiBridge.isFiltering()
        Text(
            text = stringResource(if (active) R.string.filter_active else R.string.filter_title),
            fontSize = 14.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            },
            modifier = Modifier.clickable { UiBridge.host?.showFilterDialog() }
        )
    }
}

@Composable
private fun GroupHeader(row: HeaderRow) {
    val countText = stringResource(R.string.entry_count, row.count)
    val dark = isSystemInDarkTheme()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
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
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(groupDot(row.hue, dark))
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
    listState: LazyListState,
    hue: Float
) {
    val itemKey = "e:" + entry.id
    val dragging = dragVisual.activeKey == itemKey

    // Live slot offset of this item in list coordinates. The visual anchor is
    // baseOffset + dy (grab point plus finger travel); the per-slot translation is
    // that anchor minus the item's current offset, so slot jumps - including the
    // spring-animated ones of sibling rows - cancel out at draw time and the card
    // never separates from the finger.
    fun slotOffset(): Float {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey }
        return if (info != null) info.offset.toFloat() else dragVisual.baseOffset
    }

    // The follow offset is written per pointer event by the list-level drag handler; read at
    // draw time so the card is glued to the finger with zero frame lag. On release it springs
    // back into the slot. NaN until the first release, so the release frame never flashes to 0.
    val settle = remember { Animatable(Float.NaN) }
    var wasDragging by remember { mutableStateOf(false) }
    val dark = isSystemInDarkTheme()
    LaunchedEffect(dragging) {
        if (wasDragging && !dragging) {
            // Finger released: spring from the last follow position back into the slot.
            settle.snapTo(dragVisual.baseOffset + dragVisual.dy - slotOffset())
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
            .graphicsLayer {
                shape = RoundedCornerShape(16.dp)
                // Resting shadow keeps the card lifted off the page background; dragging
                // raises it further.
                shadowElevation = if (dragging) 24f else 8f
                if (dragging) {
                    translationY = dragVisual.baseOffset + dragVisual.dy - slotOffset()
                    scaleX = 1.03f
                    scaleY = 1.03f
                } else {
                    translationY = if (settle.value.isNaN()) 0f else settle.value
                }
            }
            .border(
                width = 1.dp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                shape = RoundedCornerShape(16.dp)
            ),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = groupTint(hue, dark))
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
                    .combinedClickable(
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
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.primary)
            .clickable { UiBridge.host?.onAdd() },
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

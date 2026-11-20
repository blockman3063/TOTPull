package io.blockman.totpull.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import io.blockman.totpull.TotpEntry

/**
 * Bridge between the legacy Java activity (business logic, store, dialogs) and the
 * Compose/miuix UI. All shared UI state lives here as Compose state objects so both
 * languages can read and write it, and recomposition is driven automatically.
 */
object UiBridge {
    /** Callbacks implemented by [MainActivity]. Business logic stays on the Java side. */
    interface Host {
        /** Snapshot of the vault list; may be mutated between calls, so always re-read. */
        fun entries(): List<TotpEntry>

        /** Card body tap: copy the code (HOTP advances its counter). */
        fun onEntryTap(entry: TotpEntry)

        /** Card body long-press: open the entry editor. */
        fun onEntryEdit(entry: TotpEntry)

        /** Three-bar handle tap: share menu for a single entry. */
        fun onEntryShareMenu(entry: TotpEntry)

        /** Group header long-press: open the group editor. */
        fun onGroupEdit(packageName: String)

        /** Floating "+" button. */
        fun onAdd()

        /** Bottom navigation tab change; keeps first-run / setup-done side effects on Java. */
        fun onTabChanged(tab: Int)

        /** Filter chip tap: Java shows the radio filter dialog. */
        fun showFilterDialog()

        /** Drag reorder: move {@code entry} to where {@code target} currently sits. */
        fun moveEntry(entry: TotpEntry, target: TotpEntry)

        /** Drag finished with at least one move: persist the vault order. */
        fun persistOrder()

        // ---- InfoPage listener callbacks (re-exported by the Compose wrapper) ----

        fun onRedetect()
        fun onOpenRootManager()
        fun onFastPathChanged(enabled: Boolean)

        /** InfoPage "start" button: finish first-run setup and land on the codes page. */
        fun onSetupStart()
        fun onExportAll()
    }

    @Volatile
    var host: Host? = null

    // ---------------------------------------------------------- drag reorder

    /**
     * Drag-reorder state shared between the codes page (which owns the list and the
     * swap logic) and the root-level gesture/overlay (which must see events even when
     * the finger leaves the list, and render the card above everything). All screen
     * coordinates here are in LIST-CONTAINER space unless noted; [containerTopInRoot]
     * is the conversion offset into root coordinates for the overlay.
     */
    val drag = DragVisualState()

    /** Bumped by the Java side whenever the vault list content or order changed. */
    val dataVersion = mutableIntStateOf(0)

    /** 0 = codes page, 1 = info page. */
    val tab = mutableIntStateOf(0)

    /** Search text; blank means no filter. */
    val query = mutableStateOf("")

    /** Filter dimensions; null = unrestricted, "" = hand-added entries. */
    val filterSource = mutableStateOf<String?>(null)
    val filterType = mutableStateOf<String?>(null)

    /** Group keys (sourcePackage) the user folded; survives rebuilds and filters. */
    val collapsed = mutableStateListOf<String>()

    /** Last known module readiness; null = not determined yet. Read by the info page. */
    val moduleReady = mutableStateOf<Boolean?>(null)

    /** Whether the first-run setup was finished. Read by the info page. */
    val setupDone = mutableStateOf(false)

    /**
     * Bumped to force the info page to re-read Capabilities (root state changed without
     * moduleReady flipping, so its own states would not trigger a repaint).
     */
    val infoVersion = mutableIntStateOf(0)

    // ---------------------------------------------------------- Java-facing helpers

    /** Call after any change to the vault list (load, add, reorder, import). */
    fun notifyDataChanged() {
        dataVersion.intValue++
    }

    fun showTab(value: Int) {
        tab.intValue = value
    }

    fun readFilterSource(): String? = filterSource.value

    fun setFilterSource(value: String?) {
        filterSource.value = value
    }

    fun readFilterType(): String? = filterType.value

    fun setFilterType(value: String?) {
        filterType.value = value
    }

    fun setModuleReady(value: Boolean?) {
        moduleReady.value = value
    }

    fun setSetupDone(value: Boolean) {
        setupDone.value = value
    }

    /** Java asks for an info-page repaint after re-detecting root / module state. */
    fun refreshInfo() {
        infoVersion.intValue++
    }

    /** True when any filter dimension or a non-blank query is set. */
    fun isFiltering(): Boolean {
        return query.value.isNotBlank() || filterSource.value != null || filterType.value != null
    }
}

/**
 * State of an in-flight drag reorder. The root-level long-press gesture (MainScreen)
 * calls [probe], [moved] and [finish]; the codes page installs their implementations
 * (it owns the list) plus [slotOffset] and [containerTopInRoot] for the overlay.
 */
class DragVisualState {
    var activeKey by mutableStateOf<String?>(null)

    /** Accumulated finger movement relative to the grab point, in list coordinates. */
    var dy by mutableFloatStateOf(0f)

    /** The dragged item's slot offset at grab time (list coordinates). */
    var baseOffset by mutableFloatStateOf(0f)

    /**
     * Finger Y in list coordinates, kept between pointer events so the auto-scroll
     * loop keeps swapping rows while the finger rests in an edge zone. -1 when idle.
     */
    var lastFingerY by mutableFloatStateOf(-1f)

    /** Entry being dragged and its group hue, for the floating overlay card. */
    var overlayEntry: TotpEntry? = null
    var overlayHue by mutableFloatStateOf(0f)

    /** The list container's top edge in root coordinates (overlay placement). */
    var containerTopInRoot by mutableFloatStateOf(0f)

    /**
     * Card offset relative to its final slot at release time; the in-list card springs
     * from here back to 0 to match where the overlay card was when it vanished.
     */
    var releaseOffset by mutableFloatStateOf(0f)

    // Installed by CodesPage; invoked from the root-level gesture in MainScreen.
    var probe: (x: Float, y: Float) -> Boolean = { _, _ -> false }
    var moved: (dy: Float, y: Float) -> Unit = { _, _ -> }
    var finish: () -> Unit = {}

    /** Live slot offset of the dragged item in list coordinates (set by CodesPage). */
    var slotOffset: () -> Float = { 0f }
}

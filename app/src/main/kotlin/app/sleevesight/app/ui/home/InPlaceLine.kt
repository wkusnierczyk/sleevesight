package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.sleevesight.app.session.TrackIdentity
import app.sleevesight.app.ui.theme.ChipFrame
import app.sleevesight.app.ui.theme.Panel
import app.sleevesight.app.ui.theme.chipColours
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff
import app.sleevesight.core.model.NowPlaying

// The line of buttons under the album, each opening a panel in place (#724: *Dates*; #772: *Genres*;
// #728: *Lyrics*), and the panels open, stacked under it.

/** An item of the line under the album: a button that opens its panel in place. */
internal enum class InPlace {
    /** *Dates* (#724). */
    DATES,

    /** *Genres* (#772). */
    GENRES,

    /** *Lyrics* (#728). */
    LYRICS,
}

/**
 * An item of the line as shown: [id], its button's and panel's title [name], its button's action as
 * a screen reader names it ([action]: "show dates", "show genres"), the test tags of its button ([tag]) and its
 * panel ([panelTag], as [Panel] makes them, with `:heading` and `:close`), and what its panel holds
 * ([content]). [content] is handed `toClose`, which moves the focus to the panel's *Close* (#724),
 * for a control in it that goes once tapped (*Retry*, [RetryLine]): else a keyboard's or a screen
 * reader's focus would fall to the top of the screen. With [page], its button opens that page in
 * place of the panel (*Genres* under *Separate pages*, #772), and [action] names that.
 */
@Suppress("LongParameterList") // What the item shows and does, each its own.
internal class InPlaceItem(
    val id: InPlace,
    val name: String,
    val action: String,
    val tag: String,
    val panelTag: String,
    val page: (() -> Unit)? = null,
    val content: @Composable ColumnScope.(toClose: () -> Unit) -> Unit,
)

/**
 * Which panels of the line are open, the one opened last first ([opened]), and the track they were
 * opened on ([track]): kept across recompositions and a recreated screen (a rotation, or Android
 * restoring the screen it had closed), as *Sources*' fold is ([FoldMemory]). A new track closes them
 * all ([openOn]).
 */
@Stable
internal class InPlaceMemory(
    opened: List<InPlace> = emptyList(),
    track: NowPlaying? = null,
) {
    var opened: List<InPlace> by mutableStateOf(opened)
        private set
    var track: NowPlaying? by mutableStateOf(track)
        private set

    /** The panels open beside [playing], the one opened last first: none for a new track. */
    fun openOn(playing: NowPlaying): List<InPlace> =
        track?.takeIf { TrackIdentity.playsOn(it, playing) }?.let { opened }.orEmpty()

    /** Opens [item]'s panel beside [playing], above those open already. */
    fun open(
        item: InPlace,
        playing: NowPlaying,
    ) {
        opened = listOf(item) + openOn(playing).filter { it != item }
        track = playing
    }

    /**
     * On a new track, keeps it as the one the panels are open on, with none open, so a track played
     * again after others starts with them closed too.
     */
    fun follow(playing: NowPlaying) {
        if (track?.let { TrackIdentity.playsOn(it, playing) } == true) return
        opened = emptyList()
        track = playing
    }

    /** Closes every panel beside [playing]: the one panel's *Close* (#1128). */
    fun closeAll(playing: NowPlaying) {
        opened = emptyList()
        track = playing
    }

    /** Closes [item]'s panel beside [playing]; the others stay open. */
    fun close(
        item: InPlace,
        playing: NowPlaying,
    ) {
        opened = openOn(playing).filter { it != item }
        track = playing
    }

    companion object {
        /** Saved as plain values: the panels open, by name, and the track as [savedTrack] keeps it. */
        val Saver: Saver<InPlaceMemory, Any> =
            listSaver(
                save = { memory ->
                    listOf<Any?>(memory.opened.joinToString(",") { it.name }) + savedTrack(memory.track)
                },
                restore = { saved ->
                    val names = (saved[0] as String).split(',').filter(String::isNotEmpty)
                    InPlaceMemory(
                        names.mapNotNull { name -> InPlace.entries.firstOrNull { it.name == name } },
                        restoredTrack(saved.drop(1)),
                    )
                },
            )
    }
}

/**
 * The line's memory, kept as [InPlaceMemory] is; remembered above whatever comes and goes with the
 * track (nothing playing for a while), as *Sources*' fold is.
 */
@Composable
internal fun rememberInPlaceMemory(): InPlaceMemory = rememberSaveable(saver = InPlaceMemory.Saver) { InPlaceMemory() }

/**
 * The line under the album (#724, #772): the buttons of [items] whose panels are closed, on one line in
 * [items]' order (each drawn as *Sources*' chip is, [PanelChip]), then the panels open, the one
 * opened last on top ([InPlaceMemory.opened]), each in the frame of Home's other closable panels
 * ([Panel]): its title as a heading, and *Close* at its top right, which folds it back into its
 * button. A button opens its panel in its place, whatever *Layout* says, but for an item with a
 * page ([InPlaceItem.page]: *Genres* under *Separate pages*, #772), whose button opens the page and
 * stays. Beside [playing], from [memory]: a new track closes every panel.
 *
 * The focus moves as *Sources*' does ([FocusHandoff], #629): to a panel's *Close* when its button
 * opens it, back to its button when *Close* closes it (a keyboard's, and a screen reader's; nothing
 * moves while merely touched), and to its button when a new track closes a panel while the focus is
 * in it, if the new track has that button; and to its *Close* when a control in it that goes once
 * tapped asks for it ([InPlaceItem.content]'s `toClose`). Nothing at all when [items] is empty.
 *
 * With [onePanel] (*One panel for Dates, Genres and Lyrics*, #1128), the buttons open one shared
 * panel instead ([OnePanelLine]).
 */
@Composable
internal fun InPlaceLine(
    items: List<InPlaceItem>,
    memory: InPlaceMemory,
    playing: NowPlaying,
    onePanel: Boolean = false,
) {
    if (onePanel) {
        OnePanelLine(items, memory, playing)
        return
    }
    val handoff = rememberFocusHandoff()
    val buttons = remember { mutableMapOf<InPlace, FocusRequester>() }
    val closes = remember { mutableMapOf<InPlace, FocusRequester>() }
    val buttonOf = { id: InPlace -> buttons.getOrPut(id) { FocusRequester() } }
    val closeOf = { id: InPlace -> closes.getOrPut(id) { FocusRequester() } }
    val shown = items.filter { it.page == null }.map { it.id }
    val open = memory.openOn(playing).filter { it in shown }
    // Which panels hold the input focus, as last told, and which were open: plain, read as a panel goes.
    val inside = remember { mutableSetOf<InPlace>() }
    val before = remember { mutableSetOf<InPlace>().apply { addAll(open) } }
    // A panel gone by other than *Close* (a new track) while the focus was in it: back to its button,
    // or a keyboard's focus would fall to the top of the screen. Read before the panel goes.
    val handBack = before.firstOrNull { it !in open && it in inside }
    SideEffect {
        memory.follow(playing)
        before.clear()
        before.addAll(open)
        // No panel, no focus in it: a panel that goes may not be told it lost the focus.
        inside.retainAll(open.toSet())
        if (handBack != null) handoff.moveTo(buttonOf(handBack))
    }
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val closed = items.filter { it.id !in open }
        if (closed.isNotEmpty()) {
            ClosedButtons(closed, { Modifier.handoffTarget(handoff, buttonOf(it)) }) { item ->
                val page = item.page
                if (page != null) {
                    page()
                } else {
                    memory.open(item.id, playing)
                    handoff.moveTo(closeOf(item.id))
                }
            }
        }
        for (id in open) {
            // Keyed by the item, not its place: a panel opened above another keeps the other's state
            // (the genre open in *Genres*, #772), which a place in the list would hand to the newcomer.
            key(id) {
                OpenPanel(
                    items.first { it.id == id },
                    Modifier.handoffTarget(handoff, closeOf(id)),
                    focused = { if (it) inside += id else inside -= id },
                    toClose = { handoff.moveTo(closeOf(id)) },
                ) {
                    memory.close(id, playing)
                    handoff.moveTo(buttonOf(id))
                }
            }
        }
    }
}

// The buttons of [closed], on one line in their order, each under [target]'s modifier for it (a focus
// requester), each [tap]ped as drawn; keyed by the item, as the open panels are.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClosedButtons(
    closed: List<InPlaceItem>,
    target: (InPlace) -> Modifier,
    tap: (InPlaceItem) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in closed) {
            key(item.id) {
                // Read as picked (#724): "Dates. Button. Double-tap to show dates", no fold state.
                PanelChip(item.name, item.tag, target(item.id), collapsed = false, action = item.action) { tap(item) }
            }
        }
    }
}

// [item]'s panel, open: told whether the input focus is in it ([focused]); its *Close*, under
// [closeModifier] (a focus requester), [close]s it, and [toClose] moves the focus to it.
@Composable
private fun OpenPanel(
    item: InPlaceItem,
    closeModifier: Modifier,
    focused: (Boolean) -> Unit,
    toClose: () -> Unit,
    close: () -> Unit,
) {
    Box(Modifier.onFocusChanged { focused(it.hasFocus) }) {
        Panel(
            AnnotatedString(item.name),
            item.panelTag,
            onClose = close,
            closeModifier = closeModifier,
        ) { item.content(this, toClose) }
    }
}

/**
 * The line under the album as one shared panel (#1128, *One panel for Dates, Genres and Lyrics*):
 * folded, the buttons of [items] on one line, as [InPlaceLine] draws them; a tap on one opens the one
 * panel on its content ([InPlaceItem.content]), with the buttons at its top as its tabs, in [items]'
 * order, the one shown filled as a chosen genre chip is ([PanelTabs]), and *Close* at its top right,
 * which folds the panel back into the buttons. A tap on another tab switches the panel to it; each
 * tab's content keeps its state while the panel stays open. The tabs look the same whatever their
 * content holds: only the content says why it is empty. An item with a page ([InPlaceItem.page])
 * stays a button that opens it, above the panel. The panel shown is the one opened last
 * ([InPlaceMemory.opened]), so a new track folds it, as it closes every panel.
 *
 * The focus: to the tab tapped when a button opens the panel, back to the button of the tab shown
 * when *Close* folds it, or when a new track folds it while the focus is in it; to *Close* when a
 * control in it that goes once tapped asks for it. A screen reader reads the tabs as tabs, the one
 * shown selected, with their place among them ("Selected, Dates, tab, 1 of 3").
 */
@Composable
private fun OnePanelLine(
    items: List<InPlaceItem>,
    memory: InPlaceMemory,
    playing: NowPlaying,
) {
    val handoff = rememberFocusHandoff()
    val buttons = remember { mutableMapOf<InPlace, FocusRequester>() }
    val tabs = remember { mutableMapOf<InPlace, FocusRequester>() }
    val closeTarget = remember { FocusRequester() }
    val buttonOf = { id: InPlace -> buttons.getOrPut(id) { FocusRequester() } }
    val tabOf = { id: InPlace -> tabs.getOrPut(id) { FocusRequester() } }
    val inPanel = items.filter { it.page == null }
    val shown = memory.openOn(playing).firstOrNull { id -> inPanel.any { it.id == id } }
    // Whether the panel holds the input focus, as last told, and which tab it showed: plain, read as it goes.
    val inside = remember { BooleanArray(1) }
    val before = remember { arrayOfNulls<InPlace>(1).also { it[0] = shown } }
    // The panel gone by other than *Close* (a new track) while the focus was in it: back to the button
    // of the tab it showed, or a keyboard's focus would fall to the top of the screen.
    val handBack = before[0]?.takeIf { shown == null && inside[0] }
    SideEffect {
        memory.follow(playing)
        before[0] = shown
        if (shown == null) inside[0] = false
        if (handBack != null) handoff.moveTo(buttonOf(handBack))
    }
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val folded = if (shown == null) items else items.filter { it.page != null }
        if (folded.isNotEmpty()) {
            ClosedButtons(folded, { Modifier.handoffTarget(handoff, buttonOf(it)) }) { item ->
                val page = item.page
                if (page != null) {
                    page()
                } else {
                    memory.open(item.id, playing)
                    handoff.moveTo(tabOf(item.id))
                }
            }
        }
        if (shown != null) {
            val item = inPanel.first { it.id == shown }
            // Each tab's state kept while the panel is open (the genre open in *Genres*), dropped once it folds.
            val states = rememberSaveableStateHolder()
            Box(Modifier.onFocusChanged { inside[0] = it.hasFocus }) {
                Panel(
                    AnnotatedString(item.name),
                    item.panelTag,
                    onClose = {
                        memory.closeAll(playing)
                        handoff.moveTo(buttonOf(shown))
                    },
                    closeModifier = Modifier.handoffTarget(handoff, closeTarget),
                    titleSlot = { room ->
                        PanelTabs(inPanel, shown, room, { Modifier.handoffTarget(handoff, tabOf(it)) }) { tapped ->
                            memory.open(tapped.id, playing)
                        }
                    },
                ) {
                    states.SaveableStateProvider(item.id) { item.content(this, { handoff.moveTo(closeTarget) }) }
                }
            }
        }
    }
}

// The one panel's tabs (#1128): [tabs]' buttons, in their order, wrapping onto more lines as they
// need, each under [target]'s modifier for it (a focus requester); [shown]'s filled, as a chosen
// genre chip is, and read as selected; each [pick]ed as tapped. Read as a group of tabs, each with its
// place in it.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelTabs(
    tabs: List<InPlaceItem>,
    shown: InPlace,
    modifier: Modifier,
    target: (InPlace) -> Modifier,
    pick: (InPlaceItem) -> Unit,
) {
    FlowRow(
        modifier =
            modifier.testTag(IN_PLACE_TABS_TAG).semantics {
                selectableGroup()
                collectionInfo = CollectionInfo(rowCount = 1, columnCount = tabs.size)
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, item ->
            // Keyed by the item, so a tab keeps the focus when the panel switches to it.
            key(item.id) {
                val selected = item.id == shown
                val (fill, content) = chipColours(marked = selected)
                ChipFrame(
                    fill,
                    modifier = target(item.id).testTag(inPlaceTabTag(item.id)),
                    action =
                        Modifier
                            .selectable(selected = selected, role = Role.Tab) { pick(item) }
                            .semantics {
                                collectionItemInfo =
                                    CollectionItemInfo(rowIndex = 0, rowSpan = 1, columnIndex = i, columnSpan = 1)
                            },
                ) {
                    Text(item.name, style = MaterialTheme.typography.labelLarge, color = content)
                }
            }
        }
    }
}

/** The test tag of the one panel's tabs (#1128). */
internal const val IN_PLACE_TABS_TAG: String = "in-place-tabs"

/** The test tag of [id]'s tab in the one panel (#1128): `in-place-tab:dates`. */
internal fun inPlaceTabTag(id: InPlace): String = "in-place-tab:" + id.name.lowercase()

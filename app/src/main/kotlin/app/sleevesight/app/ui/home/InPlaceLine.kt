package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.sleevesight.app.session.TrackIdentity
import app.sleevesight.app.ui.theme.Panel
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff
import app.sleevesight.core.model.NowPlaying

// The line of buttons under the album, each opening a panel in place (#724: *Dates*; #772: *Genres*),
// and the panels open, stacked under it.

/** An item of the line under the album: a button that opens its panel in place. */
internal enum class InPlace {
    /** *Dates* (#724). */
    DATES,

    /** *Genres* (#772). */
    GENRES,
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
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InPlaceLine(
    items: List<InPlaceItem>,
    memory: InPlaceMemory,
    playing: NowPlaying,
) {
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                for (item in closed) {
                    PanelChip(
                        item.name,
                        item.tag,
                        modifier = Modifier.handoffTarget(handoff, buttonOf(item.id)),
                        // Read as picked (#724): "Dates. Button. Double-tap to show dates", no fold state.
                        collapsed = false,
                        action = item.action,
                    ) {
                        val page = item.page
                        if (page != null) {
                            page()
                        } else {
                            memory.open(item.id, playing)
                            handoff.moveTo(closeOf(item.id))
                        }
                    }
                }
            }
        }
        for (id in open) {
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

package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.wikipediaLine
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.pipeline.SourcesHeld
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An item's insight no source has anything for (#1308): its panel names the sources asked, as the
 * *Sources* chips name them, and each source it would ask that was not asked, switched off or without its
 * key, on a line of its own with a link to where it is set. A source not reached still takes precedence.
 */
@RunWith(RobolectricTestRunner::class)
class ItemSourcesAskedScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val notes = FakeItemNotes()

    private val opened = mutableListOf<SettingsTarget>()

    private val answered =
        track(
            sources =
                mapOf(SourceId.MUSICBRAINZ to done(), SourceId.WIKIPEDIA to done(evidence = listOf(wikipediaLine))),
        )

    @Suppress("LongParameterList") // What the panel shows, under what settings and keys.
    private fun show(
        item: InsightItem,
        settings: Settings = Settings(writeAutomatically = WriteAutomatically.NEVER),
        keys: Map<SecretId, SecretState> = SAVED,
        byName: Boolean = false,
        held: SourcesHeld? = null,
        block: () -> Unit,
    ) {
        val evidence = ItemEvidence(EvidenceSet.of(emptyList(), JvmHasher), emptyMap(), held = held, byName = byName)
        notes.shown.value = mapOf(item to ItemShown.Shown(InsightState.NoEvidence, evidence))
        val state =
            NowPlayingState(
                PipelineView(answered, aiReady = true),
                answered.nowPlaying,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = settings,
                keys = keys,
            )
        val actions = NowPlayingActions(normalizer::normalize, itemNotes = notes, openSettingsAt = { opened += it })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(itemRowTag(item)).performScrollTo().performClick()
            compose.waitForIdle()
            block()
        }
    }

    private fun asked(text: String) = compose.onNodeWithTag(ITEM_ASKED_TAG).assertTextIs(text)

    private fun noneNotAsked() = compose.onAllNodesWithTag(ITEM_NOT_ASKED_TAG).assertCountEquals(0)

    // [line] shown once, its link opening [target].
    private fun notAsked(
        line: String,
        target: SettingsTarget,
    ) {
        compose.onNodeWithText(line).performScrollTo().performFirstLinkClick {
            (it.item as? LinkAnnotation.Clickable)?.tag == target.linkTag
        }
        assertEquals(listOf(target), opened)
    }

    @Test
    fun theAlbumNamesEverySourceAsked() =
        show(InsightItem.ALBUM) {
            asked("Asked MusicBrainz, Wikipedia, Last.fm and a web search: none has this album.")
            compose.onNodeWithText("The sources give nothing", substring = true).assertDoesNotExist()
            noneNotAsked()
        }

    @Test
    fun theSongNamesEverySourceAsked() =
        show(InsightItem.SONG) {
            asked("Asked MusicBrainz, Wikipedia, Last.fm and a web search: none has this song.")
            noneNotAsked()
        }

    @Test
    fun theArtistNamesEverySourceAsked() =
        show(InsightItem.ARTIST) {
            asked("Asked MusicBrainz, Wikipedia, Last.fm and a web search: none has this artist.")
            noneNotAsked()
        }

    @Test
    fun lastFmSwitchedOffIsNotAskedWithALinkToSources() =
        show(InsightItem.ALBUM, settings(SourceSwitch.LASTFM)) {
            asked("Asked MusicBrainz, Wikipedia and a web search: none has this album.")
            notAsked("Not asked: Last.fm (disabled). See Settings → Sources.", SettingsTarget.sources)
        }

    @Test
    fun lastFmWithoutItsKeyIsNotAskedWithALinkToItsKey() =
        show(InsightItem.ALBUM, settings(SourceSwitch.LASTFM), SAVED + (SecretId.LASTFM to SecretState.ABSENT)) {
            asked("Asked MusicBrainz, Wikipedia and a web search: none has this album.")
            notAsked("Not asked: Last.fm (no key). See Settings → Secrets.", SettingsTarget.key(SecretId.LASTFM))
        }

    @Test
    fun exaSwitchedOffIsNotAskedWithALinkToSources() =
        show(InsightItem.ALBUM, settings(SourceSwitch.WEB)) {
            asked("Asked MusicBrainz, Wikipedia and Last.fm: none has this album.")
            notAsked("Not asked: Exa web search (disabled). See Settings → Sources.", SettingsTarget.sources)
        }

    @Test
    fun exaWithoutItsKeyIsNotAskedWithALinkToItsKey() =
        show(
            InsightItem.SONG,
            Settings(writeAutomatically = WriteAutomatically.NEVER, webEngines = emptySet()),
            SAVED + (SecretId.EXA to SecretState.ABSENT),
        ) {
            asked("Asked MusicBrainz, Wikipedia and Last.fm: none has this song.")
            notAsked("Not asked: Exa web search (no key). See Settings → Secrets.", SettingsTarget.key(SecretId.EXA))
        }

    @Test
    fun theArtistsWebSearchFollowsAiWebSearchWithALinkToInsights() =
        show(InsightItem.ARTIST, WEB_SEARCH_OFF) {
            asked("Asked MusicBrainz, Wikipedia and Last.fm: none has this artist.")
            notAsked("Not asked: AI web search (off). See Settings → Insights.", SettingsTarget.aiWebSearch)
        }

    @Test
    fun theSongsWebSearchIsTheTracksSoAiWebSearchOffDoesNotHoldItBack() =
        show(InsightItem.SONG, WEB_SEARCH_OFF) {
            asked("Asked MusicBrainz, Wikipedia, Last.fm and a web search: none has this song.")
            noneNotAsked()
        }

    @Test
    fun anAlbumLookedUpByItsNameDoesNotNameLastFm() =
        show(InsightItem.ALBUM, byName = true) {
            asked("Asked MusicBrainz, Wikipedia and a web search: none has this album.")
            noneNotAsked()
        }

    @Test
    fun oneSourceAskedIsNamedAloneAndEachNotAskedHasItsOwnLine() =
        show(InsightItem.ALBUM, settings(SourceSwitch.WIKIPEDIA, SourceSwitch.LASTFM, SourceSwitch.WEB)) {
            asked("Asked MusicBrainz: none has this album.")
            compose.onAllNodesWithTag(ITEM_NOT_ASKED_TAG).assertCountEquals(3)
            compose.onNodeWithText("Not asked: Wikipedia (disabled). See Settings → Sources.").assertExists()
        }

    @Test
    fun twoSourcesAskedAreJoinedWithAnd() =
        show(InsightItem.ALBUM, settings(SourceSwitch.LASTFM, SourceSwitch.WEB)) {
            asked("Asked MusicBrainz and Wikipedia: none has this album.")
            compose.onAllNodesWithTag(ITEM_NOT_ASKED_TAG).assertCountEquals(2)
        }

    @Test
    fun withNoSourceAskedItSaysTheSourcesGiveNothingAndWhatWasNotAsked() =
        show(InsightItem.ALBUM, settings(*ALL_ASKED)) {
            compose.onNodeWithTag(ITEM_ASKED_TAG).assertDoesNotExist()
            compose.onNodeWithText("The sources give nothing to write the album’s insight from.").assertExists()
            compose.onAllNodesWithTag(ITEM_NOT_ASKED_TAG).assertCountEquals(4)
        }

    @Test
    fun aSourceNotReachedStillTakesPrecedence() =
        show(InsightItem.ALBUM, settings(SourceSwitch.LASTFM), held = SourcesHeld(listOf(SourceId.MUSICBRAINZ))) {
            compose.onNodeWithText("MusicBrainz could not be reached.").assertExists()
            compose.onNodeWithTag(ITEM_ASKED_TAG).assertDoesNotExist()
            noneNotAsked()
        }

    @Test
    fun aScreenReaderReadsEachLineWholeAndTheLinkAsALink() =
        show(InsightItem.ALBUM, settings(SourceSwitch.LASTFM)) {
            asked("Asked MusicBrainz, Wikipedia and a web search: none has this album.")
            // The line read as one text, its link a clickable link of its own (read "…, link").
            val line = compose.onNodeWithTag(ITEM_NOT_ASKED_TAG)
            line.assertTextIs("Not asked: Last.fm (disabled). See Settings → Sources.")
            val links =
                line
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .flatMap { text -> text.getLinkAnnotations(0, text.length).map { it.item } }
            assertEquals(listOf(SettingsTarget.sources.linkTag), links.map { (it as LinkAnnotation.Clickable).tag })
        }

    @Test
    @Config(qualifiers = "w360dp-h780dp-420dpi", fontScale = 2f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun atTwiceTheFontSizeEachLineWrapsWithinTheScreen() =
        show(InsightItem.ALBUM, settings(SourceSwitch.LASTFM)) {
            val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
            for (tag in listOf(ITEM_ASKED_TAG, ITEM_NOT_ASKED_TAG)) {
                val node = compose.onNodeWithTag(tag).performScrollTo().fetchSemanticsNode()
                val box = node.boundsInRoot
                assertTrue("$tag within the screen: $box", box.left >= screen.left && box.right <= screen.right)
                // Wrapped onto several lines, none cut.
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                val layout = layouts.single()
                assertTrue("$tag wraps", layout.lineCount > 1)
                assertTrue("$tag is not cut", !layout.hasVisualOverflow)
            }
        }

    // The node's text, as a screen reader reads it, is [text], whatever its links.
    private fun SemanticsNodeInteraction.assertTextIs(text: String) =
        this.assert(
            SemanticsMatcher("text is \"$text\"") { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("") { it.text } == text
            },
        )

    private companion object {
        // Every key saved.
        val SAVED: Map<SecretId, SecretState> = SecretId.entries.associateWith { SecretState.SAVED }

        // The default settings with [off] switched off, nothing written unasked.
        fun settings(vararg off: SourceSwitch) =
            Settings(
                writeAutomatically = WriteAutomatically.NEVER,
                enabledSources = Settings.DEFAULT_SOURCES - off.toSet(),
            )

        // Every source an item asks.
        val ALL_ASKED = arrayOf(SourceSwitch.MUSICBRAINZ, SourceSwitch.WIKIPEDIA, SourceSwitch.LASTFM, SourceSwitch.WEB)

        // *AI web search* set to *Off*, nothing written unasked.
        val WEB_SEARCH_OFF = Settings(writeAutomatically = WriteAutomatically.NEVER, aiWebSearch = AiWebSearch.OFF)
    }
}

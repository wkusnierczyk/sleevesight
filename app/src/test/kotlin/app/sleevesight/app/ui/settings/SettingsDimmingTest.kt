package app.sleevesight.app.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Settings dimmed by the rule of #947, as #999 brought every row to it: a setting that does not
 * apply while another is as it is dims as a whole, its name too, and cannot be changed; one that
 * still applies but whose value something else holds (a key missing or no longer readable) dims
 * only its switch, its name staying at full colour. Either way a screen reader reads the switch or
 * the buttons as disabled. The sections are shown on their own, from the settings and keys given.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsDimmingTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private var content: @Composable () -> Unit by mutableStateOf({})

    private fun show(test: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SleevesightTheme(darkTheme = false) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { content() }
                    }
                }
            }
            test()
        }
    }

    private fun showing(next: @Composable () -> Unit) {
        content = next
        compose.waitForIdle()
    }

    /** How opaque the name of the row titled [title] is drawn: 1 at full colour, less dimmed. */
    private fun titleAlpha(title: String): Float {
        val node =
            compose
                .onNode(hasTestTag(ROW_TITLE_TAG) and hasText(title), useUnmergedTree = true)
                .fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts
            .single()
            .layoutInput.style.color.alpha
    }

    private fun assertWholeRow(title: String) =
        assertEquals("$title: dimmed as a whole, its name too", DIMMED, titleAlpha(title), TOLERANCE)

    private fun assertFullColour(title: String) =
        assertEquals("$title: its name at full colour", 1f, titleAlpha(title), TOLERANCE)

    private fun switch(title: String) = compose.onNode(isToggleable() and hasText(title))

    private fun buttons(vararg labels: String) = labels.map { compose.onNode(hasText(it) and isSelectable()) }

    @Test
    fun aKeyedSourceWithNoKeyDimsOnlyItsSwitchButAiKnowledgeWithNoServiceDimsAsAWhole() =
        show {
            val noKeys = mapOf(SecretId.LASTFM to SecretState.ABSENT, SecretId.EXA to SecretState.ABSENT)
            showing { SourcesSection(Settings(), noKeys) {} }
            for (title in listOf(text(R.string.settings_source_lastfm), text(R.string.settings_engine_exa))) {
                assertFullColour(title)
                switch(title).assertIsNotEnabled()
            }
            // No AI service on: *AI service knowledge* does not apply.
            val knowledge = text(R.string.settings_source_model)
            assertWholeRow(knowledge)
            switch(knowledge).assertIsNotEnabled()

            // An AI service on, its key's state not read yet: no note says why, so only the switch.
            showing { SourcesSection(Settings(aiProvider = ProviderId.ANTHROPIC), emptyMap()) {} }
            assertFullColour(knowledge)
            switch(knowledge).assertIsNotEnabled()
        }

    /** Shows [content], where the switch rows titled [titles] are dimmed as a whole and fixed. */
    private fun assertWholeSwitchRows(
        vararg titles: Int,
        content: @Composable () -> Unit,
    ) {
        showing(content)
        for (title in titles.map(::text)) {
            assertWholeRow(title)
            switch(title).assertIsNotEnabled()
        }
    }

    // Each of these passes `applies`; without it, it would dim only its switch.
    @Test
    fun theSwitchRowsThatDoNotApplyDimAsAWhole() =
        show {
            val insightsOff = Settings(writeInsights = false)
            val ask = R.string.settings_search_with_ai
            val saver = R.string.settings_pause_auto_write
            assertWholeSwitchRows(saver, ask, R.string.settings_artist_facts_once) {
                InsightsSection(insightsOff, {}, {})
            }
            // Insights on, but nothing written automatically.
            assertWholeSwitchRows(saver) { SaverRows(Settings(writeAutomatically = WriteAutomatically.NEVER)) {} }
            assertWholeSwitchRows(R.string.settings_lyrics_search) {
                AppearanceSection(Settings(lyricsLinks = false), {}) { CompletableDeferred(true) }
            }
            val noKey = mapOf(SecretId.LASTFM to SecretState.ABSENT)
            assertWholeSwitchRows(R.string.settings_source_lastfm) {
                GenresSection(Settings(enabledSources = Settings.DEFAULT_SOURCES + SourceSwitch.LASTFM), noKey) {}
            }
        }

    @Test
    fun anAiServiceWithNoKeyDimsOnlyItsSwitch() =
        show {
            val noKey = mapOf(SecretId.ANTHROPIC to SecretState.ABSENT)
            showing { AiSection(Settings(), noKey, { it }, {}, {}) }
            val anthropic = text(R.string.settings_ai_provider_anthropic)
            assertFullColour(anthropic)
            switch(anthropic).assertIsNotEnabled()
        }

    @Test
    fun lastfmLabelsWithAnUnreadableKeyDimOnlyTheSwitchAndAsAWholeWithGenresOff() =
        show {
            val lastfm = text(R.string.settings_source_lastfm)
            val musicbrainz = text(R.string.settings_source_musicbrainz)
            val unreadable = mapOf(SecretId.LASTFM to SecretState.UNREADABLE)
            val settings = Settings(enabledSources = Settings.DEFAULT_SOURCES + SourceSwitch.LASTFM)
            showing { GenresSection(settings, unreadable) {} }
            assertFullColour(lastfm)
            switch(lastfm).assertIsNotEnabled()
            assertFullColour(musicbrainz)
            switch(musicbrainz).assertIsEnabled()

            showing { GenresSection(settings.copy(showGenres = false), unreadable) {} }
            for (title in listOf(lastfm, musicbrainz)) {
                assertWholeRow(title)
                switch(title).assertIsNotEnabled()
            }
        }

    @Test
    fun aiWebSearchDimsAsAWholeWithInsightsOffAndOnlyItsSearchesWithExaOff() =
        show {
            val title = text(R.string.settings_ai_web_search)
            val off = text(R.string.settings_ai_web_search_off)
            val once = text(R.string.settings_ai_web_search_once)
            showing { AiWebSearchRows(Settings(writeInsights = false)) {} }
            assertWholeRow(title)
            buttons(off, once).forEach { it.assertIsNotEnabled() }

            // Exa web search off: the setting still applies, held at *Off*.
            showing { AiWebSearchRows(Settings(webEngines = emptySet())) {} }
            assertFullColour(title)
            buttons(off).single().assertIsEnabled()
            buttons(once).single().assertIsNotEnabled()
        }

    @Test
    fun theNewTrackRowsDimAsAWholeUnderSeparatePages() =
        show {
            val rows = listOf(R.string.settings_sources_on_new_track, R.string.settings_items_on_new_track).map(::text)
            showing { NewTrackRows(Settings(layout = Layout.SEPARATE_PAGES)) {} }
            rows.forEach(::assertWholeRow)
            val all = compose.onAllNodes(isSelectable())
            val count = all.fetchSemanticsNodes().size
            assertEquals("three buttons in each row", 6, count)
            repeat(count) { all[it].assertIsNotEnabled() }

            showing { NewTrackRows(Settings(layout = Layout.EXPANDABLE_BOXES)) {} }
            rows.forEach(::assertFullColour)
        }

    @Test
    fun descriptionsOnReturnAndLogoSpeedDimAsAWholeWhileTheirSwitchIsOff() =
        show {
            val rows = listOf(R.string.settings_descriptions_on_return, R.string.settings_logo_speed).map(::text)
            val off = Settings(foldDescriptions = false, spinLogo = false)
            showing { AppearanceSection(off, {}) { CompletableDeferred(true) } }
            rows.forEach(::assertWholeRow)
            buttons(text(R.string.settings_logo_speed_33)).single().assertIsNotEnabled()

            showing { AppearanceSection(Settings(), {}) { CompletableDeferred(true) } }
            rows.forEach(::assertFullColour)
            buttons(text(R.string.settings_logo_speed_33)).single().assertIsEnabled()
        }

    /** *Open website ↗* under the row of the service [name]. */
    private fun website(name: String) =
        compose.onNode(hasContentDescription(app.getString(R.string.settings_open_website_of, name)))

    /** The link to the Last.fm key under its row, as [sentence] says it. */
    private fun keyLink(sentence: Int) =
        compose.onNode(
            hasText(app.getString(sentence, text(R.string.settings_key_name_lastfm))),
            useUnmergedTree = true,
        )

    /**
     * How far from the background [node]'s inkiest pixel is drawn, in its most changed colour
     * channel: what an alpha scales, as it blends in these channels.
     */
    private fun ink(node: SemanticsNodeInteraction): Float {
        val pixels = node.captureToImage().toPixelMap()
        val background = pixels[0, 0]
        var most = 0f
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) most = maxOf(most, distance(pixels[x, y], background))
        }
        return most
    }

    private fun distance(
        a: Color,
        b: Color,
    ) = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    // Drawn, for the links' pixels. *Open website ↗* stayed at full colour under a genre label
    // source's row dimmed as a whole.
    @Test
    @Config(qualifiers = "w411dp-h891dp-420dpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theLinksUnderARowDimWithItOnlyWhereItDoesNotApply() =
        show {
            val musicbrainz = text(R.string.settings_source_musicbrainz)
            showing { GenresSection(Settings(), emptyMap()) {} }
            val full = ink(website(musicbrainz))
            showing { GenresSection(Settings(showGenres = false), emptyMap()) {} }
            val dimmed = ink(website(musicbrainz))
            assertTrue("dimmed with the row: $dimmed against $full", dimmed < full * HALF)
            website(musicbrainz).assertHasClickAction().assertIsEnabled()

            // Switch only: Last.fm with no key, its links as under a row that is not dimmed.
            val lastfm = text(R.string.settings_source_lastfm)
            val saved = mapOf(SecretId.LASTFM to SecretState.SAVED, SecretId.EXA to SecretState.SAVED)
            showing { SourcesSection(Settings(), saved) {} }
            val keyFull = ink(keyLink(R.string.settings_update_key))
            val websiteFull = ink(website(lastfm))
            val noKeys = mapOf(SecretId.LASTFM to SecretState.ABSENT, SecretId.EXA to SecretState.ABSENT)
            showing { SourcesSection(Settings(), noKeys) {} }
            val keyNoKey = ink(keyLink(R.string.settings_set_key))
            assertEquals("the key's link at full colour", keyFull, keyNoKey, keyFull * NEAR)
            assertEquals("the website's link at full colour", websiteFull, ink(website(lastfm)), websiteFull * NEAR)
            website(lastfm).assertHasClickAction().assertIsEnabled()
        }

    private companion object {
        /** Under this share of the ink of a link at full colour, a link is dimmed. */
        const val HALF = 0.5f

        /** How near a link's ink is to another's at the same colour. */
        const val NEAR = 0.1f

        /** A dimmed row's alpha, as `SettingsRows`' `DISABLED_ALPHA`. */
        const val DIMMED = 0.38f
        const val TOLERANCE = 0.01f
    }
}

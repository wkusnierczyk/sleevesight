package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.musicBrainzLine
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.wikipediaLine
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InLanguageState
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * A track searched in its language (#902), as Home shows it: under the insight, *Translated from
 * Hindi: W2 and S1.* for the citations of lines found in Hindi, none without; in *Sources*, the rows
 * *Wikipedia in Hindi* and *Web search in Hindi* after their sources' with the usual states, and each
 * line found in Hindi shown as found, its source line ending *· in Hindi*.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class InLanguageScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private val at = Instant.parse("2026-10-17T12:00:00Z")

    private fun hindi(
        source: SourceId,
        text: String,
        url: String,
        scope: Scope,
    ) = EvidenceDraft(source, scope, text, url, at, language = "hi")

    // W2 and W3, after the English W1; S1, the web search's in Hindi.
    private val film =
        hindi(
            SourceId.WIKIPEDIA,
            "फ़िल्म का गीत “आओगे जब तुम” उस्ताद राशिद ख़ान ने गाया है।",
            "https://hi.wikipedia.org/wiki/Jab_We_Met",
            Scope.RELEASE,
        )
    private val music =
        hindi(
            SourceId.WIKIPEDIA,
            "इसका संगीत संदेश शांडिल्य ने दिया।",
            "https://hi.wikipedia.org/wiki/Jab_We_Met",
            Scope.RELEASE,
        )
    private val page =
        hindi(SourceId.WEB, "आओगे जब तुम में शास्त्रीय गायकी सुनाई देती है।", "https://hindi.example.in/a", Scope.WEB)

    private val evidence = EvidenceSet.of(listOf(musicBrainzLine, wikipediaLine, film, music, page), JvmHasher)

    private val english =
        mapOf(
            SourceId.MUSICBRAINZ to done(evidence = listOf(musicBrainzLine)),
            SourceId.WIKIPEDIA to done(Outcome.ArtistArticle, evidence = listOf(wikipediaLine)),
            SourceId.WEB to done(Outcome.None),
        )

    private fun searched(
        wikipedia: SourceState = done(evidence = listOf(film, music)),
        web: SourceState = done(evidence = listOf(page)),
        insight: InsightState = InsightState.Idle(canWrite = false),
    ): TrackState {
        // The evidence of what the lookups found: the English lines, then the Hindi ones.
        val found = listOf(wikipedia, web).flatMap { (it as? SourceState.Done)?.result?.evidence.orEmpty() }
        val lines = EvidenceSet.of(listOf(musicBrainzLine, wikipediaLine) + found, JvmHasher)
        return track(sources = english, evidence = lines, insight = insight)
            .copy(inLanguage = InLanguageState("hi", mapOf(SourceId.WIKIPEDIA to wikipedia, SourceId.WEB to web)))
    }

    private fun show(
        track: TrackState,
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(track, aiReady = true),
                track.nowPlaying,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(),
            )
        val actions = NowPlayingActions(normalizer::normalize)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            scenario.onActivity { block() }
        }
    }

    private fun count(
        text: String,
        substring: Boolean = false,
    ) = compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun unfold() {
        compose.onNodeWithTag(SOURCES_CHIP_TAG).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** The rows of *Sources*' table, by their keys, top to bottom. */
    private fun rows(): List<String> =
        compose
            .onAllNodes(SemanticsMatcher("a row") { it.tag()?.startsWith(ROW_PREFIX) == true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .sortedBy { it.positionInRoot.y }
            .map { it.tag()!!.removePrefix(ROW_PREFIX) }

    private fun SemanticsNode.tag() = config.getOrNull(SemanticsProperties.TestTag)

    /** What the row of [key] says, as a screen reader is given it. */
    private fun said(key: String): List<String> {
        val words = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            node.config
                .getOrNull(SemanticsProperties.Text)
                ?.let { texts -> words += texts.map { it.text } }
            node.children.forEach(::walk)
        }
        walk(compose.onNodeWithTag(ROW_PREFIX + key).fetchSemanticsNode())
        return words
    }

    @Test
    fun theTextsAreThePickedOnes() {
        assertEquals("Translated from Hindi: W2 and S1.", text(R.string.home_note_translated, "Hindi", "W2 and S1"))
        assertEquals("Wikipedia in Hindi", text(R.string.home_source_wikipedia_in, "Hindi"))
        assertEquals("Web search in Hindi", text(R.string.home_source_web_in, "Hindi"))
        assertEquals("in Hindi", text(R.string.home_source_line_in, "Hindi"))
    }

    @Test
    fun anInsightCitingLinesFoundInHindiSaysWhichItTranslated() =
        show(
            searched(
                insight = written("It is from the film [MB1], with music by Shandilya [W2] [S1].", from = evidence),
            ),
        ) {
            assertEquals(1, count("Translated from Hindi: W2 and S1."))
        }

    @Test
    fun severalCitationsAreJoinedAsAListInTheOrderTheyComeAndEachOnce() =
        show(
            searched(
                insight =
                    written(
                        "It is from the film [W3]. Its singer was classical [W1] [S1]. The song is his [W2] [W3].",
                        from = evidence,
                    ),
            ),
        ) {
            assertEquals(1, count("Translated from Hindi: W3, S1 and W2."))
        }

    @Test
    fun anInsightCitingNoLineFoundInHindiSaysNothingOfTranslation() =
        show(
            searched(insight = written("It was recorded in 1973 [MB1]. It topped the charts [W1].", from = evidence)),
        ) {
            assertEquals(0, count("Translated from", substring = true))
        }

    @Test
    fun aCitationOutsideWhatThePromptHeldIsNoTranslatedLine() {
        val held = setOf("MB1", "W1", "W2")
        val reply = written("It is from the film [W2] [S1].", held = held, from = evidence)
        assertEquals(listOf("hi" to listOf("W2")), translatedCites(reply.grounded, evidence, held))
        assertEquals(listOf("hi" to listOf("W2", "S1")), translatedCites(reply.grounded, evidence))
        assertEquals(emptyList<Pair<String, List<String>>>(), translatedCites(reply.grounded, null))
    }

    @Test
    fun theLookupsInHindiHaveRowsOfTheirOwnAfterTheirSourcesWithWhatTheyFound() =
        show(searched()) {
            unfold()
            assertEquals(listOf("MB", "W", "W:hi", "S", "S:hi"), rows())
            // The English rows say what their own lines found; the Hindi ones theirs.
            assertEquals(listOf(text(R.string.home_source_found, text(R.string.home_source_finding_track))), said("W"))
            assertEquals(
                listOf(text(R.string.home_source_found, text(R.string.home_source_finding_album))),
                said("W:hi"),
            )
            assertEquals(listOf(text(R.string.home_source_name_web), text(R.string.home_source_none)), said("S"))
            assertEquals(listOf(text(R.string.home_source_found_pages)), said("S:hi"))
            // Its letter is a switch named for the lookup.
            compose
                .onNodeWithTag(CHIP_PREFIX + "W:hi")
                .assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Wikipedia in Hindi")),
                )
        }

    @Test
    fun theLookupsInHindiShowLookingNothingFoundAndCouldNotBeReached() {
        show(searched(wikipedia = SourceState.Pending, web = done(Outcome.None))) {
            unfold()
            assertEquals(listOf("Wikipedia in Hindi", text(R.string.home_source_pending)), said("W:hi"))
            assertEquals(listOf("Web search in Hindi", text(R.string.home_source_none)), said("S:hi"))
        }
    }

    @Test
    fun aLookupInHindiThatCouldNotBeReachedSaysSoInItsRowAndTheNote() =
        show(searched(web = done(Outcome.Unreachable))) {
            unfold()
            assertEquals(listOf("Web search in Hindi", text(R.string.home_source_unreachable)), said("S:hi"))
            assertEquals(1, count("Web search in Hindi could not be reached.", substring = true))
        }

    @Test
    fun aLineFoundInHindiIsShownAsFoundAndSaysItsLanguage() =
        show(searched()) {
            unfold()
            assertEquals(1, count(film.text))
            // W2 and W3, both on the film's article.
            assertEquals(2, count("release · hi.wikipedia.org · in Hindi"))
            assertEquals(1, count("web · hindi.example.in · in Hindi"))
            // An English line's source line says no language.
            assertEquals(0, count("en.wikipedia.org · in", substring = true))
        }

    @Test
    fun aLineFoundInHindiCarriesItsLanguageToAScreenReaderAndAnEnglishOneNone() =
        show(searched()) {
            unfold()
            // The text as a screen reader is given it: a span over the whole of it, in Hindi.
            val hindi = spoken(film.text)
            assertEquals(
                listOf(AnnotatedString.Range(SpanStyle(localeList = LocaleList("hi")), 0, film.text.length)),
                hindi.spanStyles,
            )
            assertEquals(emptyList<AnnotatedString.Range<SpanStyle>>(), spoken(wikipediaLine.text).spanStyles)
        }

    private fun spoken(text: String): AnnotatedString =
        compose
            .onNode(hasText(text), useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()
}

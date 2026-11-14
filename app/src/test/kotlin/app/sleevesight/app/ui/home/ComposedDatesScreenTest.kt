package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.ComposedDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When the music was composed, as the *Dates* panel says it (#1132, #1273): the line after the release
 * sentences, in years, and under it its source, a link to the page that says so. Which date is said is
 * `ComposedDatesTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rGB-w411dp-h891dp-420dpi")
class ComposedDatesScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val work = "https://musicbrainz.org/work/9727cab0-f302-3b71-b937-5ccd416dd36e"

    private fun said(
        sentences: List<DateSentence>,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { Column { DatesPanel(sentences) } } }
            block()
        }
    }

    // The address [tag]'s link opens.
    private fun linkOf(tag: String): String {
        val text =
            compose
                .onNodeWithTag(tag)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .single()
        return text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url }.single()
    }

    @Test
    fun k545ReadsComposedIn1788UnderTheReleaseFromMusicBrainz() {
        val composed = DateSentence.Composed(ComposedDate("1788", "1788", SourceId.MUSICBRAINZ, work))
        said(listOf(DateSentence.Album("2024-10-25"), DateSentence.FirstHere(), composed)) {
            val line = compose.onNodeWithText("The music was composed in 1788.").fetchSemanticsNode()
            val release = compose.onNodeWithText("This track was first released on this album.").fetchSemanticsNode()
            assertTrue(line.boundsInRoot.top >= release.boundsInRoot.bottom)
            compose.onNodeWithTag(DATES_FROM_TAG).assertTextEquals("From MusicBrainz")
            val from = compose.onNodeWithTag(DATES_FROM_TAG).fetchSemanticsNode()
            assertTrue(from.boundsInRoot.top >= line.boundsInRoot.bottom)
            assertEquals(work, linkOf(DATES_FROM_TAG))
        }
    }

    @Test
    fun aCompositionOverSeveralYearsReadsAsTheirSpan() {
        val turandot = ComposedDate("1921", "1924", SourceId.MUSICBRAINZ, "https://musicbrainz.org/work/876aa6e9")
        said(listOf(DateSentence.Album("1994"), DateSentence.Composed(turandot))) {
            compose.onNodeWithText("The music was composed between 1921 and 1924.").fetchSemanticsNode()
        }
    }

    @Test
    fun wikidatasDateNamesWikidataAndLinksItsItem() {
        val item = "https://www.wikidata.org/wiki/Q1"
        val composed = DateSentence.Composed(ComposedDate("1788", "1788", SourceId.WIKIDATA, item))
        said(listOf(DateSentence.Album("1994"), composed)) {
            compose.onNodeWithTag(DATES_FROM_TAG).assertTextEquals("From Wikidata")
            assertEquals(item, linkOf(DATES_FROM_TAG))
        }
    }

    @Test
    fun wikipediasInfoboxDateNamesWikipediaAloneAndLinksTheArticle() {
        // The source's name in the settings is "Wikipedia and Wikidata"; the line links the article (#1273).
        val article = "https://en.wikipedia.org/wiki/Symphony_No._5_(Beethoven)"
        val composed = DateSentence.Composed(ComposedDate("1804", "1808", SourceId.WIKIPEDIA, article))
        said(listOf(DateSentence.Album("1963"), composed)) {
            compose.onNodeWithText("The music was composed between 1804 and 1808.").fetchSemanticsNode()
            compose.onNodeWithTag(DATES_FROM_TAG).assertTextEquals("From Wikipedia")
            assertEquals(article, linkOf(DATES_FROM_TAG))
        }
    }

    @Test
    fun theReleaseSentencesHaveNoFromLine() {
        said(listOf(DateSentence.Album("2024-10-25"), DateSentence.FirstHere())) {
            assertEquals(0, compose.onAllNodesWithTag(DATES_FROM_TAG).fetchSemanticsNodes().size)
        }
    }
}

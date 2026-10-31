package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.BADGE_PREFIX
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *How long each source's findings are kept*, under *History* (#670): each source's row starts with
 * the badge the list of sources shows, the letter its citations use, in the same column and size,
 * with its name at the same place as there; and the row is read as before, the badge skipped.
 */
@RunWith(RobolectricTestRunner::class)
class CacheLifetimeBadgesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }
    private val settings by lazy { SettingsStore(stores.open("settings")) }
    private val secrets by lazy { SecretStore(stores.open("secrets"), testCipher()) }
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private val density get() = app.resources.displayMetrics.density

    private fun show(
        vararg sections: SettingsSection,
        test: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            sections.forEach { compose.openSection(it) }
            test()
        }
    }

    /** A lifetime's row, named [title]: a row a tap opens, not a source's switch. */
    private fun lifetimeRow(title: String) = compose.onNode(hasClickAction() and hasText(title) and !isToggleable())

    /** A source's switch under *Sources*, named [title]. */
    private fun sourceRow(title: String) = compose.onNode(isToggleable() and hasText(title))

    /** The bounds of the one badge [letter] within [row], in pixels. */
    private fun badgeWithin(
        row: Rect,
        letter: String,
    ): Rect {
        val badges =
            compose
                .onAllNodesWithTag(BADGE_PREFIX + letter, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .filter { row.contains(it.boundsInRoot.center) }
        assertEquals("one $letter badge in the row", 1, badges.size)
        return badges.single().boundsInRoot
    }

    /** Where the text [title] starts within [row], in pixels. */
    private fun titleStartWithin(
        row: Rect,
        title: String,
    ): Float =
        compose
            .onAllNodesWithText(title, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single { row.contains(it.boundsInRoot.center) }
            .boundsInRoot.left

    @Test
    fun eachLifetimeStartsWithItsSourcesBadge() =
        show(SettingsSection.HISTORY) {
            val rows =
                listOf(
                    R.string.settings_source_musicbrainz to "MB",
                    R.string.settings_cache_wikidata to "WD",
                    R.string.settings_cache_wikipedia to "W",
                    R.string.settings_source_lastfm to "L",
                    R.string.settings_cache_web to "S",
                )
            for ((name, letter) in rows) {
                val title = app.getString(name)
                val row = lifetimeRow(title).performScrollTo()
                val bounds = row.fetchSemanticsNode().boundsInRoot
                val badge = badgeWithin(bounds, letter)
                assertEquals("$title's badge is 40 dp wide", ROW_MARK_DP, badge.width / density, 0.5f)
                assertEquals("$title's badge is 40 dp high", ROW_MARK_DP, badge.height / density, 0.5f)
                assertEquals("$title's badge starts its row", bounds.left, badge.left, 0.5f)
                val nameStart = titleStartWithin(bounds, title)
                assertEquals("$title's name 16 dp past its badge", badge.right + 16 * density, nameStart, density)

                // Read as before: its name, then its lifetime; the badge skipped.
                row.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
                val read = row.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
                assertEquals("$title's row is read by its name first", title, read.first())
                assertFalse("$title's row does not read its badge: $read", letter in read)
            }
        }

    // The same column as the list of sources: MusicBrainz's badge and name where *Sources* has them.
    @Test
    fun theBadgesAndNamesAreInTheColumnsOfTheListOfSources() =
        show(SettingsSection.SOURCES, SettingsSection.HISTORY) {
            val title = app.getString(R.string.settings_source_musicbrainz)
            val source = sourceRow(title).performScrollTo().fetchSemanticsNode().boundsInRoot
            val sourceBadge = badgeWithin(source, "MB").left
            val sourceName = titleStartWithin(source, title)

            val lifetime = lifetimeRow(title).performScrollTo().fetchSemanticsNode().boundsInRoot
            assertEquals("the badge's column", sourceBadge, badgeWithin(lifetime, "MB").left, 0.5f)
            assertEquals("the name's column", sourceName, titleStartWithin(lifetime, title), 0.5f)
        }

    private companion object {
        /** A player's icon's size under *Players* (#535), which the badges take. */
        const val ROW_MARK_DP = 40f
    }
}

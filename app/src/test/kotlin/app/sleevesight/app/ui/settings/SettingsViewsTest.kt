package app.sleevesight.app.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.core.settings.LogoSpeed
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SettingsView
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The views' one declaration (#1447, [settingViews]) against the settings' index ([settingsIndex]),
 * which has every row a section draws (`SettingsIndexTest`): every setting has a view, so it is in
 * *All* and in the smaller views its view is in, and nothing declared is missing from the screen.
 * A setting added without a view fails here.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsViewsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    // Everything that only shows under some settings shown, as `SettingsIndexTest` has it.
    private val everything =
        Settings(
            developerMode = true,
            showDiagnostics = true,
            writeAutomatically = WriteAutomatically.WHEN_I_SKIP,
        )

    private fun declared(settings: Settings = everything): Pair<List<IndexedSection>, SettingViews> {
        var index: List<IndexedSection>? = null
        var views: SettingViews? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    index =
                        settingsIndex(
                            settings,
                            KeysShown(mapOf(SecretId.EXA to SecretState.SAVED), offerReset = false),
                            listOf(PLAYER),
                            { it },
                        )
                    views = settingViews(settings, Settings(), listOf(PLAYER))
                }
            }
            compose.waitForIdle()
        }
        return index!! to views!!
    }

    @Test
    fun everySettingHasAViewAndEveryViewedSettingIsShown() {
        val (index, views) = declared()
        for (section in index) {
            val titles = section.rows.map { it.title }.toSet()
            val viewed = views[section.section].orEmpty()
            if (section.section == SettingsSection.DEVELOPER) {
                // Shown in full in every view while developer mode is on.
                assertTrue(viewed.isEmpty())
                continue
            }
            assertEquals("${section.section}: settings without a view", emptySet<String>(), titles - viewed.keys)
            assertEquals("${section.section}: views of no setting", emptySet<String>(), viewed.keys - titles)
        }
    }

    @Test
    fun everySettingIsInAllAndEachViewHoldsTheSmallerOnes() {
        val (_, views) = declared()
        val all = views.values.flatMap { it.values }
        assertTrue(all.isNotEmpty())
        for (row in all) {
            assertTrue(SettingsView.ALL.includes(row.view))
            // In its own view and every larger one, in no smaller one.
            for (view in SettingsView.entries) assertEquals(view >= row.view, view.includes(row.view))
        }
        // Each section but *Developer* has an everyday setting.
        for (section in SettingsSection.entries - SettingsSection.DEVELOPER) {
            assertTrue(section.name, views[section].orEmpty().values.any { it.view == SettingsView.BASIC })
        }
    }

    @Test
    fun aSettingIsChangedOnlyWhileItDiffersFromItsDefault() {
        val logo = { views: SettingViews -> views.getValue(SettingsSection.APPEARANCE).values.count { it.changed } }
        assertEquals(0, logo(declared(everything).second))
        assertEquals(1, logo(declared(everything.copy(logoSpeed = LogoSpeed.RPM_45)).second))
    }

    @Test
    fun aViewShowsItsSettingsAndTheChangedOnesOnlyOnceShown() {
        val (index, views) = declared(everything.copy(logoSpeed = LogoSpeed.RPM_45))
        val section = SettingsSection.APPEARANCE
        val speed =
            views
                .getValue(section)
                .filterValues { it.changed }
                .keys
                .single()
        val basic = ViewShown(SettingsView.BASIC, views, index)
        assertEquals(listOf(speed), basic.hiddenChanges(section))
        assertFalse(basic.rowView(section)!!.shows(speed))
        val revealed = ViewShown(SettingsView.BASIC, views, index, revealed = setOf(section))
        assertTrue(revealed.rowView(section)!!.marked(speed))
        // In *All*, nothing is hidden, and nothing marked.
        val all = ViewShown(SettingsView.ALL, views, index)
        assertEquals(emptyList<String>(), all.hiddenChanges(section))
        assertFalse(all.rowView(section)!!.marked(speed))
        assertEquals(null, all.rowView(SettingsSection.DEVELOPER))
    }

    @Test
    fun aSearchShowsItsMatchesInTheViewAndSaysWhereTheOthersAre() {
        val (index, views) = declared()
        val basic = ViewShown(SettingsView.BASIC, views, index)
        val theme = basic.search(matches(index, "theme"), showMore = false)
        // *Theme* is in *Basic*, *Theme button* in *Advanced*.
        assertEquals(setOf(SettingsSection.APPEARANCE), theme.sections.keys)
        assertEquals(1, theme.more)
        assertEquals(SettingsView.ADVANCED, theme.home)
        // Matches only outside the view: no section, and the rest in *All* (*Logo speed*).
        val speed = basic.search(matches(index, "logo speed"), showMore = false)
        assertEquals(emptySet<SettingsSection>(), speed.sections.keys)
        assertEquals(1, speed.more)
        assertEquals(SettingsView.ALL, speed.home)
        // *Show them* shows them.
        assertEquals(setOf(SettingsSection.APPEARANCE), basic.search(matches(index, "logo speed"), true).sections.keys)
        // In *All*, nothing is outside.
        assertEquals(0, ViewShown(SettingsView.ALL, views, index).search(matches(index, "theme"), false).more)
    }

    private companion object {
        const val PLAYER = "Tunes"
    }
}

package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.settings.loadPlan
import app.sleevesight.app.ui.settings.APP_OFFERED
import app.sleevesight.app.ui.settings.loadChanges
import app.sleevesight.app.ui.settings.skippedLine
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SettingsFile
import app.sleevesight.core.settings.WebEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.days

/**
 * *Load settings?* (#815) lists every change a file can make: each value of each choice and switch,
 * and each item of the settings made of several (AI services, sources, lifetimes, labels, players),
 * read from a file as the app reads it ([APP_OFFERED]) and loaded, has a line. What has none is not
 * loaded either: it is none of the setting's choices.
 */
@RunWith(RobolectricTestRunner::class)
class LoadPreviewCoverageTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    /** Developer mode on, so the developer settings load too. */
    private val base = Settings(developerMode = true)

    private fun file(settings: String) = """{"file": "Sleevesight settings", "format": 1, "settings": {$settings}}"""

    /** [text] read and planned onto [base] as the app does, every key saved. */
    private fun planned(text: String): Settings =
        loadPlan(SettingsFile.read(text, APP_OFFERED)!!, base, emptySet()).settings

    /** Each value of each choice and switch, as a file has it, that loads as a change. */
    private fun valueCases(): List<Pair<String, Settings>> =
        SettingsFile.NAMES
            .flatMap { name ->
                val type = Settings::class.java.getDeclaredField(name).type
                val values =
                    when {
                        type.isEnum -> type.enumConstants!!.map { "\"${(it as Enum<*>).name}\"" }
                        type == java.lang.Boolean.TYPE -> listOf("true", "false")
                        else -> emptyList()
                    }
                values.map { "$name = $it" to planned(file("\"$name\": $it")) }
            }.filter { (_, loaded) -> loaded != base }

    /** Each item of the settings made of several, changed, through a file. */
    private fun itemCases(): List<Pair<String, Settings>> {
        val changed =
            APP_OFFERED.providers.flatMap { provider ->
                listOfNotNull(
                    "model $provider" to base.withModel(provider, "other-model"),
                    Settings.EFFORT_SCALES[provider]?.let {
                        "effort $provider" to base.withEffort(provider, ReasoningEffort.HIGH)
                    },
                )
            } +
                APP_OFFERED.sourceSwitches.map {
                    "source $it" to base.copy(enabledSources = base.enabledSources.toggled(it))
                } +
                APP_OFFERED.cacheSources.map {
                    "lifetime $it" to base.copy(cacheLifetimes = base.cacheLifetimes + (it to 2.days))
                } +
                WebEngine.entries.map { "engine $it" to base.copy(webEngines = base.webEngines.toggled(it)) } +
                LabelSource.entries.map { "labels $it" to base.copy(labelSources = base.labelSources.toggled(it)) } +
                ("player" to base.copy(followedPlayers = base.followedPlayers + ("org.example.tunes" to true)))
        return changed.map { (what, settings) ->
            val loaded = planned(SettingsFile.write(settings, "0.10.0", APP_OFFERED))
            assertNotEquals("$what does not load", base, loaded)
            what to loaded
        }
    }

    @Test
    fun everyChangeAFileCanMakeHasALine() {
        val cases = valueCases() + itemCases()
        var lines: List<Int> = emptyList()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    lines = cases.map { (_, loaded) -> loadChanges(base, loaded, { m -> m }, { p -> p }).size }
                }
            }
            compose.waitForIdle()
        }
        assertEquals(cases.size, lines.size)
        assertEquals(
            "changes with no line",
            emptyList<String>(),
            cases
                .filterIndexed {
                    i,
                    _,
                    ->
                    lines[i] == 0
                }.map { it.first },
        )
    }

    /**
     * Values no setting takes, or not all: out of range, too fine, malformed, of the wrong kind, and
     * the same inside a setting made of several items.
     */
    private val wrong =
        listOf(
            "\"20s\"",
            "\"-1s\"",
            "\"1ns\"",
            "\"36h\"",
            "\"99999d\"",
            "\"Infinity\"",
            "\"x\"",
            "12",
            "-3",
            "true",
            "null",
            "{}",
            "[]",
            "[\"X\", 3]",
            "{\"ANTHROPIC\": \"20s\", \"WEB\": \"1ns\", \"X\": 1, \"org.example.p\": 3}",
        )

    @Test
    fun everyWrongValueOfEverySettingShowsItsPreviewWithoutFailing() {
        val plans =
            SettingsFile.NAMES.flatMap { name ->
                wrong.map { value ->
                    loadPlan(SettingsFile.read(file("\"$name\": $value"), APP_OFFERED)!!, base, emptySet())
                }
            }
        var shown = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    shown =
                        plans.sumOf { plan ->
                            loadChanges(base, plan.settings, { m -> m }, { p -> p }).size +
                                plan.skipped.map { skippedLine(it) }.size
                        }
                }
            }
            compose.waitForIdle()
        }
        assertTrue("lines shown: $shown", shown > 0)
    }

    @Test
    fun aCrossfadeOverItsMostIsNamedAsTheSettingsShowATime() {
        val plan = loadPlan(SettingsFile.read(file("\"crossfade\": \"20s\""), APP_OFFERED)!!, base, emptySet())
        var lines: List<String> = emptyList()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { lines = plan.skipped.map { skippedLine(it) } } }
            compose.waitForIdle()
        }
        assertEquals(listOf("Crossfade: 20 s: not one of its choices"), lines)
    }

    private fun <T> Set<T>.toggled(item: T): Set<T> = if (item in this) this - item else this + item
}

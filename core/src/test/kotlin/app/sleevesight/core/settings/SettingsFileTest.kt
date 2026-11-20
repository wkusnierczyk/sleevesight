package app.sleevesight.core.settings

import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.SettingsFile.Skipped
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/**
 * *Save to a file* and *Load from a file* (#815): every setting in the file round-trips, those left
 * out never are in it, nothing secret is written, and a file from a later version, or edited by
 * hand, loads what this version reads and names the rest.
 */
class SettingsFileTest {
    private val defaults = Settings()

    /** Some settings moved, the developer settings among them, developer mode on. */
    private val moved =
        Settings(
            theme = ThemeChoice.DARK,
            spinLogo = false,
            followedPlayers = mapOf("org.example.tunes" to true, Settings.SPOTIFY to false),
            developerMode = true,
            showDiagnostics = true,
            reportButton = ReportButton.SHOW,
            linkStyle = LinkStyle.HIGHLIGHT,
            dismissed = setOf(Dismissable.PLAYERS_NOTE),
            unfoldedDescriptions = listOf("a", "b"),
        )

    private val inFile = SettingsFile.NAMES

    private fun settingsOf(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject["settings"]!!.jsonObject

    private fun file(vararg settings: Pair<String, String>) =
        """{"file": "Sleevesight settings", "format": 1, "appVersion": "0.10.0", "settings": {""" +
            settings.joinToString(", ") { (name, value) -> "\"$name\": $value" } + "}}"

    @Test
    fun theNamesAreEachOnceAndNoneLeftOut() {
        assertEquals(emptySet(), inFile.toSet().intersect(SettingsFile.LEFT_OUT))
        assertEquals(inFile.size, inFile.toSet().size, "a setting in the file twice")
        assertTrue(inFile.containsAll(SettingsFile.DEVELOPER))
        assertEquals(inFile, SettingsFile.NAMES)
    }

    @Test
    fun aFileReadBackLoadsAsSaved() {
        val contents = assertNotNull(SettingsFile.read(SettingsFile.write(moved, "0.10.0")))
        assertEquals("0.10.0", contents.appVersion)
        assertEquals(SettingsFile.FORMAT, contents.format)
        assertEquals(emptyList(), contents.skipped)
        val loaded = SettingsFile.load(contents, defaults.copy(developerMode = true), developerMode = true)
        assertEquals(emptyList(), loaded.skipped)
        // All but what is left out, which stays as it was.
        assertEquals(moved.copy(dismissed = emptySet(), unfoldedDescriptions = emptyList()), loaded.settings)
    }

    @Test
    fun theFileHoldsOnlyItsSettingsNothingLeftOutAndNoSecret() {
        val text = SettingsFile.write(moved, "0.10.0")
        assertEquals(inFile.toSet(), settingsOf(text).keys)
        for (left in SettingsFile.LEFT_OUT) assertFalse("\"$left\"" in text, left)
        // No setting is a key or a secret of any kind: none is named so, and none holds one.
        val secretish = Regex("(?i)key|secret|token|password|credential")
        assertEquals(emptyList(), inFile.filter { secretish.containsMatchIn(it) })
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("file", "format", "appVersion", "settings"), root.keys)
    }

    @Test
    fun theDeveloperSettingsAreInTheFileOnlyFromDeveloperMode() {
        val off = settingsOf(SettingsFile.write(moved.copy(developerMode = false), "0.10.0"))
        assertEquals(inFile.toSet() - SettingsFile.DEVELOPER, off.keys)
        val on = settingsOf(SettingsFile.write(moved, "0.10.0"))
        assertTrue(on.keys.containsAll(SettingsFile.DEVELOPER))
    }

    @Test
    fun theDeveloperSettingsLoadOnlyInDeveloperModeAndAreNamedOnceOtherwise() {
        val contents = assertNotNull(SettingsFile.read(SettingsFile.write(moved, "0.10.0")))
        val loaded = SettingsFile.load(contents, defaults, developerMode = false)
        assertEquals(listOf<Skipped>(Skipped.DeveloperModeOff), loaded.skipped)
        assertEquals(defaults.showDiagnostics, loaded.settings.showDiagnostics)
        assertEquals(defaults.reportButton, loaded.settings.reportButton)
        assertEquals(defaults.linkStyle, loaded.settings.linkStyle)
        assertEquals(moved.theme, loaded.settings.theme)
        assertFalse(loaded.settings.developerMode)
    }

    @Test
    fun aFileWithoutDeveloperSettingsNamesNothingWithDeveloperModeOff() {
        val contents = assertNotNull(SettingsFile.read(SettingsFile.write(moved.copy(developerMode = false), "0.10.0")))
        assertEquals(emptyList(), SettingsFile.load(contents, defaults, developerMode = false).skipped)
    }

    @Test
    fun aSettingTheFileDoesNotHaveKeepsItsValue() {
        val contents = assertNotNull(SettingsFile.read(file("theme" to "\"LIGHT\"")))
        val loaded = SettingsFile.load(contents, moved, developerMode = true)
        assertEquals(moved.copy(theme = ThemeChoice.LIGHT), loaded.settings)
    }

    @Test
    fun anUnknownSettingAndAValueNoneOfItsChoicesAreSkippedAndNamedTheRestLoaded() {
        val contents =
            assertNotNull(
                SettingsFile.read(
                    file(
                        "pulseView" to "true",
                        "musicBrainzWait" to "\"90s\"",
                        "theme" to "\"DARK\"",
                        "font" to "\"COMIC\"",
                        "enabledSources" to "[\"MUSICBRAINZ\", \"TIDAL\"]",
                        "cacheLifetimes" to "{\"WEB\": \"2d\", \"NOWHERE\": \"1d\", \"LASTFM\": \"-3d\"}",
                        "showGenres" to "\"yes\"",
                    ),
                ),
            )
        assertEquals(
            listOf(
                Skipped.Unknown("pulseView"),
                Skipped.Invalid("musicBrainzWait", null, "90s"),
                Skipped.Invalid("font", null, "COMIC"),
                Skipped.Invalid("enabledSources", "TIDAL", "TIDAL"),
                Skipped.Invalid("cacheLifetimes", "NOWHERE", "1d"),
                Skipped.Invalid("cacheLifetimes", "LASTFM", "-3d"),
                Skipped.Invalid("showGenres", null, "yes"),
            ),
            contents.skipped,
        )
        val loaded = SettingsFile.load(contents, defaults, developerMode = false).settings
        assertEquals(ThemeChoice.DARK, loaded.theme)
        assertEquals(defaults.font, loaded.font)
        assertEquals(defaults.musicBrainzWait, loaded.musicBrainzWait)
        assertEquals(setOf(SourceSwitch.MUSICBRAINZ), loaded.enabledSources)
        assertEquals(2.days, loaded.cacheLifetimes[SourceId.WEB])
        assertEquals(defaults.cacheLifetimes[SourceId.LASTFM], loaded.cacheLifetimes[SourceId.LASTFM])
        assertEquals(defaults.showGenres, loaded.showGenres)
    }

    @Test
    fun aFileFromALaterFormatIsReadForWhatThisVersionKnows() {
        val text = file("theme" to "\"LIGHT\"").replace("\"format\": 1", "\"format\": 7")
        val contents = assertNotNull(SettingsFile.read(text))
        assertEquals(7, contents.format)
        assertEquals(ThemeChoice.LIGHT, SettingsFile.load(contents, defaults, false).settings.theme)
    }

    @Test
    fun theSettingsLeftOutAreNeverLoaded() {
        val contents =
            assertNotNull(SettingsFile.read(file("developerMode" to "true", "dismissed" to "[\"PLAYERS_NOTE\"]")))
        assertEquals(emptyList(), contents.skipped)
        assertEquals(defaults, SettingsFile.load(contents, defaults, false).settings)
    }

    @Test
    fun aLoadNeverShortensKeepHistoryFor() {
        val shorter = assertNotNull(SettingsFile.read(file("historyLifetime" to "\"7d\"")))
        val loaded = SettingsFile.load(shorter, defaults, false)
        assertEquals(defaults.historyLifetime, loaded.settings.historyLifetime)
        assertEquals(listOf<Skipped>(Skipped.ShortensHistory(7.days)), loaded.skipped)
        val forever = assertNotNull(SettingsFile.read(file("historyLifetime" to "null")))
        assertNull(SettingsFile.load(forever, defaults, false).settings.historyLifetime)
        val longer = assertNotNull(SettingsFile.read(file("historyLifetime" to "\"180d\"")))
        assertEquals(180.days, SettingsFile.load(longer, defaults, false).settings.historyLifetime)
    }

    @Test
    fun thePlayersInTheFileAreSetAndThoseItDoesNotNameKeepTheirSwitches() {
        val here = defaults.copy(followedPlayers = mapOf("org.example.here" to true, Settings.SPOTIFY to true))
        val contents =
            assertNotNull(
                SettingsFile.read(
                    file(
                        "followedPlayers" to
                            "{\"com.spotify.music\": false, \"com.android.dialer\": true, \"x.y\": true}",
                    ),
                ),
            )
        val players = SettingsFile.load(contents, here, false).settings.followedPlayers
        assertEquals(mapOf("org.example.here" to true, Settings.SPOTIFY to false, "x.y" to true), players)
    }

    @Test
    fun aTextThatIsNoSettingsFileReadsAsNull() {
        assertNull(SettingsFile.read("not json"))
        assertNull(SettingsFile.read("[1, 2]"))
        assertNull(SettingsFile.read("{\"settings\": {}}"))
        assertNull(SettingsFile.read("{\"file\": \"Sleevesight settings\"}"))
        assertNull(SettingsFile.read(""))
    }

    @Test
    fun theFileIsReadableJson() {
        val text = SettingsFile.write(defaults, "0.10.0")
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(JsonPrimitive("Sleevesight settings"), root["file"])
        assertEquals(JsonPrimitive(SettingsFile.FORMAT), root["format"])
        assertEquals(JsonPrimitive("90d"), settingsOf(text)["historyLifetime"])
        assertEquals(JsonPrimitive("auto"), settingsOf(text)["crossfade"])
        assertTrue(text.lines().size > inFile.size, "one setting a line, pretty printed")
    }

    @Test
    fun aModelOrEffortNotReadKeepsItsValueAndADefaultInTheFileReplacesAChoice() {
        val here =
            defaults.copy(
                aiModels = mapOf(ProviderId.ANTHROPIC to "claude-mine", ProviderId.OPENAI to "gpt-mine"),
                aiEfforts =
                    mapOf(
                        ProviderId.ANTHROPIC to ReasoningEffort.HIGH,
                        ProviderId.OPENAI to ReasoningEffort.MEDIUM,
                    ),
            )
        val contents =
            assertNotNull(
                SettingsFile.read(
                    file(
                        "aiModels" to
                            "{\"ANTHROPIC\": 12, \"OPENAI\": \"${Settings.DEFAULT_MODELS[ProviderId.OPENAI]}\"}",
                        "aiEfforts" to "{\"ANTHROPIC\": \"MINIMAL\", \"OPENAI\": \"LOW\"}",
                    ),
                ),
            )
        assertEquals(
            listOf<Skipped>(
                Skipped.Invalid("aiModels", "ANTHROPIC", "12"),
                Skipped.Invalid("aiEfforts", "ANTHROPIC", "MINIMAL"),
            ),
            contents.skipped,
        )
        val loaded = SettingsFile.load(contents, here, false).settings
        // The invalid entries keep their values; the defaults in the file replace the choices.
        assertEquals(mapOf(ProviderId.ANTHROPIC to "claude-mine"), loaded.aiModels)
        assertEquals(mapOf(ProviderId.ANTHROPIC to ReasoningEffort.HIGH), loaded.aiEfforts)
        // A provider the file does not name keeps its own.
        val google = here.copy(aiModels = here.aiModels + (ProviderId.GOOGLE to "gemini-mine"))
        assertEquals("gemini-mine", SettingsFile.load(contents, google, false).settings.aiModels[ProviderId.GOOGLE])
    }

    @Test
    fun whatTheSettingsDoNotOfferIsNeitherWrittenNorLoaded() {
        val offered =
            SettingsFile.Offered(
                sourceSwitches = SourceSwitch.entries.toSet() - SourceSwitch.DISCOGS,
                cacheSources = Settings.DEFAULT_CACHE_LIFETIMES.keys - SourceId.DISCOGS,
            )
        val text =
            SettingsFile.write(
                defaults.copy(enabledSources = defaults.enabledSources + SourceSwitch.DISCOGS),
                "0.10.0",
                offered,
            )
        assertFalse("DISCOGS" in text, text)
        val contents =
            assertNotNull(
                SettingsFile.read(
                    file(
                        "aiProvider" to "\"OLLAMA\"",
                        "cacheLifetimes" to "{\"DISCOGS\": \"3d\"}",
                        "enabledSources" to "[\"MUSICBRAINZ\", \"DISCOGS\"]",
                    ),
                    offered,
                ),
            )
        assertEquals(
            listOf<Skipped>(
                Skipped.Invalid("aiProvider", null, "OLLAMA"),
                Skipped.Invalid("cacheLifetimes", "DISCOGS", "3d"),
                Skipped.Invalid("enabledSources", "DISCOGS", "DISCOGS"),
            ),
            contents.skipped,
        )
        // A switch the settings do not show keeps its value.
        val here = defaults.copy(enabledSources = setOf(SourceSwitch.DISCOGS, SourceSwitch.LASTFM))
        assertEquals(
            setOf(SourceSwitch.DISCOGS, SourceSwitch.MUSICBRAINZ),
            SettingsFile.load(contents, here, false).settings.enabledSources,
        )
    }

    @Test
    fun aFileHoldsAndALoadAddsAtMostMaxPlayersAndSavingAgainChangesNothing() {
        val many = (1..3500).associate { "org.example.p$it" to (it % 2 == 0) }
        val text = SettingsFile.write(defaults.copy(followedPlayers = many), "0.10.0")
        assertTrue(text.toByteArray().size <= SettingsFile.MAX_BYTES)
        val big = "{" + many.entries.joinToString(", ") { (k, v) -> "\"$k\": $v" } + "}"
        val contents = assertNotNull(SettingsFile.read(file("followedPlayers" to big)))
        val first = SettingsFile.load(contents, defaults, false)
        assertTrue(
            first.settings.followedPlayers.size <= SettingsFile.MAX_PLAYERS,
            "${first.settings.followedPlayers.size}",
        )
        assertTrue(first.skipped.any { it is Skipped.TooMany })
        // Saved and loaded again: nothing changes, nothing more is skipped.
        val again = assertNotNull(SettingsFile.read(SettingsFile.write(first.settings, "0.10.0")))
        val second = SettingsFile.load(again, first.settings, false)
        assertEquals(first.settings, second.settings)
        assertEquals(emptyList(), second.skipped)
    }

    @Test
    fun aTimeThatWouldNotReadBackAsWrittenIsSkipped() {
        val contents =
            assertNotNull(
                SettingsFile.read(
                    file(
                        "crossfade" to "\"1ns\"",
                        "historyLifetime" to "\"200d 1h\"",
                        "sessionGap" to "\"45s\"",
                        "cacheLifetimes" to "{\"WEB\": \"36h\"}",
                    ),
                ),
            )
        assertEquals(
            listOf<Skipped>(
                Skipped.Invalid("crossfade", null, "1ns"),
                Skipped.Invalid("historyLifetime", null, "200d 1h"),
                Skipped.Invalid("sessionGap", null, "45s"),
                Skipped.Invalid("cacheLifetimes", "WEB", "36h"),
            ),
            contents.skipped,
        )
        assertEquals(defaults, SettingsFile.load(contents, defaults, false).settings)
    }

    @Test
    fun withDeveloperModeOffDeveloperSettingsAreNamedOnceWhateverTheirValues() {
        val contents = assertNotNull(SettingsFile.read(file("reportButton" to "\"LOUD\"", "linkStyle" to "7")))
        assertEquals(listOf<Skipped>(Skipped.DeveloperModeOff), SettingsFile.load(contents, defaults, false).skipped)
        assertEquals(2, SettingsFile.load(contents, defaults, true).skipped.size)
    }

    @Test
    fun aPlayerThatIsNoPackageNameIsSkipped() {
        val long = "a".repeat(SettingsFile.MAX_TEXT + 1)
        val contents =
            assertNotNull(
                SettingsFile.read(
                    file(
                        "followedPlayers" to "{\"org.example.ok\": true, \"two words\": true, \"$long\": true}",
                    ),
                ),
            )
        assertEquals(
            listOf<Skipped>(
                Skipped.Invalid("followedPlayers", "two words", "true"),
                Skipped.Invalid("followedPlayers", long, "true"),
            ),
            contents.skipped,
        )
        assertEquals(true, SettingsFile.load(contents, defaults, false).settings.followedPlayers["org.example.ok"])
    }

    @Test
    fun theLargestFileTheLimitsAllowStaysUnderWhatALoadReads() {
        // Each text at its longest, each character one JSON writes as an escape.
        val worst = "\u0001".repeat(SettingsFile.MAX_TEXT)
        val players =
            (1..SettingsFile.MAX_PLAYERS).associate {
                "p$it." +
                    "a".repeat(
                        SettingsFile.MAX_TEXT - 6,
                    ) to true
            }
        val settings =
            Settings.DEFAULT_MODELS.keys.fold(
                moved.copy(followedPlayers = players, readAloudVoice = ReadAloudVoice(worst, worst)),
            ) { s, provider -> s.withModel(provider, worst) }
        val text = SettingsFile.write(settings, "0.10.0")
        assertTrue(text.toByteArray().size <= SettingsFile.MAX_BYTES, "${text.toByteArray().size}")
        val here = defaults.copy(developerMode = true, followedPlayers = emptyMap())
        val loaded = SettingsFile.load(assertNotNull(SettingsFile.read(text)), here, true)
        assertEquals(emptyList(), loaded.skipped)
        assertEquals(settings.followedPlayers, loaded.settings.followedPlayers)
        assertEquals(worst, loaded.settings.model(ProviderId.ANTHROPIC))
    }
}

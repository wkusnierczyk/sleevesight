package app.sleevesight.core.settings

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

    private val inFile = SettingsFile.FIELDS.map { it.name }

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
}

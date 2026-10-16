package app.sleevesight.core.settings

import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class SettingsTest {
    private val defaults = Settings()

    @Test
    fun `the defaults are the design's`() {
        assertEquals(Layout.SEPARATE_PAGES, defaults.layout)
        assertNull(defaults.aiProvider)
        assertNull(defaults.aiModel)
        assertTrue(defaults.writeInsights)
        assertEquals(WriteAutomatically.ON_EVERY_TRACK_CHANGE, defaults.writeAutomatically)
        assertEquals(Crossfade.Autodetect, defaults.crossfade)
        assertEquals(PrepareNextTrack.EVIDENCE_AND_INSIGHT, defaults.prepareNextTrack)
        assertFalse(defaults.lookUpInBackground)
    }

    @Test
    fun `the model is the user's choice, else the provider's default, else none`() {
        assertNull(defaults.model)
        assertEquals("claude-haiku-5-5", defaults.copy(aiProvider = ProviderId.ANTHROPIC).model)
        assertEquals(
            "claude-opus-5-5",
            defaults.copy(aiProvider = ProviderId.ANTHROPIC, aiModel = "claude-opus-5-5").model,
        )
        assertNull(defaults.copy(aiProvider = ProviderId.OLLAMA).model)
    }

    @Test
    fun `every source is on except the model's own knowledge`() {
        assertEquals(SourceSwitch.entries.toSet() - SourceSwitch.MODEL, defaults.enabledSources)
        assertEquals(SourceId.entries - SourceId.MODEL, SourceId.entries.filter { defaults.isEnabled(it) })
    }

    @Test
    fun `each switch covers its sources, and the player's metadata has none`() {
        assertEquals(setOf(SourceId.WIKIPEDIA, SourceId.WIKIDATA), SourceSwitch.WIKIPEDIA.sources)
        for (source in SourceId.entries - SourceId.PLAYER) {
            assertEquals(1, SourceSwitch.entries.count { source in it.sources }, source.name)
        }
        assertNull(SourceSwitch.of(SourceId.PLAYER))
        assertEquals(SourceSwitch.WIKIPEDIA, SourceSwitch.of(SourceId.WIKIDATA))
    }

    @Test
    fun `switching Wikipedia off turns Wikidata off too, but never the player's metadata`() {
        val noWikipedia = defaults.copy(enabledSources = defaults.enabledSources - SourceSwitch.WIKIPEDIA)
        assertFalse(noWikipedia.isEnabled(SourceId.WIKIPEDIA))
        assertFalse(noWikipedia.isEnabled(SourceId.WIKIDATA))
        assertTrue(noWikipedia.isEnabled(SourceId.LASTFM))
        assertTrue(defaults.copy(enabledSources = emptySet()).isEnabled(SourceId.PLAYER))
        assertTrue(defaults.copy(enabledSources = setOf(SourceSwitch.MODEL)).isEnabled(SourceId.MODEL))
    }

    @Test
    fun `web search runs only with an engine on, Exa on by default`() {
        assertEquals(setOf(WebEngine.EXA), defaults.webEngines)
        assertTrue(defaults.isEnabled(SourceId.WEB))
        assertFalse(defaults.copy(webEngines = emptySet()).isEnabled(SourceId.WEB))
        assertFalse(defaults.copy(enabledSources = defaults.enabledSources - SourceSwitch.WEB).isEnabled(SourceId.WEB))
        assertTrue(defaults.copy(webEngines = emptySet()).isEnabled(SourceId.MUSICBRAINZ))
    }

    @Test
    fun `both label sources are on by default, Last fm first`() {
        assertEquals(listOf(LabelSource.LASTFM, LabelSource.MUSICBRAINZ), LabelSource.entries)
        assertEquals(LabelSource.entries.toSet(), defaults.labelSources)
        assertTrue(LabelSource.entries.all(defaults::givesLabels))
    }

    @Test
    fun `a label source gives labels only while its source is on under Where to look`() {
        val noMusicBrainz = defaults.copy(enabledSources = defaults.enabledSources - SourceSwitch.MUSICBRAINZ)
        assertFalse(noMusicBrainz.givesLabels(LabelSource.MUSICBRAINZ))
        assertTrue(noMusicBrainz.givesLabels(LabelSource.LASTFM))

        val lastFmOnly = defaults.copy(labelSources = setOf(LabelSource.LASTFM))
        assertFalse(lastFmOnly.givesLabels(LabelSource.MUSICBRAINZ))
        // Off as a label source, MusicBrainz is still looked up for its lines.
        assertTrue(lastFmOnly.isEnabled(SourceId.MUSICBRAINZ))
    }

    @Test
    fun `only Spotify is followed until the user switches others on`() {
        assertTrue(defaults.follows("com.spotify.music"))
        assertFalse(defaults.follows("com.google.android.apps.youtube.music"))
        assertTrue(defaults.copy(followedPlayers = mapOf("com.example" to true)).follows("com.example"))
    }

    @Test
    fun `cache lifetimes follow D9`() {
        assertEquals(30.days, defaults.cacheLifetimes[SourceId.MUSICBRAINZ])
        assertEquals(30.days, defaults.cacheLifetimes[SourceId.WIKIDATA])
        assertEquals(7.days, defaults.cacheLifetimes[SourceId.WIKIPEDIA])
        assertEquals(7.days, defaults.cacheLifetimes[SourceId.LASTFM])
        assertEquals(1.days, defaults.cacheLifetimes[SourceId.WEB])
    }

    @Test
    fun `a fixed crossfade is 0 to 15 seconds`() {
        Crossfade.Fixed(0.seconds)
        Crossfade.Fixed(15.seconds)
        assertFailsWith<IllegalArgumentException> { Crossfade.Fixed(16.seconds) }
        assertFailsWith<IllegalArgumentException> { Crossfade.Fixed((-1).seconds) }
    }
}

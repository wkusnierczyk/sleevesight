package app.sleevesight.core.settings

import app.sleevesight.core.ai.EffortScale
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SettingsTest {
    private val defaults = Settings()

    @Test
    fun `the defaults are the design's`() {
        assertEquals(Layout.SEPARATE_PAGES, defaults.layout)
        assertNull(defaults.aiProvider)
        assertEquals(emptyMap(), defaults.aiModels)
        assertEquals(emptyMap(), defaults.aiEfforts)
        assertTrue(defaults.writeInsights)
        assertEquals(WriteAutomatically.ON_EVERY_TRACK_CHANGE, defaults.writeAutomatically)
        assertEquals(Crossfade.Autodetect, defaults.crossfade)
        assertEquals(PrepareNextTrack.EVIDENCE, defaults.prepareNextTrack)
        assertFalse(defaults.lookUpInBackground)
        assertFalse(defaults.keepSourcesUnfolded)
    }

    @Test
    fun `turning developer mode off turns off every developer option`() {
        val on = Settings(developerMode = true, showDiagnostics = true, showGroundingDetails = true)
        assertEquals(Settings(), on.withoutDeveloperMode())
        assertFalse(defaults.showGroundingDetails)
    }

    @Test
    fun `the app's own font is the default, not the system's`() {
        assertEquals(FontChoice.APP_FONT, defaults.font)
    }

    @Test
    fun `the model is the user's choice, else the provider's default, else none`() {
        assertNull(defaults.model)
        assertEquals("claude-haiku-5-5", defaults.copy(aiProvider = ProviderId.ANTHROPIC).model)
        assertEquals(
            "claude-opus-5-5",
            defaults.copy(aiProvider = ProviderId.ANTHROPIC).withModel(ProviderId.ANTHROPIC, "claude-opus-5-5").model,
        )
        assertEquals("gpt-6-luna", defaults.copy(aiProvider = ProviderId.OPENAI).model)
        assertNull(defaults.copy(aiProvider = ProviderId.OLLAMA).model)
    }

    @Test
    fun `each provider keeps its own model and effort while another is switched on`() {
        val chosen =
            defaults
                .copy(aiProvider = ProviderId.ANTHROPIC)
                .withModel(ProviderId.ANTHROPIC, "claude-opus-5-5")
                .withEffort(ProviderId.ANTHROPIC, ReasoningEffort.HIGH)
                .withModel(ProviderId.OPENAI, "gpt-other")
        // Another switched on: Anthropic's choices stay, and the switched-on provider's are used.
        val other = chosen.copy(aiProvider = ProviderId.OPENAI)
        assertEquals("gpt-other", other.model)
        assertEquals("claude-opus-5-5", other.model(ProviderId.ANTHROPIC))
        assertEquals(ReasoningEffort.HIGH, other.effort(ProviderId.ANTHROPIC))
        // Back on: restored.
        val back = other.copy(aiProvider = ProviderId.ANTHROPIC)
        assertEquals("claude-opus-5-5", back.model)
        assertEquals(ReasoningEffort.HIGH, back.effort)
        // None switched on: no model and no effort, while each provider's choices stay.
        val none = chosen.copy(aiProvider = null)
        assertNull(none.model)
        assertNull(none.effort)
        assertEquals("claude-opus-5-5", none.model(ProviderId.ANTHROPIC))
    }

    @Test
    fun `the effort is the provider's choice from its own scale, else its scale's default`() {
        val anthropic = Settings.EFFORT_SCALES.getValue(ProviderId.ANTHROPIC)
        assertEquals(ReasoningEffort.LOW, anthropic.default)
        assertEquals(ReasoningEffort.LOW, defaults.effort(ProviderId.ANTHROPIC))
        // The provider's own default is a choice of its own, kept apart from no choice.
        val providerDefault = defaults.withEffort(ProviderId.ANTHROPIC, null)
        assertEquals(mapOf(ProviderId.ANTHROPIC to null), providerDefault.aiEfforts)
        assertNull(providerDefault.effort(ProviderId.ANTHROPIC))
        // The scale's default is stored as no choice, so a revised default reaches it.
        assertEquals(defaults, providerDefault.withEffort(ProviderId.ANTHROPIC, ReasoningEffort.LOW))
        // OpenAI's: low, medium and high, low by default.
        val openAi = Settings.EFFORT_SCALES.getValue(ProviderId.OPENAI)
        assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH), openAi.levels)
        assertEquals(ReasoningEffort.LOW, defaults.effort(ProviderId.OPENAI))
        // A provider with no scale sends no effort, and keeps none.
        assertNull(defaults.effort(ProviderId.OLLAMA))
        assertEquals(defaults, defaults.withEffort(ProviderId.OLLAMA, ReasoningEffort.HIGH))
    }

    @Test
    fun `Google has its default model and its own scale, with minimal, low by default`() {
        val google = defaults.copy(aiProvider = ProviderId.GOOGLE)
        assertEquals("gemini-3.5-flash-lite", google.model)
        assertEquals(ReasoningEffort.LOW, google.effort)
        assertEquals(
            EffortScale(
                listOf(ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                ReasoningEffort.LOW,
            ),
            Settings.EFFORT_SCALES.getValue(ProviderId.GOOGLE),
        )
        // Minimal is Google's alone: chosen for Google it stays, while Anthropic's scale makes it its default.
        val minimal = google.withEffort(ProviderId.GOOGLE, ReasoningEffort.MINIMAL)
        assertEquals(ReasoningEffort.MINIMAL, minimal.effort)
        assertEquals(defaults, defaults.withEffort(ProviderId.ANTHROPIC, ReasoningEffort.MINIMAL))
        val stored = defaults.copy(aiEfforts = mapOf(ProviderId.ANTHROPIC to ReasoningEffort.MINIMAL))
        assertEquals(ReasoningEffort.LOW, stored.effort(ProviderId.ANTHROPIC))
    }

    @Test
    fun `a level the provider's scale does not offer is its default`() {
        val scale = EffortScale(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), default = ReasoningEffort.HIGH)
        assertEquals(ReasoningEffort.HIGH, scale.coerce(ReasoningEffort.MEDIUM))
        assertEquals(ReasoningEffort.LOW, scale.coerce(ReasoningEffort.LOW))
        assertNull(scale.coerce(null))
        assertFailsWith<IllegalArgumentException> { EffortScale(listOf(ReasoningEffort.LOW), ReasoningEffort.HIGH) }
        assertFailsWith<IllegalArgumentException> { EffortScale(emptyList(), null) }
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
    fun `a label source gives labels only while its source is on under Sources`() {
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
    fun `a phone call is never followed, even switched on`() {
        val telecom = "com.android.server.telecom"
        assertTrue(Settings.isCall(telecom))
        assertFalse(defaults.copy(followedPlayers = mapOf(telecom to true)).follows(telecom))
        assertFalse(Settings.isCall(Settings.SPOTIFY))
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

    @Test
    fun `the history is kept 90 days, a session ends after a 30-minute break, and both are positive`() {
        assertEquals(90.days, defaults.historyLifetime)
        assertEquals(30.minutes, defaults.sessionGap)
        // Kept for ever.
        defaults.copy(historyLifetime = null)
        assertFailsWith<IllegalArgumentException> { defaults.copy(historyLifetime = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { defaults.copy(sessionGap = Duration.ZERO) }
    }

    @Test
    fun `automatic writing pauses after 5 track changes away, or never, and after a positive number`() {
        assertEquals(5, defaults.pauseAutoWriteAfter)
        assertTrue(Settings.DEFAULT_PAUSE_AUTO_WRITE_AFTER in Settings.PAUSE_AUTO_WRITE_CHOICES)
        // Off.
        defaults.copy(pauseAutoWriteAfter = null)
        assertFailsWith<IllegalArgumentException> { defaults.copy(pauseAutoWriteAfter = 0) }
        assertFailsWith<IllegalArgumentException> { defaults.copy(pauseAutoWriteAfter = -1) }
    }
}

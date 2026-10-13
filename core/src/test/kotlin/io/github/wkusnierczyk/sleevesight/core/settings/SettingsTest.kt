package io.github.wkusnierczyk.sleevesight.core.settings

import io.github.wkusnierczyk.sleevesight.core.model.SourceId
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
    fun `every source is on except the model's own knowledge`() {
        assertFalse(SourceId.MODEL in defaults.enabledSources)
        assertEquals(SourceId.entries.toSet() - SourceId.MODEL - SourceId.PLAYER, defaults.enabledSources)
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

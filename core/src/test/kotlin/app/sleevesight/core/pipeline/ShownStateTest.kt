package app.sleevesight.core.pipeline

import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the pipeline shows, against *Clear history* (#547). */
class ShownStateTest {
    private val playing = NowPlaying(title = "Sundown", artists = "Gordon Lightfoot", player = "p")
    private val track = TrackState(playing, Normalizer(JvmAccentFolder).normalize(playing), emptyMap())

    @Test
    fun `a forget between a publication and its copy is not undone by the copy`() {
        var forgetNow = false
        lateinit var shown: ShownState
        shown =
            ShownState {
                if (forgetNow) {
                    forgetNow = false
                    shown.forget()
                }
            }
        val since = shown.forgets

        // The run publishes; before its copy reaches the screen, the clear forgets.
        forgetNow = true
        shown.publish(track, since) { true }

        assertNull(shown.state.value)
        shown.publish(track, since) { true }
        assertNull(shown.state.value, "a run started before the forget publishes nothing after it")
    }

    @Test
    fun `a run started after a forget publishes`() {
        val shown = ShownState()
        shown.forget()
        shown.publish(track, shown.forgets) { true }
        assertEquals(track, shown.state.value)
    }
}

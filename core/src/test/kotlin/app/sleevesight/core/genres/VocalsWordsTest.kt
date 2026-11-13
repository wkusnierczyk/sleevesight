package app.sleevesight.core.genres

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The word check of the vocals check's answer (#1053): an answer is taken only when the page it cites
 * says it in words, in its title or passage: *instrumental* for instrumental; *vocals*, *sung*,
 * *sings*, *featuring* or *lyrics* for vocals. Whole words, case aside; `unknown` needs none.
 */
class VocalsWordsTest {
    @Test
    fun `instrumental is taken only from a page that says instrumental`() {
        assertTrue(VocalsAnswer.INSTRUMENTAL.saidIn(null, "Nasnusa, an instrumental from Kokoro."))
        assertTrue(VocalsAnswer.INSTRUMENTAL.saidIn("Nasnusa (Instrumental)", "A track by Sababa 5."))
        assertFalse(VocalsAnswer.INSTRUMENTAL.saidIn("Nasnusa", "A track by Sababa 5, from Kokoro."))
        // Words for vocals say nothing of instrumental.
        assertFalse(VocalsAnswer.INSTRUMENTAL.saidIn("Nasnusa lyrics", "Sung by Yurika Hanashima."))
    }

    @Test
    fun `vocals is taken from a page that says vocals, sung, sings, featuring or lyrics`() {
        for (passage in listOf(
            "Vocals by Yurika Hanashima.",
            "Sung in Japanese.",
            "Yurika Hanashima sings on it.",
            "Sababa 5 featuring Yurika Hanashima.",
            "Read the lyrics.",
        )) {
            assertTrue(VocalsAnswer.VOCALS.saidIn(null, passage), passage)
        }
        assertTrue(VocalsAnswer.VOCALS.saidIn("NASNUSA LYRICS", ""))
        assertFalse(VocalsAnswer.VOCALS.saidIn("Nasnusa", "Nasnusa, an instrumental from Kokoro."))
    }

    @Test
    fun `a word counts only whole, not inside another`() {
        assertFalse(VocalsAnswer.VOCALS.saidIn(null, "Unsung heroes of Tel Aviv funk."))
        assertFalse(VocalsAnswer.VOCALS.saidIn(null, "A lyricsheet."))
        assertFalse(VocalsAnswer.INSTRUMENTAL.saidIn(null, "Instrumentalists of the band."))
        assertTrue(VocalsAnswer.VOCALS.saidIn(null, "(vocals: Yurika Hanashima)"))
        assertTrue(VocalsAnswer.VOCALS.saidIn(null, "lyrics—Yurika"))
    }

    @Test
    fun `unknown is never said by a page`() {
        assertFalse(VocalsAnswer.UNKNOWN.saidIn("unknown", "unknown instrumental vocals"))
    }
}

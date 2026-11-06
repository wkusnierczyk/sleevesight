package app.sleevesight.core.evidence

import app.sleevesight.core.language.Languages
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.synthesis.InsightPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A line any lookup found in a script that names a language (#949, `EvidenceSet.of`): marked with
 * that language, as a line the search in the track's language found is, so the prompt marks it and
 * the insight says it translated it; but still its own lookup's ([EvidenceLine.found] none).
 */
class ScriptedLanguageTest {
    private val at = Instant.parse("2026-11-06T12:00:00Z")

    private fun web(
        text: String,
        language: String? = null,
    ) = EvidenceDraft(SourceId.WEB, Scope.WEB, text, "https://www.youtube.com/watch?v=x", at, language = language)

    private fun set(
        vararg drafts: EvidenceDraft,
        known: String? = null,
    ) = EvidenceSet.of(drafts.toList(), JvmHasher, known?.let(Languages::ofCode))

    // Tum Se Hi, from Jab We Met: S4 and S5 are captions in Devanagari the English web search found.
    private val tumSeHi =
        listOf(
            web("Tum Se Hi is a song from the 2007 film Jab We Met."),
            web("Sung by Mohit Chauhan, music by Pritam."),
            web("Lyrics by Irshad Kamil."),
            web("ना है ये पाना, ना खोना ही है"),
            web("तेरा ना होना जाने, क्यों होना ही है"),
        )

    @Test
    fun `Devanagari lines the English search found are Hindi, marked in the prompt, and still the search's own`() {
        val evidence = EvidenceSet.of(tumSeHi, JvmHasher)
        assertEquals(listOf(null, null, null, "hi", "hi"), evidence.lines.map { it.language })
        assertEquals(listOf(false, false, false, true, true), evidence.lines.map { it.scripted })
        assertTrue(evidence.lines.all { it.found == null })
        assertEquals(
            "[S4] (Web search, web, Hindi) ना है ये पाना, ना खोना ही है",
            InsightPrompt.evidenceLine(evidence["S4"]!!),
        )
        assertEquals(
            "[S1] (Web search, web) Tum Se Hi is a song from the 2007 film Jab We Met.",
            InsightPrompt.evidenceLine(evidence["S1"]!!),
        )
    }

    @Test
    fun `a line mostly in Latin letters that quotes another script is not marked`() {
        val evidence = set(web("The chorus of Tum Se Hi, तुम से ही, is sung in a single breath by the singer."))
        assertNull(evidence.lines.single().language)
    }

    @Test
    fun `the track's language, when known, names the language of its script`() {
        val line = web("माझं गाणं आहे हे")
        assertEquals("hi", set(line).lines.single().language)
        assertEquals("mr", set(line, known = "mr").lines.single().language)
        // A known language in another script leaves Devanagari to its own.
        assertEquals("hi", set(line, known = "ko").lines.single().language)
    }

    @Test
    fun `a line the search found in the language gives it to the other lines in its script`() {
        val found = web("गाणं मराठी आहे", language = "mr")
        val evidence = set(web("हे गाणं आहे"), found)
        assertEquals(listOf("mr", "mr"), evidence.lines.map { it.language })
        assertEquals(listOf(null, "mr"), evidence.lines.map { it.found })
    }

    @Test
    fun `a script several languages share marks a line only with the track's language written in it`() {
        assertNull(set(web("这是一首歌")).lines.single().language)
        assertNull(set(web("Это песня")).lines.single().language)
        assertNull(set(web("هذه أغنية")).lines.single().language)
        assertEquals("zh", set(web("这是一首歌"), known = "zh").lines.single().language)
        assertEquals("ru", set(web("Это песня"), known = "ru").lines.single().language)
        assertEquals("ur", set(web("یہ گانا ہے"), known = "ur").lines.single().language)
        assertNull(set(web("Это песня"), known = "hi").lines.single().language)
        // Kana is Japanese, whatever Han stands beside it; Hangul Korean.
        assertEquals("ja", set(web("この曲は映画の主題歌です")).lines.single().language)
        assertEquals("ko", set(web("이 노래는 영화의 주제가입니다")).lines.single().language)
        // A Korean line quoting a Japanese title is Korean, and Japanese mostly in Han is Japanese.
        assertEquals("ko", set(web("이 노래는 일본 영화 君の名は의 주제가를 한국어로 부른 곡이다.")).lines.single().language)
        assertEquals("ja", set(web("映画の主題歌として作られた")).lines.single().language)
        // A caption quoting a romanised title is still in its own script.
        assertEquals("ko", set(web("아이유 신곡 Love wins all 뮤직비디오 공개")).lines.single().language)
        assertEquals("ja", set(web("米津玄師『Lemon』MV"), known = "ja").lines.single().language)
        // A Chinese line quoting a Japanese title, or a name with the katakana middle dot, stays Han.
        val quoting = web("《残酷な天使のテーゼ》是新世纪福音战士的主题曲")
        assertNull(set(quoting).lines.single().language)
        assertEquals("zh", set(quoting, known = "zh").lines.single().language)
        assertEquals("zh", set(web("约翰・列侬是披头士成员"), known = "zh").lines.single().language)
    }

    @Test
    fun `lines added to a set are read with its track's language, though the search found none`() {
        val base = set(web("A page."), web("Another page."), known = "ru")
        val more = base.with(listOf(web("Это песня")), JvmHasher)
        assertEquals(listOf(null, null, "ru"), more.lines.map { it.language })
        val fewer = base.without(JvmHasher) { it.id == "S2" }.with(listOf(web("Это песня")), JvmHasher)
        assertEquals(listOf(null, "ru"), fewer.lines.map { it.language })
    }

    @Test
    fun `a line's own language is kept, and a set made again keeps the mark`() {
        val evidence = set(web("ना है ये पाना"), web("Una canción.", language = "es"))
        assertEquals(listOf("hi", "es"), evidence.lines.map { it.language })
        assertEquals(listOf(true, false), evidence.lines.map { it.scripted })
        val again = evidence.with(listOf(web("Another page.")), JvmHasher)
        assertEquals(listOf(true, false, false), again.lines.map { it.scripted })
        assertEquals(listOf("hi", "es", null), again.lines.map { it.language })
        assertFalse(again.lines.any { it.found == "hi" })
    }
}

package app.sleevesight.sources.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sentences of a lead in the track's language (#902), cut at its script's sentence ends. */
class LanguageSentencesTest {
    // Hindi Wikipedia's lead of "Tum Hi Ho", as its extract gives it (CC BY-SA 4.0), fetched 2026-11-05.
    private val tumHiHo =
        "तुम ही हो 2013 की सुपरहिट फिल्म आशिकी 2 का एक सुपरहिट गाना हैं। आशिकी 2 का यह गाना फिल्म के लिए " +
            "अरिजीत सिंह ने गाया है। इस गाने को गीतकार संगीतकार मिथुन ने लिखा हैं।"

    @Test
    fun `a danda ends a Devanagari sentence before a space and a letter`() {
        assertEquals(
            listOf(
                "तुम ही हो 2013 की सुपरहिट फिल्म आशिकी 2 का एक सुपरहिट गाना हैं।",
                "आशिकी 2 का यह गाना फिल्म के लिए अरिजीत सिंह ने गाया है।",
                "इस गाने को गीतकार संगीतकार मिथुन ने लिखा हैं।",
            ),
            LanguageSentences.split(tumHiHo),
        )
    }

    @Test
    fun `the first sentence and those naming a subject are quoted, at most three`() {
        val lead = "$tumHiHo यह 2013 का गाना था। तुम ही हो का संगीत मिथुन का है। तुम ही हो लोकप्रिय हुआ।"
        assertEquals(
            listOf(
                "तुम ही हो 2013 की सुपरहिट फिल्म आशिकी 2 का एक सुपरहिट गाना हैं।",
                "तुम ही हो का संगीत मिथुन का है।",
                "तुम ही हो लोकप्रिय हुआ।",
            ),
            LanguageSentences.of(lead, listOf("तुम ही हो")),
        )
    }

    @Test
    fun `an ideographic full stop needs no space after it`() {
        assertEquals(
            listOf("「夜に駆ける」は、YOASOBIの楽曲。", "2019年に配信された。"),
            LanguageSentences.split("「夜に駆ける」は、YOASOBIの楽曲。2019年に配信された。"),
        )
    }

    @Test
    fun `a Latin full stop ends a sentence before a capital, not after an initial or before a lower case letter`() {
        assertEquals(
            listOf("Evidências é uma canção de J. Augusto, etc. gravada em 1989.", "Foi um sucesso."),
            LanguageSentences.split("Evidências é uma canção de J. Augusto, etc. gravada em 1989. Foi um sucesso."),
        )
    }

    @Test
    fun `Hangul, which has no case, starts a sentence after a full stop`() {
        assertEquals(
            listOf("《BE》는 방탄소년단의 앨범이다.", "2020년에 발매되었다."),
            LanguageSentences.split("《BE》는 방탄소년단의 앨범이다. 2020년에 발매되었다."),
        )
    }

    @Test
    fun `a tail without an end is dropped, and a sentence never ends inside a quotation`() {
        assertEquals(listOf("यह “एक। दो” गाना है।"), LanguageSentences.split("यह “एक। दो” गाना है। और यह"))
    }

    @Test
    fun `a sentence longer than the most quoted is left out, but for nothing else`() {
        val long = "आ".repeat(301) + "।"
        assertEquals(
            listOf("तुम ही हो गाना है।"),
            LanguageSentences.of("$long तुम ही हो गाना है।", listOf("तुम ही हो")),
        )
    }

    @Test
    fun `German and Polish low quotes, guillemets and Swedish quotes are quotations too`() {
        assertEquals(
            listOf("„Evidências“ ist ein Lied.", "Es wurde 1989 veröffentlicht."),
            LanguageSentences.split("„Evidências“ ist ein Lied. Es wurde 1989 veröffentlicht."),
        )
        assertEquals(
            listOf("„Kocham cię” to piosenka.", "Wydana w 1984."),
            LanguageSentences.split("„Kocham cię” to piosenka. Wydana w 1984."),
        )
        assertEquals(
            listOf("« Évidences » est une chanson.", "Elle date de 1989."),
            LanguageSentences.split("« Évidences » est une chanson. Elle date de 1989."),
        )
        assertEquals(
            listOf("”Låten” är känd.", "Den kom 1989."),
            LanguageSentences.split("”Låten” är känd. Den kom 1989."),
        )
        assertEquals(listOf("「夜に駆ける」は楽曲。", "配信された。"), LanguageSentences.split("「夜に駆ける」は楽曲。配信された。"))
    }

    @Test
    fun `a sentence ends after a German closing quote, and never inside the quotation`() {
        assertEquals(
            listOf("Er sang „Hallo. Welt“ laut.", "Dann ging er."),
            LanguageSentences.split("Er sang „Hallo. Welt“ laut. Dann ging er."),
        )
        assertEquals(
            listOf("Er sagte: „Es ist ein Lied.“", "Dann ging er."),
            LanguageSentences.split("Er sagte: „Es ist ein Lied.“ Dann ging er."),
        )
    }

    @Test
    fun `Thai, which ends no sentence with a mark, is quoted in stretches between spaces`() {
        val lead =
            "ขอใจเธอแลกเบอร์โทร เป็นเพลงของ หญิงลี ศรีจุมพล ออกเมื่อ พ.ศ. 2560 " +
                "เพลงนี้ได้รับความนิยมอย่างมากในประเทศไทย"
        // One stretch: the whole paragraph fits, the year kept with the era before it.
        assertEquals(listOf(lead), LanguageSentences.stretches(lead))
        assertEquals(listOf(lead), LanguageSentences.of(lead, listOf("ขอใจเธอแลกเบอร์โทร"), stops = false))
        // With marks looked for, a Thai paragraph ends no sentence at its era's full stop.
        assertEquals(emptyList(), LanguageSentences.split("ออกเมื่อ พ.ศ. 2560 เพลงนี้ได้รับความนิยม"))
    }

    @Test
    fun `a long Thai paragraph is cut at spaces into stretches no longer than a quote, never before a number`() {
        val part = "ก".repeat(100)
        val lead = "$part $part พ.ศ. 2560 $part $part"
        val stretches = LanguageSentences.stretches(lead)
        assertEquals(listOf("$part $part พ.ศ. 2560", "$part $part"), stretches)
        assertTrue(stretches.all { it.length <= 300 })
    }
}

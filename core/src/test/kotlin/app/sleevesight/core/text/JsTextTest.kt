package app.sleevesight.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

/** JavaScript's whitespace told by the character (#1318), as the regex of its class told it. */
class JsTextTest {
    @Test
    fun `every character is whitespace just when the class's regex matches it`() {
        val regex = Regex(JS_WHITESPACE_CLASS)
        for (code in Char.MIN_VALUE.code..Char.MAX_VALUE.code) {
            val c = code.toChar()
            assertEquals(regex.matches(c.toString()), isJsWhitespace(c), "U+" + code.toString(HEX).padStart(4, '0'))
        }
    }

    private companion object {
        const val HEX = 16
    }
}

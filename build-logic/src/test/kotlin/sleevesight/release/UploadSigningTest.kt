package sleevesight.release

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadSigningTest {
    private val all =
        mapOf(
            UploadSigning.STORE_FILE to "/runner/temp/upload.jks",
            UploadSigning.STORE_PASSWORD to "store-secret",
            UploadSigning.KEY_ALIAS to "upload",
            UploadSigning.KEY_PASSWORD to "key-secret",
        )

    @Test
    fun `no variables leave the build unsigned`() {
        assertNull(UploadSigning.from { null })
        assertNull(UploadSigning.from { "" })
    }

    @Test
    fun `all four sign it`() {
        assertEquals(
            UploadSigning("/runner/temp/upload.jks", "store-secret", "upload", "key-secret"),
            UploadSigning.from(all::get),
        )
    }

    @Test
    fun `some of them fail, naming the missing ones`() {
        val partial = all - UploadSigning.KEY_PASSWORD + (UploadSigning.KEY_ALIAS to "")
        val error = assertFailsWith<IllegalArgumentException> { UploadSigning.from(partial::get) }
        val message = error.message.orEmpty()
        assertTrue(message.endsWith("missing or empty: ${UploadSigning.KEY_ALIAS}, ${UploadSigning.KEY_PASSWORD}"), message)
    }

    @Test
    fun `the passwords stay out of its string form`() {
        val text = UploadSigning.from(all::get).toString()
        assertFalse("secret" in text, text)
    }
}

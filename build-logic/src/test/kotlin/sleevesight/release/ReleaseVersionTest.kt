package sleevesight.release

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReleaseVersionTest {
    @Test
    fun `the code packs major, minor and patch`() {
        assertEquals(ReleaseVersion("0.1.0", 1_000), ReleaseVersion.parse("0.1.0"))
        assertEquals(ReleaseVersion("1.2.3", 1_002_003), ReleaseVersion.parse("1.2.3"))
        assertEquals(ReleaseVersion("0.0.1", 1), ReleaseVersion.parse("0.0.1"))
        assertEquals(ReleaseVersion("2100.0.0", ReleaseVersion.MAX_CODE), ReleaseVersion.parse("2100.0.0"))
    }

    @Test
    fun `a higher version has a higher code`() {
        val ordered = listOf("0.0.1", "0.0.999", "0.1.0", "0.999.999", "1.0.0", "1.0.10", "1.1.0", "10.0.0")
        val codes = ordered.map { ReleaseVersion.parse(it).code }
        assertEquals(codes.sorted(), codes)
        assertEquals(codes.distinct(), codes)
    }

    @Test
    fun `other forms are refused`() {
        listOf(
            "",
            "v1.0.0",
            "1.0",
            "1.0.0.0",
            "1.0.0-beta",
            "01.0.0",
            "1.00.0",
            "1.0.1000",
            "1.1000.0",
            "0.0.0",
            "2100.0.1",
            "10000.0.0",
        ).forEach { version ->
            val error = assertFailsWith<IllegalArgumentException>(version) { ReleaseVersion.parse(version) }
            assertTrue("'$version'" in error.message.orEmpty(), error.message)
        }
    }
}

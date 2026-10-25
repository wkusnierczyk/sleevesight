package sleevesight.checks

import kotlin.test.Test
import kotlin.test.assertEquals

class CheckApkNoticesTest {
    private val list = "# module\tlicence\tnotice\tname\na:b:1\tApache-2.0\tapache-2.0.txt\t\nc:d:1\tMIT\tmit-d.txt\tD\n"

    @Test
    fun `an APK with the list and each notice it names passes`() {
        val entries = setOf("assets/licences/libraries.txt", "assets/licences/apache-2.0.txt", "assets/licences/mit-d.txt")
        assertEquals(emptyList(), CheckApkNotices.problems(entries, list))
    }

    @Test
    fun `an APK without the list, or without a notice it names, fails`() {
        assertEquals(listOf("no assets/licences/libraries.txt"), CheckApkNotices.problems(emptySet(), null))
        assertEquals(
            listOf("no assets/licences/mit-d.txt"),
            CheckApkNotices.problems(setOf("assets/licences/apache-2.0.txt"), list),
        )
        assertEquals(listOf("assets/licences/libraries.txt lists no library"), CheckApkNotices.problems(emptySet(), "# only\n"))
    }
}

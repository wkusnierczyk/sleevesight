package sleevesight.checks

import kotlin.test.Test
import kotlin.test.assertEquals

class CheckDependencyPageTest {
    private val modules = listOf("io.ktor:ktor-client-core:3.6.0", "io.ktor:ktor-io:3.6.0", "org.slf4j:slf4j-api:2.0.19")

    @Test
    fun `only the backticked groups on Groups lines count`() {
        val page =
            """
            Ktor (`io.ktor`) makes the calls.

            - **Groups:** `io.ktor`, `org.slf4j` (the API only)
            * **Groups:** `com.example`
            - **Module:** `:net`
            Groups: `org.ignored`
            """.trimIndent()
        assertEquals(listOf("io.ktor", "org.slf4j", "com.example"), CheckDependencyPage.groupsOn(page))
    }

    @Test
    fun `a page naming each shipped group once passes`() {
        val page = "- **Groups:** `io.ktor`\n- **Groups:** `org.slf4j`\n"
        assertEquals(emptyList(), CheckDependencyPage.problems(page, modules))
    }

    @Test
    fun `a shipped group missing from the page fails`() {
        assertEquals(
            listOf("shipped, not on the page: org.slf4j"),
            CheckDependencyPage.problems("- **Groups:** `io.ktor`", modules),
        )
    }

    @Test
    fun `a group on the page that is not shipped fails`() {
        assertEquals(
            listOf("on the page, not shipped: com.example"),
            CheckDependencyPage.problems("- **Groups:** `io.ktor`, `org.slf4j`, `com.example`", modules),
        )
    }

    @Test
    fun `a group listed twice, or a token that is no group, fails`() {
        assertEquals(
            listOf("not a Maven group: `io.ktor:ktor-io`", "listed more than once: io.ktor"),
            CheckDependencyPage.problems(
                "- **Groups:** `io.ktor`, `org.slf4j`\n- **Groups:** `io.ktor`, `io.ktor:ktor-io`",
                modules,
            ),
        )
    }

    @Test
    fun `groups match exactly, not as prefixes`() {
        assertEquals(
            listOf("shipped, not on the page: io.ktor", "on the page, not shipped: io"),
            CheckDependencyPage.problems("- **Groups:** `io`, `org.slf4j`", modules),
        )
    }
}

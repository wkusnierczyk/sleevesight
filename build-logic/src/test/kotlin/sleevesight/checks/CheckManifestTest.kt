package sleevesight.checks

import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckManifestTest {
    private fun manifest(applicationAttributes: String): Document =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application android:label="x" $applicationAttributes />
                </manifest>
                """.trimIndent().byteInputStream(),
            )

    @Test
    fun `a manifest that refuses cleartext passes`() {
        assertEquals(emptyList(), CheckManifest.cleartextProblems(manifest("""android:usesCleartextTraffic="false"""")))
    }

    @Test
    fun `a manifest that does not say whether cleartext is allowed fails, as Android 8 allows it`() {
        assertEquals(
            listOf("android:usesCleartextTraffic is not \"false\""),
            CheckManifest.cleartextProblems(manifest("")),
        )
        assertEquals(
            listOf("android:usesCleartextTraffic is not \"false\""),
            CheckManifest.cleartextProblems(manifest("""android:usesCleartextTraffic="true"""")),
        )
    }

    @Test
    fun `a network security config fails, as it takes the attribute's place from Android 7 on`() {
        val attributes = """android:usesCleartextTraffic="false" android:networkSecurityConfig="@xml/net""""
        assertEquals(
            listOf("android:networkSecurityConfig is set"),
            CheckManifest.cleartextProblems(manifest(attributes)),
        )
    }

    @Test
    fun `the attribute must be in the android namespace`() {
        val doc =
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse("""<manifest><application usesCleartextTraffic="false" /></manifest>""".byteInputStream())
        assertEquals(listOf("android:usesCleartextTraffic is not \"false\""), CheckManifest.cleartextProblems(doc))
    }
}

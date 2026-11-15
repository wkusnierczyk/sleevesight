package sleevesight.checks

import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    private fun componentsManifest(componentsXml: String): Document =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application>$componentsXml</application>
                </manifest>
                """.trimIndent().byteInputStream(),
            )

    @Test
    fun `components that say android exported true count as exported, of every kind`() {
        val doc =
            componentsManifest(
                """
                <activity android:name="a.Activity" android:exported="true" />
                <activity-alias android:name="a.Alias" android:exported="true" />
                <service android:name="a.Service" android:exported="true" />
                <receiver android:name="a.Receiver" android:exported="true" />
                <provider android:name="a.Provider" android:exported="true" />
                """.trimIndent(),
            )
        assertEquals(
            listOf(
                CheckManifest.Component("activity", "a.Activity"),
                CheckManifest.Component("activity-alias", "a.Alias"),
                CheckManifest.Component("service", "a.Service"),
                CheckManifest.Component("receiver", "a.Receiver"),
                CheckManifest.Component("provider", "a.Provider"),
            ),
            CheckManifest.exportedComponents(doc),
        )
    }

    @Test
    fun `a component with an intent filter and no android exported counts, as older targets exported it`() {
        val doc =
            componentsManifest(
                """
                <receiver android:name="a.Implicit">
                    <intent-filter><action android:name="a.ACTION" /></intent-filter>
                </receiver>
                <receiver android:name="a.Unexported" android:exported="false">
                    <intent-filter><action android:name="a.ACTION" /></intent-filter>
                </receiver>
                <service android:name="a.Private" />
                """.trimIndent(),
            )
        assertEquals(listOf(CheckManifest.Component("receiver", "a.Implicit")), CheckManifest.exportedComponents(doc))
    }

    @Test
    fun `an android exported value other than a plain false counts as exported, as aapt2 may read it as true`() {
        val doc =
            componentsManifest(
                """
                <activity android:name="a.Capital" android:exported="True" />
                <activity android:name="a.Spaced" android:exported=" true" />
                <activity android:name="a.Resource" android:exported="@bool/x" />
                <activity android:name="a.SpacedFalse" android:exported=" FALSE " />
                """.trimIndent(),
            )
        assertEquals(
            listOf("a.Capital", "a.Spaced", "a.Resource"),
            CheckManifest.exportedComponents(doc).map { it.name },
        )
    }

    @Test
    fun `one run reports an extra permission and an unexpected component together`() {
        val doc =
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(
                    """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                        <uses-permission android:name="android.permission.INTERNET" />
                        <uses-permission android:name="android.permission.READ_CONTACTS" />
                        <application android:usesCleartextTraffic="false">
                            <service
                                android:name="a.Listener"
                                android:exported="true"
                                android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
                                <meta-data
                                    android:name="android.service.notification.default_filter_types"
                                    android:value="" />
                                <meta-data
                                    android:name="android.service.notification.disabled_filter_types"
                                    android:value="conversations|alerting|silent|ongoing" />
                            </service>
                            <receiver android:name="a.Extra" android:exported="true" />
                        </application>
                    </manifest>
                    """.trimIndent().byteInputStream(),
                )
        val problems =
            CheckManifest.problems(
                doc,
                CheckManifest.Allowlist("permissions.txt", listOf("android.permission.INTERNET")),
                CheckManifest.Allowlist("components.txt", listOf("a.Listener")),
                "debug",
                "app.x",
            )
        assertEquals(2, problems.size)
        assertTrue(problems[0].startsWith("Permissions outside permissions.txt:\n  android.permission.READ_CONTACTS"))
        assertTrue(
            problems[1].startsWith(
                "Components the debug variant's merged manifest exports outside components.txt:\n  <receiver> a.Extra",
            ),
        )
    }

    @Test
    fun `an allowlist entry with variants after it is allowed in those variants only`() {
        val lines =
            listOf(
                "# a comment",
                "a.Everywhere  # in every variant",
                "",
                "a.DebugOnly debug",
                "\${applicationId}.Mine release debug",
            )
        assertEquals(
            setOf("a.Everywhere", "a.DebugOnly", "app.x.debug.Mine"),
            CheckManifest.allowedComponents(lines, "debug", "app.x.debug"),
        )
        assertEquals(
            setOf("a.Everywhere", "app.x.Mine"),
            CheckManifest.allowedComponents(lines, "release", "app.x"),
        )
    }

    private fun listenerManifest(metaDataXml: String): Document =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application>
                        <service
                            android:name=".session.SleevesightListenerService"
                            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
                            <intent-filter>
                                <action android:name="android.service.notification.NotificationListenerService" />
                            </intent-filter>
                            $metaDataXml
                        </service>
                    </application>
                </manifest>
                """.trimIndent().byteInputStream(),
            )

    @Test
    fun `a notification listener that opts out of default filter types and disables all four categories passes`() {
        val doc =
            listenerManifest(
                """
                <meta-data
                    android:name="android.service.notification.default_filter_types"
                    android:value="" />
                <meta-data
                    android:name="android.service.notification.disabled_filter_types"
                    android:value="conversations|alerting|silent|ongoing" />
                """.trimIndent(),
            )
        assertEquals(emptyList(), CheckManifest.notificationFilterProblems(doc))
    }

    @Test
    fun `a notification listener missing either filter meta-data entry fails`() {
        assertEquals(
            listOf(
                ".session.SleevesightListenerService: android.service.notification.default_filter_types must be \"\"",
                ".session.SleevesightListenerService: android.service.notification.disabled_filter_types must be \"conversations|alerting|silent|ongoing\"",
            ),
            CheckManifest.notificationFilterProblems(listenerManifest("")),
        )
    }

    @Test
    fun `a notification listener that uses comma-separated disabled_filter_types fails`() {
        val doc =
            listenerManifest(
                """
                <meta-data
                    android:name="android.service.notification.default_filter_types"
                    android:value="" />
                <meta-data
                    android:name="android.service.notification.disabled_filter_types"
                    android:value="conversations,alerting,silent,ongoing" />
                """.trimIndent(),
            )
        assertEquals(
            listOf(
                ".session.SleevesightListenerService: android.service.notification.disabled_filter_types must be \"conversations|alerting|silent|ongoing\"",
            ),
            CheckManifest.notificationFilterProblems(doc),
        )
    }
}

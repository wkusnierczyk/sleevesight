package sleevesight.checks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fails when a variant's merged manifest requests a permission that is not in the permission
 * allowlist. Libraries can merge permissions in unseen; this makes each one a reviewed edit.
 * `${applicationId}` in the allowlist stands for the application id.
 *
 * Fails too when the merged manifest could allow an unencrypted connection (#696): its
 * `<application>` must say `android:usesCleartextTraffic="false"` (Android 8 allows cleartext by
 * default, and `minSdk` is 26) and carry no `android:networkSecurityConfig`, which from Android 7
 * on takes that attribute's place and could allow cleartext.
 *
 * Fails too when a notification-listener `<service>` does not opt out of receiving notifications on
 * Android 12+ (#739): it must declare `android.service.notification.default_filter_types=""` and
 * `android.service.notification.disabled_filter_types="conversations|alerting|silent|ongoing"`.
 */
abstract class CheckManifest : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allowlist: RegularFileProperty

    @get:Input
    abstract val applicationId: Property<String>

    /** Written on success, so the check is up to date until its inputs change. */
    @get:OutputFile
    abstract val marker: RegularFileProperty

    init {
        marker.convention(project.layout.buildDirectory.file("checks/$name.ok"))
    }

    @TaskAction
    fun check() {
        val doc =
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(mergedManifest.get().asFile)
        val requested =
            PERMISSION_TAGS.flatMap { tag ->
                val nodes = doc.getElementsByTagName(tag)
                (0 until nodes.length).map { i ->
                    nodes.item(i).attributes.getNamedItemNS(ANDROID_NS, "name")?.nodeValue
                        ?: throw GradleException("A <$tag> in the merged manifest has no android:name.")
                }
            }
        val allowed =
            allowlist.get().asFile.readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .map { it.replace("\${applicationId}", applicationId.get()) }
                .toSet()
        val extra = requested.filterNot { it in allowed }
        if (extra.isNotEmpty()) {
            throw GradleException(
                "Permissions outside ${allowlist.get().asFile.name}:\n" +
                    extra.joinToString("\n") { "  $it" } +
                    "\nRemove them with tools:node=\"remove\", or add them after review.",
            )
        }
        val cleartext = cleartextProblems(doc)
        if (cleartext.isNotEmpty()) {
            throw GradleException(
                "The merged manifest could allow unencrypted connections:\n" +
                    cleartext.joinToString("\n") { "  $it" },
            )
        }
        val filter = notificationFilterProblems(doc)
        if (filter.isNotEmpty()) {
            throw GradleException(
                "The merged manifest does not filter out notifications on its NotificationListenerService:\n" +
                    filter.joinToString("\n") { "  $it" },
            )
        }
        marker.get().asFile.writeText("ok\n")
    }

    internal companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val LISTENER_PERMISSION = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
        private const val LISTENER_ACTION = "android.service.notification.NotificationListenerService"
        private const val DEFAULT_FILTER_TYPES = "android.service.notification.default_filter_types"
        private const val DISABLED_FILTER_TYPES = "android.service.notification.disabled_filter_types"
        private const val ALL_FILTER_TYPES = "conversations|alerting|silent|ongoing"
        private val PERMISSION_TAGS = listOf("uses-permission", "uses-permission-sdk-23")

        /** What in [doc]'s `<application>` could let the app connect unencrypted; empty if nothing. */
        fun cleartextProblems(doc: Document): List<String> {
            val applications = doc.getElementsByTagName("application")
            if (applications.length != 1) return listOf("expected one <application>, found ${applications.length}")
            val application = applications.item(0) as Element
            return listOfNotNull(
                "android:usesCleartextTraffic is not \"false\"".takeIf {
                    application.getAttributeNS(ANDROID_NS, "usesCleartextTraffic") != "false"
                },
                "android:networkSecurityConfig is set".takeIf {
                    application.hasAttributeNS(ANDROID_NS, "networkSecurityConfig")
                },
            )
        }

        /**
         * What in [doc]'s notification-listener `<service>` declarations could let Android 12+
         * deliver notifications to the listener (#739); empty if every listener opts out of all
         * default filter types and disables all four categories.
         */
        fun notificationFilterProblems(doc: Document): List<String> {
            val services = doc.getElementsByTagName("service")
            val listeners =
                (0 until services.length)
                    .map { services.item(it) as Element }
                    .filter { isNotificationListener(it) }
            if (listeners.isEmpty()) {
                return listOf("expected a NotificationListenerService <service>, found none")
            }
            return listeners.flatMap { service ->
                val name = service.getAttributeNS(ANDROID_NS, "name").ifEmpty { "<service>" }
                val meta = metaDataOf(service)
                listOfNotNull(
                    "$name: $DEFAULT_FILTER_TYPES must be \"\"".takeIf {
                        meta[DEFAULT_FILTER_TYPES] != ""
                    },
                    "$name: $DISABLED_FILTER_TYPES must be \"$ALL_FILTER_TYPES\"".takeIf {
                        meta[DISABLED_FILTER_TYPES] != ALL_FILTER_TYPES
                    },
                )
            }
        }

        private fun isNotificationListener(service: Element): Boolean {
            if (service.getAttributeNS(ANDROID_NS, "permission") == LISTENER_PERMISSION) return true
            val actions = service.getElementsByTagName("action")
            return (0 until actions.length).any { i ->
                (actions.item(i) as Element).getAttributeNS(ANDROID_NS, "name") == LISTENER_ACTION
            }
        }

        private fun metaDataOf(service: Element): Map<String, String> {
            val nodes = service.getElementsByTagName("meta-data")
            return buildMap {
                for (i in 0 until nodes.length) {
                    val el = nodes.item(i) as Element
                    if (!el.hasAttributeNS(ANDROID_NS, "name")) continue
                    if (!el.hasAttributeNS(ANDROID_NS, "value")) continue
                    put(el.getAttributeNS(ANDROID_NS, "name"), el.getAttributeNS(ANDROID_NS, "value"))
                }
            }
        }
    }
}

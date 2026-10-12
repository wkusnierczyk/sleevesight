package io.github.wkusnierczyk.sleevesight.spike

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject

/**
 * The probe's screen: notification access, the sessions it follows with buttons to drive them,
 * the deep links to try, and the log. Plain views, since the probe is removed after Phase 0.
 */
class ProbeActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var sessions: LinearLayout
    private lateinit var logTail: TextView
    private val refresh: () -> Unit = { runOnUiThread { render() } }

    private val export =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
            uri?.let { target ->
                contentResolver.openOutputStream(target)?.use { it.write(ProbeLog.contents().toByteArray()) }
                ProbeLog.event("log_exported", JSONObject().put("uri", target.toString()))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeLog.init(this)
        status = TextView(this)
        sessions = vertical()
        logTail = TextView(this).apply { typeface = Typeface.MONOSPACE }
        val root =
            vertical().apply {
                setPadding(PADDING, PADDING, PADDING, PADDING)
                addView(status)
                addView(
                    row(
                        button(
                            "Notification access",
                        ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                        button("Export log") { export.launch("probe.jsonl") },
                        button("Clear log") { ProbeLog.clear() },
                    ),
                )
                addView(sessions)
                addView(logTail)
            }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        ProbeLog.observers += refresh
        SessionWatcher.observers += refresh
        ProbeLog.event("probe_opened", Install.describe(this))
        // The listener may have connected before this screen, or the process restarted: ask directly.
        if (Install.hasNotificationAccess(this)) {
            val component = ComponentName(this, ProbeListenerService::class.java)
            SessionWatcher.update(this, getSystemService(MediaSessionManager::class.java).getActiveSessions(component))
        }
        render()
    }

    override fun onPause() {
        ProbeLog.observers -= refresh
        SessionWatcher.observers -= refresh
        super.onPause()
    }

    private fun render() {
        val install = Install.describe(this)
        status.text =
            buildString {
                appendLine(
                    "Notification access: ${if (Install.hasNotificationAccess(
                            this@ProbeActivity,
                        )
                    ) {
                        "granted"
                    } else {
                        "NOT granted"
                    }}",
                )
                appendLine("Installed by: ${install.optString("installer")}")
                append("${install.optString("device")}, API ${install.optInt("sdk")}")
            }
        sessions.removeAllViews()
        SessionWatcher.controllers().forEach { sessions.addView(sessionView(it)) }
        logTail.text =
            ProbeLog
                .tail()
                .takeLast(LOG_LINES_SHOWN)
                .reversed()
                .joinToString("\n") { summary(it) }
    }

    private fun sessionView(controller: MediaController): View {
        val metadata = controller.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val state = controller.playbackState?.let { Describe.stateName(it.state) } ?: "no state"
        return vertical().apply {
            setPadding(0, PADDING, 0, PADDING)
            addView(
                TextView(context).apply {
                    text =
                        "${controller.packageName}\n$title — $artist\n$state, mediaId=$mediaId"
                },
            )
            addView(
                row(
                    button("Prev") { SessionWatcher.command(controller, "previous") },
                    button("Play") { SessionWatcher.command(controller, "play") },
                    button("Pause") { SessionWatcher.command(controller, "pause") },
                    button("Next") { SessionWatcher.command(controller, "next") },
                ),
            )
            addView(
                row(
                    button("Open player") { openSessionActivity(controller) },
                    button("Open track") { openTrack(mediaId) },
                    button("Search") { openUri("spotify:search:${Uri.encode("$title $artist")}", "search") },
                ),
            )
        }
    }

    private fun openSessionActivity(controller: MediaController) {
        val intent = controller.sessionActivity
        val result = JSONObject().put("package", controller.packageName).put("present", intent != null)
        try {
            // Android 14+ starts the activity only if the sender opts in to lending its own right to
            // start activities; without this, the launch can be blocked with only a logcat line.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION") // The API 36 replacement is not available below 36.
                val options =
                    ActivityOptions
                        .makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                        ).toBundle()
                intent?.send(this, 0, null, null, null, null, options)
            } else {
                intent?.send()
            }
            result.put("sent", intent != null).put("note", "check which screen opened")
        } catch (e: PendingIntent.CanceledException) {
            result.put("sent", false).put("error", e.javaClass.name)
        }
        ProbeLog.event("open_session_activity", result)
    }

    /** Opens the track in Spotify if the media id is a `spotify:track:` URI; logs what it was otherwise. */
    private fun openTrack(mediaId: String?) {
        if (mediaId != null && mediaId.startsWith("spotify:track:")) {
            openUri(mediaId, "track")
        } else {
            ProbeLog.event(
                "deep_link",
                JSONObject()
                    .put(
                        "kind",
                        "track",
                    ).put("skipped", "media id is not a spotify:track URI")
                    .put("mediaId", mediaId),
            )
        }
    }

    private fun openUri(
        uri: String,
        kind: String,
    ) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val handler = intent.resolveActivity(packageManager)?.packageName
        val result = JSONObject().put("kind", kind).put("uri", uri).put("resolvesTo", handler)
        try {
            startActivity(intent)
            result.put("started", true)
        } catch (e: ActivityNotFoundException) {
            result.put("started", false).put("error", e.javaClass.name)
        }
        ProbeLog.event("deep_link", result)
    }

    private fun summary(line: String): String {
        val json = JSONObject(line)
        val detail =
            json.optJSONObject("trackChange")?.let { change ->
                " remaining=${change.opt(
                    "previousRemainingAtChange",
                )} newStateFirst=${change.opt("newStateBeforeMetadata")}"
            } ?: json
                .optJSONObject("playbackState")
                ?.optString("state")
                ?.let { " $it" }
                .orEmpty()
        return "${json.optString("type")} ${json.optString("package")}$detail"
    }

    private companion object {
        const val PADDING = 24
        const val LOG_LINES_SHOWN = 40
    }
}

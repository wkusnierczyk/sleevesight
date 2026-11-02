package app.sleevesight.app.report

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle

/**
 * An email the app composes for the user to send from their own mail app (#390): the app has no
 * server, so nothing it composes leaves the phone unless the user sends it there. Plain text, as
 * shown before it is handed over.
 */
data class Mail(
    val to: String,
    val subject: String,
    val body: String,
) {
    /** The whole mail as plain text, address and subject included, for copying when no mail app takes it. */
    fun asText(): String = "To: $to\nSubject: $subject\n\n$body"

    companion object {
        /** Where reports go. */
        const val SUPPORT: String = "support@sleevesight.app"
    }
}

/**
 * Where a composed [Mail] goes: to a mail app, for the user to send ([send]), or, when none takes
 * it, to the clipboard ([copy]). One path for every report the app composes: the insight's, and
 * the tester's report to come (#452).
 */
interface MailHandoff {
    /** Hands [mail] to a mail app, filled in, for the user to send there, and says how it went. */
    fun send(mail: Mail): Handed

    /** Copies [mail], address and subject included, to the clipboard. */
    fun copy(mail: Mail)

    /** Whether the system confirms a [copy] itself, as Android 13 and later do, so the app need not. */
    val confirmsCopy: Boolean
}

/** How a [Mail] was handed to a mail app ([MailHandoff.send]). */
enum class Handed {
    /** A mail app took it, address, subject and body filled in. */
    OPENED,

    /** No mail app took it. */
    NO_MAIL_APP,
}

/**
 * The phone's own mail apps, through [context], an activity's: an `ACTION_SEND` that only mail
 * apps answer ([mailIntent]), so a message app or a share target is never offered; no app is
 * chosen for the user. No app answering is caught as such, so no `<queries>`
 * entry is needed to ask first.
 */
class PhoneMail(
    private val context: Context,
) : MailHandoff {
    override fun send(mail: Mail): Handed =
        try {
            // Started from outside an activity, it needs a task of its own.
            val intent = mailIntent(mail)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Handed.OPENED
        } catch (_: ActivityNotFoundException) {
            Handed.NO_MAIL_APP
        }

    override val confirmsCopy: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // Marked sensitive, so Android 13 and later do not show its text in the copy's preview: the
    // note is the user's own words.
    override fun copy(mail: Mail) {
        val clip = ClipData.newPlainText(mail.subject, mail.asText())
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private companion object {
        // `ClipDescription.EXTRA_IS_SENSITIVE`, which names it from Android 13; earlier ones ignore it.
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}

/**
 * [mail] as an `ACTION_SEND` intent, the address, subject and body in the extras Android documents
 * for it, with an `ACTION_SENDTO` of a `mailto:` address as its selector, so only mail apps answer
 * it (#732). Not an `ACTION_SENDTO` itself: Android documents no extras for that, and Gmail ignores
 * its `EXTRA_TEXT`, so a body too long to go in the `mailto:` address opened empty.
 */
fun mailIntent(mail: Mail): Intent =
    Intent(Intent.ACTION_SEND)
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(mail.to))
        .putExtra(Intent.EXTRA_SUBJECT, mail.subject)
        .putExtra(Intent.EXTRA_TEXT, mail.body)
        .apply { selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")) }

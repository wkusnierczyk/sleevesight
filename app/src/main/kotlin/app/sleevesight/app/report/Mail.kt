package app.sleevesight.app.report

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

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
    /** Hands [mail] to a mail app, filled in, for the user to send there; false when no app takes it. */
    fun send(mail: Mail): Boolean

    /** Copies [mail], address and subject included, to the clipboard. */
    fun copy(mail: Mail)
}

/**
 * The phone's own mail apps, through [context], an activity's: an `ACTION_SENDTO` of a `mailto:`
 * address ([mailIntent]), which only mail apps answer, so a message app or a share target is never
 * offered; no app is chosen for the user. No app answering is caught as such, so no `<queries>`
 * entry is needed to ask first.
 */
class PhoneMail(
    private val context: Context,
) : MailHandoff {
    override fun send(mail: Mail): Boolean =
        try {
            // Started from outside an activity, it needs a task of its own.
            val intent = mailIntent(mail)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }

    override fun copy(mail: Mail) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(mail.subject, mail.asText()))
    }
}

/**
 * [mail] as an `ACTION_SENDTO` intent: the address, subject and body in the `mailto:` URI
 * (RFC 6068), which mail apps read, and again as the extras Android documents for it, which some
 * read instead.
 */
fun mailIntent(mail: Mail): Intent =
    Intent(Intent.ACTION_SENDTO, mailtoUri(mail))
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(mail.to))
        .putExtra(Intent.EXTRA_SUBJECT, mail.subject)
        .putExtra(Intent.EXTRA_TEXT, mail.body)

/** [mail] as a `mailto:` URI, each part percent-encoded (a space as `%20`, a line break as `%0A`). */
fun mailtoUri(mail: Mail): Uri =
    Uri.parse("mailto:${Uri.encode(mail.to, "@")}?subject=${Uri.encode(mail.subject)}&body=${Uri.encode(mail.body)}")

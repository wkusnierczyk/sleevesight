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
    /** Hands [mail] to a mail app, filled in, for the user to send there; false when no app takes it. */
    fun send(mail: Mail): Boolean

    /** Copies [mail], address and subject included, to the clipboard. */
    fun copy(mail: Mail)

    /** Whether the system confirms a [copy] itself, as Android 13 and later do, so the app need not. */
    val confirmsCopy: Boolean
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

    // Marked sensitive, so Android 13 and later do not show its text in the copy's preview: the
    // note is the user's own words.
    override val confirmsCopy: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

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
 * [mail] as an `ACTION_SENDTO` intent: the address, subject and body in the `mailto:` URI
 * (RFC 6068), which mail apps read, and again as the extras Android documents for it, which some
 * read instead. A body whose URI would pass [MAX_URI_LENGTH] goes in the extras only, whole, so no
 * app that cuts a long URI short is given part of it.
 */
fun mailIntent(mail: Mail): Intent {
    val whole = mailtoUri(mail)
    val uri = if (whole.toString().length <= MAX_URI_LENGTH) whole else mailtoUri(mail, body = false)
    return Intent(Intent.ACTION_SENDTO, uri)
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(mail.to))
        .putExtra(Intent.EXTRA_SUBJECT, mail.subject)
        .putExtra(Intent.EXTRA_TEXT, mail.body)
}

/**
 * [mail] as a `mailto:` URI, each part percent-encoded as UTF-8 (a space as `%20`), and each line
 * break of the body as `%0D%0A`, as RFC 6068 asks; without the body unless [body].
 */
fun mailtoUri(
    mail: Mail,
    body: Boolean = true,
): Uri {
    val head = "mailto:${Uri.encode(mail.to, "@")}?subject=${Uri.encode(mail.subject)}"
    val text = mail.body.replace("\r\n", "\n").replace("\n", "\r\n")
    return Uri.parse(if (body) "$head&body=${Uri.encode(text)}" else head)
}

/**
 * The longest `mailto:` URI the body goes in, in characters, encoded. Mail apps set no documented
 * limit; a report is a few kilobytes, and a very long note or evidence list could pass this.
 */
const val MAX_URI_LENGTH: Int = 16_000

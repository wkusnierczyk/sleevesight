package app.sleevesight.app.report

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.SleevesightApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The hand-off of a composed mail to the phone's mail apps, or to the clipboard (#390). */
@RunWith(RobolectricTestRunner::class)
class MailTest {
    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val mail = Mail(Mail.SUPPORT, "Sleevesight report: Wrong: A & B", "Line one\nLine two: 50% off?")

    @Test
    fun theIntentIsASendWithTheAddressSubjectAndBodyInTheExtras() {
        val intent = mailIntent(mail)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertArrayEquals(arrayOf(Mail.SUPPORT), intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals(mail.subject, intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(mail.body, intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun onlyMailAppsAnswerIt() {
        // The selector is what the system resolves: an `ACTION_SENDTO` of a `mailto:` address.
        val selector = mailIntent(mail).selector!!

        assertEquals(Intent.ACTION_SENDTO, selector.action)
        assertEquals(Uri.parse("mailto:"), selector.data)
    }

    @Test
    fun aLongBodyGoesWhole() {
        // Longer than the `mailto:` address took, when it held the body (#732): a tester's report.
        val long = Mail(Mail.SUPPORT, "Long", "Zażółć gęślą jaźń 🎵\n".repeat(4_000))

        assertEquals(long.body, mailIntent(long).getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun sendStartsTheMailAppWhenOneTakesIt() {
        assertEquals(Handed.OPENED, PhoneMail(app).send(mail))

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_SEND, started.action)
        assertEquals(mail.body, started.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun sendSaysSoWhenNoMailAppTakesIt() {
        // Robolectric resolves nothing: with the check on, an intent no app answers is refused.
        shadowOf(app).checkActivities(true)

        assertEquals(Handed.NO_MAIL_APP, PhoneMail(app).send(mail))
    }

    @Test
    fun copyPutsTheWholeMailOnTheClipboard() {
        PhoneMail(app).copy(mail)

        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals(
            "To: support@sleevesight.app\nSubject: ${mail.subject}\n\n${mail.body}",
            clip.getItemAt(0).text.toString(),
        )
        // Not shown in Android 13's copy preview: the note is the user's own words.
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    @Test
    fun theSystemConfirmsTheCopyFromAndroid13() {
        // The tests run on 36.
        assertTrue(PhoneMail(app).confirmsCopy)
    }

    @Test
    @Config(sdk = [32])
    fun belowAndroid13TheSystemDoesNotConfirmTheCopy() {
        assertFalse(PhoneMail(app).confirmsCopy)
    }
}

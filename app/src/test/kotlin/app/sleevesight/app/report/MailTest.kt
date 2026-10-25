package app.sleevesight.app.report

import android.content.ClipboardManager
import android.content.Intent
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

/** The hand-off of a composed mail to the phone's mail apps, or to the clipboard (#390). */
@RunWith(RobolectricTestRunner::class)
class MailTest {
    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val mail = Mail(Mail.SUPPORT, "Sleevesight report: Wrong: A & B", "Line one\nLine two: 50% off?")

    @Test
    fun theIntentIsASendtoOfAMailtoAddressWithTheSubjectAndBodyInTheUriAndTheExtras() {
        val intent = mailIntent(mail)

        assertEquals(Intent.ACTION_SENDTO, intent.action)
        val uri = intent.data!!
        assertEquals("mailto", uri.scheme)
        assertEquals(
            "mailto:support@sleevesight.app?subject=Sleevesight%20report%3A%20Wrong%3A%20A%20%26%20B" +
                "&body=Line%20one%0ALine%20two%3A%2050%25%20off%3F",
            uri.toString(),
        )
        assertArrayEquals(arrayOf(Mail.SUPPORT), intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals(mail.subject, intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(mail.body, intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun sendStartsTheMailAppWhenOneTakesIt() {
        assertTrue(PhoneMail(app).send(mail))

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, started.action)
    }

    @Test
    fun sendSaysSoWhenNoMailAppTakesIt() {
        // Robolectric resolves nothing: with the check on, an intent no app answers is refused.
        shadowOf(app).checkActivities(true)

        assertFalse(PhoneMail(app).send(mail))
    }

    @Test
    fun copyPutsTheWholeMailOnTheClipboard() {
        PhoneMail(app).copy(mail)

        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals(
            "To: support@sleevesight.app\nSubject: ${mail.subject}\n\n${mail.body}",
            clip.getItemAt(0).text.toString(),
        )
    }
}

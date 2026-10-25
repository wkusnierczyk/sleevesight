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

/** The hand-off of a composed mail to the phone's mail apps, or to the clipboard (#390). */
@RunWith(RobolectricTestRunner::class)
class MailTest {
    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    // A `mailto:` URI is opaque, with no query Android parses: its body, decoded.
    private fun body(uri: Uri): String = Uri.decode(uri.toString().substringAfter("&body="))

    private val mail = Mail(Mail.SUPPORT, "Sleevesight report: Wrong: A & B", "Line one\nLine two: 50% off?")

    @Test
    fun theIntentIsASendtoOfAMailtoAddressWithTheSubjectAndBodyInTheUriAndTheExtras() {
        val intent = mailIntent(mail)

        assertEquals(Intent.ACTION_SENDTO, intent.action)
        val uri = intent.data!!
        assertEquals("mailto", uri.scheme)
        assertEquals(
            "mailto:support@sleevesight.app?subject=Sleevesight%20report%3A%20Wrong%3A%20A%20%26%20B" +
                "&body=Line%20one%0D%0ALine%20two%3A%2050%25%20off%3F",
            uri.toString(),
        )
        assertArrayEquals(arrayOf(Mail.SUPPORT), intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals(mail.subject, intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(mail.body, intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun aMultibyteBodyIsEncodedAsUtf8InTheUri() {
        val uri = mailtoUri(Mail(Mail.SUPPORT, "Zażółć", "Björk – Jóga 🎵"))

        assertEquals(
            "mailto:support@sleevesight.app?subject=Za%C5%BC%C3%B3%C5%82%C4%87" +
                "&body=Bj%C3%B6rk%20%E2%80%93%20J%C3%B3ga%20%F0%9F%8E%B5",
            uri.toString(),
        )
        assertEquals("Björk – Jóga 🎵", body(uri))
    }

    @Test
    fun aBodyTooLongForTheUriGoesWholeInTheExtrasOnly() {
        // Multibyte text grows most when encoded: each of these is 2 to 4 bytes, each byte 3 characters.
        val long = Mail(Mail.SUPPORT, "Long", "Zażółć gęślą jaźń 🎵\n".repeat(400))
        assertTrue(mailtoUri(long).toString().length > MAX_URI_LENGTH)

        val intent = mailIntent(long)

        assertEquals("mailto:support@sleevesight.app?subject=Long", intent.data.toString())
        assertEquals(long.body, intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun aBodyThatFitsGoesInTheUriWhole() {
        val fits = Mail(Mail.SUPPORT, "Fits", "Zażółć gęślą jaźń 🎵\n".repeat(40))

        assertEquals(fits.body.replace("\n", "\r\n"), body(mailIntent(fits).data!!))
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
        // Not shown in Android 13's copy preview: the note is the user's own words.
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    @Test
    fun theSystemConfirmsTheCopyFromAndroid13() {
        // The tests run on 36; below 33 the dialog says the report was copied itself.
        assertTrue(PhoneMail(app).confirmsCopy)
    }
}

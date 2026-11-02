package app.sleevesight.app.report

import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.SleevesightApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The hand-off of a composed mail to the phone's mail apps, or to the clipboard (#390). */
@RunWith(RobolectricTestRunner::class)
class MailTest {
    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val mail = Mail(Mail.SUPPORT, "Sleevesight report: Wrong: A & B", "Line one\nLine two: 50% off?")

    // Longer than the `mailto:` address took, when it held the body (#732): a tester's report.
    private val long = Mail(Mail.SUPPORT, "Long", "Zażółć gęślą jaźń 🎵\n".repeat(4_000))

    // An app on the phone answering [filters], and only them; with the check on, nothing else is.
    private fun installed(
        name: String,
        vararg filters: IntentFilter,
    ) {
        val pm = shadowOf(app.packageManager)
        val component = ComponentName("com.example.$name", "com.example.$name.Compose")
        pm.addActivityIfNotPresent(component)
        for (filter in filters) {
            pm.addIntentFilterForActivity(component, filter.apply { addCategory(Intent.CATEGORY_DEFAULT) })
        }
        shadowOf(app).checkActivities(true)
    }

    private fun mailto() = IntentFilter(Intent.ACTION_SENDTO).apply { addDataScheme("mailto") }

    private fun share() = IntentFilter(Intent.ACTION_SEND, "text/plain")

    // The mail as the app that took it reads it.
    private fun assertCarries(
        mail: Mail,
        started: Intent,
    ) {
        assertEquals(Intent.ACTION_SEND, started.action)
        assertArrayEquals(arrayOf(mail.to), started.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals(mail.subject, started.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(mail.body, started.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun theIntentIsAPlainTextSendWithAMailtoSelector() {
        val intent = mailIntent(mail)

        assertCarries(mail, intent)
        assertEquals("text/plain", intent.type)
        val selector = intent.selector!!
        assertEquals(Intent.ACTION_SENDTO, selector.action)
        assertEquals(Uri.parse("mailto:"), selector.data)
    }

    @Test
    fun onlyMailAppsAnswerIt() {
        installed("mail", mailto(), share())
        installed("chat", share())

        // Android finds the app for an intent with a selector by the selector alone
        // (`Intent.setSelector`); Robolectric's resolution reads the intent itself, so it is asked
        // for the selector's here.
        val answering = app.packageManager.queryIntentActivities(mailIntent(mail).selector!!, 0)

        assertEquals(listOf("com.example.mail"), answering.map { it.activityInfo.packageName })
    }

    @Test
    fun sendFromAnActivityHandsAMailAppTheWholeMail() {
        installed("mail", mailto(), share())
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        assertEquals(Handed.OPENED, PhoneMail(activity).send(long))

        val started = shadowOf(activity).nextStartedActivity
        assertCarries(long, started)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun sendFromOutsideAnActivityStartsTheMailAppInATaskOfItsOwn() {
        installed("mail", mailto(), share())

        assertEquals(Handed.OPENED, PhoneMail(app).send(long))

        val started = shadowOf(app).nextStartedActivity
        assertCarries(long, started)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
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

package app.sleevesight.app.speech

import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.SleevesightApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/** What is said when an insight cannot be read aloud (#325): a missing voice by its language's name. */
@RunWith(RobolectricTestRunner::class)
class ReadAloudMessagesTest {
    private val messages = ReadAloudMessages(ApplicationProvider.getApplicationContext<SleevesightApplication>())

    @Test
    fun aMissingVoiceIsNamedByItsLanguage() {
        Locale.setDefault(Locale.US)
        val said = messages.text(ReadAloudProblem.NO_VOICE, Locale.UK)
        assertTrue(said, said.startsWith("Your phone’s text-to-speech engine has no English voice"))
    }

    @Test
    fun noEngineSaysWhereOneIsSetUp() {
        assertEquals(
            "This phone has no text-to-speech engine to read the insight aloud. One can be set up in Android’s " +
                "settings, under Accessibility, Text-to-speech output.",
            messages.text(ReadAloudProblem.NO_ENGINE, Locale.UK),
        )
    }
}

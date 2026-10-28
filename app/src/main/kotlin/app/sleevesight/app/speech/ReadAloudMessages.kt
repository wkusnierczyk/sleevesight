package app.sleevesight.app.speech

import android.content.Context
import android.widget.Toast
import app.sleevesight.app.R
import java.util.Locale

/**
 * Why an insight cannot be read aloud (#325), told in a short message ([tell]): no engine, no voice
 * for the insight's language (named in the phone's language), no audio focus, or the engine failing.
 */
class ReadAloudMessages(
    private val context: Context,
) {
    /** Tells [problem], of a reading in [locale]. */
    fun tell(
        problem: ReadAloudProblem,
        locale: Locale,
    ) {
        Toast.makeText(context, text(problem, locale), Toast.LENGTH_LONG).show()
    }

    /** What is said of [problem], of a reading in [locale]. */
    fun text(
        problem: ReadAloudProblem,
        locale: Locale,
    ): String =
        when (problem) {
            ReadAloudProblem.NO_ENGINE -> context.getString(R.string.read_aloud_no_engine)
            ReadAloudProblem.NO_VOICE ->
                context.getString(R.string.read_aloud_no_voice, locale.getDisplayLanguage(Locale.getDefault()))
            ReadAloudProblem.BUSY -> context.getString(R.string.read_aloud_busy)
            ReadAloudProblem.FAILED -> context.getString(R.string.read_aloud_failed)
        }
}

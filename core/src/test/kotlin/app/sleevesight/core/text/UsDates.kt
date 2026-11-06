package app.sleevesight.core.text

import app.sleevesight.core.platform.JvmDateWords

/** The rule on dates in the app's form, as the prompts give it with no phone ([DateForm.APP]). */
const val APP_DATE_RULE: String = "- Write dates in the form 17 October 2026, October 2026 or 2026."

/** The rule on dates in a US phone's form ([US_DATES]). */
const val US_DATE_RULE: String = "- Write dates in the form October 17, 2026, October 2026 or 2026."

/** A US phone's form (#957): Android's US English patterns, as the app passes them. */
val US_DATES: DateForm =
    DateForm(
        JvmDateWords { skeleton, _ ->
            when (skeleton) {
                JvmDateWords.DAY -> "MMMM d, y"
                JvmDateWords.MONTH -> "MMMM y"
                else -> "y"
            }
        },
        "en-US",
    )

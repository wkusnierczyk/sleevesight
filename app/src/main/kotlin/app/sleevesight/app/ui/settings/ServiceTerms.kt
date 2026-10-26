package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId

/**
 * The terms an AI service's API is used under, on the user's own key (#409): what they require, in
 * plain words ([note], its first sentence, the agreement, marked bold: [boldStringResource], #543),
 * and where they are ([url]). Checked against each service's own page on 24 October 2026;
 * `privacy.md` says the same, with the same date.
 */
internal class ServiceTerms(
    @StringRes val note: Int,
    val url: String,
)

/** The terms the key [id] is used under, or null for a source's key, which has no such note. */
internal fun serviceTerms(id: SecretId): ServiceTerms? =
    when (id) {
        // Commercial Terms of Service, effective 17 June 2025.
        SecretId.ANTHROPIC -> ServiceTerms(R.string.settings_terms_anthropic, ANTHROPIC_TERMS)
        // OpenAI Services Agreement, effective 1 January 2026.
        SecretId.OPENAI -> ServiceTerms(R.string.settings_terms_openai, OPENAI_TERMS)
        // Gemini API Additional Terms of Service, effective 23 March 2026.
        SecretId.GOOGLE -> ServiceTerms(R.string.settings_terms_google, GOOGLE_TERMS)
        SecretId.LASTFM, SecretId.EXA -> null
    }

internal const val ANTHROPIC_TERMS = "https://www.anthropic.com/legal/commercial-terms"
internal const val OPENAI_TERMS = "https://openai.com/policies/services-agreement/"
internal const val GOOGLE_TERMS = "https://ai.google.dev/gemini-api/terms"

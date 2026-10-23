package app.sleevesight.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.genres.LabelSource

/**
 * The credits (architecture, *UI*, *Licensing*): each data source the app uses, with its licence
 * and attribution, then the font it bundles, Roboto (#377), and then its icons' set, Material
 * Symbols. MusicBrainz is listed with both its licences, CC0 for its core data and CC BY-NC-SA 3.0
 * for its tags, which are a genre label source (D12, #84).
 */
@Composable
fun CreditsScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.credits_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.credits_intro), style = MaterialTheme.typography.bodyLarge)
            for (credit in CREDITS) CreditEntry(credit)
            CreditEntry(FONT_CREDIT)
            CreditEntry(ICON_CREDIT)
        }
    }
}

/** One source: its [name], what the app takes from it ([text]), and its [licence] with a link. */
internal class Credit(
    @StringRes val name: Int,
    @StringRes val text: Int,
    val licence: Licence?,
)

internal class Licence(
    val name: String,
    val url: String,
)

private val CC0 = Licence("CC0", "https://creativecommons.org/publicdomain/zero/1.0/")

/** The sources with an adapter; Discogs joins with its own in Phase 3. */
internal val CREDITS: List<Credit> =
    listOf(
        Credit(R.string.credits_musicbrainz, R.string.credits_musicbrainz_text, CC0),
        Credit(
            R.string.credits_musicbrainz_tags,
            R.string.credits_musicbrainz_tags_text,
            Licence("CC BY-NC-SA 3.0", checkNotNull(LabelSource.MUSICBRAINZ.licenceUrl)),
        ),
        Credit(
            R.string.credits_wikipedia,
            R.string.credits_wikipedia_text,
            Licence("CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0/"),
        ),
        Credit(R.string.credits_wikidata, R.string.credits_wikidata_text, CC0),
        Credit(R.string.credits_lastfm, R.string.credits_lastfm_text, null),
        Credit(R.string.credits_exa, R.string.credits_exa_text, null),
    )

/**
 * Roboto, the app's font, with its copyright notice and its licence, as the SIL Open Font License
 * asks of software that bundles the font; the licence's text is in the app too
 * (`assets/licences/roboto-ofl.txt`).
 */
internal val FONT_CREDIT: Credit =
    Credit(
        R.string.credits_roboto,
        R.string.credits_roboto_text,
        Licence("SIL Open Font License 1.1", "https://openfontlicense.org"),
    )

/**
 * Material Symbols, Google's icons, whose outlines the app's icons are (the cogwheel, #445, and the
 * others in `res/drawable`), under the Apache License 2.0.
 */
internal val ICON_CREDIT: Credit =
    Credit(
        R.string.credits_material_symbols,
        R.string.credits_material_symbols_text,
        Licence("Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    )

@Composable
private fun CreditEntry(credit: Credit) {
    Text(
        stringResource(credit.name),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(stringResource(credit.text), style = MaterialTheme.typography.bodyMedium)
    credit.licence?.let { licence ->
        val linkStyle =
            TextLinkStyles(
                SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
            )
        Text(
            buildAnnotatedString { withLink(LinkAnnotation.Url(licence.url, linkStyle)) { append(licence.name) } },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CreditsPreview() {
    SleevesightTheme { CreditsScreen() }
}

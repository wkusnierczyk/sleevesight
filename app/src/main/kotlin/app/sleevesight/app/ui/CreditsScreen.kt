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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.app.ui.theme.linkStyles
import app.sleevesight.core.genres.LabelSource
import java.io.IOException

/**
 * The credits (architecture, *UI*, *Licensing*): each data source the app uses, with its licence
 * and attribution, then the font it bundles, Roboto (#377), its icons' set, Material Symbols, and
 * the libraries it ships, by licence, each with the licence's text (#488). MusicBrainz is listed
 * with both its licences, CC0 for its core data and CC BY-NC-SA 3.0 for its tags, which are a
 * genre label source (D12, #84).
 */
@Composable
fun CreditsScreen() {
    val assets = LocalContext.current.assets
    // Read as the screen composes, not after: a recreated screen restores its scroll position on
    // its first layout, and content that came later would find it cut to the shorter screen. The
    // list and the longest licence are about 12 to 17 KB.
    val read = { name: String -> readAsset { assets.open(name).bufferedReader().use { it.readText() } } }
    val libraries = remember { libraryNotices(read(LIBRARIES)) }
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
            Text(
                stringResource(R.string.credits_libraries),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(stringResource(R.string.credits_libraries_text), style = MaterialTheme.typography.bodyMedium)
            for (library in libraries) {
                LibraryEntry(library) { read(LICENCES + it) }
            }
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
 * Material Symbols, Google's icons, whose outlines the `ic_*` symbols in `res/drawable` other than
 * the launcher's are (the cogwheel, #445, among them), under the Apache License 2.0; the licence's
 * text is in the app too (`assets/licences/apache-2.0.txt`).
 */
internal val ICON_CREDIT: Credit =
    Credit(
        R.string.credits_material_symbols,
        R.string.credits_material_symbols_text,
        Licence("Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    )

private const val LICENCES = "licences/"

/** The list of the shipped libraries, which the build writes (`listLibraryLicences`, #488). */
internal const val LIBRARIES = LICENCES + "libraries.txt"

/** The libraries under one notice: its asset file, its title, its licence, and the libraries. */
internal data class LibraryNotice(
    val notice: String,
    val name: String?,
    val licence: String,
    val libraries: List<String>,
)

/** The licences the build knows (`ListLibraryLicences.spdx`), by their names. */
private val LICENCE_NAMES =
    mapOf(
        "Apache-2.0" to "Apache License 2.0",
        "MIT" to "MIT License",
        "BSD-3-Clause" to "BSD 3-Clause License",
        "MPL-2.0" to "Mozilla Public License 2.0",
        "Unicode-3.0" to "Unicode License v3",
    )

internal fun licenceName(spdx: String): String = LICENCE_NAMES[spdx] ?: spdx

/**
 * The libraries in [list] (`module`, `licence`, `notice`, `name`, a tab between) by their notice:
 * the Apache License's first, by group, since it covers most of them, then the others by name,
 * each library as `group:name`. Empty when there is no list.
 */
internal fun libraryNotices(list: String?): List<LibraryNotice> =
    list
        .orEmpty()
        .lines()
        .filterNot { it.isBlank() || it.startsWith("#") }
        .map { it.split('\t') }
        .filter { it.size >= NAME }
        .map { Row(it[0].substringBeforeLast(':'), it[1], it[2], it.getOrNull(NAME)?.ifBlank { null }) }
        .groupBy { it.notice }
        .map { (notice, rows) ->
            val name = rows.first().name
            val modules = rows.map { if (name == null) it.module.substringBefore(':') else it.module }
            LibraryNotice(notice, name, rows.first().licence, modules.distinct().sorted())
        }.sortedWith(APACHE_FIRST_THEN_BY_NAME)

private val APACHE_FIRST_THEN_BY_NAME =
    compareBy<LibraryNotice> { it.name != null }.thenBy(nullsFirst(String.CASE_INSENSITIVE_ORDER)) { it.name }

/** The column of a row's name, after its module, licence and notice. */
private const val NAME = 3

/** A row of the list, its module as `group:name`. */
private data class Row(
    val module: String,
    val licence: String,
    val notice: String,
    val name: String?,
)

/** [read], or null when the asset is missing (a preview, or a build without the list). */
private fun readAsset(read: () -> String): String? =
    try {
        read()
    } catch (_: IOException) {
        null
    }

/**
 * [text] reflowed for a narrow screen, since the licences are wrapped at about 78 columns: the lines
 * of each paragraph joined, except a line that starts a list item, such as `(a)`, `4.` or `*`, and
 * the rules and boxes of `=` and `-` some NOTICE files are drawn with, which stay lines of their own.
 * Runs of spaces become one, and indentation goes. The asset itself stays as it is.
 */
internal fun reflow(text: String): String =
    text.split(Regex("\n[ \t]*\n")).filter { it.isNotBlank() }.joinToString("\n\n") { paragraph ->
        val lines = mutableListOf<String>()
        for (line in paragraph.lines().map { it.trim().replace(Regex(" {2,}"), " ") }.filter { it.isNotEmpty() }) {
            if (lines.isEmpty() || OWN_LINE.containsMatchIn(line) || RULE.containsMatchIn(lines.last())) {
                lines += line
            } else {
                lines[lines.lastIndex] = lines.last() + " " + line
            }
        }
        lines.joinToString("\n")
    }

/** A list item's start, or a line of a rule or box. */
private val OWN_LINE = Regex("""^([-*•] |\(?[0-9a-z]{1,3}[.)] |[=-]{2})""")

/** A line of a rule or box, which the next line does not join. */
private val RULE = Regex("""^[=-]{2}""")

@Composable
private fun LibraryEntry(
    library: LibraryNotice,
    read: (String) -> String?,
) {
    Text(
        library.name ?: stringResource(R.string.credits_libraries_apache),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(library.libraries.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
    // The Apache License's entry names it in its title.
    if (library.name != null) Text(licenceName(library.licence), style = MaterialTheme.typography.bodyMedium)
    var open by rememberSaveable(library.notice) { mutableStateOf(false) }
    // A link, as the other licences are, not a button: in non-touch mode the screen's first button
    // takes the initial focus, and the screen would open scrolled down to it.
    val toggle = stringResource(if (open) R.string.credits_hide_licence else R.string.credits_show_licence)
    val state = stringResource(if (open) R.string.credits_licence_shown else R.string.credits_licence_hidden)
    LinkedText(
        buildAnnotatedString {
            withLink(LinkAnnotation.Clickable(library.notice, linkStyles()) { open = !open }) { append(toggle) }
        },
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { stateDescription = state },
    )
    if (open) {
        val text = remember(library.notice) { read(library.notice)?.let(::reflow) }
        text?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun CreditEntry(credit: Credit) {
    Text(
        stringResource(credit.name),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(stringResource(credit.text), style = MaterialTheme.typography.bodyMedium)
    credit.licence?.let { licence ->
        LinkedText(
            buildAnnotatedString { withLink(LinkAnnotation.Url(licence.url, linkStyles())) { append(licence.name) } },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CreditsPreview() {
    SleevesightTheme { CreditsScreen() }
}

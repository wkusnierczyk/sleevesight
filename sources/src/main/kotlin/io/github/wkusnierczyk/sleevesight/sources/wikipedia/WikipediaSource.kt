package io.github.wkusnierczyk.sleevesight.sources.wikipedia

import io.github.wkusnierczyk.sleevesight.core.evidence.EvidenceDraft
import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import io.github.wkusnierczyk.sleevesight.core.resolution.IdentityCheck
import io.github.wkusnierczyk.sleevesight.core.resolution.IdentityCheck.Route
import io.github.wkusnierczyk.sleevesight.core.resolution.IdentityCheck.Verdict
import io.github.wkusnierczyk.sleevesight.core.resolution.Lookup
import io.github.wkusnierczyk.sleevesight.core.resolution.NameMatcher
import io.github.wkusnierczyk.sleevesight.core.resolution.WikidataEvidence
import io.github.wkusnierczyk.sleevesight.core.sources.Rejection
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.core.sources.ResolvedArtist
import io.github.wkusnierczyk.sleevesight.core.sources.SourceAdapter
import io.github.wkusnierczyk.sleevesight.core.sources.SourceResult
import io.github.wkusnierczyk.sleevesight.sources.lastfm.LastFmBio
import kotlinx.coroutines.Deferred
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Wikipedia as a source (`design/architecture.md`, *Source adapters*, *Entity resolution* step 5),
 * with the Wikidata lines (`WD`) of the items it confirms.
 *
 * It looks up by name at once (D4): one request for the articles the track's title and artists
 * would have (`Title (Artist song)`, `Title (song)`, `Title`, each artist's name), before awaiting
 * the resolution. Then every candidate goes through the identity check: the items MusicBrainz
 * links first, then the articles found by name; the first confirmed song is the song or original
 * song article. Each credited artist's article is reached through its MusicBrainz id, and an
 * article found by an artist's name is checked against it.
 *
 * Lines (D1): from each confirmed article's lead, at most [MAX_SENTENCES] sentences, verbatim,
 * those that name the article's subject and are at most [MAX_SENTENCE_LENGTH] characters, each
 * linking its article (CC BY-SA 4.0, credited to Wikipedia through the link), with only the
 * punctuation the plain-text extract leaves where it drops a pronunciation tidied; then the item's
 * templated Wikidata lines. In order: the song's, then each artist's, in the track's order.
 */
class WikipediaSource(
    private val wikipedia: WikipediaClient,
    private val check: IdentityCheck,
    private val names: NameMatcher,
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : SourceAdapter {
    override val id: SourceId = SourceId.WIKIPEDIA

    override suspend fun lookup(
        track: NormalizedTrack,
        resolution: Deferred<Resolution>,
    ): SourceResult {
        val start = timeSource.markNow()
        val credited = track.artists.filter { it.isNotBlank() }
        val byName = wikipedia.pages(songTitles(track) + credited)
        val resolved = resolution.await()
        val findings = Findings()
        if (byName is Lookup.Failed) findings.unreachable = true
        val pages = (byName as? Lookup.Found)?.value.orEmpty()
        findings.song = song(track, resolved, pages, findings)
        val artists = resolved.artists.ifEmpty { credited.map { ResolvedArtist(it, null) } }
        findings.artists = artists.mapNotNull { artist(it, pages[it.name], findings) }
        val retrievedAt = clock.now()
        val evidence =
            (
                listOfNotNull(
                    findings.song,
                ) + findings.artists
            ).flatMap { lines(it, track, retrievedAt, findings) }
        return SourceResult(findings.outcome(), evidence, start.elapsedNow(), rejections = findings.rejections)
    }

    private class Findings {
        var song: Verdict.Accepted? = null
        var artists: List<Verdict.Accepted> = emptyList()
        val rejections = mutableListOf<Rejection>()
        var unreachable = false

        fun outcome(): Outcome =
            when {
                song?.scope == Scope.SONG -> Outcome.SongArticle
                song != null -> Outcome.OriginalSong
                artists.isNotEmpty() -> Outcome.ArtistArticle
                rejections.isNotEmpty() -> Outcome.Rejected(rejections.first().reason)
                unreachable -> Outcome.Unreachable
                else -> Outcome.None
            }
    }

    private class Candidate(
        val item: String,
        val route: Route,
        val title: String?,
    )

    // The first confirmed song candidate; the rejections only when none is confirmed, since the
    // other candidates were alternatives for the same song, not further subjects.
    private suspend fun song(
        track: NormalizedTrack,
        resolution: Resolution,
        pages: Map<String, WpPage?>,
        findings: Findings,
    ): Verdict.Accepted? {
        val linked =
            when (val found = check.musicBrainzItems(resolution)) {
                is Lookup.Found -> found.value
                else -> emptyList<String>().also { findings.unreachable = true }
            }
        val named = songTitles(track).mapNotNull { pages[it] }.distinctBy { it.title }
        val candidates =
            (
                linked.map { Candidate(it, Route.MUSICBRAINZ, null) } +
                    named.mapNotNull { page -> page.item?.let { Candidate(it, Route.NAME, page.title) } }
            ).distinctBy { it.item }
        val rejections = mutableListOf<Rejection>()
        for (candidate in candidates) {
            when (val verdict = check.song(candidate.item, track, resolution, candidate.route)) {
                is Verdict.Accepted -> return verdict
                is Verdict.Rejected -> rejections += Rejection(candidate.title ?: candidate.item, verdict.reason)
                is Verdict.Unreachable -> findings.unreachable = true
            }
        }
        findings.rejections += rejections
        return null
    }

    // An artist's article: through its MusicBrainz id when resolution has one, else the article
    // found by its name, which the check then rejects or confirms.
    private suspend fun artist(
        artist: ResolvedArtist,
        page: WpPage?,
        findings: Findings,
    ): Verdict.Accepted? {
        val mbid = artist.mbid
        val item =
            if (mbid != null) {
                when (val found = check.artistItem(mbid)) {
                    is Lookup.Found -> found.value
                    else -> null.also { findings.unreachable = true }
                }
            } else {
                page?.item
            }
        val verdict = item?.let { check.artist(it, artist) }
        when (verdict) {
            is Verdict.Rejected -> findings.rejections += Rejection(artist.name, verdict.reason)
            is Verdict.Unreachable -> findings.unreachable = true
            else -> Unit
        }
        return verdict as? Verdict.Accepted
    }

    private suspend fun lines(
        accepted: Verdict.Accepted,
        track: NormalizedTrack,
        retrievedAt: Instant,
        findings: Findings,
    ): List<EvidenceDraft> {
        val title = accepted.item.enwiki
        val lead =
            when (val found = title?.let { wikipedia.lead(it) }) {
                null -> null
                is Lookup.Found -> found.value
                else -> null.also { findings.unreachable = true }
            }
        val subjects =
            listOfNotNull(accepted.item.label, track.title.takeIf { accepted.scope != Scope.ARTIST })
        val sentences = lead?.let { sentences(it, subjects) }.orEmpty()
        val quoted =
            sentences.map {
                EvidenceDraft(SourceId.WIKIPEDIA, accepted.scope, it, title?.let(::articleUrl), retrievedAt)
            }
        return quoted + WikidataEvidence.lines(accepted, retrievedAt)
    }

    // The lead's sentences that name one of [subjects], short enough to quote, at most MAX_SENTENCES.
    private fun sentences(
        lead: String,
        subjects: List<String>,
    ): List<String> {
        val keys = subjects.map { " ${names.key(it)} " }.filter { it.isNotBlank() }
        // The lead's first sentence defines the subject, often under a fuller name ("Gordon Meredith
        // Lightfoot Jr."), so it is taken whether or not it names the subject as the label does.
        // Only the first piece of the first paragraph is exempt: if the splitter drops it, no other
        // sentence takes its place unchecked.
        val paragraphs = lead.split('\n').map { tidy(it) }.filter { it.isNotEmpty() }
        val first = paragraphs.firstOrNull()?.let { LastFmBio.split(it).firstOrNull() }
        return paragraphs
            .flatMap { split(it) }
            .filter { sentence -> sentence == first || keys.any { it in " ${names.key(sentence)} " } }
            .filter { it.length <= MAX_SENTENCE_LENGTH }
            .take(MAX_SENTENCES)
    }

    companion object {
        /** At most this many sentences per article (D1: short attributed quotes, never whole passages). */
        const val MAX_SENTENCES: Int = 3

        /** A longer sentence is not quoted. */
        const val MAX_SENTENCE_LENGTH: Int = 300

        // The plain-text extract drops pronunciations and leaves their punctuation behind:
        // `Frehley (, FRAY-lee; April 27, 1951 – …)`. Only that, and doubled spaces, are tidied.
        private val ORPHANED_PUNCTUATION = Regex("\\(\\s*[,;]\\s*")
        private val SPACES = Regex(" {2,}")

        /**
         * [paragraph]'s sentences, never ending inside a quotation: the Last.fm splitter's pieces
         * (`LastFmBio.split`) are joined while a quotation they open is not closed, and a piece left
         * with an open quotation at the end of the paragraph is dropped.
         *
         * A paragraph gives no sentences when its straight quotes do not pair up, scanning from its
         * start: a `"` that would open a quotation right after a letter or digit (an inch mark, `a 7"
         * single`), or a quotation still open at its end. A `"` after a digit may still close one
         * (`"1999" is a song`). So every straight `"` in a paragraph that gives sentences opens or
         * closes a quotation in turn, and no sentence ends inside one; a paragraph with an inch mark
         * outside a quotation is lost whole, a known limit. Curly quotes need only balance in count.
         */
        internal fun split(paragraph: String): List<String> {
            if (!quotesPair(paragraph)) return emptyList()
            val sentences = mutableListOf<String>()
            var open: String? = null
            for (piece in LastFmBio.split(paragraph)) {
                val joined = open?.let { "$it $piece" } ?: piece
                if (balanced(joined)) {
                    sentences += joined
                    open = null
                } else {
                    open = joined
                }
            }
            return sentences
        }

        // Whether [text]'s straight quotes pair up, scanning: no `"` opens a quotation right after a
        // letter or digit (an inch mark), and none is left open at the end.
        private fun quotesPair(text: String): Boolean {
            var inside = false
            for (i in text.indices) {
                if (text[i] != '"') continue
                if (!inside && text.getOrNull(i - 1)?.isLetterOrDigit() == true) return false
                inside = !inside
            }
            return !inside && text.count { it == '“' } == text.count { it == '”' }
        }

        // A piece's quotes balance by count; [quotesPair] has already checked the paragraph's order.
        private fun balanced(text: String): Boolean =
            text.count { it == '"' } % 2 == 0 && text.count { it == '“' } == text.count { it == '”' }

        private fun tidy(paragraph: String): String =
            paragraph.replace(ORPHANED_PUNCTUATION, "(").replace(SPACES, " ").trim()

        private val SMALL_WORDS =
            setOf(
                "a",
                "an",
                "and",
                "as",
                "at",
                "but",
                "by",
                "for",
                "from",
                "in",
                "into",
                "nor",
                "of",
                "on",
                "or",
                "the",
                "to",
                "with",
            )

        /**
         * The article titles a song would have, most specific first: `Title (Artist song)`,
         * `Title (song)`, `Title`; for the title as given, in Wikipedia's casing (small words in
         * lowercase), and without a leading `The`. Wikipedia's redirects cover most other spellings.
         */
        fun songTitles(track: NormalizedTrack): List<String> {
            val title = track.title.trim()
            if (title.isEmpty()) return emptyList()
            val bases =
                listOf(
                    title,
                    wikiCase(title),
                    wikiCase(title.removePrefix("The ").removePrefix("the ")),
                ).distinct()
            val artist = track.primary.trim()
            return bases
                .flatMap { base ->
                    listOfNotNull(artist.takeIf { it.isNotEmpty() }?.let { "$base ($it song)" }, "$base (song)", base)
                }.distinct()
        }

        private fun wikiCase(title: String): String =
            title
                .split(' ')
                .mapIndexed { i, word -> if (i > 0 && word.lowercase() in SMALL_WORDS) word.lowercase() else word }
                .joinToString(" ")
                .replaceFirstChar { it.uppercaseChar() }
    }
}

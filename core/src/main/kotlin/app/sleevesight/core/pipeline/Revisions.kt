package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.synthesis.CheckedSentence
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import app.sleevesight.core.synthesis.SelfRevision
import app.sleevesight.core.text.trimJs

// A reply that revises itself where a card would show it (#798, `SelfRevision`), read the same way for
// the key insight (`InsightWriter`) and the song's, the artist's and the album's insights (#522,
// `ItemInsights`): while it streams, after it ends, and when it is served from the cache.

/**
 * [text] as a card would show it, checked against [evidence]: the grounding check's reply, a note
 * on the model's own evidence at its end taken out (`MetaNote`, #1207); or, when
 * that still revises itself, the part after the revision ([SelfRevision.salvage]), the rest
 * withdrawn; null when nothing usable is left. [revised] is told of a revision found, and whether
 * anything was salvaged. Either way its sentences are checked for support by the lines they cite,
 * the words of [subject] (what the card is about) allowed besides (`Support`, #1036).
 */
internal fun readReply(
    text: String,
    evidence: EvidenceSet,
    allowModel: Boolean,
    subject: Collection<String> = emptyList(),
    revised: (SelfRevision.Found, Boolean) -> Unit = { _, _ -> },
): InsightReply? {
    val reply = GroundingCheck.check(text, evidence, allowModel, notes = true, subject = subject)
    val grounded = (reply as? InsightReply.Written)?.grounded
    val found = grounded?.let { SelfRevision.find(it.text) } ?: return reply
    val salvaged = SelfRevision.salvage(checkNotNull(grounded), found, evidence.ids.toSet())
    revised(found, salvaged != null)
    return salvaged?.let { InsightReply.Written(GroundingCheck.supported(it, evidence, subject)) }
}

/**
 * The names a card about [entity] is written about (#1036): the words a sentence may name without a
 * line it cites holding them, since a line often says *it* or *the song* (`Support`). The track's
 * title, artists and album as the player reports them ([nowPlaying], when given) and as normalized;
 * an artist's name; an album's title, artist and performers.
 */
internal fun subjectOf(
    entity: CardEntity,
    nowPlaying: NowPlaying? = null,
): List<String> =
    when (entity) {
        is CardEntity.Track -> {
            val track = entity.track
            listOfNotNull(nowPlaying?.title, nowPlaying?.artists, nowPlaying?.album, track.title, track.primary) +
                track.artists + track.titleArtists + listOfNotNull(entity.subject)
        }

        is CardEntity.Artist -> {
            listOf(entity.name)
        }

        is CardEntity.Album -> {
            listOf(entity.title, entity.artist) + entity.performers
        }
    }

/**
 * [reply] without the sentences that cite any of [ids] (#958: lines the model was given that the
 * evidence it is kept with no longer has), and, with [described] (a line of that evidence describes
 * the song), without the plain sentence ([GroundingCheck.ONLY_RELEASE_SENTENCE]), so what is shown and
 * kept reads as written from that evidence: as it came when no sentence goes, or it is `NO_EVIDENCE`;
 * empty when no sentence is left.
 */
internal fun withoutCiting(
    reply: String,
    ids: Set<String>,
    described: Boolean = false,
): String {
    val paragraphs =
        (GroundingCheck.check(reply, ids, allowModel = true) as? InsightReply.Written)?.grounded?.paragraphs
    val goes = { sentence: CheckedSentence ->
        sentence.cites.any { it in ids } ||
            (described && sentence.text.trimJs() == GroundingCheck.ONLY_RELEASE_SENTENCE)
    }
    if (paragraphs == null || paragraphs.flatten().none(goes)) return reply
    return paragraphs
        .map { paragraph -> paragraph.filterNot(goes) }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n") { paragraph ->
            paragraph.drop(1).fold(paragraph.first().text) { text, sentence ->
                text + (if (sentence.afterLineBreak) "\n" else " ") + sentence.text
            }
        }
}

/**
 * Whether a sign read while the reply streams stops what the card shows (#798): an aside, the first
 * person or talk of the evidence; not a repeated opening, whose second occurrence is what is kept.
 */
internal fun SelfRevision?.freezes(): Boolean = this != null && this != SelfRevision.REPEATED_OPENING

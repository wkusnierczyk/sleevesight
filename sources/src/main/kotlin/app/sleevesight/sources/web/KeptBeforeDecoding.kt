package app.sleevesight.sources.web

import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.KeptPart
import app.sleevesight.core.text.HtmlReferences
import app.sleevesight.sources.KeptDrafts

/**
 * What this keeps for [key] under [part]; else what it kept under [before], the part's version
 * before Exa's HTML character references were decoded (#1165), when none of its lines holds one:
 * those lines are what they would be now, so they are kept again under [part] and given, and the
 * web lines kept as history (#885) are not all searched again, nor the cards written from them
 * written again. Lines that hold one are a miss, so they are searched again.
 */
internal suspend fun ArtistResults.getCarried(
    part: ArtistPart,
    before: ArtistPart,
    key: String,
): KeptPart? =
    get(part, key)
        ?: get(before, key)
            ?.takeIf { withoutReferences(it.value) }
            ?.also { keep(part, key, it.value) }

// Whether [value], kept lines, decodes and none of its lines' texts holds a character reference.
private fun withoutReferences(value: String): Boolean =
    KeptDrafts.decode(value)?.none { HtmlReferences.decode(it.text) !== it.text } == true

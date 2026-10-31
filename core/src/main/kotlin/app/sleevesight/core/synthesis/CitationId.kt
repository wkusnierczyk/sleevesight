package app.sleevesight.core.synthesis

import app.sleevesight.core.model.SourceId

/**
 * The id a citation of [id] stands for, so a reply or card written under an older letter reads,
 * counts and shows as one written now; [id] itself otherwise:
 *
 * - [GroundingCheck.MODEL_ID] for [GroundingCheck.LEGACY_MODEL_ID], the model's own knowledge's id
 *   until 0.1.8 (#668): `M` is read as `AI`.
 * - Web search's line for its id until 0.1.12 (#769), spelled with [SourceId.LEGACY_WEB_TAG]: `G2`
 *   is read as `S2`, the same line. No source has taken `G`, so a `G<n>` is only ever that.
 *
 * The grounding check reads each citation through it, and so does the card's split into chips. An
 * evidence id kept from before reads through it too, so a kept citation and its line still meet.
 */
fun citationId(id: String): String =
    when {
        id == GroundingCheck.LEGACY_MODEL_ID -> GroundingCheck.MODEL_ID
        LEGACY_WEB_ID.matches(id) -> SourceId.WEB.tag + id.substring(SourceId.LEGACY_WEB_TAG.length)
        else -> id
    }

/**
 * Whether [text], a sentence or reply as written, cites the model's own knowledge by its id before
 * 0.1.8, `[M]` (#668). The reports print a kept reply's text as written, so they add
 * [LEGACY_MODEL_NOTE] when it does, rather than rewrite the text.
 */
fun citesLegacyModelId(text: String): Boolean = "[${GroundingCheck.LEGACY_MODEL_ID}]" in text

/** The reports' note on a kept reply citing `[M]`: what that citation is now. */
const val LEGACY_MODEL_NOTE: String =
    "[M] is the old form of [AI], the AI model's own knowledge (replies written before 0.1.8)."

/**
 * Whether [text], a sentence or reply as written, cites a web search line by its id before 0.1.12,
 * `[G1]`, `[G2]` (#769). The reports add [LEGACY_WEB_NOTE] when it does, as for `[M]`.
 */
fun citesLegacyWebId(text: String): Boolean = LEGACY_WEB_CITATION.containsMatchIn(text)

/** The reports' note on a kept reply citing `[G1]`, `[G2]`: what those citations are now. */
const val LEGACY_WEB_NOTE: String =
    "[G1], [G2], … are the old form of [S1], [S2], …, web search's lines (replies written before 0.1.12)."

/**
 * The notes the reports add under a kept reply's [text], printed as written: [LEGACY_MODEL_NOTE] when
 * it cites `[M]`, [LEGACY_WEB_NOTE] when it cites `[G1]`; none for a reply written now.
 */
fun legacyCitationNotes(text: String): List<String> =
    listOfNotNull(
        LEGACY_MODEL_NOTE.takeIf { citesLegacyModelId(text) },
        LEGACY_WEB_NOTE.takeIf { citesLegacyWebId(text) },
    )

private val LEGACY_WEB_ID = Regex("${SourceId.LEGACY_WEB_TAG}[0-9]+")

private val LEGACY_WEB_CITATION = Regex("\\[${SourceId.LEGACY_WEB_TAG}[0-9]+\\]")

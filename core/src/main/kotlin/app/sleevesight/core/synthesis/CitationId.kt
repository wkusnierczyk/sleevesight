package app.sleevesight.core.synthesis

/**
 * The id a citation of [id] stands for: [GroundingCheck.MODEL_ID] for
 * [GroundingCheck.LEGACY_MODEL_ID], the model's own knowledge's id until 0.1.8 (#668), so a reply or
 * card written before then reads, counts and shows as one written now; [id] itself otherwise. The
 * grounding check reads each citation through it, and so does the card's split into chips.
 */
fun citationId(id: String): String = if (id == GroundingCheck.LEGACY_MODEL_ID) GroundingCheck.MODEL_ID else id

/**
 * Whether [text], a sentence or reply as written, cites the model's own knowledge by its id before
 * 0.1.8, `[M]` (#668). The reports print a kept reply's text as written, so they add
 * [LEGACY_MODEL_NOTE] when it does, rather than rewrite the text.
 */
fun citesLegacyModelId(text: String): Boolean = "[${GroundingCheck.LEGACY_MODEL_ID}]" in text

/** The reports' note on a kept reply citing `[M]`: what that citation is now. */
const val LEGACY_MODEL_NOTE: String =
    "[M] is the old form of [AI], the AI model's own knowledge (replies written before 0.1.8)."

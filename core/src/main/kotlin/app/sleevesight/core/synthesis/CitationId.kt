package app.sleevesight.core.synthesis

/**
 * The id a citation of [id] stands for: [GroundingCheck.MODEL_ID] for
 * [GroundingCheck.LEGACY_MODEL_ID], the model's own knowledge's id until 0.1.8 (#668), so a reply or
 * card written before then reads, counts and shows as one written now; [id] itself otherwise. The
 * grounding check reads each citation through it, and so does the card's split into chips.
 */
fun citationId(id: String): String = if (id == GroundingCheck.LEGACY_MODEL_ID) GroundingCheck.MODEL_ID else id

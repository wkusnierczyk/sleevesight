package app.sleevesight.app.ui.home

import app.sleevesight.core.synthesis.GroundedText
import app.sleevesight.core.synthesis.SentenceStatus

// The lines cited only by sentences they do not back (#1036): their tags in *Sources*
// (`SourcesSection.kt`) drawn as a dim outline. Their chips in the insight's prose are grey
// (`CiteStyles.unsupported`, #1458).

/**
 * The ids of the lines [grounded] cites only from sentences they do not back (#1036,
 * `SentenceStatus.UNSUPPORTED`): a line that any other of its sentences cites, whatever that one's
 * status, is not one.
 */
internal fun unsupportedLines(grounded: GroundedText): Set<String> {
    val (unsupported, others) = grounded.paragraphs.flatten().partition { it.status == SentenceStatus.UNSUPPORTED }
    return unsupported.flatMap { it.cites }.toSet() - others.flatMap { it.cites }.toSet()
}

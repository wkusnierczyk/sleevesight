package app.sleevesight.core.pipeline

import app.sleevesight.core.synthesis.PromptChoices

/**
 * [key] as a card written under these choices of the prompt lab's (#1003) is keyed: its style's hash
 * for the choice's style ([PromptChoices.style], by [hash]) and its [CardKey.variant], the choice's
 * id; [key] itself for a card the lab does not vary, or with the lab off. The release prompt's choice
 * keeps the release card's style, so its card is the one the release build keeps.
 */
fun PromptChoices.keyed(
    key: CardKey,
    hash: (String) -> String,
): CardKey {
    val choice = of(key.kind, key.subject) ?: return key
    val style = style(key.kind, key.subject)
    return key.copy(styleHash = if (style.isEmpty()) key.styleHash else hash(style), variant = choice.id)
}

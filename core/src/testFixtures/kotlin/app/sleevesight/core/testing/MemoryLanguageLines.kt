package app.sleevesight.core.testing

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.sources.ItemLanguageLines
import app.sleevesight.core.sources.LanguageSubject

/**
 * [ItemLanguageLines] in a map, by subject as given, for the tests of what reads and keeps them: the
 * first lines found in each language kept, in the language's order, and each [renew] counted
 * ([renewed]). Not thread-safe: for single-threaded tests (`runTest`). The keys, the table and the
 * memory are `:sources`' (`LanguageLinesSource`), tested there.
 */
class MemoryLanguageLines : ItemLanguageLines {
    private val entries = mutableMapOf<LanguageSubject, List<EvidenceDraft>>()

    /** The subjects renewed, in order. */
    val renewed: MutableList<LanguageSubject> = mutableListOf()

    override suspend fun kept(subject: LanguageSubject): List<EvidenceDraft> = entries[subject].orEmpty()

    override suspend fun keep(
        subject: LanguageSubject,
        language: String,
        lines: List<EvidenceDraft>,
    ) {
        val now = entries[subject].orEmpty()
        if (lines.isNotEmpty() && now.none { it.language == language }) {
            entries[subject] = (now + lines).sortedBy { it.language.orEmpty() }
        }
    }

    override suspend fun renew(subject: LanguageSubject) {
        renewed += subject
    }
}

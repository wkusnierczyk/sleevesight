package app.sleevesight.app.settings

import android.content.Context
import androidx.core.content.edit
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.ai.ModelNames

/**
 * The names the AI services gave their models in the lists the settings fetched (#444), kept in
 * `SharedPreferences` by id, so the settings name a model chosen from a list by its name (*Claude
 * Opus 5.5*, not `claude-opus-5-5`) without fetching the list again. Only a name that differs from
 * its id is kept. Not settings, and not sent anywhere; like the settings, not backed up.
 */
class ListedModelNames(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [id]'s name: as a list gave it, else as the app knows it ([ModelNames]), else the id itself. */
    fun of(id: String): String = prefs.getString(id, null) ?: ModelNames.of(id) ?: id

    /** Keeps the names [models] give that differ from their ids, replacing those kept before. */
    fun learn(models: List<ModelInfo>) {
        val named = models.filter { it.displayName != it.id && prefs.getString(it.id, null) != it.displayName }
        if (named.isEmpty()) return
        prefs.edit { named.forEach { putString(it.id, it.displayName) } }
    }

    private companion object {
        const val PREFS = "model_names"
    }
}

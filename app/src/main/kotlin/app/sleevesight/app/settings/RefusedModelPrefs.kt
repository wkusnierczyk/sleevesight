package app.sleevesight.app.settings

import android.content.Context
import app.sleevesight.app.pipeline.ModelRefusal
import app.sleevesight.app.pipeline.RefusedModelStore
import app.sleevesight.app.pipeline.RefusedModels
import app.sleevesight.core.model.ProviderId

/**
 * The models a call found refused with each AI service's saved key (#970), kept in
 * `SharedPreferences` across starts: per service, a set of `<how>:<model id>`. Only the models' ids
 * and how each was refused; nothing of the key. Not settings, and not sent anywhere; like the
 * settings, not backed up. A service or a way of refusal this version does not know is left out.
 */
class RefusedModelPrefs(
    context: Context,
) : RefusedModelStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): RefusedModels =
        ProviderId.entries
            .mapNotNull { provider ->
                val kept =
                    prefs
                        .getStringSet(provider.name, null)
                        .orEmpty()
                        .mapNotNull(::decode)
                        .toMap()
                kept.takeIf { it.isNotEmpty() }?.let { provider to it }
            }.toMap()

    // Written to the file before it returns (`commit`), saying whether it was: a key is written
    // only once what was refused with the one it replaces is gone from here (#1425).
    override fun save(refused: RefusedModels): Boolean =
        prefs
            .edit()
            .clear()
            .apply {
                for ((provider, models) in refused) {
                    val entries = models.map { (id, how) -> "${how.name}$SEP$id" }.toSet()
                    if (entries.isNotEmpty()) putStringSet(provider.name, entries)
                }
            }.commit()

    private companion object {
        const val PREFS = "refused_models"

        /** Between how a model was refused and its id, which may hold it too (OpenRouter's `…:free`). */
        const val SEP = ':'

        fun decode(entry: String): Pair<String, ModelRefusal>? {
            val how = ModelRefusal.entries.firstOrNull { it.name == entry.substringBefore(SEP) }
            val id = entry.substringAfter(SEP, "")
            return if (how != null && id.isNotEmpty()) id to how else null
        }
    }
}

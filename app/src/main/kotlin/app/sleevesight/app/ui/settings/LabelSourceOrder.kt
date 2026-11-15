package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.core.genres.LabelSource

/**
 * The label sources in the order *Settings → Genres* lists them: by their names there, alphabetically,
 * whatever order they are declared or their labels combine in (#1098).
 */
@Composable
internal fun labelSourcesByName(): List<LabelSource> {
    val names = LabelSource.entries.associateWith { stringResource(labelSourceName(it)) }
    return names.keys.sortedBy { names.getValue(it).lowercase() }
}

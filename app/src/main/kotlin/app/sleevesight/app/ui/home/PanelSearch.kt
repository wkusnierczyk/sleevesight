package app.sleevesight.app.ui.home

import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.TrackState

/**
 * What a panel on Home needs for its *Search with AI* (#773): the searches ([feed]), and the [track]
 * and the [header] it is shown beside. The default searches nothing: the panel shows its empty state
 * alone. A search that starts by itself (*Search with AI*, the setting) starts on the app's side,
 * whether the panel is open or not (`AutoSearches`).
 */
internal class PanelSearch(
    val feed: AiSearchFeed = AiSearchFeed.None,
    val track: TrackState? = null,
    val header: NowPlaying? = null,
)

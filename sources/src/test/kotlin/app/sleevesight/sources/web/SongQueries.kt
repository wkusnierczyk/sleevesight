package app.sleevesight.sources.web

import app.sleevesight.core.language.Language
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SongWebQuery

/** [track]'s song query, with the roles its [resolution] (or its title) tells (#1180, [ArtistRoles.of]). */
internal fun songQuery(
    track: NormalizedTrack,
    language: Language? = null,
    resolution: Resolution? = null,
): SongWebQuery = SongWebQuery(track, language, resolution, ArtistRoles.of(track, resolution?.roles))

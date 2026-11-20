package app.sleevesight.app.session

/**
 * Which players are video sources (#1257; `design/design.md`, *Video sources*): players of videos, not
 * of music, whose sessions report a video's title, its channel as the artist, no album, and the
 * video's thumbnail as the art. Home shows such a track's thumbnail whole, as a wide strip with the
 * black bars trimmed ([app.sleevesight.app.ui.home.trimBars]), read as a video's thumbnail, and leaves
 * out the line that no album was reported.
 *
 * A short list of packages, as [AdDetection] lists the signals of ads: players are added as they are
 * seen (NewPipe and the like). YouTube Music (`com.google.android.apps.youtube.music`) is a music
 * player and not listed, nor is any other.
 */
object VideoSources {
    /** The free YouTube app. */
    const val YOUTUBE: String = "com.google.android.youtube"

    /** The packages of the players that are video sources. */
    val PACKAGES: Set<String> = setOf(YOUTUBE)

    /** Whether [player] (a package name) is a video source: one of [PACKAGES]. */
    fun isVideo(player: String?): Boolean = player in PACKAGES
}

// Use an integer for version numbers — bump it on every release.
version = 2

cloudstream {
    description = "CastleTV — movies and series from the Castle catalog with a separate link for every audio language (multi-audio titles are labelled, play those via VLC to pick the language). Castle caps free playback at 720p; 1080p is Castle-premium."
    authors = listOf("Karthi")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta
     */
    status = 3

    tvTypes = listOf("Movie", "TvSeries")

    language = "ta"
}

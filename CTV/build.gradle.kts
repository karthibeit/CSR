// Use an integer for version numbers — bump it on every release.
version = 1

cloudstream {
    description = "CastleTV — movies and series from the Castle catalog with a separate link for every audio language (multi-audio titles are labelled, play those via VLC to pick the language)"
    authors = listOf("Karthi")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta
     */
    status = 3

    tvTypes = listOf("Movie", "TvSeries")

    language = "ta"
}

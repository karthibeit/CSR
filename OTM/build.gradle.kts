// Use an integer for version numbers — bump it on every release.
version = 8

cloudstream {
    description = "1TamilMV forum — Tamil, Telugu, Malayalam, Kannada and Hindi releases (magnet/torrent + direct/file-host links)"
    authors = listOf("Karthi")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta
     * Kept at 3 (Beta) because the 1TamilMV domain rotates often.
     */
    status = 3

    tvTypes = listOf("Movie", "TvSeries")

    language = "ta"
}

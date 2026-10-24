// Use an integer for version numbers — bump it on every release.
version = 2

cloudstream {
    description = "1TamilMV Tamil sub-forums as main sections — PreDVD, WEB-HD, HD-Rips, HDTV, Web Series and more (magnet/torrent + direct/file-host links)"
    authors = listOf("Karthi")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta
     * Kept at 3 (Beta) because the 1TamilMV domain rotates often.
     */
    status = 3

    tvTypes = listOf("Movie", "TvSeries")

    language = "ta"
}

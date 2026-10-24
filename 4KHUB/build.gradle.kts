// Use an integer for version numbers — bump it on every release.
version = 5

cloudstream {
    description = "4KHDHub — movies and web series in 4K/HD (HubCloud/HubDrive links): Hindi, English, Korean, OTT originals, anime and more"
    authors = listOf("Karthi")

    /**
     * Status: 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta
     * Kept at 3 (Beta) — first release.
     */
    status = 3

    tvTypes = listOf("Movie", "TvSeries")

    language = "en"
}

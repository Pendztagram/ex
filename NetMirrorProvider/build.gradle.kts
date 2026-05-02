version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "id"

    description = "NetMirror search and streaming provider with live playlist fallback."
    authors = listOf("Duro92")

    status = 1 // Ok
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AsianDrama"
    )

    iconUrl = "https://raw.githubusercontent.com/Sushan64/NetMirror-Extension/refs/heads/master/Netmirror/logo.jpeg"

    requiresResources = false
    isCrossPlatform = false
}

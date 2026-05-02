version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "en"

    description = "NetMirror search and streaming provider with live playlist fallback."
    authors = listOf("OpenAI")

    status = 3
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

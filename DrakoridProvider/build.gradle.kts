version = 1

android {
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        val cookieFile = project.file(".drakorid.cookies")
        val cookieValue = if (cookieFile.exists()) {
            cookieFile.readText(Charsets.UTF_8).trim()
        } else {
            ""
        }

        buildConfigField(
            "String",
            "DRAKORID_COOKIE",
            "\"${cookieValue
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")}\""
        )
    }
}

cloudstream {
    description = "Drakor.id"
    language = "id"
    authors = listOf("Codex")

    status = 1
    tvTypes = listOf(
        "AsianDrama",
        "TvSeries",
        "Movie",
    )

    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://drakorid.co&size=%size%"
}

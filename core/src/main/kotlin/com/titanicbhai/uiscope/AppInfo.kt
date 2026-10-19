package com.titanicbhai.uiscope

import java.util.Properties

/** Version facts produced by the build ([BuildInfo]); a plain value so tests can supply their own. */
data class BuildMeta(
    val version: String,
    val buildDate: String,
    val commit: String,
) {
    companion object {
        fun generated(): BuildMeta = BuildMeta(BuildInfo.VERSION, BuildInfo.BUILD_DATE, BuildInfo.COMMIT)
    }
}

/**
 * Who and what the application is: the one place identity, links and version live (plan P0-4).
 * Loaded once in `:app` from `app.properties` and passed to engine/ui by constructor injection.
 */
data class AppInfo(
    val name: String,
    val publisher: String,
    val developer: String,
    val version: String,
    val buildDate: String,
    val commit: String,
    val repoUrl: String,
    val releasesApiUrl: String,
    val issuesUrl: String,
    val privacyUrl: String,
    val docsUrl: String,
    val supportUrl: String,
    /** True when running from the Microsoft Store package. The update check is disabled there (decision D6). */
    val isStoreBuild: Boolean,
) {
    companion object {
        /** Set `UISCOPE_STORE=1` to force Store-build behaviour (for testing). */
        const val STORE_ENV = "UISCOPE_STORE"

        private val URL_KEYS = listOf(
            "app.repo.url",
            "app.releases.api.url",
            "app.issues.url",
            "app.privacy.url",
            "app.docs.url",
            "app.support.url",
        )
        private val TEXT_KEYS = listOf("app.name", "app.publisher", "app.developer")

        /**
         * Builds an [AppInfo] from the contents of `app.properties`.
         * @throws IllegalArgumentException listing every missing key and every non-https URL.
         */
        fun fromProperties(
            props: Properties,
            isStoreBuild: Boolean,
            build: BuildMeta = BuildMeta.generated(),
        ): AppInfo {
            val problems = mutableListOf<String>()
            fun value(key: String): String {
                val v = props.getProperty(key)?.trim().orEmpty()
                if (v.isEmpty()) problems += "missing key '$key'"
                return v
            }
            TEXT_KEYS.forEach { value(it) }
            URL_KEYS.forEach { key ->
                val v = value(key)
                if (v.isNotEmpty() && !v.startsWith("https://")) problems += "'$key' must start with https:// (was '$v')"
            }
            require(problems.isEmpty()) { "app.properties is invalid: ${problems.joinToString("; ")}" }
            return AppInfo(
                name = value("app.name"),
                publisher = value("app.publisher"),
                developer = value("app.developer"),
                version = build.version,
                buildDate = build.buildDate,
                commit = build.commit,
                repoUrl = value("app.repo.url"),
                releasesApiUrl = value("app.releases.api.url"),
                issuesUrl = value("app.issues.url"),
                privacyUrl = value("app.privacy.url"),
                docsUrl = value("app.docs.url"),
                supportUrl = value("app.support.url"),
                isStoreBuild = isStoreBuild,
            )
        }

        /**
         * Store build = the executable runs from a `WindowsApps` folder (where MSIX packages are installed),
         * or `UISCOPE_STORE=1` is set.
         */
        fun detectStoreBuild(installPath: String?, env: Map<String, String>): Boolean {
            if (env[STORE_ENV] == "1") return true
            val normalized = installPath?.replace('/', '\\')?.lowercase().orEmpty()
            return normalized.contains("\\windowsapps\\")
        }
    }
}

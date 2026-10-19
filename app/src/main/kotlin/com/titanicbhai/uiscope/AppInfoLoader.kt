package com.titanicbhai.uiscope

import java.io.IOException
import java.util.Properties

/** Loads [AppInfo] from the bundled `app.properties`. Lives in `:app` so `core`, `engine` and `ui` never import `:app`. */
object AppInfoLoader {
    private const val RESOURCE = "/app.properties"

    fun load(
        env: Map<String, String> = System.getenv(),
        installPath: String? = currentExecutablePath(),
    ): AppInfo {
        val props = Properties()
        val stream = AppInfoLoader::class.java.getResourceAsStream(RESOURCE)
            ?: error("$RESOURCE is missing from the application resources")
        try {
            stream.use { props.load(it.reader(Charsets.UTF_8)) }
        } catch (e: IOException) {
            throw IllegalStateException("$RESOURCE could not be read", e)
        }
        return AppInfo.fromProperties(props, AppInfo.detectStoreBuild(installPath, env))
    }

    private fun currentExecutablePath(): String? = ProcessHandle.current().info().command().orElse(null)
}

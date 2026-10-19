package com.titanicbhai.uiscope

/** The operating system UIScope is running on. Pure value; no native calls. */
enum class HostOs {
    WINDOWS,
    MACOS,
    LINUX,
    OTHER,
    ;

    companion object {
        /** Maps the `os.name` system property value to a [HostOs]. Case-insensitive. */
        fun fromOsName(osName: String?): HostOs {
            val name = osName?.lowercase().orEmpty()
            return when {
                name.contains("win") && !name.contains("darwin") -> WINDOWS
                name.contains("mac") || name.contains("darwin") -> MACOS
                name.contains("nux") || name.contains("nix") || name.contains("linux") -> LINUX
                else -> OTHER
            }
        }

        fun current(): HostOs = fromOsName(System.getProperty("os.name"))
    }
}

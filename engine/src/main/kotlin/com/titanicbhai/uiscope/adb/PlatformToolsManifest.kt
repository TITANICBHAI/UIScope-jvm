package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.HostOs

/**
 * One download: an exact versioned URL (never "latest"), its size and its checksum. A pinned entry carries a SHA-256; an entry read from
 * Google's package list ([PlatformToolsFeed]) carries the SHA-1 that list publishes. [AdbDownloader] needs at least one of the two.
 */
data class PlatformToolsEntry(val url: String, val sha256: String, val sizeBytes: Long, val sha1: String? = null)

data class PlatformToolsManifest(val revision: String, val entries: Map<HostOs, PlatformToolsEntry>)

/**
 * The manifest compiled into this build. It may be filled by `scripts/update-platform-tools.sh` / `.ps1`, which download the official
 * archives once, compute their SHA-256 and rewrite this file. **It ships empty on purpose**: when it has no entry for the host, the app
 * asks [PlatformToolsFeed] (Google's own package list) for the current release instead, so the in-app download works without anyone
 * having to refresh a pin. A non-empty pin always wins over the feed.
 */
object PlatformToolsManifestData {
    val MANIFEST = PlatformToolsManifest(revision = "", entries = emptyMap())
}

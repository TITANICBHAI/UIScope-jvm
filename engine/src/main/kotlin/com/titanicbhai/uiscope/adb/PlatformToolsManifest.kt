package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.HostOs

/** One pinned download: an exact versioned URL (never "latest"), its SHA-256 and size. */
data class PlatformToolsEntry(val url: String, val sha256: String, val sizeBytes: Long)

data class PlatformToolsManifest(val revision: String, val entries: Map<HostOs, PlatformToolsEntry>)

/**
 * The manifest compiled into this build. **It is written by `scripts/update-platform-tools.sh` / `.ps1`**, which downloads the official
 * archives once, computes their SHA-256 and rewrites this file. Until that has been run (it needs access to dl.google.com) the
 * manifest is empty and the in-app download is reported as unavailable instead of guessing a checksum.
 */
object PlatformToolsManifestData {
    val MANIFEST = PlatformToolsManifest(revision = "", entries = emptyMap())
}

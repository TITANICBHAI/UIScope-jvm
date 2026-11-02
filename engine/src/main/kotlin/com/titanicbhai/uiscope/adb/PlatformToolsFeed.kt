package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.HostOs
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.error.Remedy
import com.titanicbhai.uiscope.process.NetworkGateway
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.logging.Level
import java.util.logging.Logger
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import kotlin.time.Duration.Companion.seconds
import org.w3c.dom.Element
import org.xml.sax.SAXException

/**
 * Finds the current Android SDK Platform-Tools in Google's own SDK package list (the file the SDK Manager reads), so the build does not
 * have to carry a checksum that somebody must remember to refresh. The build used to ship an empty pinned manifest, which made the
 * in-app "Download platform-tools" button permanently unavailable.
 *
 * What is trusted, and why it is enough: the list and the archive both come from `dl.google.com` over TLS through the one
 * [NetworkGateway]. The list names a **versioned** archive (never "latest"), its size and its checksum; [AdbDownloader] refuses to
 * install anything that does not match both. Any URL in the list that is not an https address on [allowedHost] is rejected.
 *
 * A pinned entry compiled into the build ([PlatformToolsManifestData]) always wins; this feed is the fallback when it has none.
 */
class PlatformToolsFeed(
    private val gateway: NetworkGateway,
    private val listUrl: String = LIST_URL,
    /** Archive URLs must be https on this host. Null disables the check (tests against a local server only). */
    private val allowedHost: String? = GOOGLE_HOST,
    private val arch: String = System.getProperty("os.arch").orEmpty(),
    private val logger: Logger = Logger.getLogger("uiscope.adb"),
) {
    /** Downloads the list and picks the stable platform-tools archive for [os]. */
    suspend fun resolve(os: HostOs): Outcome<PlatformToolsManifest> {
        val response = when (val r = gateway.getText(listUrl, LIST_TIMEOUT)) {
            is Outcome.Failure -> {
                logger.log(Level.WARNING, "Google's package list could not be fetched: " + r.error.code)
                return r
            }
            is Outcome.Success -> r.value
        }
        return parse(response.body, os)
    }

    /** Pure and testable: [xml] is the text of `repository2-*.xml`. */
    fun parse(xml: String, os: HostOs): Outcome<PlatformToolsManifest> {
        val root = when (val r = parseXml(xml)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val hostName = hostOsName(os) ?: return unavailable("There is no platform-tools download for this operating system")
        val stableChannel = children(root, "channel").firstOrNull { it.textContent.trim().equals("stable", ignoreCase = true) }?.getAttribute("id")
        val candidates = children(root, "remotePackage")
            .filter { it.getAttribute("path") == "platform-tools" }
            .mapNotNull { candidateOf(it, hostName) }
        if (candidates.isEmpty()) return unavailable("Google's package list has no platform-tools for this system")
        // Stable releases win over previews; among equals the highest revision wins.
        val best = candidates.sortedWith(
            compareByDescending<Candidate> { stableChannel != null && it.channel == stableChannel }.thenByDescending { it.revision },
        ).first()
        val url = resolveUrl(best.url) ?: return unavailable("Google's package list names an archive address UIScope does not trust")
        if (best.checksum == null) return unavailable("Google's package list has no usable checksum for the archive")
        val entry = PlatformToolsEntry(
            url = url,
            sha256 = if (best.checksum.type == ChecksumType.SHA256) best.checksum.hex else "",
            sizeBytes = best.size,
            sha1 = if (best.checksum.type == ChecksumType.SHA1) best.checksum.hex else null,
        )
        return Outcome.Success(PlatformToolsManifest(best.revision.toString(), mapOf(os to entry)))
    }

    // ── parsing ────────────────────────────────────────────────────────────────────────────────

    private enum class ChecksumType { SHA1, SHA256 }

    private class Checksum(val type: ChecksumType, val hex: String)

    private data class Revision(val major: Int, val minor: Int, val micro: Int, val preview: Int?) : Comparable<Revision> {
        override fun compareTo(other: Revision): Int {
            compareValuesBy(this, other, { it.major }, { it.minor }, { it.micro }).let { if (it != 0) return it }
            // A preview of the same number sorts below the final release.
            return when {
                preview == null && other.preview == null -> 0
                preview == null -> 1
                other.preview == null -> -1
                else -> preview.compareTo(other.preview)
            }
        }

        override fun toString(): String = "$major.$minor.$micro" + (preview?.let { " (preview $it)" } ?: "")
    }

    private class Candidate(val revision: Revision, val channel: String?, val url: String, val size: Long, val checksum: Checksum?)

    private fun candidateOf(pkg: Element, hostName: String): Candidate? {
        val revisionElement = children(pkg, "revision").firstOrNull() ?: return null
        fun part(name: String) = children(revisionElement, name).firstOrNull()?.textContent?.trim()?.toIntOrNull()
        val revision = Revision(part("major") ?: return null, part("minor") ?: 0, part("micro") ?: 0, part("preview"))
        val channel = children(pkg, "channelRef").firstOrNull()?.getAttribute("ref")
        val archives = children(pkg, "archives").flatMap { children(it, "archive") }
        val archive = archives.firstOrNull { archiveMatches(it, hostName) } ?: return null
        val complete = children(archive, "complete").firstOrNull() ?: return null
        val url = children(complete, "url").firstOrNull()?.textContent?.trim().orEmpty()
        if (url.isEmpty()) return null
        val size = children(complete, "size").firstOrNull()?.textContent?.trim()?.toLongOrNull() ?: 0L
        val checksum = children(complete, "checksum").firstNotNullOfOrNull { checksumOf(it) }
        return Candidate(revision, channel, url, size, checksum)
    }

    private fun checksumOf(element: Element): Checksum? {
        val hex = element.textContent.trim().lowercase()
        val type = when (element.getAttribute("type").lowercase().replace("-", "")) {
            "sha256" -> ChecksumType.SHA256
            "sha1" -> ChecksumType.SHA1
            else -> return null
        }
        val expectedLength = if (type == ChecksumType.SHA256) SHA256_HEX_LENGTH else SHA1_HEX_LENGTH
        return if (hex.length == expectedLength && hex.all { it in '0'..'9' || it in 'a'..'f' }) Checksum(type, hex) else null
    }

    private fun archiveMatches(archive: Element, hostName: String): Boolean {
        val hostOs = children(archive, "host-os").firstOrNull()?.textContent?.trim()
        if (!hostName.equals(hostOs, ignoreCase = true)) return false
        val hostArch = children(archive, "host-arch").firstOrNull()?.textContent?.trim()?.lowercase() ?: return true
        val wantsArm = arch.lowercase().let { it.contains("aarch64") || it.contains("arm64") }
        val archiveIsArm = hostArch.contains("aarch64") || hostArch.contains("arm64")
        return wantsArm == archiveIsArm
    }

    private fun hostOsName(os: HostOs): String? = when (os) {
        HostOs.WINDOWS -> "windows"
        HostOs.MACOS -> "macosx"
        HostOs.LINUX -> "linux"
        HostOs.OTHER -> null
    }

    /** Direct children whose name matches [name] once any namespace prefix is ignored (the list mixes prefixed and plain tags). */
    private fun children(parent: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName.substringAfter(':') == name) out += node
        }
        return out
    }

    private fun parseXml(xml: String): Outcome<Element> =
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val text = xml.removePrefix("\uFEFF")
            Outcome.Success(factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))).documentElement)
        } catch (e: SAXException) {
            logger.log(Level.WARNING, "Google's package list is not valid XML", e)
            Outcome.Failure(CodedError(ErrorCodes.PARSE_FAILED, "Google's package list could not be read", Remedy.RETRY))
        } catch (e: ParserConfigurationException) {
            logger.log(Level.WARNING, "the XML parser could not be configured", e)
            Outcome.Failure(CodedError(ErrorCodes.PARSE_FAILED, "The XML parser could not be configured", Remedy.RETRY))
        } catch (e: IOException) {
            logger.log(Level.WARNING, "Google's package list could not be read", e)
            Outcome.Failure(CodedError(ErrorCodes.PARSE_FAILED, "Google's package list could not be read", Remedy.RETRY))
        }

    /** Relative addresses in the list resolve against the list's own folder; anything off [allowedHost] is refused. */
    private fun resolveUrl(url: String): String? {
        val absolute = if (url.startsWith("https://") || url.startsWith("http://")) url else listUrl.substringBeforeLast('/') + "/" + url.trimStart('/')
        val uri = try {
            URI(absolute)
        } catch (e: URISyntaxException) {
            logger.log(Level.FINE, "the archive address in the package list is not a valid URI", e)
            return null
        }
        val scheme = uri.scheme?.lowercase()
        if (allowedHost == null) return absolute.takeIf { scheme == "https" || scheme == "http" }
        return absolute.takeIf { scheme == "https" && uri.host.equals(allowedHost, ignoreCase = true) }
    }

    private fun unavailable(detail: String): Outcome.Failure =
        Outcome.Failure(CodedError(ErrorCodes.ADB_DOWNLOAD_UNAVAILABLE, detail, Remedy.RETRY))

    companion object {
        const val GOOGLE_HOST = "dl.google.com"
        const val LIST_URL = "https://dl.google.com/android/repository/repository2-3.xml"
        private val LIST_TIMEOUT = 60.seconds
        private const val SHA1_HEX_LENGTH = 40
        private const val SHA256_HEX_LENGTH = 64
    }
}

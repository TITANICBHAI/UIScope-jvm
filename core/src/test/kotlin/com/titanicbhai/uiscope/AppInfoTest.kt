package com.titanicbhai.uiscope

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppInfoTest {
    private val build = BuildMeta(version = "1.2.3", buildDate = "2026-01-02", commit = "abc1234")

    private fun validProps() = Properties().apply {
        setProperty("app.name", "UIScope")
        setProperty("app.publisher", "TBTechs")
        setProperty("app.developer", "TITANICBHAI")
        setProperty("app.repo.url", "https://example.test/repo")
        setProperty("app.releases.api.url", "https://api.example.test/releases/latest")
        setProperty("app.issues.url", "https://example.test/repo/issues")
        setProperty("app.privacy.url", "https://example.test/repo/blob/main/PRIVACY.md")
        setProperty("app.docs.url", "https://example.test/repo#readme")
        setProperty("app.support.url", "https://example.test/repo/issues")
    }

    @Test
    fun `valid properties produce AppInfo with build facts`() {
        val info = AppInfo.fromProperties(validProps(), isStoreBuild = false, build = build)
        assertEquals("UIScope", info.name)
        assertEquals("TBTechs", info.publisher)
        assertEquals("1.2.3", info.version)
        assertEquals("abc1234", info.commit)
        assertEquals("2026-01-02", info.buildDate)
        assertFalse(info.isStoreBuild)
    }

    @Test
    fun `missing keys are all reported`() {
        val props = validProps().apply {
            remove("app.name")
            remove("app.issues.url")
        }
        val error = assertFailsWith<IllegalArgumentException> { AppInfo.fromProperties(props, false, build) }
        assertTrue(error.message!!.contains("app.name"), error.message)
        assertTrue(error.message!!.contains("app.issues.url"), error.message)
    }

    @Test
    fun `non https urls are rejected`() {
        val props = validProps().apply { setProperty("app.docs.url", "http://example.test") }
        val error = assertFailsWith<IllegalArgumentException> { AppInfo.fromProperties(props, false, build) }
        assertTrue(error.message!!.contains("app.docs.url"), error.message)
    }

    @Test
    fun `store build is detected from WindowsApps path or UISCOPE_STORE`() {
        val store = "C:\\Program Files\\WindowsApps\\TBTechs.UIScope_1.0.0.0_x64__abc\\UIScope\\UIScope.exe"
        assertTrue(AppInfo.detectStoreBuild(store, emptyMap()))
        assertTrue(AppInfo.detectStoreBuild(store.replace('\\', '/'), emptyMap()))
        assertTrue(AppInfo.detectStoreBuild("D:\\anywhere\\UIScope.exe", mapOf("UISCOPE_STORE" to "1")))
        assertFalse(AppInfo.detectStoreBuild("C:\\Users\\me\\AppData\\Local\\UIScope\\UIScope.exe", emptyMap()))
        assertFalse(AppInfo.detectStoreBuild(null, emptyMap()))
        assertFalse(AppInfo.detectStoreBuild(null, mapOf("UISCOPE_STORE" to "0")))
    }

    @Test
    fun `generated BuildInfo matches version txt and is well formed`() {
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(BuildInfo.VERSION), BuildInfo.VERSION)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(BuildInfo.BUILD_DATE), BuildInfo.BUILD_DATE)
        assertTrue(Regex("""[0-9a-f]{4,40}|unknown""").matches(BuildInfo.COMMIT), BuildInfo.COMMIT)
        // Gradle runs module tests with the module directory as working directory.
        val versionFile = File("../version.txt")
        if (versionFile.exists()) assertEquals(versionFile.readText().trim(), BuildInfo.VERSION)
    }
}

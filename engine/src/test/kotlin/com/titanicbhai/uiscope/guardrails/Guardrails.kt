package com.titanicbhai.uiscope.guardrails

import java.nio.file.Files
import java.nio.file.Path

/** Where a rule looks: in code only (comments and string contents blanked), or in code and string literals (comments blanked). */
enum class Scope { CODE, CODE_AND_STRINGS }

/**
 * One forbidden pattern (plan P3-9, documented in `docs/guardrails.md`).
 *
 * @property allowed Path fragments (with `/` separators) of files that may contain the pattern: the one place that owns the capability.
 * @property extensions Which files the rule reads.
 */
class Rule(
    val id: String,
    val why: String,
    val regex: Regex,
    val scope: Scope,
    val allowed: List<String> = emptyList(),
    val extensions: Set<String> = setOf("kt"),
)

class Violation(val rule: Rule, val file: String, val line: Int, val text: String) {
    override fun toString() = "[${rule.id}] $file:$line: ${text.trim()}  (${rule.why})"
}

object Guardrails {
    private const val PROCESS_DIR = "engine/src/main/kotlin/com/titanicbhai/uiscope/process/"

    val rules: List<Rule> = listOf(
        Rule(
            "process-builder", "processes are started only by engine/process (B10: timeouts and kill-on-cancel live there)",
            Regex("""\bProcessBuilder\b|\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\b"""), Scope.CODE, allowed = listOf(PROCESS_DIR),
        ),
        Rule(
            "shell-and-automation-tools", "Windows inspection goes through the UI Automation helper, never PowerShell or desktop-automation tools (B2, B8)",
            Regex("""(?i)powershell\.exe|["']pwsh(\.exe)?["']|["']powershell["']|osascript|xdotool|pyatspi|ExecutionPolicy"""), Scope.CODE_AND_STRINGS,
            // The code generator writes PowerShell text for the user to copy ("run it with powershell.exe -STA ..."); core cannot start processes.
            allowed = listOf("core/src/main/kotlin/com/titanicbhai/uiscope/codegen/"),
        ),
        Rule(
            "global-input-hooks", "no global keyboard/mouse hooks (the pick hotkey uses RegisterHotKey in the helper)",
            Regex("""(?i)jnativehook"""), Scope.CODE_AND_STRINGS, extensions = setOf("kt", "kts", "toml"),
        ),
        Rule(
            "awt-pointer-input", "no AWT pointer polling or synthetic input (B8: it only sees the primary monitor and breaks under DPI scaling)",
            Regex("""\bMouseInfo\b|\bjava\.awt\.Robot\b|\bRobot\s*\("""), Scope.CODE,
        ),
        Rule(
            "network-outside-gateway", "all HTTP goes through NetworkGateway so proxy, TLS and timeouts are decided in one place",
            Regex("""\bURL\s*\(|\bHttpClient\b|\bopenConnection\b|\bHttpURLConnection\b"""), Scope.CODE,
            allowed = listOf(PROCESS_DIR + "NetworkGateway.kt", PROCESS_DIR + "JdkNetworkGateway.kt"),
        ),
        Rule(
            "host-shell", "processes start with argument lists only - never through a host shell (P7-3; the adb path is user-supplied)",
            Regex("""(?i)\bsh\s+-c\b|\bbash\s+-c\b|\bcmd(\.exe)?\s+/c\b|\bcommand\s+/c\b"""), Scope.CODE_AND_STRINGS,
        ),
    )

    /** Violations of [rules] in one file's [text]. [path] uses `/` separators and is relative to the repository root. */
    fun scan(path: String, text: String, rules: List<Rule> = Guardrails.rules): List<Violation> {
        val extension = path.substringAfterLast('.', "")
        val applicable = rules.filter { extension in it.extensions && it.allowed.none { a -> path.contains(a) } }
        if (applicable.isEmpty()) return emptyList()
        val (code, withStrings) = if (extension == "kt" || extension == "kts") blank(text) else text to text
        val original = text.split('\n')
        val result = ArrayList<Violation>()
        for (rule in applicable) {
            val haystack = if (rule.scope == Scope.CODE) code else withStrings
            haystack.split('\n').forEachIndexed { i, line ->
                if (rule.regex.containsMatchIn(line)) result += Violation(rule, path, i + 1, original.getOrElse(i) { line })
            }
        }
        return result
    }

    /** Scans every `src/main` Kotlin file of the four modules, the build scripts and the version catalog; `legacy/` and `build/` are skipped. */
    fun scanRepository(root: Path, rules: List<Rule> = Guardrails.rules): List<Violation> {
        val files = ArrayList<Path>()
        for (module in listOf("core", "engine", "ui", "app")) {
            val main = root.resolve(module).resolve("src").resolve("main")
            if (Files.isDirectory(main)) Files.walk(main).use { s -> s.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.forEach { files.add(it) } }
            root.resolve(module).resolve("build.gradle.kts").takeIf { Files.isRegularFile(it) }?.let { files.add(it) }
        }
        listOf(root.resolve("build.gradle.kts"), root.resolve("settings.gradle.kts"), root.resolve("gradle").resolve("libs.versions.toml"))
            .filter { Files.isRegularFile(it) }.forEach { files.add(it) }
        return files.flatMap { f ->
            val relative = root.relativize(f).toString().replace('\\', '/')
            scan(relative, Files.readString(f), rules)
        }
    }

    /** Finds the repository root from a working directory inside it (the folder with `settings.gradle.kts`). */
    fun findRoot(start: Path): Path? {
        var dir: Path? = start.toAbsolutePath()
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))) return dir
            dir = dir.parent
        }
        return null
    }

    /**
     * Returns (code with comments and string contents blanked, code with only comments blanked). Both keep every newline, so line
     * numbers stay valid. Handles line and nested block comments, normal and raw strings and character literals.
     */
    fun blank(text: String): Pair<String, String> {
        val code = StringBuilder(text.length)
        val withStrings = StringBuilder(text.length)
        var i = 0
        fun both(c: Char) { code.append(c); withStrings.append(c) }
        fun blankBoth(c: Char) { val b = if (c == '\n') '\n' else ' '; code.append(b); withStrings.append(b) }
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') { blankBoth(text[i]); i++ }
                }
                c == '/' && next == '*' -> {
                    var depth = 0
                    while (i < text.length) {
                        if (text.startsWith("/*", i)) { depth++; blankBoth('/'); blankBoth('*'); i += 2 }
                        else if (text.startsWith("*/", i)) { depth--; blankBoth('*'); blankBoth('/'); i += 2; if (depth == 0) break }
                        else { blankBoth(text[i]); i++ }
                    }
                }
                text.startsWith("\"\"\"", i) -> {
                    both('"'); both('"'); both('"'); i += 3
                    while (i < text.length && !text.startsWith("\"\"\"", i)) { code.append(if (text[i] == '\n') '\n' else ' '); withStrings.append(text[i]); i++ }
                    if (i < text.length) { both('"'); both('"'); both('"'); i += 3 }
                }
                c == '"' -> {
                    both('"'); i++
                    while (i < text.length && text[i] != '"' && text[i] != '\n') {
                        if (text[i] == '\\' && i + 1 < text.length) { code.append("  "); withStrings.append(text, i, i + 2); i += 2 }
                        else { code.append(' '); withStrings.append(text[i]); i++ }
                    }
                    if (i < text.length && text[i] == '"') { both('"'); i++ }
                }
                c == '\'' && text.getOrNull(i + 2) == '\'' -> { both('\''); code.append(' '); withStrings.append(text[i + 1]); both('\''); i += 3 }
                else -> { both(c); i++ }
            }
        }
        return code.toString() to withStrings.toString()
    }
}

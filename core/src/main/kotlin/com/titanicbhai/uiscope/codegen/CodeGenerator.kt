package com.titanicbhai.uiscope.codegen

import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.selector.Candidate
import com.titanicbhai.uiscope.selector.Condition
import com.titanicbhai.uiscope.selector.Selector
import com.titanicbhai.uiscope.selector.SelectorKey
import com.titanicbhai.uiscope.selector.Tier

/**
 * Renders a chosen [Candidate] as code for a [CodeTarget]. Templates take the selector, never a raw node, so what the Selector panel
 * shows and what the code does are the same thing. Every literal goes through [Escapers] (fixes B6). When the tier is below Good the
 * verdict is added as a comment. Device serials never appear in generated code (M14).
 */
object CodeGenerator {
    fun generate(target: CodeTarget, candidate: Candidate, captureTarget: Target, packageName: String? = null): CodeResult {
        val isWindows = captureTarget is Target.Windows
        if ((target.platform == CodePlatform.WINDOWS) != isWindows) {
            return CodeResult.NotAvailable("${target.displayName} is for ${if (isWindows) "Android" else "Windows"} captures only")
        }
        val pkg = packageName ?: (captureTarget as? Target.Android)?.foregroundPackage
        val verdict = verdictLine(candidate)
        return when (target) {
            CodeTarget.UIAUTOMATOR2_PYTHON -> Android.u2(candidate, pkg, verdict)
            CodeTarget.UIAUTOMATOR_KOTLIN -> Android.uiAutomator(candidate, pkg, verdict)
            CodeTarget.APPIUM_JAVA -> Android.appiumJava(candidate, pkg, verdict)
            CodeTarget.APPIUM_PYTHON -> Android.appiumPython(candidate, pkg, verdict)
            CodeTarget.MAESTRO -> Android.maestro(candidate, pkg, verdict)
            CodeTarget.XPATH -> Android.xpath(candidate)
            CodeTarget.PYWINAUTO -> Windows.pywinauto(candidate, (captureTarget as Target.Windows), verdict)
            CodeTarget.FLAUI -> Windows.flaui(candidate, (captureTarget as Target.Windows), verdict)
            CodeTarget.AUTOHOTKEY_V2 -> Windows.autohotkey(candidate, (captureTarget as Target.Windows), verdict)
            CodeTarget.POWERSHELL_UIA -> Windows.powershell(candidate, (captureTarget as Target.Windows), verdict)
        }
    }

    /** The verdict as one comment line (without the comment marker), or null when the tier is Good or better. Never contains user text. */
    internal fun verdictLine(c: Candidate): String? {
        if (c.tier.rank <= Tier.GOOD.rank) return null
        val warnings = if (c.warnings.isEmpty()) "" else " Warnings: " + c.warnings.joinToString(", ") { it.message } + "."
        return "Selector quality: ${c.tier.label}. ${c.reasons.joinToString("; ")}.$warnings"
    }

    internal fun lines(vararg parts: String?): String = parts.filterNotNull().joinToString("\n") + "\n"

    /** Child positions below the window root: drops the leading root index of an absolute path. */
    internal fun belowRoot(path: String): List<Int> = path.split('/').drop(1).map { it.toInt() }
}

private object Android {
    private val NO_PATH = CodeResult.NotAvailable("This target has no path-based selector. Use an attribute selector, or pick uiautomator2, Appium or XPath.")

    // ── uiautomator2 (Python) ─────────────────────────────────────────────────────────────────
    fun u2(c: Candidate, pkg: String?, verdict: String?): CodeResult {
        val sel = c.selector
        val (selector, action) = when (sel) {
            is Selector.Attr -> (u2Args(sel.conditions) ?: return unsupported(c)).let { "d($it)" to "d($it).click()" }
            is Selector.Index -> (u2Args(sel.base.conditions) ?: return unsupported(c)).let { "d($it, instance=${sel.index})" to "d($it, instance=${sel.index}).click()" }
            is Selector.Anchor, is Selector.Path -> XPath.render(sel)?.let { "d.xpath(${Escapers.python(it)})" to "d.xpath(${Escapers.python(it)}).click()" } ?: return unsupported(c)
            is Selector.Coordinates -> "d.click(${sel.x}, ${sel.y})" to "d.click(${sel.x}, ${sel.y})"
        }
        val code = CodeGenerator.lines(
            "# uiautomator2: ${CodeTarget.UIAUTOMATOR2_PYTHON.docUrl}",
            verdict?.let { "# $it" },
            "import uiautomator2 as u2",
            "",
            "d = u2.connect()  # several devices attached? u2.connect(\"<serial>\") or set ANDROID_SERIAL",
            pkg?.let { "d.app_start(${Escapers.python(it)})" },
            action,
        )
        return CodeResult.Code(code, selector, "python")
    }

    private fun u2Args(conditions: List<Condition>): String? {
        val parts = conditions.map { c ->
            val name = when (c.key) {
                SelectorKey.IDENTIFIER -> "resourceId"
                SelectorKey.TEXT -> "text"
                SelectorKey.DESCRIPTION -> "description"
                SelectorKey.CLASS -> "className"
                else -> return null
            }
            "$name=${Escapers.python(c.value)}"
        }
        return parts.joinToString(", ")
    }

    // ── UI Automator (Kotlin) ─────────────────────────────────────────────────────────────────
    fun uiAutomator(c: Candidate, pkg: String?, verdict: String?): CodeResult {
        val sel = c.selector
        val (selector, action) = when (sel) {
            is Selector.Attr -> (by(sel.conditions) ?: return unsupported(c)).let { "device.findObject($it)" to "device.findObject($it).click()" }
            is Selector.Index -> (by(sel.base.conditions) ?: return unsupported(c)).let { "device.findObjects($it)[${sel.index}]" to "device.findObjects($it)[${sel.index}].click()" }
            is Selector.Anchor, is Selector.Path -> return NO_PATH
            is Selector.Coordinates -> "device.click(${sel.x}, ${sel.y})" to "device.click(${sel.x}, ${sel.y})"
        }
        val code = CodeGenerator.lines(
            "// UI Automator: ${CodeTarget.UIAUTOMATOR_KOTLIN.docUrl}",
            verdict?.let { "// $it" },
            "import androidx.test.platform.app.InstrumentationRegistry",
            "import androidx.test.uiautomator.By",
            "import androidx.test.uiautomator.UiDevice",
            "",
            "val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())",
            pkg?.let { "device.executeShellCommand(${Escapers.kotlin("monkey -p $it -c android.intent.category.LAUNCHER 1")})" },
            action,
        )
        return CodeResult.Code(code, selector, "kotlin")
    }

    private fun by(conditions: List<Condition>): String? {
        val parts = conditions.withIndex().map { (i, c) ->
            val fn = when (c.key) {
                SelectorKey.IDENTIFIER -> "res"
                SelectorKey.TEXT -> "text"
                SelectorKey.DESCRIPTION -> "desc"
                SelectorKey.CLASS -> "clazz"
                else -> return null
            }
            (if (i == 0) "By." else ".") + "$fn(${Escapers.kotlin(c.value)})"
        }
        return parts.joinToString("")
    }

    // ── Appium (Java / Python) ────────────────────────────────────────────────────────────────
    /** The locator strategy and value for a selector; null when no Appium locator exists. */
    private data class Locator(val strategy: String, val value: String, val listable: Boolean)

    private fun locator(sel: Selector): Locator? {
        if (sel is Selector.Attr && sel.conditions.size == 1) {
            val c = sel.conditions.single()
            when (c.key) {
                SelectorKey.IDENTIFIER -> return Locator("id", c.value, true)
                SelectorKey.DESCRIPTION -> return Locator("accessibilityId", c.value, true)
                SelectorKey.CLASS -> return Locator("className", c.value, true)
                else -> Unit
            }
        }
        if (sel is Selector.Index && sel.base.conditions.size == 1) {
            locator(sel.base)?.let { return it.copy(listable = true) }
        }
        return XPath.render(sel)?.let { Locator("xpath", it, false) }
    }

    fun appiumJava(c: Candidate, pkg: String?, verdict: String?): CodeResult {
        val sel = c.selector
        if (sel is Selector.Coordinates) return CodeResult.NotAvailable("Appium has no one-line coordinate tap. Use another target for coordinates.")
        val loc = locator(sel) ?: return unsupported(c)
        val by = "AppiumBy.${loc.strategy}(${Escapers.java(loc.value)})"
        val find = if (sel is Selector.Index && loc.listable) "driver.findElements($by).get(${sel.index})" else "driver.findElement($by)"
        val code = CodeGenerator.lines(
            "// Appium Java client: ${CodeTarget.APPIUM_JAVA.docUrl}",
            verdict?.let { "// $it" },
            "import io.appium.java_client.AppiumBy;",
            "import io.appium.java_client.android.AndroidDriver;",
            "import io.appium.java_client.android.options.UiAutomator2Options;",
            "import java.net.URL;",
            "import org.openqa.selenium.WebElement;",
            "",
            "// Appium 2 server on its default address. Several devices attached? Set the appium:udid capability.",
            "UiAutomator2Options options = new UiAutomator2Options();",
            "AndroidDriver driver = new AndroidDriver(new URL(\"http://127.0.0.1:4723\"), options);",
            pkg?.let { "driver.activateApp(${Escapers.java(it)});" },
            "WebElement element = $find;",
            "element.click();",
        )
        return CodeResult.Code(code, find, "java")
    }

    fun appiumPython(c: Candidate, pkg: String?, verdict: String?): CodeResult {
        val sel = c.selector
        if (sel is Selector.Coordinates) return CodeResult.NotAvailable("Appium has no one-line coordinate tap. Use another target for coordinates.")
        val loc = locator(sel) ?: return unsupported(c)
        val by = when (loc.strategy) {
            "id" -> "AppiumBy.ID"
            "accessibilityId" -> "AppiumBy.ACCESSIBILITY_ID"
            "className" -> "AppiumBy.CLASS_NAME"
            else -> "AppiumBy.XPATH"
        }
        val args = "by=$by, value=${Escapers.python(loc.value)}"
        val find = if (sel is Selector.Index && loc.listable) "driver.find_elements($args)[${sel.index}]" else "driver.find_element($args)"
        val code = CodeGenerator.lines(
            "# Appium Python client: ${CodeTarget.APPIUM_PYTHON.docUrl}",
            verdict?.let { "# $it" },
            "from appium import webdriver",
            "from appium.options.android import UiAutomator2Options",
            "from appium.webdriver.common.appiumby import AppiumBy",
            "",
            "# Appium 2 server on its default address. Several devices attached? Set the appium:udid capability.",
            "options = UiAutomator2Options()",
            "driver = webdriver.Remote('http://127.0.0.1:4723', options=options)",
            pkg?.let { "driver.activate_app(${Escapers.python(it)})" },
            "element = $find",
            "element.click()",
        )
        return CodeResult.Code(code, find, "python")
    }

    // ── Maestro ───────────────────────────────────────────────────────────────────────────────
    fun maestro(c: Candidate, pkg: String?, verdict: String?): CodeResult {
        val sel = c.selector
        val body = ArrayList<String>()
        var describesByText = false
        fun attrLines(attr: Selector.Attr): List<String>? {
            val keys = attr.conditions.map { it.key }
            if (SelectorKey.TEXT in keys && SelectorKey.DESCRIPTION in keys) return null
            return attr.conditions.map { cond ->
                val name = when (cond.key) {
                    SelectorKey.IDENTIFIER -> "id"
                    SelectorKey.TEXT -> "text"
                    SelectorKey.DESCRIPTION -> "text".also { describesByText = true }
                    else -> return null
                }
                // text and id are regular expressions in Maestro: escape them so only this exact value matches
                "$name: ${Escapers.yaml(Escapers.regexLiteral(cond.value))}"
            }
        }
        when (sel) {
            is Selector.Attr -> body += attrLines(sel) ?: return unsupported(c)
            is Selector.Index -> {
                body += attrLines(sel.base) ?: return unsupported(c)
                body += "index: ${sel.index}"
            }
            is Selector.Coordinates -> body += "point: \"${sel.x}, ${sel.y}\""
            is Selector.Anchor, is Selector.Path -> return NO_PATH
        }
        val code = CodeGenerator.lines(
            "# Maestro: ${CodeTarget.MAESTRO.docUrl}",
            verdict?.let { "# $it" },
            if (pkg != null) "appId: ${Escapers.yaml(pkg)}" else "appId: your.app.package  # replace with your app's package name",
            "---",
            "- launchApp",
            if (describesByText) "# Maestro matches a content description through the text selector (accessibility text)." else null,
            "- tapOn:",
            body.joinToString("\n") { "    $it" },
        )
        return CodeResult.Code(code, body.joinToString("\n"), "yaml")
    }

    // ── XPath ─────────────────────────────────────────────────────────────────────────────────
    fun xpath(c: Candidate): CodeResult {
        val expression = XPath.render(c.selector) ?: return CodeResult.NotAvailable("XPath cannot express screen coordinates.")
        return CodeResult.Code(expression + "\n", expression, "xpath")
    }

    private fun unsupported(c: Candidate) = CodeResult.NotAvailable("This target cannot express the selector ${c.expression}.")
}

private object Windows {
    private val NO_COORDS = CodeResult.NotAvailable("This target has no coordinate click in its basic API. Use pywinauto, FlaUI or AutoHotkey.")
    private val NO_PATH = CodeResult.NotAvailable("This target has no path-based selector. Use an attribute selector, or pick pywinauto, FlaUI or AutoHotkey.")
    private val controlType = Regex("[A-Za-z]+")

    private fun unsupported(c: Candidate) = CodeResult.NotAvailable("This target cannot express the selector ${c.expression}.")

    // ── pywinauto ─────────────────────────────────────────────────────────────────────────────
    fun pywinauto(c: Candidate, t: Target.Windows, verdict: String?): CodeResult {
        val w = t.window
        fun kwargs(conditions: List<Condition>): String? {
            val parts = conditions.map { cond ->
                val name = when (cond.key) {
                    SelectorKey.IDENTIFIER -> "auto_id"
                    SelectorKey.NAME -> "title"
                    SelectorKey.CONTROL_TYPE -> "control_type"
                    SelectorKey.CLASS -> "class_name"
                    else -> return null
                }
                "$name=${Escapers.python(cond.value)}"
            }
            return parts.joinToString(", ")
        }
        val sel = c.selector
        var needsMouse = false
        val element: String = when (sel) {
            is Selector.Attr -> "window.child_window(${kwargs(sel.conditions) ?: return unsupported(c)})"
            is Selector.Index -> "window.child_window(${kwargs(sel.base.conditions) ?: return unsupported(c)}, found_index=${sel.index})"
            is Selector.Anchor -> "window.child_window(${kwargs(sel.anchor.conditions) ?: return unsupported(c)})" + sel.relativePath.joinToString("") { ".children()[$it]" }
            is Selector.Path -> "window" + CodeGenerator.belowRoot(sel.path.value).joinToString("") { ".children()[$it]" }
            is Selector.Coordinates -> {
                needsMouse = true
                "mouse"
            }
        }
        val action = if (sel is Selector.Coordinates) "mouse.click(coords=(${sel.x}, ${sel.y}))  # absolute screen pixels; DPI-aware apps report physical pixels" else "$element.click_input()"
        val connect = if (w.pid != null) {
            "app = Application(backend=\"uia\").connect(process=${w.pid})  # the process id changes on every run; connect(path=${Escapers.python(w.processName)}) is stable"
        } else {
            "app = Application(backend=\"uia\").connect(path=${Escapers.python(w.processName)})"
        }
        val code = CodeGenerator.lines(
            "# pywinauto (UIA backend): ${CodeTarget.PYWINAUTO.docUrl}",
            verdict?.let { "# $it" },
            "from pywinauto import Application",
            if (needsMouse) "from pywinauto import mouse" else null,
            "",
            connect,
            if (w.title.isBlank()) "window = app.top_window()" else "window = app.window(title=${Escapers.python(w.title)})",
            action,
        )
        return CodeResult.Code(code, element, "python")
    }

    // ── FlaUI (C#) ────────────────────────────────────────────────────────────────────────────
    fun flaui(c: Candidate, t: Target.Windows, verdict: String?): CodeResult {
        val w = t.window
        fun cond(conditions: List<Condition>): String? = conditions.map { cond ->
            when (cond.key) {
                SelectorKey.IDENTIFIER -> "cf.ByAutomationId(${Escapers.csharp(cond.value)})"
                SelectorKey.NAME -> "cf.ByName(${Escapers.csharp(cond.value)})"
                SelectorKey.CLASS -> "cf.ByClassName(${Escapers.csharp(cond.value)})"
                SelectorKey.CONTROL_TYPE -> if (controlType.matches(cond.value)) "cf.ByControlType(ControlType.${cond.value})" else return null
                else -> return null
            }
        }.reduce { a, b -> "$a.And($b)" }
        val sel = c.selector
        val find: String = when (sel) {
            is Selector.Attr -> "window.FindFirstDescendant(cf => ${cond(sel.conditions) ?: return unsupported(c)})"
            is Selector.Index -> "window.FindAllDescendants(cf => ${cond(sel.base.conditions) ?: return unsupported(c)})[${sel.index}]"
            is Selector.Anchor -> "window.FindFirstDescendant(cf => ${cond(sel.anchor.conditions) ?: return unsupported(c)})" + sel.relativePath.joinToString("") { ".FindAllChildren()[$it]" }
            is Selector.Path -> "window" + CodeGenerator.belowRoot(sel.path.value).joinToString("") { ".FindAllChildren()[$it]" }
            is Selector.Coordinates -> "Mouse.Click(new System.Drawing.Point(${sel.x}, ${sel.y}))"
        }
        val attach = if (w.pid != null) "Application.Attach(${w.pid})" else "Application.Attach(${Escapers.csharp(w.processName)})"
        val coords = sel is Selector.Coordinates
        val code = CodeGenerator.lines(
            "// FlaUI: ${CodeTarget.FLAUI.docUrl}",
            verdict?.let { "// $it" },
            "using FlaUI.Core;",
            "using FlaUI.Core.Definitions;",
            if (coords) "using FlaUI.Core.Input;" else null,
            "using FlaUI.UIA3;",
            "",
            "using var app = $attach;  // the process id changes on every run; Attach(\"<exe name>\") is stable",
            "using var automation = new UIA3Automation();",
            "var window = app.GetMainWindow(automation);",
            if (coords) "$find;  // absolute screen pixels" else "var element = $find;",
            if (coords) null else "element.Click();",
        )
        return CodeResult.Code(code, find, "csharp")
    }

    // ── AutoHotkey v2 with UIA-v2 ─────────────────────────────────────────────────────────────
    fun autohotkey(c: Candidate, t: Target.Windows, verdict: String?): CodeResult {
        val w = t.window
        fun obj(conditions: List<Condition>): String? {
            val parts = conditions.map { cond ->
                val name = when (cond.key) {
                    SelectorKey.IDENTIFIER -> "AutomationId"
                    SelectorKey.NAME -> "Name"
                    SelectorKey.CONTROL_TYPE -> "Type"
                    SelectorKey.CLASS -> "ClassName"
                    else -> return null
                }
                "$name: ${Escapers.autohotkey(cond.value)}"
            }
            return "{" + parts.joinToString(", ") + "}"
        }
        val sel = c.selector
        val find: String = when (sel) {
            is Selector.Attr -> "window.FindElement(${obj(sel.conditions) ?: return unsupported(c)})"
            is Selector.Index -> "window.FindElement(${obj(sel.base.conditions) ?: return unsupported(c)}, 4, ${sel.index + 1})"
            is Selector.Anchor -> "window.FindElement(${obj(sel.anchor.conditions) ?: return unsupported(c)})" + sel.relativePath.joinToString("") { "[${it + 1}]" }
            is Selector.Path -> "window" + CodeGenerator.belowRoot(sel.path.value).joinToString("") { "[${it + 1}]" }
            is Selector.Coordinates -> "Click(${sel.x}, ${sel.y})"
        }
        val coords = sel is Selector.Coordinates
        val handle = if (w.pid != null) "ahk_pid ${w.pid}" else "ahk_exe ${w.processName}"
        val code = CodeGenerator.lines(
            "; AutoHotkey v2 with UIA-v2 (copy UIA.ahk into a Lib folder): ${CodeTarget.AUTOHOTKEY_V2.docUrl}",
            verdict?.let { "; $it" },
            "#Requires AutoHotkey v2.0",
            "#include <UIA>",
            "",
            if (coords) "CoordMode(\"Mouse\", \"Screen\")  ; absolute screen pixels" else "window := UIA.ElementFromHandle(${Escapers.autohotkey(handle)})  ; the process id changes on every run; \"ahk_exe <name>\" is stable",
            if (coords) find else "element := $find",
            if (coords) null else "element.Click()",
        )
        return CodeResult.Code(code, find, "autohotkey")
    }

    // ── PowerShell (built-in UI Automation) ───────────────────────────────────────────────────
    fun powershell(c: Candidate, t: Target.Windows, verdict: String?): CodeResult {
        val w = t.window
        val ns = "System.Windows.Automation"
        fun prop(cond: Condition): String? = when (cond.key) {
            SelectorKey.IDENTIFIER -> "New-Object $ns.PropertyCondition([$ns.AutomationElement]::AutomationIdProperty, ${Escapers.powershell(cond.value)})"
            SelectorKey.NAME -> "New-Object $ns.PropertyCondition([$ns.AutomationElement]::NameProperty, ${Escapers.powershell(cond.value)})"
            SelectorKey.CLASS -> "New-Object $ns.PropertyCondition([$ns.AutomationElement]::ClassNameProperty, ${Escapers.powershell(cond.value)})"
            SelectorKey.CONTROL_TYPE -> if (controlType.matches(cond.value)) "New-Object $ns.PropertyCondition([$ns.AutomationElement]::ControlTypeProperty, [$ns.ControlType]::${cond.value})" else null
            else -> null
        }
        val attr: Selector.Attr = when (val sel = c.selector) {
            is Selector.Attr -> sel
            is Selector.Index -> sel.base
            is Selector.Anchor, is Selector.Path -> return NO_PATH
            is Selector.Coordinates -> return NO_COORDS
        }
        val conditionLines = ArrayList<String>()
        attr.conditions.forEachIndexed { i, cond -> conditionLines += "\$c${i + 1} = ${prop(cond) ?: return unsupported(c)}" }
        val combined = if (attr.conditions.size == 1) "\$c1" else "(New-Object $ns.AndCondition(" + attr.conditions.indices.joinToString(", ") { "\$c${it + 1}" } + "))"
        val index = (c.selector as? Selector.Index)?.index
        val find = if (index == null) "\$window.FindFirst([$ns.TreeScope]::Descendants, $combined)" else "\$window.FindAll([$ns.TreeScope]::Descendants, $combined).Item($index)"
        val windowCondition = if (w.pid != null) {
            "New-Object $ns.PropertyCondition([$ns.AutomationElement]::ProcessIdProperty, ${w.pid})"
        } else {
            "New-Object $ns.PropertyCondition([$ns.AutomationElement]::NameProperty, ${Escapers.powershell(w.title)})"
        }
        val code = CodeGenerator.lines(
            "# PowerShell with the built-in UI Automation assemblies: ${CodeTarget.POWERSHELL_UIA.docUrl}",
            "# Run it in Windows PowerShell with the -STA switch (powershell.exe -STA -File script.ps1).",
            verdict?.let { "# $it" },
            "Add-Type -AssemblyName UIAutomationClient",
            "Add-Type -AssemblyName UIAutomationTypes",
            "",
            "\$root = [$ns.AutomationElement]::RootElement",
            "\$windowCondition = $windowCondition  # the process id changes on every run; match the window name instead if needed",
            "\$window = \$root.FindFirst([$ns.TreeScope]::Children, \$windowCondition)",
            conditionLines.joinToString("\n"),
            "\$element = $find",
            "# Invoke works for buttons and similar controls; other controls need another pattern.",
            "\$element.GetCurrentPattern([$ns.InvokePattern]::Pattern).Invoke()",
        )
        return CodeResult.Code(code, conditionLines.joinToString("\n"), "powershell")
    }
}

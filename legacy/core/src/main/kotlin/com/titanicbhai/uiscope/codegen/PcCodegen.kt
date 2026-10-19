package com.titanicbhai.uiscope.codegen

import com.titanicbhai.uiscope.model.ElementNode

enum class PcCodeTarget(val label: String, val language: String) {
    AUTOHOTKEY_V2("AutoHotKey v2", "autohotkey"),
    PYTHON_PYWINAUTO("Python (pywinauto)", "python"),
    CSHARP_FLAUI("C# (FlaUI)", "csharp"),
    POWERSHELL_UIA("PowerShell (UIAutomation)", "powershell")
}

object PcCodegen {

    fun generate(node: ElementNode, target: PcCodeTarget): GeneratedCode {
        val fragile = isFragile(node)
        val fragilityReason = when {
            node.name.isBlank() && node.properties["AutomationId"].isNullOrBlank() ->
                "No Name or AutomationId — using bounds/class fallback selector"
            else -> null
        }
        val code = when (target) {
            PcCodeTarget.AUTOHOTKEY_V2 -> autoHotkeyV2(node)
            PcCodeTarget.PYTHON_PYWINAUTO -> pythonPywinauto(node)
            PcCodeTarget.CSHARP_FLAUI -> csharpFlaui(node)
            PcCodeTarget.POWERSHELL_UIA -> powershellUia(node)
        }
        return GeneratedCode(code, target.language, fragile, fragilityReason)
    }

    private fun isFragile(node: ElementNode): Boolean =
        node.name.isBlank() && node.properties["AutomationId"].isNullOrBlank()

    private fun bestSelector(node: ElementNode): String {
        val automationId = node.properties["AutomationId"]
        val controlType = node.properties["ControlType"] ?: node.className
        return when {
            !automationId.isNullOrBlank() -> "AutomationId: $automationId"
            node.name.isNotBlank() -> "Name: ${node.name}"
            node.className.isNotBlank() -> "ClassName: ${node.className}"
            else -> "ControlType: $controlType"
        }
    }

    private fun autoHotkeyV2(node: ElementNode): String {
        val automationId = node.properties["AutomationId"]
        val controlType = node.properties["ControlType"] ?: "Control"
        val name = node.name
        val bounds = node.bounds

        return buildString {
            appendLine("; AutoHotKey v2 — UIScope generated")
            appendLine("; Requires: UIAutomation library for AHKv2")
            appendLine("; https://github.com/Descolada/UIAutomation")
            appendLine()
            appendLine("#Requires AutoHotkey v2.0")
            appendLine("""#Include "UIAutomation.ahk"""")
            appendLine()
            appendLine("; Find the target element")
            if (!automationId.isNullOrBlank()) {
                appendLine("el := UIA.ElementFromHandle(WinExist()).FindElement({AutomationId: \"$automationId\"})")
            } else if (name.isNotBlank()) {
                appendLine("el := UIA.ElementFromHandle(WinExist()).FindElement({Name: \"$name\"})")
            } else if (node.className.isNotBlank()) {
                appendLine("el := UIA.ElementFromHandle(WinExist()).FindElement({Type: \"$controlType\", ClassName: \"${node.className}\"})")
            } else if (bounds != null) {
                val cx = bounds.x + bounds.width / 2
                val cy = bounds.y + bounds.height / 2
                appendLine("; Fallback — click by screen coordinates (fragile)")
                appendLine("Click $cx, $cy")
                return@buildString
            } else {
                appendLine("; WARNING: No reliable selector found")
                appendLine("el := UIA.ElementFromHandle(WinExist())")
            }
            appendLine()
            appendLine("; Interact")
            appendLine("el.Click()                      ; left-click")
            appendLine("el.Value := \"hello\"             ; set value (edit controls)")
            appendLine("el.Invoke()                     ; invoke default action")
            appendLine("MsgBox el.Name                  ; read name")
            appendLine("MsgBox el.Value                 ; read value")
        }
    }

    private fun pythonPywinauto(node: ElementNode): String {
        val automationId = node.properties["AutomationId"]
        val controlType = node.properties["ControlType"] ?: "Control"
        val name = node.name

        val selector = when {
            !automationId.isNullOrBlank() -> "auto_id=\"$automationId\""
            name.isNotBlank() -> "title=\"$name\""
            node.className.isNotBlank() -> "class_name=\"${node.className}\""
            else -> "control_type=\"$controlType\""
        }

        return buildString {
            appendLine("# Python — pywinauto")
            appendLine("# pip install pywinauto")
            appendLine()
            appendLine("from pywinauto import Application, Desktop")
            appendLine()
            appendLine("# Connect to running application")
            appendLine("app = Application(backend='uia').connect(title_re='.*')  # adjust title filter")
            appendLine()
            appendLine("# OR use Desktop to find across all windows")
            appendLine("# desktop = Desktop(backend='uia')")
            appendLine()
            appendLine("# Find element")
            appendLine("el = app.window().child_window($selector)")
            appendLine()
            appendLine("# Inspect")
            appendLine("print(el.window_text())         # read text/name")
            appendLine("print(el.rectangle())           # bounds")
            appendLine("el.print_control_identifiers()  # dump all children")
            appendLine()
            appendLine("# Interact")
            appendLine("el.click_input()                # click")
            appendLine("el.type_keys('hello')           # type text")
            appendLine("el.set_edit_text('hello')       # set text (edit controls)")
        }
    }

    private fun csharpFlaui(node: ElementNode): String {
        val automationId = node.properties["AutomationId"]
        val controlType = node.properties["ControlType"] ?: "Custom"
        val name = node.name

        val findCode = when {
            !automationId.isNullOrBlank() ->
                """var el = window.FindFirstDescendant(cf => cf.ByAutomationId("$automationId"));"""
            name.isNotBlank() ->
                """var el = window.FindFirstDescendant(cf => cf.ByName("$name"));"""
            node.className.isNotBlank() ->
                """var el = window.FindFirstDescendant(cf => cf.ByClassName("${node.className}"));"""
            else ->
                """var el = window.FindFirstDescendant(cf => cf.ByControlType(ControlType.$controlType));"""
        }

        return buildString {
            appendLine("// C# — FlaUI (UIA3)")
            appendLine("// NuGet: Install-Package FlaUI.UIA3")
            appendLine()
            appendLine("using FlaUI.Core;")
            appendLine("using FlaUI.Core.AutomationElements;")
            appendLine("using FlaUI.Core.Conditions;")
            appendLine("using FlaUI.UIA3;")
            appendLine()
            appendLine("using var automation = new UIA3Automation();")
            appendLine()
            appendLine("// Get the target window")
            appendLine("var app = Application.Attach(\"process-name\");  // or .Launch()")
            appendLine("var window = app.GetMainWindow(automation);")
            appendLine()
            appendLine("// Find element")
            appendLine(findCode)
            appendLine()
            appendLine("if (el == null)")
            appendLine("    throw new Exception(\"Element not found\");")
            appendLine()
            appendLine("// Inspect")
            appendLine("Console.WriteLine(el.Name);")
            appendLine("Console.WriteLine(el.BoundingRectangle);")
            appendLine("Console.WriteLine(el.ControlType);")
            appendLine()
            appendLine("// Interact")
            appendLine("el.Click();                               // click")
            appendLine("el.AsButton().Invoke();                   // invoke (buttons)")
            appendLine("el.AsTextBox().Enter(\"hello\");            // type text")
            appendLine("Console.WriteLine(el.AsTextBox().Text);   // read text")
        }
    }

    private fun powershellUia(node: ElementNode): String {
        val automationId = node.properties["AutomationId"]
        val name = node.name
        val controlType = node.properties["ControlType"] ?: "Custom"

        val dollar = "$"
        val findLine = when {
            !automationId.isNullOrBlank() ->
                "${dollar}el = ${dollar}window | Get-ChildElement -AutomationId \"$automationId\""
            name.isNotBlank() ->
                "${dollar}el = ${dollar}window | Get-ChildElement -Name \"$name\""
            node.className.isNotBlank() ->
                "${dollar}el = ${dollar}window | Get-ChildElement -ClassName \"${node.className}\""
            else ->
                "${dollar}el = ${dollar}window | Get-ChildElement -ControlType \"$controlType\""
        }

        return buildString {
            appendLine("# PowerShell — UIAutomation module")
            appendLine("# Install: Install-Module -Name UIAutomation")
            appendLine()
            appendLine("Import-Module UIAutomation")
            appendLine()
            appendLine("# Attach to process")
            appendLine("\$proc = Get-Process 'process-name' | Select-Object -First 1")
            appendLine("\$window = \$proc | Get-Window")
            appendLine()
            appendLine("# Find element")
            appendLine(findLine)
            appendLine()
            appendLine("# Inspect")
            appendLine("\$el | Get-UIACurrentPropertyValue Name")
            appendLine("\$el | Get-UIACurrentPropertyValue BoundingRectangle")
            appendLine("\$el | Get-UIACurrentPropertyValue ControlType")
            appendLine("\$el | Get-UIACurrentPropertyValue IsEnabled")
            appendLine()
            appendLine("# Interact")
            appendLine("\$el | Invoke-Click")
            appendLine("\$el | Set-UIAValue 'hello'            # set text")
            appendLine("\$el | Get-UIAValue                   # read value")
            appendLine("\$el | Invoke-UIAInvoke               # invoke (buttons)")
        }
    }
}

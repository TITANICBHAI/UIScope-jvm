package com.titanicbhai.uiscope.codegen

import com.titanicbhai.uiscope.model.Target

enum class CodePlatform { ANDROID, WINDOWS }

/** The languages/frameworks the app can generate code for (plan §4.6). [docUrl] is the documentation the template was checked against. */
enum class CodeTarget(val displayName: String, val platform: CodePlatform, val language: String, val docUrl: String) {
    UIAUTOMATOR2_PYTHON("uiautomator2 (Python)", CodePlatform.ANDROID, "python", "https://github.com/openatx/uiautomator2"),
    UIAUTOMATOR_KOTLIN("UI Automator (Kotlin)", CodePlatform.ANDROID, "kotlin", "https://developer.android.com/training/testing/ui-automator"),
    APPIUM_JAVA("Appium (Java)", CodePlatform.ANDROID, "java", "https://github.com/appium/java-client"),
    APPIUM_PYTHON("Appium (Python)", CodePlatform.ANDROID, "python", "https://github.com/appium/python-client"),
    MAESTRO("Maestro (YAML)", CodePlatform.ANDROID, "yaml", "https://docs.maestro.dev/reference/selectors/core-selectors"),
    XPATH("XPath", CodePlatform.ANDROID, "xpath", "https://www.w3.org/TR/1999/REC-xpath-19991116/"),
    PYWINAUTO("pywinauto (UIA)", CodePlatform.WINDOWS, "python", "https://pywinauto.readthedocs.io/en/latest/getting_started.html"),
    FLAUI("FlaUI (C#)", CodePlatform.WINDOWS, "csharp", "https://github.com/FlaUI/FlaUI/wiki"),
    AUTOHOTKEY_V2("AutoHotkey v2 (UIA-v2)", CodePlatform.WINDOWS, "autohotkey", "https://github.com/Descolada/UIA-v2/wiki"),
    POWERSHELL_UIA("PowerShell (UI Automation)", CodePlatform.WINDOWS, "powershell", "https://learn.microsoft.com/dotnet/api/system.windows.automation.automationelement"),
    ;

    companion object {
        fun forCapture(target: Target): List<CodeTarget> {
            val platform = if (target is Target.Windows) CodePlatform.WINDOWS else CodePlatform.ANDROID
            return entries.filter { it.platform == platform }
        }
    }
}

/** What the app shows in the Code panel: the code and the bare selector (for "Copy selector"), or why this target cannot express the selector. */
sealed interface CodeResult {
    data class Code(val code: String, val selectorText: String, val language: String) : CodeResult

    data class NotAvailable(val reason: String) : CodeResult
}

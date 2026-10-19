package com.titanicbhai.uiscope.codegen

import com.titanicbhai.uiscope.model.ElementNode

enum class CodeTarget(val label: String, val language: String) {
    PYTHON_UIAUTOMATOR2("Python (uiautomator2)", "python"),
    KOTLIN_UIAUTOMATOR2("Kotlin (UIAutomator2)", "kotlin"),
    APPIUM_JAVA("Appium Java", "java"),
    APPIUM_PYTHON("Appium Python", "python"),
    MAESTRO_YAML("Maestro YAML", "yaml"),
    XPATH("XPath", "xml")
}

data class GeneratedCode(
    val code: String,
    val language: String,
    val isFragile: Boolean,
    val fragilityReason: String? = null
)

object AndroidCodegen {

    private val obfuscatedIdRegex = Regex("""0x[0-9a-fA-F]+""")

    fun generate(node: ElementNode, target: CodeTarget): GeneratedCode {
        val fragile = isFragile(node)
        val fragilityReason = when {
            node.resourceId.isNullOrBlank() -> "No resource ID — using text/class fallback selector"
            obfuscatedIdRegex.containsMatchIn(node.resourceId) -> "Obfuscated resource ID (hex address) — may break on rebuild"
            else -> null
        }
        val code = when (target) {
            CodeTarget.PYTHON_UIAUTOMATOR2 -> pythonU2(node)
            CodeTarget.KOTLIN_UIAUTOMATOR2 -> kotlinU2(node)
            CodeTarget.APPIUM_JAVA -> appiumJava(node)
            CodeTarget.APPIUM_PYTHON -> appiumPython(node)
            CodeTarget.MAESTRO_YAML -> maestroYaml(node)
            CodeTarget.XPATH -> xpathSelector(node)
        }
        return GeneratedCode(code, target.language, fragile, fragilityReason)
    }

    private fun isFragile(node: ElementNode): Boolean =
        node.resourceId.isNullOrBlank() ||
                obfuscatedIdRegex.containsMatchIn(node.resourceId ?: "")

    private fun selector(node: ElementNode): String = when {
        !node.resourceId.isNullOrBlank() && !obfuscatedIdRegex.containsMatchIn(node.resourceId) ->
            "resource_id='${node.resourceId}'"
        !node.text.isNullOrBlank() -> "text='${node.text}'"
        !node.contentDescription.isNullOrBlank() -> "description='${node.contentDescription}'"
        else -> "class_name='${node.className}'"
    }

    private fun pythonU2(node: ElementNode): String {
        val sel = when {
            !node.resourceId.isNullOrBlank() && !obfuscatedIdRegex.containsMatchIn(node.resourceId) ->
                "resourceId='${node.resourceId}'"
            !node.text.isNullOrBlank() -> "text='${node.text}'"
            !node.contentDescription.isNullOrBlank() -> "description='${node.contentDescription}'"
            else -> "className='${node.className}'"
        }
        return buildString {
            appendLine("import uiautomator2 as u2")
            appendLine()
            appendLine("d = u2.connect()  # or u2.connect('${node.packageName ?: "device-serial"}')")
            appendLine()
            appendLine("# Find element")
            appendLine("el = d($sel)")
            appendLine()
            appendLine("# Interact")
            appendLine("el.click()                   # tap")
            appendLine("el.set_text('hello')         # type text")
            appendLine("el.get_text()                # read text")
            appendLine("el.info                      # all properties")
        }
    }

    private fun kotlinU2(node: ElementNode): String {
        val sel = when {
            !node.resourceId.isNullOrBlank() && !obfuscatedIdRegex.containsMatchIn(node.resourceId) ->
                "By.res(\"${node.resourceId}\")"
            !node.text.isNullOrBlank() -> "By.text(\"${node.text}\")"
            !node.contentDescription.isNullOrBlank() -> "By.desc(\"${node.contentDescription}\")"
            else -> "By.clazz(\"${node.className}\")"
        }
        return buildString {
            appendLine("// Kotlin UIAutomator2 (in an Android instrumented test)")
            appendLine()
            appendLine("val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())")
            appendLine()
            appendLine("// Find element")
            appendLine("val el = device.findObject($sel)")
            appendLine()
            appendLine("// Interact")
            appendLine("el.click()                             // tap")
            appendLine("el.setText(\"hello\")                   // type text")
            appendLine("el.text                                // read text")
            node.packageName?.let { appendLine("\n// Launch app\ndevice.executeShellCommand(\"am start -n $it/.MainActivity\")") }
        }
    }

    private fun appiumJava(node: ElementNode): String {
        val strategy = when {
            !node.resourceId.isNullOrBlank() && !obfuscatedIdRegex.containsMatchIn(node.resourceId) ->
                "By.id(\"${node.resourceId}\")"
            !node.text.isNullOrBlank() -> "By.xpath(\"//*[@text='${node.text}']\")"
            !node.contentDescription.isNullOrBlank() -> "By.xpath(\"//*[@content-desc='${node.contentDescription}']\")"
            else -> "By.className(\"${node.className}\")"
        }
        return buildString {
            appendLine("// Appium Java")
            appendLine("// Required: io.appium:java-client")
            appendLine()
            appendLine("AndroidDriver driver = /* your driver */;")
            appendLine()
            appendLine("// Find element")
            appendLine("WebElement el = driver.findElement($strategy);")
            appendLine()
            appendLine("// Interact")
            appendLine("el.click();                    // tap")
            appendLine("el.sendKeys(\"hello\");          // type text")
            appendLine("el.getText();                  // read text")
        }
    }

    private fun appiumPython(node: ElementNode): String {
        val strategy = when {
            !node.resourceId.isNullOrBlank() && !obfuscatedIdRegex.containsMatchIn(node.resourceId) ->
                "(AppiumBy.ID, \"${node.resourceId}\")"
            !node.text.isNullOrBlank() -> "(AppiumBy.XPATH, \"//*[@text='${node.text}']\")"
            !node.contentDescription.isNullOrBlank() -> "(AppiumBy.XPATH, \"//*[@content-desc='${node.contentDescription}']\")"
            else -> "(AppiumBy.CLASS_NAME, \"${node.className}\")"
        }
        return buildString {
            appendLine("# Appium Python")
            appendLine("from appium.webdriver.common.appiumby import AppiumBy")
            appendLine()
            appendLine("driver = ...  # your Appium driver")
            appendLine()
            appendLine("# Find element")
            appendLine("el = driver.find_element(*$strategy)")
            appendLine()
            appendLine("# Interact")
            appendLine("el.click()                     # tap")
            appendLine("el.send_keys('hello')          # type text")
            appendLine("el.text                        # read text")
        }
    }

    private fun maestroYaml(node: ElementNode): String {
        val tap = when {
            !node.text.isNullOrBlank() -> "- tapOn: \"${node.text}\""
            !node.contentDescription.isNullOrBlank() -> "- tapOn:\n    description: \"${node.contentDescription}\""
            !node.resourceId.isNullOrBlank() && !obfuscatedIdRegex.containsMatchIn(node.resourceId) ->
                "- tapOn:\n    id: \"${node.resourceId}\""
            node.bounds != null -> {
                val b = node.bounds
                val cx = b.x + b.width / 2
                val cy = b.y + b.height / 2
                "- tapOn:\n    point: \"${cx},${cy}\""
            }
            else -> "- tapOn:\n    index: ${node.siblingIndex}"
        }
        return buildString {
            appendLine("# Maestro YAML flow")
            node.packageName?.let {
                appendLine("appId: $it")
                appendLine("---")
            }
            appendLine("- launchApp")
            appendLine()
            appendLine("# Tap this element")
            appendLine(tap)
            if (!node.text.isNullOrBlank()) {
                appendLine()
                appendLine("# Assert element is visible")
                appendLine("- assertVisible: \"${node.text}\"")
            }
        }
    }

    private fun xpathSelector(node: ElementNode): String {
        val parts = mutableListOf<String>()
        if (!node.resourceId.isNullOrBlank() && !obfuscatedIdRegex.containsMatchIn(node.resourceId)) {
            parts.add("@resource-id='${node.resourceId}'")
        }
        if (!node.text.isNullOrBlank()) parts.add("@text='${node.text}'")
        if (!node.contentDescription.isNullOrBlank()) parts.add("@content-desc='${node.contentDescription}'")
        if (!node.packageName.isNullOrBlank()) parts.add("@package='${node.packageName}'")

        val className = node.className
        val predicate = if (parts.isNotEmpty()) "[${parts.joinToString(" and ")}]" else ""
        val xpath = "//$className$predicate"
        return buildString {
            appendLine("<!-- XPath selector for UIAutomator / Appium -->")
            appendLine()
            appendLine(xpath)
            appendLine()
            appendLine("<!-- Usage in Appium: -->")
            appendLine("<!-- driver.findElement(By.xpath(\"$xpath\")); -->")
            if (parts.isEmpty()) {
                appendLine()
                appendLine("<!-- WARNING: No unique attributes found. -->")
                appendLine("<!-- Add index or parent context to make this more specific. -->")
            }
        }
    }
}

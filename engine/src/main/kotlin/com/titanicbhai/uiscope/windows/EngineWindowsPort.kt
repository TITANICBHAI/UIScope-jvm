package com.titanicbhai.uiscope.windows

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.model.CaptureWarning
import com.titanicbhai.uiscope.model.ScreenInfo
import com.titanicbhai.uiscope.model.Target
import com.titanicbhai.uiscope.model.WindowInfo
import com.titanicbhai.uiscope.ports.PickEvent
import com.titanicbhai.uiscope.ports.PickedElement
import com.titanicbhai.uiscope.ports.WindowInfo as PortWindowInfo
import com.titanicbhai.uiscope.ports.WindowsInspectorPort
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The real [WindowsInspectorPort] on top of [WindowsHelperClient] (plan P3-3). Translates the Appendix J responses into
 * model types: the screenshot stays at the helper's temp-file path (Appendix J: never base64), the origin travels in
 * [ScreenInfo] so every coordinate mapping goes through `CaptureViewport` (P3-5), and the tree nodes arrive as Appendix
 * B properties mapped into ElementNodes. Pick events are translated 1:1.
 */
class EngineWindowsPort(
    private val client: WindowsHelperClient,
    dispatchers: DispatcherProvider,
    private val clock: Clock = Clock.systemUTC(),
) : WindowsInspectorPort {
    // The helper client owns its process lifetime; the port starts it on first use.
    private val clientScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    /** Starts the helper client the first time (idempotent); later calls reuse the running helper. */
    suspend fun connect(): Outcome<Unit> = client.start(clientScope)

    override suspend fun listWindows(): Outcome<List<PortWindowInfo>> = when (val r = client.request("listWindows")) {
        is Outcome.Failure -> r
        is Outcome.Success -> {
            @Suppress("UNCHECKED_CAST")
            val windows = r.value["windows"] as? List<Any?> ?: emptyList()
            Outcome.Success(windows.mapNotNull { entry ->
                @Suppress("UNCHECKED_CAST")
                (entry as? Map<String, Any?>)?.let { m -> windowInfo(m) }
            })
        }
    }

    override suspend fun captureWindow(hwnd: String): Outcome<Capture> = when (val r = client.request(
        "captureWindow",
        mapOf("hwnd" to hwnd, "maxDepth" to 40L, "maxNodes" to 10_000L),
    )) {
        is Outcome.Failure -> r
        is Outcome.Success -> parseCapture(hwnd, r.value)
    }

    override suspend fun startPick(hideFromCapture: Boolean): Outcome<Unit> =
        when (val r = client.request("startPick", mapOf("hideFromCapture" to hideFromCapture))) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.Success(Unit)
        }

    override suspend fun stopPick(): Outcome<Unit> = when (val r = client.request("stopPick")) {
        is Outcome.Failure -> r
        is Outcome.Success -> Outcome.Success(Unit)
    }

    override fun pickEvents(): Flow<PickEvent> = client.events.map { event ->
        when (event.name) {
            "pickHover" -> PickEvent.Hover(
                label = hoverLabel(event.payload),
                element = pickedElement(event.payload),
            )
            "pickResult" -> PickEvent.Result(
                hwnd = (event.payload["hwnd"] as? String) ?: "",
                element = pickedElement(event.payload),
            )
            else -> PickEvent.Cancelled
        }
    }

    private fun parseCapture(hwnd: String, value: Map<String, Any?>): Outcome<Capture> {
        @Suppress("UNCHECKED_CAST")
        val window = value["window"] as? Map<String, Any?>
            ?: return Outcome.Failure(CodedError(ErrorCodes.UIA_UNAVAILABLE, "the helper's response has no window"))
        @Suppress("UNCHECKED_CAST")
        val screenshot = value["screenshot"] as? Map<String, Any?>
            ?: return Outcome.Failure(CodedError(ErrorCodes.UIA_UNAVAILABLE, "the helper's response has no screenshot"))
        val rootsRaw = value["roots"] as? List<Any?> ?: emptyList()

        val width = (screenshot["width"] as? Long)?.toInt() ?: 0
        val height = (screenshot["height"] as? Long)?.toInt() ?: 0
        val originX = (screenshot["originX"] as? Long)?.toInt() ?: 0
        val originY = (screenshot["originY"] as? Long)?.toInt() ?: 0
        val path = screenshot["path"] as? String
            ?: return Outcome.Failure(CodedError(ErrorCodes.UIA_UNAVAILABLE, "the helper's screenshot has no path"))

        val info = WindowInfo(
            title = window["title"] as? String ?: "",
            processName = window["processName"] as? String ?: "",
            pid = (window["pid"] as? Long) ?: 0L,
            isElevated = window["isElevated"] as? Boolean == true,
            hwnd = (window["hwnd"] as? String) ?: hwnd,
        )

        val warnings = if (value["truncated"] as? Boolean == true) {
            listOf(CaptureWarning(ErrorCodes.TREE_TRUNCATED, "the tree was cut at the helper's limits"))
        } else {
            emptyList()
        }

        val capture = Capture(
            id = "win-" + hwnd.removePrefix("0x").filter { it.isLetterOrDigit() } + "-" + clock.instant().toEpochMilli(),
            takenAt = clock.instant(),
            target = Target.Windows(info),
            screen = ScreenInfo(width = width, height = height, originX = originX, originY = originY),
            screenshot = com.titanicbhai.uiscope.model.Screenshot(path, width, height),
            roots = WindowsNodeMapper.mapRoots(rootsRaw),
            warnings = warnings,
        )
        return Outcome.Success(capture)
    }

    private fun windowInfo(m: Map<String, Any?>) = PortWindowInfo(
        hwnd = m["hwnd"] as? String ?: "",
        title = m["title"] as? String ?: "",
        processName = m["processName"] as? String ?: "",
        pid = (m["pid"] as? Long) ?: 0L,
        isElevated = m["isElevated"] as? Boolean == true,
    )

    private fun pickedElement(payload: Map<String, Any?>): PickedElement {
        @Suppress("UNCHECKED_CAST")
        val element = payload["element"] as? Map<String, Any?> ?: PickedElement(null, null, null, null, boundsZero)
        @Suppress("UNCHECKED_CAST")
        val rect = element["rect"] as? Map<String, Any?> ?: boundsZero
        fun long(key: String) = (rect[key] as? Long)?.toInt() ?: 0
        return PickedElement(
            runtimeId = element["runtimeId"] as? String,
            automationId = element["automationId"] as? String,
            name = element["name"] as? String,
            controlType = element["controlType"] as? String,
            rect = com.titanicbhai.uiscope.model.Bounds(long("x"), long("y"), long("width"), long("height")),
        )
    }

    private fun hoverLabel(payload: Map<String, Any?>): String {
        @Suppress("UNCHECKED_CAST")
        val element = payload["element"] as? Map<String, Any?> ?: return ""
        val controlType = element["controlType"] as? String ?: ""
        val name = element["name"] as? String ?: ""
        return listOf(controlType, name).filter { it.isNotBlank() }.joinToString(" - ")
    }

    private companion object {
        val boundsZero = com.titanicbhai.uiscope.model.Bounds(0, 0, 0, 0)
    }
}

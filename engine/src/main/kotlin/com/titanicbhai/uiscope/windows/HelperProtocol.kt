package com.titanicbhai.uiscope.windows

import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.json.JsonReader
import com.titanicbhai.uiscope.json.JsonWriter

/**
 * The Windows helper protocol, version 1 (Appendix J): UTF-8, one JSON object per line on stdin (requests) and stdout
 * (responses and events). Requests carry a `type` and the client's correlation `id`; responses repeat the `id` with
 * `ok: true` plus result fields, or `ok: false` plus an `error` object; events carry an `event` name instead of an id.
 * Unknown fields are ignored by both sides. Screenshots travel as temp-file paths, never base64.
 */
object HelperProtocol {
    const val PROTOCOL_VERSION = 1

    fun request(id: Long, type: String, params: Map<String, Any?> = emptyMap()): String {
        val fields = LinkedHashMap<String, Any?>()
        fields["id"] = id
        fields["type"] = type
        fields.putAll(params)
        return writeJson(fields)
    }

    /** A decoded stdout line. Malformed lines are reported (the client ignores and logs them). */
    sealed interface Line {
        data class Response(val id: Long, val ok: Boolean, val value: Map<String, Any?>, val errorCode: String?, val errorMessage: String?) : Line
        data class Event(val name: String, val payload: Map<String, Any?>) : Line
        data object Malformed : Line
    }

    fun decode(line: String): Line {
        val root = when (val parsed = JsonReader.parse(line)) {
            is Outcome.Failure -> return Line.Malformed
            is Outcome.Success -> parsed.value
        }
        @Suppress("UNCHECKED_CAST")
        val obj = root as? Map<String, Any?> ?: return Line.Malformed
        val event = obj["event"]
        if (event is String) return Line.Event(event, obj)
        val id = obj["id"] as? Long ?: return Line.Malformed
        val ok = obj["ok"] as? Boolean ?: return Line.Malformed
        @Suppress("UNCHECKED_CAST")
        val error = obj["error"] as? Map<String, Any?>
        return Line.Response(
            id = id,
            ok = ok,
            value = obj,
            errorCode = error?.get("code") as? String,
            errorMessage = error?.get("message") as? String,
        )
    }
}

/** A helper-reported failure (its own codes, e.g. WINDOW_GONE, HOTKEY_IN_USE). */
data class HelperError(val code: String, val message: String?)

/** Serializes protocol values: String, Long/Int, Double, Boolean, null, List and Map - the shapes [JsonReader] produces. */
fun writeJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> JsonWriter.string(value)
    is Long -> value.toString()
    is Int -> value.toString()
    is Double -> value.toString()
    is Boolean -> value.toString()
    is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { writeJson(it) }
    is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
        "${JsonWriter.string(k.toString())}: ${writeJson(v)}"
    }
    else -> JsonWriter.string(value.toString())
}

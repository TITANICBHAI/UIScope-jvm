package com.titanicbhai.uiscope.process

import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import java.util.logging.Logger

/**
 * The one sanctioned process spawn for restarting the app (plan P7-2's crash dialog "Restart"): argument lists only,
 * never a shell, so the P3-9 guardrail's no-shell rule and B10 stay intact. Lives in `engine/process` because that is
 * where ProcessBuilder is allowed.
 */
object Relauncher {
    private val logger = Logger.getLogger("uiscope.process")

    /** Spawns [cmd] detached (the current process keeps running until the caller exits it). */
    fun relaunch(cmd: List<String>): Outcome<Unit> = try {
        val process = ProcessBuilder(cmd).start()
        logger.info("relaunch spawned pid=" + process.pid())
        Outcome.Success(Unit)
    } catch (t: Throwable) {
        logger.warning("relaunch failed: " + t.javaClass.simpleName)
        Outcome.Failure(CodedError(ErrorCodes.UNEXPECTED, t.message))
    }
}

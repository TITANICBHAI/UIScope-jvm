package com.titanicbhai.uiscope.process

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SystemProcessLauncher(
    private val dispatchers: DispatcherProvider,
    private val logger: Logger = Logger.getLogger("uiscope.process"),
) : ProcessLauncher {

    override fun start(cmd: List<String>, scope: CoroutineScope, env: Map<String, String>): Outcome<ManagedProcess> {
        val process = when (val started = startProcess(cmd, env, logger)) {
            is Outcome.Failure -> return started
            is Outcome.Success -> started.value
        }
        val name = executableName(cmd.first())
        val managed = SystemManagedProcess(process, name, dispatchers, logger)
        logger.info("managed process $name started pid=${process.pid()}")
        // The process lives exactly as long as the caller's scope: cancelling the scope kills the tree.
        // ATOMIC: even if the scope is already cancelled, the body runs far enough to kill the process.
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                managed.awaitExit()
            } finally {
                managed.kill()
            }
        }
        return Outcome.Success(managed)
    }
}

internal class SystemManagedProcess(
    private val process: Process,
    private val name: String,
    dispatchers: DispatcherProvider,
    private val logger: Logger,
) : ManagedProcess {
    private val io = dispatchers.io
    private val readerScope = CoroutineScope(SupervisorJob() + io)
    private val stdoutChannel = Channel<String>(STDOUT_BUFFER)
    private val stderrChannel = Channel<String>(STDERR_BUFFER, BufferOverflow.DROP_OLDEST)
    private val stdin: OutputStream = process.outputStream.buffered()
    private val stdinLock = Mutex()
    private val killed = AtomicBoolean(false)

    override val pid: Long = process.pid()
    override val isAlive: Boolean get() = process.isAlive

    // `for (line in channel)` keeps this single-consumer stream independent of coroutines-version differences.
    override val stdoutLines: Flow<String> = flow { for (line in stdoutChannel) emit(line) }
    override val stderrLines: Flow<String> = flow { for (line in stderrChannel) emit(line) }

    init {
        readerScope.launch { pump(process.inputStream, stdoutChannel, "stdout") }
        readerScope.launch { pump(process.errorStream, stderrChannel, "stderr") }
    }

    override suspend fun writeLine(line: String): Outcome<Unit> =
        stdinLock.withLock {
            withContext(io) {
                try {
                    stdin.write((line + "\n").toByteArray(Charsets.UTF_8))
                    stdin.flush()
                    Outcome.Success(Unit)
                } catch (e: IOException) {
                    logger.log(Level.FINE, "write to $name failed", e)
                    Outcome.Failure(CodedError(ErrorCodes.PROCESS_WRITE_FAILED, "Could not send a command to '$name'"))
                }
            }
        }

    override suspend fun awaitExit(): Int = process.awaitExit()

    override fun kill() {
        if (!killed.compareAndSet(false, true)) return
        if (process.isAlive) logger.info("managed process $name killed")
        terminateTree(process, logger)
        readerScope.cancel()
    }

    private suspend fun pump(stream: InputStream, channel: Channel<String>, label: String) {
        try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    channel.send(line)
                }
            }
        } catch (e: IOException) {
            logger.log(Level.FINE, "$name $label closed", e)
        } finally {
            channel.close()
        }
    }

    private companion object {
        const val STDOUT_BUFFER = 1024
        const val STDERR_BUFFER = 256
    }
}

package com.titanicbhai.uiscope.adb

import com.titanicbhai.uiscope.DispatcherProvider
import com.titanicbhai.uiscope.error.CodedError
import com.titanicbhai.uiscope.error.ErrorCodes
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.error.map
import com.titanicbhai.uiscope.model.AdbDevice
import com.titanicbhai.uiscope.model.Capture
import com.titanicbhai.uiscope.ports.AdbPort
import com.titanicbhai.uiscope.ports.AdbStatus
import com.titanicbhai.uiscope.ports.TrackerEvent
import com.titanicbhai.uiscope.process.ProcessRunner
import com.titanicbhai.uiscope.storage.DataPaths
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Connects the state holder to the real engine: locator, client and tracker. [locate] creates the client; the other calls need it. */
class EngineAdbPort(
    private val locator: AdbLocator,
    private val runner: ProcessRunner,
    private val dataPaths: DataPaths,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock = Clock.systemUTC(),
    private val serverPort: Int = 5037,
) : AdbPort {
    @Volatile private var client: AdbClient? = null

    override suspend fun locate(): Outcome<AdbStatus> = when (val r = locator.locate()) {
        is Outcome.Failure -> r
        is Outcome.Success -> {
            val location = r.value
            client = AdbClient(location.path.toString(), runner, dataPaths.tmp, dispatchers, clock)
            Outcome.Success(AdbStatus(location.path.toString(), location.version.toString(), location.source.name))
        }
    }

    override fun trackDevices(): Flow<TrackerEvent> {
        val c = client ?: return flowOf(TrackerEvent.Problem(CodedError(ErrorCodes.ADB_NOT_FOUND, "adb has not been located yet")))
        return DeviceTracker(
            refresh = { c.devices().map { it.devices } },
            dispatchers = dispatchers,
            port = serverPort,
            startServer = { c.startServer() },
        ).track()
    }

    override suspend fun capture(device: AdbDevice): Outcome<Capture> =
        client?.capture(device) ?: Outcome.Failure(CodedError(ErrorCodes.ADB_NOT_FOUND, "adb has not been located yet"))
}

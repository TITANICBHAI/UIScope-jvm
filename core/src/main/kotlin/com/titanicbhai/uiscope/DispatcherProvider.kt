package com.titanicbhai.uiscope

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Every coroutine in the app gets its dispatcher from here, so tests can substitute theirs (plan §3.10). */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

/**
 * Production dispatchers. [main] is supplied by the app (the Compose/Swing main dispatcher is not available in `core`).
 */
class StandardDispatcherProvider(
    override val main: CoroutineDispatcher,
) : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
}

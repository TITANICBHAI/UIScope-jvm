package com.titanicbhai.uiscope

import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertSame

class DispatcherProviderTest {
    @Test
    fun `standard provider uses IO and Default and the supplied main`() {
        val provider = StandardDispatcherProvider(main = Dispatchers.Unconfined)
        assertSame(Dispatchers.Unconfined, provider.main)
        assertSame(Dispatchers.IO, provider.io)
        assertSame(Dispatchers.Default, provider.default)
    }
}

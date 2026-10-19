package com.titanicbhai.uiscope.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AppErrorTest {
    private val failure: Outcome<Int> = Outcome.Failure(CodedError("X", "detail", Remedy.RETRY))

    @Test
    fun `map and flatMap transform success and pass failure through`() {
        assertEquals(Outcome.Success(4), Outcome.Success(2).map { it * 2 })
        assertEquals(Outcome.Success("2!"), Outcome.Success(2).flatMap<Int, String> { Outcome.Success("$it!") })
        assertSame<Any?>(failure, failure.map { it * 2 })
        assertSame<Any?>(failure, failure.flatMap<Int, String> { Outcome.Success("never") })
    }

    @Test
    fun `fold getOrNull and errorOrNull`() {
        assertEquals("ok 1", Outcome.Success(1).fold({ "ok $it" }, { "bad ${it.code}" }))
        assertEquals("bad X", failure.fold({ "ok $it" }, { "bad ${it.code}" }))
        assertEquals(1, Outcome.Success(1).getOrNull())
        assertNull(failure.getOrNull())
        assertNull(Outcome.Success(1).errorOrNull())
        assertEquals("X", failure.errorOrNull()?.code)
    }

    @Test
    fun `onSuccess and onFailure run only for their case`() {
        var seen = ""
        Outcome.Success(1).onSuccess { seen += "s$it" }.onFailure { seen += "f" }
        failure.onSuccess { seen += "s" }.onFailure { seen += "f${it.code}" }
        assertEquals("s1fX", seen)
    }

    @Test
    fun `unexpected error has the UNEXPECTED code and keeps its cause`() {
        val cause = IllegalStateException("boom")
        val error = UnexpectedError(cause)
        assertEquals(ErrorCodes.UNEXPECTED, error.code)
        assertEquals("boom", error.detail)
        assertSame(cause, error.cause)
        assertEquals(Remedy.REPORT_ISSUE, error.remedy)
    }
}

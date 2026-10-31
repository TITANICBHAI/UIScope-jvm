package com.titanicbhai.uiscope.process

import com.titanicbhai.uiscope.HostOs
import com.titanicbhai.uiscope.error.Outcome
import com.titanicbhai.uiscope.testing.FakeProcess
import kotlin.test.Test
import kotlin.test.assertTrue

/** P7-2: the one sanctioned spawn can start a process and reports its own failures typed. */
class RelauncherTest {

    @Test
    fun `relaunching a real command succeeds`() {
        val result = Relauncher.relaunch(FakeProcess.command("exit", "0"))
        assertTrue(result is Outcome.Success, "a real spawn succeeds: $result")
    }

    @Test
    fun `an impossible command is a typed failure, not a throw`() {
        val impossible = if (HostOs.current() == HostOs.WINDOWS) {
            listOf("C:\\definitely\\not\\a\\real\\executable-xyz.exe")
        } else {
            listOf("/definitely/not/a/real/executable-xyz")
        }
        val result = Relauncher.relaunch(impossible)
        assertTrue(result is Outcome.Failure, "a bad command fails typed: $result")
    }
}

package tech.csalliance.unstuck.calls

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.csalliance.unstuck.calls.CallLink.Route

/**
 * A tap on `unstuck://call/<id>` used to land on Today every time; iOS resolves
 * the call first (openCall / routeResolvedCall) — X2, parity with iOS.
 */
class CallLinkTest {
    private fun route(
        callId: String = "c1", active: String? = null, ringing: String? = null,
        taskId: String? = null, local: Boolean = false, assistant: Boolean = true,
    ) = CallLink.route(callId, active, ringing, taskId, local, assistant)

    @Test fun `the call this phone is on stays on Today, where the in-call bar has End`() {
        assertEquals(Route.IN_CALL, route(active = "c1", ringing = "c1", taskId = "t1", local = true))
    }

    @Test fun `a call still ringing here opens the ring screen`() {
        assertEquals(Route.RING, route(ringing = "c1", taskId = "t1", local = true))
        assertEquals("another call ringing is not this one", Route.TASK, route(ringing = "c2", taskId = "t1", local = true))
    }

    @Test fun `otherwise its task, then the assistant, then Today`() {
        assertEquals(Route.TASK, route(taskId = "t1", local = true))
        assertEquals("a task this phone doesn't have", Route.ASSISTANT, route(taskId = "t1", local = false))
        assertEquals(Route.ASSISTANT, route())
        assertEquals("AI switched off", Route.TODAY, route(assistant = false))
        assertEquals(Route.TODAY, route(callId = ""))
    }

    @Test fun `the call id comes off the link`() {
        assertEquals("abc", CallLink.callId("unstuck://call/abc"))
        assertEquals("abc", CallLink.callId("unstuck://call/abc?x=1"))
        assertEquals("", CallLink.callId("unstuck://call/"))
    }
}

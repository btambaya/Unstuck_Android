package tech.csalliance.unstuck.calls

/**
 * Where a tap on `unstuck://call/<id>` lands — the ring, the in-call
 * notification, a call-result notification, a bell row. It used to land on
 * Today every time; iOS resolves the call first (AppModel+Notifications
 * openCall / routeResolvedCall), and so does this, in the same order:
 *  - the conversation THIS phone is running → Today, where the in-call bar
 *    carries End;
 *  - the call ringing on this phone → the ring screen, to answer it;
 *  - a call anchored to a task this phone has → that task's editor (its
 *    "Call me about this" section);
 *  - otherwise the assistant (when it's on), else Today.
 * Pure — MainScaffold supplies the facts (X2, parity with iOS).
 */
object CallLink {
    enum class Route { IN_CALL, RING, TASK, ASSISTANT, TODAY }

    fun callId(link: String): String =
        link.removePrefix(tech.csalliance.unstuck.core.logic.IncomingCallPayload.DEEP_LINK_PREFIX)
            .substringBefore('?').substringBefore('#').trim()

    fun route(
        callId: String,
        activeCallId: String?,
        ringingCallId: String?,
        taskId: String?,
        taskIsLocal: Boolean,
        assistantAllowed: Boolean,
    ): Route = when {
        callId.isEmpty() -> Route.TODAY
        activeCallId == callId -> Route.IN_CALL
        ringingCallId == callId -> Route.RING
        taskId != null && taskIsLocal -> Route.TASK
        assistantAllowed -> Route.ASSISTANT
        else -> Route.TODAY
    }
}

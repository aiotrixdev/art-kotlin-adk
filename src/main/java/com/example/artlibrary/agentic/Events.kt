/**
 * Typed envelope for events emitted on the `agent_com_<agentId>` channel.
 *
 * The wire shape `{ event: string, content: object }` is produced by the
 * agentbuilder backend (see `art-agentbuilder-v2/src/builder/response_types.py`
 * `RESPONSE_TYPE_TO_EVENT`). The `event` string is the discriminator; each
 * variant's `content` shape mirrors the corresponding Pydantic class.
 */

val AGENT_EVENTS: Set<String> = setOf(
    "agent_output",
    "agent_error",
    "human_input_request",
    "agent_wait",
    "planner_correction"
)

typealias AgentEventName = String

/**
 * Routing/correlation fields injected into every egress `content` body by
 * `_content_with_meta` on the server. May be empty strings if the upstream
 * task lacked them.
 */
interface EnvelopeMeta {
    val thread_id: String
    val ref_id: String
    val agent_id: String
    /** ref_id of the request this message is responding to. */
    val reply_to: String
}

data class AgentOutput(
    override val thread_id: String,
    override val ref_id: String,
    override val agent_id: String,
    override val reply_to: String,
    val type: String = "agent_general_response",
    val status: String = "final_response",
    val message: String,
    val data: Map<String, Any?>? = null,
    val metadata: Map<String, Any?>? = null
) : EnvelopeMeta

data class AgentError(
    override val thread_id: String,
    override val ref_id: String,
    override val agent_id: String,
    override val reply_to: String,
    val type: String = "agent_error_response",
    val status: String = "error",
    /** Framework codes are defined in `response_types.ErrorCode`; agents may
     *  also emit free-form codes from prompts. */
    val code: String,
    val message: String,
    val details: Map<String, Any?>? = null
) : EnvelopeMeta

// Mirrors the TS `ExpectedResponseType` union
typealias ExpectedResponseType = String

object ExpectedResponseTypes {
    const val TEXT = "text"
    const val CHOICE = "choice"
    const val CONFIRM = "confirm"
    const val FILE = "file"
    const val STRUCTURED = "structured"
}

data class HumanInputRequest(
    override val thread_id: String,
    override val ref_id: String,
    override val agent_id: String,
    override val reply_to: String,
    val type: String = "human_input_request",
    val status: String = "awaiting_input",
    val prompt: String,
    val context: Map<String, Any?>? = null,
    /** Server currently emits "text" only; the union documents the intended
     *  modalities for clients to render input widgets. */
    val expected_response_type: ExpectedResponseType,
    val timeout: Int? = null,
    /** Optional JSON schema when `expected_response_type === "structured"`. */
    val schema: Any? = null
) : EnvelopeMeta

data class AgentWait(
    override val thread_id: String,
    override val ref_id: String,
    override val agent_id: String,
    override val reply_to: String,
    val type: String = "agent_wait_response",
    val status: String = "waiting_for_agent",
    val waiting_for_agent_id: String,
    val invocation_id: String? = null,
    val reason: String? = null,
    val timeout: Int? = null,
    val progress: Map<String, Any?>? = null
) : EnvelopeMeta

data class AgentErrorResponse(
    override val thread_id: String,
    override val ref_id: String,
    override val agent_id: String,
    override val reply_to: String,
    val type: String = "agent_error_response",
    val status: String = "error",
    val code: String,
    val message: String,
    val details: Map<String, Any?>? = null
) : EnvelopeMeta

data class PlannerCorrection(
    override val thread_id: String,
    override val ref_id: String,
    override val agent_id: String,
    override val reply_to: String,
    val type: String = "planner_correction_request",
    val correction_required: Boolean,
    val reason: String,
    val new_goal: String? = null,
    val suggested_agents: List<String>? = null
) : EnvelopeMeta

/** Discriminated union over the known event kinds. */
sealed class AgentEvent {
    data class AgentOutputEvent(val event: String = "agent_output", val content: AgentOutput) : AgentEvent()
    data class AgentErrorEvent(val event: String = "agent_error", val content: AgentError) : AgentEvent()
    data class HumanInputRequestEvent(val event: String = "human_input_request", val content: HumanInputRequest) : AgentEvent()
    data class AgentWaitEvent(val event: String = "agent_wait", val content: AgentWait) : AgentEvent()
    data class PlannerCorrectionEvent(val event: String = "planner_correction", val content: PlannerCorrection) : AgentEvent()
}

/** Catch-all envelope when the server emits an event the SDK doesn't yet model. */
data class UnknownAgentEvent(
    val event: String,
    val content: Map<String, Any?>
)

/** Wraps either a known typed AgentEvent or an UnknownAgentEvent. */
data class AgentEventOrUnknown(
    val event: String,
    val content: Any
)

private val KNOWN_EVENT_SET: Set<String> = AGENT_EVENTS

/**
 * Runtime guard: narrows an unknown envelope to the typed `AgentEvent`
 * union when the event name matches a known kind. Use to drive an
 * exhaustive `when` on `evt.event`.
 */
fun isKnownAgentEvent(e: AgentEventOrUnknown): Boolean {
    return KNOWN_EVENT_SET.contains(e.event)
}
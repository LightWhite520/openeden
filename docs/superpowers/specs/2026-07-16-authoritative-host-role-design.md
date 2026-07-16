# Authoritative Host Role Design

## Goal

Stop treating every current user as ATRI's host. A user receives host semantics only when authoritative runtime configuration identifies that exact platform user as the host. Group members and unconfigured direct-message users remain ordinary interlocutors.

## Decision

Use a dedicated host identity configuration and resolver. Do not reuse heartbeat owner metadata: heartbeat ownership controls delivery, while host identity controls relationship semantics. These concepts may point to the same account, but equality must be configured rather than assumed.

Rejected alternatives:

- Reusing `HeartbeatOwner` couples delivery routing to character relationship and contradicts the owner-metadata boundary.
- Persona-only neutral wording prevents false claims but gives the runtime no way to restore host-specific semantics for an actual configured host.

## Runtime Contract

Introduce a two-value relationship role:

- `HOST`: the incoming message's platform and sender user ID exactly match the configured host identity.
- `INTERLOCUTOR`: no host is configured, the identity does not match, or the turn has no authoritative sender relationship.

The resolver is pure and deterministic. It receives platform and sender user ID and returns the role. It does not inspect conversation content, relationship scores, session scope, memory, vectors, or heartbeat activity.

Host configuration is optional and consists of both platform and user ID. A partial configuration is invalid and must fail startup rather than silently broadening host identity. Heartbeat owner configuration remains separate.

## Prompt Data Flow

`DevelopmentMessagePipeline` resolves the role for every turn and passes it through `PromptInput`. `DefaultPromptBuilder` injects it into the logical runtime context as `relationship_role` before user input.

The prompt hard constraint states that ATRI must not assume the current user is the host and may apply host-specific address or relationship semantics only when `relationship_role` is `HOST`. `relationship_context` continues to carry familiarity, trust, safety, boundary sensitivity, and tension; those continuous relationship values do not grant host identity.

Heartbeat turns use `INTERLOCUTOR` because their synthetic `INTERNAL` sender is not an authoritative person. Their persona context must describe a proactive impulse without claiming that the eventual delivery target is the host.

## Persona Changes

`persona.identity` retains ATRI's robot identity but removes the unconditional current-user-as-host assertion. Chinese persona prose may describe how ATRI behaves toward a confirmed host, but must use conditional wording such as `被确认的宿主`. It must not imply that every interlocutor, group member, or heartbeat recipient is the host.

The three playthrough starting points remain unchanged as self-models. Their host-related history is canonical background, while present-user host treatment is gated by the injected relationship role.

## Boundaries

- Persona behavior remains YAML data; Kotlin carries only identity metadata.
- Session identity remains `platform:scope_id`; host identity does not split shared group state.
- Heartbeat owner remains delivery-only metadata.
- Per-user relationship state remains independent from host role.
- No changes are made to VQ-VAE inference, 8D vectors, derived dissonance, Omega, memory retrieval, or vector write serialization.
- All new runtime logic is pure or coroutine-compatible and introduces no blocking I/O.

## Testing

- The resolver returns `HOST` only for an exact configured platform/user match.
- Missing configuration and mismatches return `INTERLOCUTOR`.
- Partial host configuration is rejected by server configuration loading.
- Prompt output includes the resolved `relationship_role` and the English non-assumption constraint.
- Persona guards reject the unconditional `current user is your host` assertion and unconditional Chinese current-user host wording.
- Existing persona stage, VQ-VAE, heartbeat, and pipeline tests remain green.

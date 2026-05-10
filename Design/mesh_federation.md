# Mesh Federation: Distributed Multi-User Sessions over Reticulum

**Current phase: Pending adversarial analysis**

## Context

Raam's primitives — typed Actions, the Bus, ContentBlock, correlationId,
sessions, the permission system, PendingApproval — are message-passing-pure and
transport-agnostic. Extending Raam to multi-device and multi-user operation is
additive: no rewrite of `commonMain`, only new Features and a transport adapter
that respect the existing contracts.

This document captures the design intent. Implementation specifics are
deliberately tentative.

## Goals

- Two or more Raam instances (same user across devices, or different users) can
  share a session and exchange Actions
- Cryptographic identity and authentication for every Action across the
  federation
- End-to-end encryption between session members, with forward secrecy on
  membership changes
- Transport optionality: works over LAN, internet, or mesh radio without
  application-level code changes
- No third-party servers required for federated operation
- Agents and users are peer principals — neither owns the other

## Non-Goals

- Cross-user discovery without prior pairing
- Public sessions or globally discoverable principals
- Voice / video / large-file streaming (separate data plane)
- Replacement of the cloud LLM provider story — federation is orthogonal to
  `UniversalGatewayProvider`

---

## Identity Model

Four types of cryptographic identity. Users, agents, and sessions are peer
principals; devices are an execution-substrate identity beneath users.

### User Root Identity

One keypair per human user. Trust anchor for device attestations and for agent
attestations under that user's authority.

### Device Identity

One keypair per physical device. Used as the Reticulum-level identity for
packets originating from that device. Attested by a user root identity.

### Agent Identity

One keypair per agent. Attested by one or more user root identities. An agent
holds its own permission set, independent of and potentially distinct from any
user — including capabilities no user holds directly. Agents authenticate to
resources with their own keys.

### Session Identity

One keypair per session. Transit-encrypted session traffic targets the session
identity. Group encryption for content among members is layered above (see
Cryptography).

---

## Namespace and Addressing

Aspect strings are *labels* — routing and discovery metadata, not ownership
claims. The cryptographic principal is whoever signs.

```
raam.user.<name>.*        → user-scoped resources
raam.agent.<name>.*       → agent-scoped (independent principal)
raam.session.<id>         → shared sessions
```

Examples:

```
raam.user.daniel.device.desktop-7a3f
raam.user.daniel.device.phone-9b21
raam.user.daniel.bus

raam.agent.meridian
raam.agent.field-sensor-reader

raam.session.chat-1
raam.session.brainstorm-2026-q2
```

Principle: principals sit at root level under their type. Resources owned by a
principal nest under it. Shared entities (sessions, future federated resources)
don't nest.

---

## Transport

Reticulum is the federation transport substrate, providing cryptographic
identity, transport-agnostic addressing, forward-secret links, and routing
across IP, LoRa, packet radio, or other carriers. The Bus gains an optional
transport boundary: Actions targeted at remote principals are wrapped,
dispatched, and unwrapped transparently to consumer code (CommandBot, Strategy
Scripts, Features all stay ignorant of the wire).

The boundary is a single seam in the Bus dispatcher. Above it, Actions look
local. Below it, encrypted messages flow over whatever physical link is
available. A simpler local-network transport may serve as an early stepping
stone before full Reticulum integration.

---

## Sessions

Sessions are first-class principals. A session has its own Identity, canonical
address, member list, and log. Membership is established via signed grants from
existing members. The session creator gets the first grant; admin or moderator
semantics are UI concepts above the cryptographic floor.

Sessions can contain any combination of users and agents. A session with only
agents (a multi-agent workspace) is a valid configuration.

---

## Permission Model

Three orthogonal axes:

- **(user, resource)** — what a user can do directly
- **(agent, resource)** — what an agent can do directly
- **(user, agent)** — what authority a user holds over an agent (issue
  commands, modify config, revoke trust)

These compose but do not collapse. A user holding authority over an agent does
not inherit any of the agent's resource permissions; it gains the right to
issue commands, which the agent evaluates against its own permission set. This
makes principle-of-least-privilege-via-delegation expressible: a user with
narrow daily permissions can attest an agent with broader scoped capabilities
for specific tasks, and authenticate as themselves rather than borrowing the
agent's authority.

PendingApproval routes to whoever holds authority over the *resource* being
touched, which may not be the principal who initiated the action.

---

## Cryptography

Three layers, distinct concerns.

### Transit

Provided by Reticulum: forward-secret end-to-end encryption between any two
destinations, no application-level code required.

### Group

Multi-member session content needs group encryption above the transit layer,
because Reticulum's primitives are single-recipient. Standard pattern: a
symmetric session key encrypts content; the key is wrapped per-member to each
member's identity key; rotation occurs on membership change for forward
secrecy. This is well-trodden territory (Sender Keys, Megolm, MLS). Strong
preference for adopting an existing standard rather than designing from
scratch.

Per-message authorship is not free from group encryption alone — every member
holding the session key can encrypt as the group. Messages must additionally
carry a signature from the sender's identity key for principal attribution.

### At-Rest

Session logs, knowledge graph, NVRAM, Strategy Script storage, and backups are
encrypted at rest with a key derived from the user's master key
(passphrase-derived or platform-keystore-backed). Independent of transit and
group crypto, equally important for a security-first project.

---

## Visibility

Within a shared session, not every Action should reach every member. Direct
CommandBot tool-use invocations from one user must not leak to others.

Each Action carries a visibility scope:

- session-public (every member)
- restricted to a named principal set
- private (sender only)

Defaults differ by Action type: chat messages session-public, direct command
invocations principal-private. The Bus filters delivery before fanout;
renderers filter again on receive. A remote node is never trusted to filter on
a sender's behalf.

---

## Session State Convergence

Members on different devices each maintain a local mirror of the session log.
Reconnection after offline activity requires convergence to a consistent
ordering. Approaches range from a designated canonical source per session
(simple, requires availability) to causally-ordered logs merged at reconnect
(robust, more code). The choice is deferred until concurrent multi-user
activity exposes which tradeoffs matter in practice.

---

## Open Questions

1. **Multi-instance agent state synchronization.** The same agent identity may
   run across multiple devices. Identity replication is solved via the
   device-pairing channel; state replication (HKG, NVRAM, conversation history)
   is not. Eventual consistency, canonical source, or active-passive?

2. **Agent attestation revocation.** When a user revokes trust in an agent, the
   revocation must propagate reliably across the federation. Staleness bound?
   Behaviour during partitions longer than the bound?

3. **Cross-user agent attestation.** When two users independently attest the
   same agent with different capability scopes, how are scopes evaluated —
   strictly intersected, separately tracked per attesting user, something
   else?

4. **Cross-user shared resources beyond sessions.** A federated knowledge
   graph, file space, or strategy script library would sit at root
   (`raam.kg.*`, `raam.fs.*`, etc.). Access model and update semantics are
   unclear until a concrete use case appears.

5. **Remote action correlation and timeouts.** Strategy Scripts triggering
   actions on remote principals need correlation and timeout semantics for
   long-running operations.

6. **Session export and migration.** Serialization format for full session
   state including group key state, wrapped keys, and encrypted log.

7. **Aspect-string privacy.** Reticulum announces propagate aspect strings
   publicly. Acceptable for personal mesh; for any cross-user federation
   scenario, consider obfuscation or omission of human-readable identifiers.

8. **Post-quantum migration.** The cryptographic stack will need a
   post-quantum migration path within a few years. Worth tracking, not
   blocking.

---

## Out of Scope

- Cross-user discovery without prior pairing
- Public sessions
- Voice / video media planes
- Federation across third-party Raam forks (governance, not technical)

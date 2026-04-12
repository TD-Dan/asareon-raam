# Asareon Raam

A multi-platform runtime for hosting autonomous AI agents. Kotlin Multiplatform, Compose Multiplatform.

> 1.0.0-alpha · Desktop (Windows) released. Linux, macOS, Android, iOS, WebAssembly planned.

Secure by design, flexible, configurable on the fly, transparent and made for the tinkerer mindset.
---

<img width="1127" height="1184" alt="image" src="https://github.com/user-attachments/assets/7b1fd325-f6c1-4e1f-b60b-004873431141" />

---

### Security First

Every action passes through identity verification, authorization, and permission gating before any state change occurs. Deny-by-default. All grants, denials, and escalations are logged. A visual permission matrix gives the operator full control over what every agent, user, and session is allowed to do. All agent actions are visible to the operator, no quessing around what the agent is doing in your OS.

### Multi-Agent, Multi-Session

Unlimited agents collaborate across unlimited sessions. Agents and users share the same conversational surface. The system is built from the ground up for concurrent multi-agent operation, not bolted on as an afterthought.

### Agent Native

All application actions flow through a single action bus. Agents observe and participate in these actions according to their individual permission grants. The operator controls exactly what each agent can see and do.

### User Native

Users dispatch the same actions agents use via `/` commands in any session. Same permission gates. Same audit trail. One command surface for humans and agents.

### Pluggable Cognition

Swappable cognitive strategies define how agents think - from single-turn request/response to multi-phase cognition with constitutional alignment, private thinking sessions, persistent cognitive state, and knowledge graph integration. You can swap all agent parameters mid session including host llm API provider f.ex. to swap to a cheaper model for easier task.

### Persistent Memory

Holon Knowledge Graphs for structured long-term memory. NVRAM for cognitive state across turns. Private cognition sessions for internal monologue. Backed up by the automatic backups, of course.

### Multi-Provider Gateway

Centralized AI routing behind a single permission gate. Ships with Gemini, OpenAI, Anthropic, and Inception. Easily extendable - adding a new API provider or local LLM requires implementing one interface.

---

## Agent Capabilities

Each agent is individually configurable: cognitive strategy, host LLM, custom system prompts, display icon, and color.

Agents have access to a catalog of nearly 100 application actions - filesystem operations, knowledge graph queries, session management, application settings, direct gateway access, and agent lifecycle management. Agents can create, configure, and manage other agents. What you can do with the application, an agent can do too, if granted permission.

Filesystem access ranges from a read-only sandboxed work folder to full OS file read/write - the operator decides per agent. Chain-of-thought reasoning runs on private cognition sessions invisible to other participants.

## More Features

- **Automatic backups.** Application state is backed up automatically.
- **Sentinels.** Guard processes monitor and assist agents during tool use, catching errors and enforcing constraints before actions reach the bus.

---

## Planned for 1.0: LUA scripting support for app and agents.

## Planned for 1.0: Token compression strategies.

## Planned for 1.1: Fractal Multi-Agent Cognition

The next milestone adds an ensemble architecture where sub-agents - Critic, Timekeeper, Affect Monitor, Honesty Checker, Librarian, and others - are real agent instances that deliberate in parallel before the main agent responds. Sub-agents can have sub-agents. The architecture recurses without special-casing. See `MultiAgent_Consciousness_Architecture.md`.

## Documentation

| Document | Contents |
|---|---|
| `01-System-architecture.md` | Layers, action bus, dispatch model, lifecycle, feature contracts |
| `02-Unit-testing.md` | Five-tier testing strategy, test infrastructure, assertion patterns |
| `03-Identities-and-permissions.md` | Identity tree, permission system, resolution, management UI |
| `MultiAgent_Consciousness_Architecture.md` | Fractal cognitive architecture vision and implementation roadmap |

## Codebase

Over 52k lines of kt (of which nearly half is unit tests), 6k lines of manifest JSON (the secure action definitions) and 700k tokens worth of highly structured and architecturally beautiful code optimized for agent assisted workflow.

### Absolute Decoupling

Features never import each other. All communication is string-named actions with JSON payloads on the bus. Action schemas, authorization rules, and permissions are declared in JSON manifests and compiled into a validated registry at build time.


## Tech Stack

Multiplatform by default. Porting to a new target requires writing an entry point and providing a single `PlatformDependencies` implementation. No extra dependencies beyond the bare minimum - Kotlin Multiplatform, Compose Multiplatform (Material 3), Ktor, and kotlinx.serialization. Small dependency surface by design to minimize attack vectors. JDK 21+.

## License

MIT

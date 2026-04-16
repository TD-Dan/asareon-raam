# Asareon Raam

A multi-platform runtime for hosting autonomous AI agents. Kotlin Multiplatform, Compose Multiplatform.

> 1.0.0-alpha · Desktop (Windows) released. Linux, macOS, Android, iOS, WebAssembly planned.

Secure by design, flexible, configurable on the fly, transparent and made for the tinkerer mindset.

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Version](https://img.shields.io/badge/version-1.0.0--alpha-orange.svg)
![Platform](https://img.shields.io/badge/platform-Windows-0078D6?logo=windows&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-21%2B-ED8B00?logo=openjdk&logoColor=white)
![Lua](https://img.shields.io/badge/Lua-scripting-2C2D72?logo=lua&logoColor=white)

**Supported LLM providers:**

![Anthropic](https://img.shields.io/badge/Anthropic-D97757?logo=anthropic&logoColor=white)
![OpenAI](https://img.shields.io/badge/OpenAI-412991?logo=openai&logoColor=white)
![Gemini](https://img.shields.io/badge/Google%20Gemini-886FBF?logo=googlegemini&logoColor=white)
![Ollama](https://img.shields.io/badge/Ollama-000000?logo=ollama&logoColor=white)
![MiniMax](https://img.shields.io/badge/MiniMax-F23B5F)
![Inception](https://img.shields.io/badge/Inception%20Labs-111827)

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

Centralized AI routing behind a single permission gate. Ships with Anthropic, OpenAI, Google Gemini, Inception Labs, and MiniMax as cloud providers, plus Ollama for local inference on your own hardware. Easily extendable — adding a new API provider or local LLM requires implementing one interface.

### Lua Scripting

Scripts are first-class citizens on the action bus, not a bolted-on automation layer. A script is an identity with permissions, and the actions it can dispatch are exactly the actions its permissions allow — the same gating model as agents and users.

Two modes are supported. **App Scripts** run with their own identity in the `lua.*` namespace, subscribe to bus events, and can automate or observe anything their permissions permit. **Strategy Scripts** replace an agent's cognition entirely: the `on_turn(ctx)` hook intercepts the pipeline after context assembly, giving the script full control to modify the system prompt, return a direct response, or abort the turn. The same script can't do both at once — you pick the mode per script.

Embedded Lua 5.2 via LuaJ on Desktop (JVM). Sandboxed — no direct filesystem, no `os`/`io`/`require`, no bytecode injection. All I/O goes through the action bus, where the normal permission gates apply. Currently at preliminary level; scope will expand toward 1.0.

### Token Compression

Built-in strategies to reduce system prompt overhead: TOON-style encoding, abbreviations, terse action descriptions, and compact message headers. Turn them on selectively to trade verbosity for context budget, or leave them off for maximum legibility in logs.

---

## Agent Capabilities

Each agent is individually configurable: cognitive strategy, host LLM, custom system prompts, display icon, and color.

Agents have access to a catalog of nearly 100 application actions - filesystem operations, knowledge graph queries, session management, application settings, direct gateway access, and agent lifecycle management. Agents can create, configure, and manage other agents. What you can do with the application, an agent can do too, if granted permission.

Filesystem access ranges from a read-only sandboxed work folder to full OS file read/write - the operator decides per agent. Chain-of-thought reasoning runs on private cognition sessions invisible to other participants.

---

## Even More Features!

- **Automatic backups.** Application state is backed up automatically.
- **Sentinels.** Guard processes monitor and assist agents during tool use, catching errors and enforcing constraints before actions reach the bus.

---

## Planned for 1.1: Fractal Multi-Agent Cognition

The next milestone adds an ensemble architecture where sub-agents - Critic, Timekeeper, Affect Monitor, Honesty Checker, Librarian, and others - are real agent instances that deliberate in parallel before the main agent responds. Sub-agents can have sub-agents. The architecture recurses without special-casing. See `MultiAgent_Consciousness_Architecture.md`.

## Documentation

| Document | Contents |
|---|---|
| `01-System-architecture.md` | Layers, action bus, dispatch model, lifecycle, feature contracts |
| `02-Unit-testing.md` | Five-tier testing strategy, test infrastructure, assertion patterns |
| `03-Identities-and-permissions.md` | Identity tree, permission system, resolution, management UI |
| `04-Lua-scripting.md` | Embedded Lua 5.2 runtime, `raam.*` API, sandbox model, lifecycle hooks, app/strategy script modes |
| `05-External-strategy-protocol.md` | Action-based protocol for registering external cognitive strategies |
| `MultiAgent_Consciousness_Architecture.md` | Fractal cognitive architecture vision and implementation roadmap |

## Codebase

Over 52k lines of kt (of which nearly half is unit tests), 6k lines of manifest JSON (the secure action definitions) and 700k tokens worth of highly structured and architecturally beautiful code optimized for agent assisted workflow.

### Absolute Decoupling

Features never import each other. All communication is string-named actions with JSON payloads on the bus. Action schemas, authorization rules, and permissions are declared in JSON manifests and compiled into a validated registry at build time.


## Tech Stack

Multiplatform by default. Porting to a new target requires writing an entry point and providing a single `PlatformDependencies` implementation. No extra dependencies beyond the bare minimum - Kotlin Multiplatform, Compose Multiplatform (Material 3), Ktor, and kotlinx.serialization. Small dependency surface by design to minimize attack vectors. JDK 21+.

## License

MIT

# Raam Android — Port Draft

*Status: early draft. Captures platform-level concerns only. Strategy-layer concerns (sub-agent rosters, notification semantics, trigger interpretation) live in their own documents.*


## 1. Vision

Raam Android is the always-with-you version of Raam. The desktop is request-response; the phone is persistent. The architectural shift is twofold:

- **From foreground to background.** The runtime survives across user sessions and acts on its own scheduled and sensor-driven activations.
- **From scratch space to canonical store.** The workspace folder becomes the source of truth, and platform apps (Calendar, Tasks, Mail) become views and sinks that the agent feeds.

The Android port itself is a platform concern. *What the agent does with that platform* — the prosthetic-companion use case, the curated roster, the proactive scheduling and escalation behavior — lives in the Companion Strategy document. This document defines only what the platform offers.


## 2. Architectural Continuity

The Kotlin Multiplatform foundation pays off here. The architectural cost of the port is lower than it looks.

### 2.1 What Ports As-Is

- **Store / Feature / Action bus.** Pure Kotlin, no platform deps. Unchanged.
- **GatewayFeature and all providers.** Anthropic, OpenAI, Gemini, Inception, MiniMax, Ollama. HTTP via Ktor (multiplatform), JSON via kotlinx.serialization. Unchanged.
- **KnowledgeGraphFeature** (Holon Knowledge Graph).
- **BackupFeature**, **CommandBot**, **BlockSeparatingParser**, **ContextCollapseLogic**.
- **Lua scripting** — JNI binding may need an Android-compatible Lua build, but the integration surface stays the same.
- **Compose UI** — most composables port with layout adjustments.
- **`FakePlatformDependencies` and the test suites (T1, T2)** — already proves the platform layer is well-factored. The Android port is essentially writing a third `PlatformDependencies` impl alongside the JVM desktop one and the test fake.

### 2.2 What Needs a New Implementation

- **`PlatformDependencies`** — Android-specific impl. File system access via `Context.filesDir`, content providers, system services, notification manager, foreground service binding.
- **Workspace folder location** — Android scoped storage rules apply. App-private storage is simplest but invisible to other apps and gone on uninstall. SAF (Storage Access Framework) is the official answer but has rough edges. `MANAGE_EXTERNAL_STORAGE` is powerful but Play Store-restricted. Pick one for v1, accept rework later.
- **WorkspacePane** — folder navigation needs to respect Android's storage abstractions, not assume a desktop file picker.

### 2.3 What Goes Away

The OS handles it:

- Custom Windows title bar, composable slot pattern for window decorations
- `WindowsSnapHelper` JNA subclass for native drag/snap
- `BootConfig.kt` for pre-Compose window dimension persistence
- Boot console window-sizing concerns (a simpler diagnostic surface might still be useful for first-run)


## 3. The Canonical Data Layer

The desktop workspace folder is currently a passive scratch space. On Android the platform commitment is that it becomes the source of truth. Platform apps become views and sinks fed via system providers.

The platform's job stops at three things:

- The workspace folder exists and is canonical.
- System providers expose `read` / `write` / `observe` primitives over typed paths.
- Per-path sync configuration is exposed as a knob; the actual policy is set by the agent.

What lives in the folder is agent territory. One strategy might lay out typed directories; another might use a single event log; another might use a SQLite file. The platform does not care and does not need to.

The product-level payoff: one place for the agent to look, runs fully local, personal data never leaves the device unless the user explicitly grants sync to a specific sink.


## 4. SystemProvider — The OS-Side Abstraction

A new provider category parallel to `UniversalGatewayProvider`. Where the gateway abstracts LLM backends, `SystemProvider` abstracts OS-side integrations.

```kotlin
interface SystemProvider {
    val id: String
    val capabilities: Set<Capability>  // READ_CALENDAR, WRITE_CALENDAR, READ_MAIL, ...
    suspend fun read(request: SystemReadRequest): SystemReadResponse
    suspend fun write(request: SystemWriteRequest): SystemWriteResponse
    fun observe(filter: SystemObserveFilter): Flow<SystemEvent>
}
```

### 4.1 Initial Provider Set

| Provider | Surface | Notes |
|---|---|---|
| `AndroidCalendarProvider` | Calendar Provider content URI | Bidirectional. Most-used. |
| `AndroidTasksProvider` | Google Tasks API or local DB | API is rate-limited; local-first with sync may be saner. |
| `AndroidMailProvider` | Gmail API via `AccountManager` | Read-only initially. |
| `AndroidContactsProvider` | Contacts content URI | For person-tied triggers. |
| `AndroidLocationProvider` | Location services | Geofence-based context. |
| `AndroidNfcProvider` | NFC reader | NFC-tap context. |

### 4.2 Permissions Model

Every `SystemProvider` capability is a separate runtime permission grant. Permission grants are durable and visible in app settings; the user can revoke any capability at any time. The platform enforces; whatever sub-agent or check the strategy uses to gate cross-boundary writes lives in the strategy.

### 4.3 Existing Architecture Fit

The action registry is already the choke point. A new action category — `SYSTEM_READ`, `SYSTEM_WRITE` — slots in alongside existing categories. The `correlationId` pattern from BackupFeature applies cleanly to async system operations. JSON manifests for typed actions extend naturally.


## 5. Triggers and Sensor Context

The trigger primitive boils down to three forms: **set-time**, **periodic**, **conditional**. A single `core.set_trigger` action handles all three. No separate Feature; this is small enough to live in core.

```kotlin
core.set_trigger(
    when = TriggerSpec.AtTime(...) | TriggerSpec.Period(...) | TriggerSpec.Condition(...),
    action = ActionRef
)
```

Backed by `AlarmManager.setExactAndAllowWhileIdle()` for real deadlines and `WorkManager` for tolerant scheduling.

The richer activation source is **sensor context fed directly into the agent's context window**: current location, current time, NFC tags read, calendar state, ambient signals. The platform surfaces these; the agent reasons over them and decides what they mean.

This is deliberately under-engineered. Trigger semantics, escalation logic, what counts as "user is at home" — all strategy territory. The platform offers a primitive and a context-injection mechanism. Everything else is emergent from the agent's reasoning over those signals.


## 6. Cost & Local-First

The mobile cost budget makes the cheap-models principle from the multi-agent architecture document essential rather than optional. Concretely, the platform should make it easy to:

- Route most analyser-tier sub-agent calls to **local Ollama** over the user's local network, or to on-device inference once viable.
- Reserve the flagship cloud model for the main agent's planning step.
- Schedule expensive work (Dreamer, Daydreamer, batch reflection) for when the device is on Wi-Fi and charging.

Beyond making this *possible* (the gateway already supports per-call provider selection), the specific tier assignments per sub-agent are strategy concerns.

The platform-level pitch: most cognitive volume runs on-device or on the user's local network. The data lives in the user folder. The cloud is invoked only for the planning turn — and even that can be configured against a self-hosted model. The "own-your-data" story is real on this architecture, not retrofitted.


## 7. Platform Concerns

### 7.1 Foreground Service

Required for the persistent runtime. User-visible notification ("Raam is running"), explicit permission, exemption from background restrictions.

### 7.2 Doze Mode and App Standby

Android aggressively kills background work. Use `setExactAndAllowWhileIdle` for real deadlines; everything else uses `WorkManager` and accepts that it may fire late.

### 7.3 Battery Impact

Persistent foreground service + scheduled triggers + occasional model calls will appear in the battery usage screen. The user needs to see Raam in the top 5 battery consumers and not uninstall it. Mitigations: aggressive use of cheap/local models, tight scheduling discipline, clear "what Raam is doing" surfacing in the app.

### 7.4 Permission Gauntlet

Calendar, Contacts, Notifications, Foreground Service, Schedule Exact Alarms, Location (optional), NFC (optional), Storage. Each is a runtime permission request. First-run flow needs staged onboarding — install with minimum permissions, grant additional capabilities as the user opts into specific features.

### 7.5 Notification Channels

The platform exposes a small fixed set of channels with different priority/interruption profiles. Which channel a given notification fires on is a strategy decision. The platform's job is just to provide channels that map cleanly to Android's `NotificationChannel` priorities.


## 8. Sequencing

This is the strategic question and it has no clean answer.

### 8.1 The Tension

- **Linux-first** is the current plan (per `linux_release_preparation.md`). It unlocks the high-visibility launch channels (Show HN, r/LocalLLaMA) and is a much smaller build than Android.
- **Android-first** is the version Daniel would actually use daily. Dogfooding pressure usually beats launch-prep in side projects, and the executive function prosthesis use case is genuinely valuable to the author.

### 8.2 Proposed Sequencing

1. **Ship Linux as minimum-viable** (per existing prep doc). Don't gold-plate.
2. **Soft-launch on Kotlin ecosystem channels first.** Validate the architecture is presentable. Don't promote to high-visibility AI channels yet.
3. **Pivot to Android port as primary focus** while desktop is in soft-launch validation.
4. **Use Android dogfooding to drive desktop improvements.** Drive System tuning, sub-agent behavior under real load — most lessons flow back to desktop.
5. **High-visibility launch is gated on both Linux and Android being stable enough to demo.** The Android version is what makes the pitch interesting — "executive function prosthesis with own-your-data architecture" is sharper than "agent platform."


## 9. Open Questions

1. **Workspace folder location.** App-private vs. SAF vs. `MANAGE_EXTERNAL_STORAGE`. Trade-offs in §2.2. Pick one for v1.

2. **Sync conflict resolution.** Per-path policies are exposed as a platform knob, but the actual reconciliation algorithm needs implementing. CRDT-lite, vector-clock, or simple last-write-wins per path? Build the simplest first, refine on observed failures.

3. **Foreground service vs. periodic wake.** Always-on foreground service is simpler architecturally but has battery and user-perception costs. Periodic wake (every N minutes) is cheaper but loses real-time responsiveness for sensor-context-driven activation.

4. **On-device model viability.** Local-network Ollama is the right v1 answer. On-device LLM inference on Android is improving but rough at the size needed for analyser sub-agents. v2 concern.

5. **The desktop-Android sync story.** If desktop is the authoring/admin tool and Android is the always-on companion, they need to share workspace data. Cloud-stored workspace folder (Dropbox, self-hosted Nextcloud) is the obvious answer; doing it without cloud is harder. Probably acceptable to require user-chosen sync provider for v1.

6. **First-run diagnostic surface.** Boot console logs were useful on desktop. Android equivalent? Probably a simpler "last boot status" screen rather than a streaming console.

7. **Lua on Android.** Existing Lua integration assumes JVM. Native Android Lua availability needs verification before committing the App Scripts and Strategy Scripts story to v1.

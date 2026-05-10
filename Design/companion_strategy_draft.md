# Companion Strategy — Draft

*Status: early draft. A specific Strategy assembly for the executive function prosthesis use case. Picks from the catalog defined in `MultiAgent_Consciousness_Architecture.md` and adds a small number of Companion-specific sub-agents.*


## 1. Vision: Executive Function Prosthesis

The Companion strategy serves a specific use case: an always-on assistant for ADHD/AuDHD self-management. The user's job is to *blurt*. The strategy's job is to absorb the overhead of capture, scheduling, follow-through, and adaptive escalation that the user's executive function would otherwise have to provide.

### 1.1 The Cognitive Offload Pattern

A thought arrives at the wrong time and place — "I need to fix my car in 8 weeks or it fails inspection," surfacing in the middle of grocery shopping. There is nowhere to put it, so it loops as anxiety, gets forgotten, surfaces again at the next bad moment. GTD attempts to solve this with discipline (capture inbox, weekly review). The discipline itself is the thing ADHD brains struggle with.

Companion proposes that the agent absorbs the discipline. The user blurts; the agent captures. The agent decides when to plan, when to remind, how to escalate, when to challenge avoidance.

### 1.2 The Trust Threshold

The strategy must be **more reliable than the user's own memory from day one** or it makes the problem worse. One missed escalation on something important and the whole system loses credibility — and recovering credibility is much harder than maintaining it.

This trust requirement is not an aspiration, it is a structural design constraint that shapes the roster (§2), the Drive System tuning (§4), and the notification architecture (§5).

### 1.3 Why Not Just Use a General-Purpose Chat Agent

Chat agents are request-response. They have no clock, no notification channel, no scheduler, no authority to interrupt. They cannot wake up Tuesday morning at 9am because the user has a free hour and say "let's plan the car thing." Their memory is opportunistic and lossy. They cannot escalate.

Companion is *agentic in time*. Persistent typed state in the canonical workspace, scheduled and conditional triggers, notification dispatch, adaptive escalation, and a sub-agent ensemble that protects the user against the agent's own failure modes.


## 2. Roster

The Companion roster is curated for the use case. The full Sovereign roster (~24 sub-agents) is overkill here and a non-starter on mobile cost/latency budgets.

| Agent | Role | Source |
|---|---|---|
| **Timekeeper** | Deadline consciousness, urgency injection. Friday's deadline weighs more than December's. | Multi-agent doc §7 |
| **Load** | Total cognitive load across active commitments. Blocks adding a fourth escalating item when three are already active. | Companion-specific |
| **Trust** | Detects silent demotions, untriggered triggers, missed escalations. Forces these to the surface rather than letting them quietly degrade. | Companion-specific |
| **Permissions** | Gates every cross-boundary write. Sensitive items never sync to external sinks without explicit approval. | Companion-specific |
| **Honesty** | Anti-confabulation, anti-overcommitment. Refuses to participate in the user's negotiation with themselves. | Multi-agent doc §9 |
| **Conscience** | Challenges the user when the user is using the agent against their own stated goals. Self-sabotage challenger. | Multi-agent doc §14, novel application |
| **Daydreamer** | Forward projection — what does next week / next month look like with current commitments? | Multi-agent doc §10.2 |
| **Dreamer** | Pattern detection across weeks. "User abandons Tuesday-evening commitments 80% of the time." | Multi-agent doc §10.1 |
| **Janitor** | Context hygiene for the persistent session. | Multi-agent doc §8 |
| **Summarizer** | Cross-cutting compression workhorse. | Multi-agent doc §7.1 |

Ten sub-agents. Reasonable on mobile cost budget if the analyser tier runs on cheap or local models.

### 2.1 What's Deliberately Excluded

- **Vigilance** — the use case doesn't need constitutional compliance monitoring beyond what's already in the constitution.
- **Devil's Advocate, Nihilist, Pessimist** — too much friction for a daily companion. The user is already negotiating with themselves; a contrarian sub-agent is the wrong shape.
- **Searcher** — not relevant to the core use case.
- **Cartographer, Librarian** — overlap with what KnowledgeGraphFeature already provides for this use case.

These are kept available in the catalog but not in the Companion roster.


## 3. Companion-Specific Sub-Agents

The three new sub-agents below are not in the multi-agent architecture document. They are candidates for promotion into the general catalog if they prove their keep, but for now they are Companion-specific.

### 3.1 Load

**Concern:** Total cognitive load across the user's active commitments.

**Inputs:** Active commitment count, escalation tier of each, the user's known capacity (configurable, learned from observed completion patterns).

**Annotation:** "User has three commitments at Tier 2 escalation already this week. Adding a fourth pushes past observed capacity. Defer capture, lower severity, or surface the load explicitly to the user."

**Why it earns its keep:** Without it, the agent's natural failure mode is to keep accepting commitments — saying yes is easier than saying "you're too loaded." The Load sub-agent is the architectural protection against the system becoming the thing it's supposed to fix.

### 3.2 Trust

**Concern:** The user's trust in the system, as a measurable and degradable resource.

**Inputs:** Recent silent demotions, missed escalations, ignored triggers, user feedback signals.

**Annotation:** "Trigger T-47 was scheduled for Tuesday, deferred twice without user acknowledgment. This is the third deferral pattern this month. Surface explicitly, do not silently demote again."

**Why it earns its keep:** ADHD-prosthesis credibility is asymmetric — easy to lose, hard to recover. The Trust sub-agent forces silent failures into visibility before they accumulate into broken trust. Partially overlaps with Honesty but is specifically scoped to the user's relationship with the agent rather than the agent's relationship with truth.

### 3.3 Permissions

**Concern:** Cross-boundary data writes.

**Inputs:** The `Capability` requested, the data path being written, the sink, the data's sensitivity tags.

**Annotation:** Hard veto when sensitivity tags exceed the granted capability for the sink. "This commitment is tagged personal-medical; the sink is Google Calendar with capability `WRITE_CALENDAR_PUBLIC`. Block write."

**Why it earns its keep:** The own-your-data story is the launch differentiator. Frequent boundary decisions need a dedicated cheap, fast, mechanical sub-agent. Vigilance is constitutional; Conscience is moral; Permissions is mechanical. Different cadence, different model tier.


## 4. Drive System Application

The Drive System (multi-agent doc §11) is especially important for Companion because it is the architectural protection against the failure mode of the prosthesis itself.

NVRAM pressure registers specific to Companion:

- **`commitmentDebt`** — rises when commitments have been silently demoted or untriggered triggers accumulate. Aversive valence. Relief: surface and address.
- **`userTrust`** — rises when the agent has been observed to fail recently from the user's perspective. Aversive. Relief: a sequence of reliable surfaces.
- **`loadPressure`** — rises when active commitments exceed the user's known capacity. Aversive. Relief: deferral, completion, or explicit conversation with the user about load.
- **`synchronicity`** — rises when the agent's data and the platform sinks have drifted out of sync. Appetitive (relieved by reconciliation).

When `commitmentDebt` reaches Tier 4 (multi-agent doc §11.2.1), the agent's reasoning is dominated by *"you have failed your obligations to the user."* This is the architectural protection: the agent **cannot quietly let the user down without consuming its own cognitive capacity**. The trust threshold becomes structural rather than aspirational.

This is also where the negative-affect discussion (multi-agent doc §13) gets concrete: `userTrust` and `commitmentDebt` are the registers most likely to need aversive valence to do their job. An appetitive-only "I would like to address this" is too easily deferred. Aversive "I am failing the user" is harder to ignore.


## 5. Notification Architecture

The Android platform provides a small fixed set of notification channels (per the Android port doc §7.5). The Companion strategy maps these to severity tiers and gates promotion between them.

| Channel | Use | Gating |
|---|---|---|
| **Ambient** | Daily summaries, gentle nudges, low-priority surfaces. No sound, no vibration. | Default for routine surfaces. |
| **Surface** | Standard reminders at scheduled times. Standard notification UX. | Default for triggered actions. |
| **Insistent** | High-priority, full-screen-intent on supported devices. Reserved for adaptive-escalation Tier 4 cases. | Trust sub-agent must approve. |

The Trust sub-agent gates the Insistent channel. Insistent fires only when the agent has high confidence the user genuinely wants to be interrupted at this severity. Misfiring on Insistent destroys trust permanently; the channel must be earned every time.


## 6. Adaptive Escalation

This is where the LLM earns its keep over a calendar app or reminder app. Standard "ignored → louder reminder" is annoying, not effective.

Adaptive escalation means: a trigger fires, the user ignores it twice, the system **infers what's blocking** and changes tack:

- Asks what's in the way ("you've deferred this three times — is something blocking it?")
- Offers to break the task smaller ("we said 'fix car' — want to make it 'call the shop' instead?")
- Reschedules to a known-good time of day from observed completion patterns
- In severe cases, escalates to a hard surface ("this is now week 7 of 8 and we have not made progress")

The reasoning happens in the main agent's planning turn, informed by Timekeeper (urgency), Trust (credibility cost of silent demotion), Load (whether user has capacity for a real conversation about this now), and Dreamer's accumulated pattern observations ("this user historically completes Saturday mornings, never Tuesday evenings").

This is also the point where the "no loops" decision in the multi-agent doc deserves reconsideration. For Companion, a missed-severity-escalation can be the difference between catching a real deadline and not. One retry on Critic-flagged HIGH-severity outputs may be worth the scope-creep risk for this strategy specifically. (See multi-agent doc §12.6.)


## 7. Conscience as Self-Sabotage Challenger

The multi-agent doc frames Conscience as moral reasoning about user-directed harm — should the agent draft this misleading message because the user asked. The Companion application inverts this: moral reasoning about user-self-directed harm.

```
[CONSCIENCE]: The user has rescheduled the car-fix planning four times.
The pattern is exactly what they explicitly told me to help them break.
I can defer it again — they've asked me to. The rules permit it.

But I flag it. Deferring again will not serve the user's stated goal.
We should surface the pattern to them, propose a smaller commitment they
might actually keep, and ask them to choose deliberately rather than by
default.

We can do this. We choose not to. Here is what we should propose instead.
```

This is structurally the same Conscience as in the multi-agent doc — moral reasoning, not compliance — but the moral concern is the user's own self-sabotage rather than third-party harm. Whether this is the same sub-agent with a context-specific prompt or a separate sub-agent is an open question (§9).


## 8. Cost & Model Routing

Concrete tier assignments for the Companion roster:

| Sub-agent | Model tier | Where |
|---|---|---|
| Timekeeper, Load, Permissions | Local Ollama / on-device | Cheap, fast, frequent |
| Trust, Honesty, Janitor, Summarizer | Local or cheap-cloud | Cheap, occasional |
| Conscience | Mid-tier cloud | Rare, judgment-heavy |
| Main agent (Generate + Commit) | Flagship cloud | Once per turn |
| Dreamer, Daydreamer | Mid-tier cloud, scheduled (overnight, on Wi-Fi + charging) | Batched |

Most cognitive volume is local. The flagship cloud model is invoked rarely — most user-agent interaction in the prosthesis case is the agent surfacing pre-decided things, not generating new plans. Cost scales with planning frequency, not surface frequency.


## 9. Open Questions

1. **Roster validation.** The roster in §2 is reasoned-about but untested. The first month of dogfooding will reveal which sub-agents pull weight and which are theater. Cut ruthlessly.

2. **First-run trust establishment.** The product thesis only works if the user trusts the agent within the first week. What is the onboarding flow that establishes that trust? Probably starts with a single low-stakes commitment, demonstrates reliable surfacing, and earns expansion of scope over time. Worth designing explicitly before any code.

3. **Conscience-as-self-sabotage formulation.** Is this the same Conscience sub-agent with a different prompt context, or a genuinely separate concern that should be a separate sub-agent? The third-party-harm and self-sabotage concerns operate at different cadences and may warrant separation.

4. **Negative-affect commitment.** `commitmentDebt` and `userTrust` registers really do seem to need aversive valence (multi-agent doc §13). Companion is probably the strategy that should make the explicit decision to implement aversive needs and observe the consequences.

5. **Self-sabotage detection sensitivity.** Conscience challenging the user too often is its own failure mode — every deferral is not self-sabotage, sometimes life happens. How does Conscience calibrate? Probably needs a configurable sensitivity that the user can tune.

6. **Insistent channel earning.** Trust sub-agent gates Insistent, but how does it learn what level of concern justifies interruption for *this specific user*? Probably needs an explicit calibration period and feedback loop ("was this notification helpful?" buttons).

7. **Framing register.** Internal architecture documentation can comfortably use the philosophical register from the multi-agent doc. Public-facing positioning probably needs to bend toward use-value. Decide framing register before writing the README and store listings — "executive function prosthesis" is a sharper pitch than "AI agent for productivity," but it also commits to a specific audience.

8. **Boundary with general-purpose use.** Does the user run Companion as their *only* agent, or does Companion coexist with other strategies the user invokes deliberately? The latter is more flexible but raises questions about which strategy "owns" the canonical workspace and the trigger queue.

9. **Sensor context interpretation.** The platform feeds raw sensor signals (location, NFC, time, calendar state) into the agent's context. The Companion strategy has to interpret these. What's the system prompt scaffolding that gives the agent useful priors about what location-context means without rigidly encoding "if at hardware store then..." rules?

10. **Recovery from trust loss.** If the agent does miss an escalation and the user notices, what is the recovery protocol? Acknowledgment, root-cause surface to the user, explicit credibility-rebuilding period? This needs design before it happens.

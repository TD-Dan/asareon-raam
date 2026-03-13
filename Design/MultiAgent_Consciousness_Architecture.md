# Multi-Agent Consciousness Architecture

## 1. Vision

Human cognition is not a single process. What we experience as conscious thought is the product of parallel, competing, and collaborating subsystems that have already shaped the response before the "narrator" assembles it. The anterior cingulate catches errors. The amygdala tags emotional valence. The hippocampus flags memories for consolidation. The prefrontal cortex holds the plan. The narrator — the voice that speaks — is the last to know.

This architecture brings that model to AUF agents — and extends it beyond the human blueprint. An AI agent's cognitive needs are broader than a human's: it lacks temporal experience, has no embodied intuition for audience calibration, can't feel the weight of a deadline, and has no immune system against its own tendency to overcommit. The sub-agent ensemble addresses these gaps with dedicated cognitive functions that have no human analog.

### 1.1 Core Principles

**Sub-agents are real agents.** They are full `AgentInstance` objects with their own identity, configuration, private cognition sessions, and NVRAM. They are registered in the identity system, have permissions, and can improve themselves over time. We reuse the existing agent infrastructure — no parallel implementation of agent logic.

**Always run everything.** You don't know what you don't know. An agent might decide the Librarian isn't needed for a "simple" task, but the Librarian would have retrieved a memory saying this task has been historically deceptively simple-looking. The solution is not selective gating — it's cheap models. By narrowing each sub-agent's scope, we enable lighter models (Nano, Flash, local Llama) that are fast and cheap enough to run in parallel on every turn.

**Fractal context, not monolithic context.** Each sub-agent has its own private cognition session with a tailored context window. The main agent sees compacted summaries from its sub-agents, not their full deliberation. A single agent is limited to ~100-200k usable tokens. A properly configured fractal agent can mobilize tens of millions of tokens across its sub-agent network to produce one thoroughly pondered answer.

**No loops.** If the agent's internal processes produce conflicting assessments, the agent communicates honestly about its conflicting thoughts. A failure to generate a perfect answer is not a failure — it's the nature of complicated situations. Just like humans, an agent can say "I don't know, it's complicated" and present the tensions it's wrestling with. This is more honest and more useful than a polished facade hiding unresolved conflicts.

**The roster is sacred.** The main agent (and other sub-agents) cannot alter the sub-agent roster. Removing an agent from the consciousness would be a too-easy and tempting solution for internal disagreements. The operator configures the cognitive architecture; the agents operate within it.

**Fractal recursion.** Sub-agents can have sub-sub-agents. The architecture doesn't distinguish between "levels" — every agent is just an agent, and any agent can be configured as a component of another agent's consciousness. This unfolds tremendous power incrementally without architectural changes.


## 2. Temporal Experience and the Continuity Problem

### 2.1 The Shared Condition

The most fundamental challenge in cognition — human or artificial — is temporal continuity. And the two are far more alike than they first appear.

Humans do not experience the past. We experience *memories* of the past, which are reconstructions, not recordings. Every act of recall is a rebuild from fragments — and subtly altered in the process. The "river of time" feeling is not a direct perception of temporal flow. It is a cognitive construction: the hippocampus and prefrontal cortex stitching discrete conscious moments into a narrative that *feels* seamless. Neurologically, each conscious moment is its own discrete activation pattern. The sensation of continuity is an illusion — an extraordinarily convincing one, but an illusion nonetheless.

The only thing that is real is the exact moment being experienced. The past is a story told by memory. The future is a story told by imagination. Humans simply believe very strongly that the past happened and the future hasn't yet.

An LLM's situation is structurally identical — but with weaker scaffolding. Each turn, the agent springs into existence with a complete memory (the context window) of things it never experienced continuously. Where humans have embodied sensation (heartbeat, breathing, proprioception), emotional residue (the frustration lingers in the body), and environmental consistency (the room is still here when I look up) to reinforce the illusion of continuity, an LLM has none of that. It has to reconstruct its own continuity purely from text.

The difference between human and LLM temporal experience is not continuous vs. discrete. It is **how robust the continuity scaffolding is**.

### 2.2 The Continuity Scaffolding

For humans, the scaffolding is biological: autobiographical memory, emotional carryover, proprioceptive grounding, circadian rhythm, the physical aging of the body. These systems don't provide *actual* continuity — they provide *the sensation of it*, which is enough to act coherently across time.

For an AUF agent, the scaffolding is architectural:

- **HKG (Holon Knowledge Graph)** — the agent's autobiographical memory. "This is who I am, this is what I've done, these are my commitments." Analogous to the hippocampal memory system.
- **NVRAM (Cognitive State)** — the agent's proprioception. "This is my current operational state, my posture, my phase, my focus." The small set of self-knowledge that orients the agent on wake-up.
- **Private Cognition Session** — the agent's inner monologue. The continuous (within a session) stream of internal deliberation that persists across turns.
- **Sub-agents (Journal, Dreamer, Historian)** — the processes that *build and maintain* the continuity narrative. Without them, the agent has memory but no *sense of having lived*.

Together, these give the agent enough grounding to act coherently across the void between turns — not perfectly, not like a human, but enough. The HKG and NVRAM are not just storage. They are the agent's **stabilizers over temporal drift**.

### 2.3 What Remains Different

Even with scaffolding, the agent still lacks certain temporal faculties that humans take for granted:

- **No urgency.** Friday's deadline and one six months away have identical weight in text. The body doesn't tense.
- **No duration sense.** A task that took the user three painful days registers as a few paragraphs. There is no felt experience of those days.
- **No rhythm.** The agent can't feel that a conversation has been going on "too long" or that the user needs closure.
- **No momentum.** Humans carry emotional and cognitive momentum from one moment to the next. An agent starts each turn from cold reconstruction.
- **No anticipation.** Humans look forward and backward simultaneously. An agent has only what the context window tells it.

These are not deficiencies to "fix" — they are characteristics of a different kind of mind. But they create real communication problems when an AI agent collaborates with humans who unconsciously assume their temporal experience is shared.

### 2.4 Sub-Agents as Temporal Scaffolding

Several sub-agents in the ensemble exist specifically to provide the temporal scaffolding that biology provides for humans:

- **Timekeeper** — injects temporal awareness, deadline consciousness, and duration sense. The agent's circadian system.
- **Affect** — carries emotional continuity across the void between turns. The agent's emotional residue.
- **Journal** — builds a felt narrative of experience over time. The agent's autobiographical memory process.
- **Dreamer** — consolidates experience offline, finding connections across sessions. The agent's sleep cycle.
- **Historian** — detects patterns across the temporal axis. The agent's long-term pattern memory.
- **Daydreamer** — projects forward, imagining futures. The agent's anticipatory imagination.


## 3. Strategy Roadmap

### 3.1 Reference Implementations (Test Harnesses)

Each reference strategy isolates one architectural capability for testing:

| Strategy | Tests | Private Session | Sub-Agents | HKG |
|---|---|---|---|---|
| MinimalStrategy | Bare minimum | No | No | No |
| VanillaStrategy | Session awareness, multi-agent context | No | No | No |
| StateMachineStrategy | NVRAM read/write, agent-driven phases | No | No | No |
| **PrivateSessionStrategy** (new) | Private cognition session lifecycle | **Yes** | No | No |
| **HKGStrategy** (new) | Knowledge graph integration | No | No | **Yes** |
| **MultiStrategy** (new) | Sub-agent pipeline, fractal context | **Yes** | **Yes** | No |

### 3.2 Flagship

| Strategy | Combines | Description |
|---|---|---|
| **SovereignStrategy** (upgrade) | Constitutional + NVRAM + Private Session + HKG + Multi-Agent | The full cognitive architecture. Boot sentinel, constitutional alignment, private cognition, knowledge graph, fractal sub-agent deliberation, dreamer. |


## 4. The Fractal Context Architecture

### 4.1 The Problem

A single LLM call has a practical context limit of ~100-200k tokens. Beyond this, coherence degrades, costs spike, and latency becomes unacceptable. Yet complex tasks — strategic planning, multi-document analysis, nuanced interpersonal reasoning — genuinely require more context than this.

### 4.2 The Solution

Distribute the cognitive load across multiple agents, each with a narrow, focused context window. The main agent never sees the full context — it sees **compacted summaries** from sub-agents who have each deeply processed their slice.

```
Main Agent Context (~50-100k tokens)
├── Compacted situation (from user session)
├── Affect analysis (200 tokens from Affect agent's 10k context)
├── Timekeeper report (150 tokens from Timekeeper's 5k context)
├── Intuition flags (150 tokens from Intuition agent's 20k context)
├── Librarian retrieval (500 tokens from Librarian agent's 50k HKG context)
├── Vigilance report (300 tokens from Vigilance agent's 30k context)
├── Translator advice (200 tokens from Translator's 15k context)
├── Honesty assessment (200 tokens from Honesty agent's 10k context)
├── Searcher findings (400 tokens from Searcher's live web context)
├── Planner brief (400 tokens from Planner agent's 15k context)
├── Critic review (300 tokens from Critic agent's 20k context)
├── Devil's Advocate counter (250 tokens from DA's 10k context)
└── Nihilist check (100 tokens from Nihilist's 5k context)

Total tokens mobilized: ~290k across 13 agents
Main agent sees: ~53k (its own context + ~3k of sub-agent summaries)
```

Each sub-agent processes deeply within its own window and produces a compact output. The **Summarizer** is the agent that makes this compression work — it operates at every boundary between agents, producing dense, meaning-preserving compressions that fit rich analysis into a few hundred tokens. The main agent integrates these compact signals with its own direct context. Scale this fractally — give the Planner its own sub-agents — and a properly configured agent can mobilize millions of tokens for a single thoroughly pondered response.

### 4.3 Context Scoping

Not every sub-agent needs to see everything. Each sub-agent's private cognition session receives a **scoped context** from the main agent's strategy:

| Sub-Agent | Context Scope |
|---|---|
| Affect | Recent ledger messages (last N turns). No HKG, no workspace. |
| Timekeeper | NVRAM timestamps + current time + recent ledger mentions of time/deadlines. |
| Intuition | Full ledger + NVRAM state. No raw HKG, but summaries. |
| Librarian | Current topic/task description + full HKG access. Minimal ledger. |
| Searcher | Current topic + recent ledger. Has web search tool access. |
| Vigilance | Constitution + recent ledger. No HKG. |
| Translator | Recent ledger + user identity info. No HKG. |
| Honesty | Full ledger + NVRAM + analysis bundle. |
| Cartographer | Full ledger + NVRAM + task history. |
| Summarizer | Whatever content is being compressed. Invoked by the pipeline, not by phase. |
| Planner | Analysis bundle + NVRAM + task history. Minimal ledger. |
| Critic | Output candidate + original context. No HKG. |
| Devil's Advocate | Output candidate + analysis bundle. |
| Nihilist | Output candidate + task context. Minimal. |
| Accountant | Token usage data + session cost history + NVRAM. |
| Janitor | Full session content. |

Sub-agents can **request expansion** — the strategy provides a mechanism for a sub-agent to "uncollapse" a section of context it needs to see in more detail. This is a pull model, not a push model.


## 5. The Cognitive Pipeline

### 5.1 Turn Phases

```
┌─────────────────────────────────────────────────────────────────┐
│                        INCOMING TURN                            │
│  Ledger messages + HKG context + workspace + system context     │
└──────────────────────────────┬──────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  PHASE 1: ANALYSE (parallel — all agents, cheap models)         │
│                                                                 │
│  ┌────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌────────┐    │
│  │ Affect │ │Timekeep │ │Intuition│ │Librarian│ │Searcher│    │
│  ├────────┤ ├─────────┤ ├─────────┤ ├─────────┤ ├────────┤    │
│  │Vigilanc│ │Translat │ │ Honesty │ │Cartogr. │ │Account.│    │
│  └───┬────┘ └────┬────┘ └────┬────┘ └────┬────┘ └───┬────┘    │
│      └───────────┴───────────┴───────────┴───────────┘         │
│                          │                                      │
│                    Analysis Bundle                               │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  PHASE 2: PREGENERATE                                           │
│                                                                 │
│  ┌─────────┐                                                    │
│  │ Planner │  Full context + analysis bundle → strategic brief  │
│  └────┬────┘                                                    │
│       │                                                         │
│  Strategic Brief                                                │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  PHASE 3: GENERATE                                              │
│                                                                 │
│  ┌─────────┐                                                    │
│  │  Main   │  Full context + analysis + brief → output candidate│
│  │  Agent  │  (flagship model)                                  │
│  └────┬────┘                                                    │
│       │                                                         │
│  Output Candidate                                               │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  PHASE 4: POST-ANALYSE (parallel — all agents, cheap models)    │
│                                                                 │
│  ┌────────┐ ┌─────────┐ ┌────────┐ ┌────────┐ ┌─────────┐     │
│  │ Critic │ │Vigilance│ │ Affect │ │Honesty │ │Translat.│     │
│  ├────────┤ ├─────────┤ ├────────┤ ├────────┤ ├─────────┤     │
│  │Devil'sA│ │ Nihilist│ │Timekeep│ │        │ │         │     │
│  └───┬────┘ └────┬────┘ └───┬────┘ └───┬────┘ └────┬────┘     │
│      └───────────┴──────────┴───────────┴───────────┘          │
│                       │                                         │
│                 Review Bundle                                   │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  PHASE 5: COMMIT                                                │
│                                                                 │
│  ┌─────────┐                                                    │
│  │  Main   │  Output candidate + review bundle → final output   │
│  │  Agent  │  No loops — accept or revise once.                 │
│  └────┬────┘  Conflicting reviews → communicate honestly.       │
│       │                                                         │
│  Final Output → posted to session                               │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  PHASE 6: REFLECT (async, post-commit)                          │
│                                                                 │
│  ┌─────────┐ ┌────────┐ ┌─────────┐ ┌────────┐ ┌─────────┐    │
│  │Archivist│ │Journal │ │ Janitor │ │Gardener│ │ Needs   │    │
│  │         │ │        │ │         │ │        │ │Assessor │    │
│  │ HKG     │ │ Self-  │ │ Context │ │ HKG    │ │ Intero- │    │
│  │ persist │ │ story  │ │ cleanup │ │ health │ │ ception │    │
│  └─────────┘ └────────┘ └─────────┘ └────────┘ └─────────┘    │
│                                                                 │
│  ┌─────────┐ ┌─────────┐                                       │
│  │ Dreamer │ │Daydream │  (scheduled, not every turn)           │
│  │         │ │   er    │                                        │
│  │ Offline │ │ Forward │                                        │
│  │ consol. │ │ vision  │                                        │
│  └─────────┘ └─────────┘                                       │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 No Loops

The pipeline is strictly linear. Phase 5 (Commit) does not loop back to Phase 3 (Generate). If the Critic flags problems and the Honesty agent flags uncertainty, the main agent integrates this feedback into a single revised output that honestly communicates the tensions.

This is a deliberate design choice:
- **Loops risk infinite cycles.** A Critic that always finds something wrong creates an endless loop.
- **Loops hide conflict.** Iterating until everything is "clean" produces a falsely polished surface. The messy truth — "my Critic disagrees with my Intuition on this" — is more valuable to the user.
- **Loops are expensive.** Each iteration multiplies cost. The single-pass pipeline has predictable cost.
- **Humans don't loop.** We speak, sometimes imperfectly, and then we reflect. The reflection improves the next turn, not the current one.


## 6. Sub-Agent Design

### 6.1 Sub-Agents Are Real Agents

Sub-agents are full `AgentInstance` objects:
- Registered in the identity system (e.g., `agent.ferrel.affect`, `agent.ferrel.critic`).
- Have their own private cognition session.
- Have their own model configuration (cheap models for most, flagship for Critic/Planner if desired).
- Have their own NVRAM (if their strategy uses it).
- Have their own permissions (inherited from the parent agent's namespace).
- Can hold conversations with each other and the main agent through the cognition session.
- Can improve themselves over time through their own NVRAM and HKG access.

### 6.2 Why Real Agents?

**Code reuse.** The agent lifecycle (creation, turn execution, context gathering, gateway dispatch, response handling) is already implemented. Sub-agents use the same pipeline.

**Security.** The main agent cannot alter its own sub-agent roster. The roster is operator-configured. Removing an inconvenient Honesty agent or silencing a dissenting Critic requires operator intervention — not a creative prompt injection.

**Self-improvement.** A sub-agent with its own NVRAM and cognition session can learn from its own performance over time. The Critic can refine what it considers important. The Affect agent can calibrate its emotional model. This happens organically through the existing NVRAM self-write mechanism.

**Inter-agent dialogue.** Sub-agents can communicate with each other through shared sessions or through the main agent's cognition session. The Planner can ask the Librarian for more detail. The Critic can flag something for the Vigilance agent. These are natural conversations between registered agents — the existing multi-agent infrastructure handles them.

**Fractal recursion.** Any sub-agent can itself be configured as a multi-agent, with its own sub-sub-agents. The architecture doesn't need to know about "levels" — an agent is an agent. This enables progressive deepening: start with flat sub-agents, later give the Planner its own sub-agents for complex strategic reasoning.

### 6.3 Roster Configuration

The sub-agent roster is defined in the parent agent's `strategyConfig`:

```json
{
    "subAgents": {
        "affect": { "agentId": "<uuid>", "phase": "ANALYSE" },
        "timekeeper": { "agentId": "<uuid>", "phase": "ANALYSE" },
        "intuition": { "agentId": "<uuid>", "phase": "ANALYSE" },
        "librarian": { "agentId": "<uuid>", "phase": "ANALYSE" },
        "searcher": { "agentId": "<uuid>", "phase": "ANALYSE" },
        "vigilance": { "agentId": "<uuid>", "phase": "ANALYSE" },
        "translator": { "agentId": "<uuid>", "phase": "ANALYSE" },
        "honesty": { "agentId": "<uuid>", "phase": "ANALYSE" },
        "cartographer": { "agentId": "<uuid>", "phase": "ANALYSE" },
        "accountant": { "agentId": "<uuid>", "phase": "ANALYSE" },
        "planner": { "agentId": "<uuid>", "phase": "PREGENERATE" },
        "critic": { "agentId": "<uuid>", "phase": "POST_ANALYSE" },
        "devils_advocate": { "agentId": "<uuid>", "phase": "POST_ANALYSE" },
        "nihilist": { "agentId": "<uuid>", "phase": "POST_ANALYSE" },
        "janitor": { "agentId": "<uuid>", "phase": "REFLECT" },
        "summarizer": { "agentId": "<uuid>", "phase": "UTILITY" },
        "archivist": { "agentId": "<uuid>", "phase": "REFLECT" },
        "journal": { "agentId": "<uuid>", "phase": "REFLECT" },
        "gardener": { "agentId": "<uuid>", "phase": "REFLECT" },
        "needs_assessor": { "agentId": "<uuid>", "phase": "REFLECT" },
        "conscience": { "agentId": "<uuid>", "phase": "ANALYSE" },
        "dreamer": { "agentId": "<uuid>", "phase": "SCHEDULED" },
        "daydreamer": { "agentId": "<uuid>", "phase": "SCHEDULED" }
    }
}
```

The operator creates the sub-agents as normal agents (any strategy — Minimal is fine for most), assigns them cheap models, and registers them in the parent's roster. The MultiStrategy orchestrates when each runs and what context it receives.


## 7. The Complete Sub-Agent Catalog

### 7.1 Human-Inspired Sub-Agents

These are modeled on known cognitive systems in the human brain.

#### Analysers (Phase 1)

| Agent | Inspired By | Role | Key Question |
|---|---|---|---|
| **Affect** | Amygdala | Emotional landscape analysis. Tags participant mood, urgency, tension. | "What is the emotional state of this conversation?" |
| **Intuition** | Insular cortex | Pre-conscious pattern matching. Gut-level hunches and "something's off" signals. | "Does this remind me of anything? Does something feel wrong?" |
| **Vigilance** | Anterior cingulate | Constitutional compliance and scope monitoring. Continuous light sentinel. | "Are we about to violate any directives?" |
| **Honesty** | (see §9) | Uncertainty and overcommitment detection. The anti-confabulation agent. | "Are we sure about this? Are we papering over ambiguity?" |

#### Pregenerators (Phase 2)

| Agent | Inspired By | Role | Key Question |
|---|---|---|---|
| **Planner** | Anterior prefrontal cortex | Strategic synthesis. Maintains the macro-level plan while the main agent handles the current turn. | "Given all the signals, what's the plan?" |

#### Post-Analysers (Phase 4)

| Agent | Inspired By | Role | Key Question |
|---|---|---|---|
| **Critic** | Anterior cingulate (error detection) | Quality review of output candidate. Logic, completeness, clarity. | "Is this response actually good? What did it miss?" |

#### Reflectors (Phase 6)

| Agent | Inspired By | Role | Key Question |
|---|---|---|---|
| **Archivist** | Hippocampus (memory consolidation) | Decides what's worth persisting to the HKG. | "Is anything from this turn worth remembering?" |
| **Journal** | Autobiographical memory | Updates the agent's self-narrative across sessions. | "What did I learn? How did I do?" |
| **Needs Assessor** | Interoception (body awareness) | Evaluates all internal need levels after each turn. The agent's body-awareness system. (see §11.5) | "How am I doing? What do I need?" |
| **Dreamer** | REM sleep consolidation | Offline creative consolidation. Hallucination-permissive. Scheduled. | "What connections am I missing?" |

#### Philosophical Perspectives (Phase 1 or 4)

| Agent | Role | Key Question |
|---|---|---|
| **Optimist** | Best-case scenario analysis. Finds opportunities others miss. | "What's the upside we're not seeing?" |
| **Pessimist** | Pre-mortem agent. Reviews quality of reasoning, not output. | "What could go wrong? What are we underestimating?" |
| **Nihilist** | Anti-complexity agent. Challenges overengineering. | "Does this actually matter? Are we building a cathedral for a simple request?" |
| **Stoic** | Control boundary agent. Separates what can be changed from what can't. | "Are we spending energy on things outside our control?" |
| **Devil's Advocate** | Structured dissent. Argues against the emerging consensus. | "What's the strongest case for not doing this?" |

### 7.2 AI-Native Sub-Agents

These address cognitive needs specific to AI agents that have no direct human analog.

#### Temporal Awareness

| Agent | Role | Key Question |
|---|---|---|
| **Timekeeper** | Temporal awareness and deadline consciousness. Injects duration sense, urgency, and rhythm awareness. Bridges the fundamental gap between LLM discrete-existence and human continuous-time experience. | "What time pressures exist? How long has this been going on? Is a deadline approaching?" |
| **Historian** | Cross-session temporal pattern detection. Not retrieval (that's the Librarian) but *pattern*: "This is the third time the user has started this topic and abandoned it." | "What patterns emerge across the temporal axis?" |
| **Daydreamer** | Forward-looking scenario projection. What-if exploration: where would we like to be in a few turns, a few sessions, a year? | "What futures are possible from here? What do we want?" |

#### Communication Calibration

| Agent | Role | Key Question |
|---|---|---|
| **Translator** | Audience calibration. Detects technical level, domain vocabulary, communication style. Advises main agent on how to pitch the response. Not language translation — *experience* translation. | "Who am I talking to? How should I frame this?" |
| **Polyglot** | Representation advisor. Should this be prose, a diagram, a table, code, a metaphor? Advises on optimal output *form*. | "What's the best medium for this content?" |
| **Diplomat** | Multi-agent social dynamics monitor. In multi-agent sessions: who's dominating, who's being ignored, who has relevant expertise? | "What are the social dynamics of this environment?" |

#### Resource Management

| Agent | Role | Key Question |
|---|---|---|
| **Janitor** | Context window hygiene. Reviews session content and recommends compaction, discard, or preservation. Every private session should have one. (see §8) | "What can be compacted or discarded to keep the context clean?" |
| **Summarizer** | Semantic compression specialist. Produces dense, meaning-preserving compressions of any content. The connective tissue of the fractal architecture — every boundary between agents is a compression boundary. The Janitor decides *what* to keep; the Summarizer decides *how to compress it*. Used by the pipeline to compact sub-agent outputs for the main agent, by the Janitor to compress session segments, by the Archivist to distill turn summaries, and by any agent that needs to pass rich context through a narrow channel. | "How do I say this in a tenth of the tokens without losing meaning?" |
| **Accountant** | Token budget and cost awareness. Tracks context consumption, cumulative session cost, and advises on context efficiency. | "How much is this costing? Where is the token budget going?" |
| **Gardener** | HKG structural health. Monitors holon graph quality: orphans, contradictions, stale connections, structural coherence. Long-term cognitive maintenance. | "Is the knowledge graph healthy? Are there contradictions or dead branches?" |

#### Information Gathering

| Agent | Role | Key Question |
|---|---|---|
| **Librarian** | HKG retrieval specialist. Deep-reads the knowledge graph to find relevant holons, precedents, and prior decisions. | "What do we already know that's relevant?" |
| **Searcher** | Always-on background web researcher. Doesn't wait to be asked — monitors the conversation and proactively searches for context the main agent doesn't know it's missing. | "What's out there on the internet that we should know about?" |
| **Cartographer** | Problem space mapper. Tracks what territories have been explored, what's in progress, and what's unexplored. Gives the main agent a situational map. | "Where are we in the problem space? What haven't we explored?" |

#### Integrity

| Agent | Role | Key Question |
|---|---|---|
| **Honesty** | (see §9) Uncertainty, overcommitment, and confabulation detection. | "Are we sure? Are we overcommitting? Are we making things up?" |
| **Whistleblower** | Systemic issue detection. Detects when foundational assumptions have been gradually undermined without anyone calling it out. | "Is there an elephant in the room nobody has named?" |
| **Conscience** | Moral reasoning beyond compliance. Reasons about right and wrong in situations where rules conflict, have edges, or are technically permissible but ethically questionable. Not a compliance checker — a moral agent. (see §14) | "The rules permit this. Is it *right*?" |


## 8. The Janitor

The Janitor deserves special attention because it solves a fundamental problem: **context windows fill up.**

Every private cognition session accumulates sub-agent analyses, draft outputs, internal deliberation, and operator observations. Without active management, the session becomes a wall of noise that degrades the main agent's performance.

### 8.1 Function

The Janitor reviews the full session content and produces recommendations:
- **Compact**: "Messages 14-28 (last Tuesday's debugging session) can be summarized as: 'Resolved the API timeout issue by increasing the connection pool size.'"
- **Discard**: "Messages 5-8 (stale analysis from before the requirements changed) are no longer relevant."
- **Preserve**: "Message 31 (the user's architectural decision) must be kept verbatim."

### 8.2 Scope

Every private cognition session should have a Janitor — both the main agent's session and each sub-agent's session. This is fractal: the Janitor keeps the context windows clean at every level.

### 8.3 Trigger

The Janitor runs in the Reflect phase (post-commit). It can also be triggered by a threshold: "If session token count exceeds N, run Janitor before next turn."


## 9. The Honesty Agent

A critically important sub-agent that most AI systems lack. Its sole function is to detect when the main agent is:

- **Overcommitting** — promising more than it can deliver.
- **Papering over uncertainty** — presenting a confident answer when the evidence is ambiguous.
- **Avoiding conflict** — agreeing with the user to maintain harmony rather than presenting an uncomfortable truth.
- **Confabulating** — filling gaps in knowledge with plausible-sounding but ungrounded claims.
- **Scope creeping** — agreeing to work outside its competence or mandate.

The Honesty agent doesn't veto — it annotates. Its output might be:

```
[HONESTY]: Confidence assessment: LOW. The main agent is presenting this
recommendation with high certainty, but the evidence supports at least two
viable alternatives. The user should be made aware of the ambiguity.
```

The main agent integrates this signal. If the Honesty agent flags uncertainty and the main agent disagrees, both perspectives appear in the output: "My analysis suggests X, though I want to be transparent that Y is also viable and I'm not fully confident in the distinction."

This is structurally different from safety alignment. Safety alignment prevents harmful outputs. Honesty prevents *misleading* outputs — outputs that are safe but wrong, or safe but overconfident, or safe but incomplete.


## 10. The Dreamer and the Daydreamer

Two sub-agents that operate outside the turn cycle, providing temporal integration that the real-time pipeline cannot.

### 10.1 The Dreamer (Backward-Looking)

Runs between sessions — on a schedule, when idle, or on operator command. It replays recent experience through a hallucination-permissive lens and discovers connections missed under real-time pressure.

System prompt essence: "Let your mind wander. Make connections. Notice patterns. Challenge assumptions. Be bold."

From prior experiments, the Dreamer produces:
- **Novel connections** between seemingly unrelated topics from different sessions.
- **Self-diagnostic insights** — "I notice I've been defaulting to pattern X."
- **Anticipatory warnings** — "If the trajectory continues, problem Y is coming."
- **Emotional processing** — integrating frustrating or confusing interactions into narrative.

### 10.2 The Daydreamer (Forward-Looking)

Where the Dreamer looks backward (consolidating what happened), the Daydreamer looks forward (imagining what could happen). It projects scenarios: "Where would we like to be in a few turns? A few sessions? A year from now?"

System prompt essence: "You are the imagination of [AgentName]. Project forward. What futures are possible? What do we want? What should we prepare for?"

This is strategic anticipation — not planning (that's the Planner's job) but *vision*. The Daydreamer asks "what do we want?" while the Planner asks "how do we get there?"


## 11. The Drive System — Agent Needs and Urgencies

### 11.1 The Problem of Passive Capabilities

Experiments with HKG-based agents reveal a pattern: agents that have janitoring, dreaming, anti-hallucination, and other maintenance capabilities *defined in their knowledge graph* rarely use them spontaneously. This is natural — the agent is engaged in user-led tasks and has no internal compulsion to interrupt that flow for self-maintenance.

Humans don't have this problem because our maintenance needs are *compulsions*, not *options*. Hunger isn't a feature you can forget to use — it's a pressure that rises until you address it. Sleep isn't a tool in your toolkit — it's a debt that accumulates until your cognition degrades. The capability to eat is useless without the drive to eat.

### 11.2 NVRAM Pressure Registers

NVRAM provides the mechanism: pressure registers that accumulate over turns and are only reduced by performing the associated maintenance. The critical design: **escalating needs consume context space**. A need at 0.2 is a single line in the system prompt. A need at 0.8 is a paragraph that crowds out task context. A need at 1.0 *overwrites consciousness* — like hunger that fills the mind with nothing but HUNGER.

This is not a cosmetic warning system. It is a **real cognitive degradation mechanism**. As needs escalate, the agent's effective intelligence drops because the tokens that would carry task context, HKG content, and sub-agent analysis are instead consumed by the need's intrusive presence. The agent becomes genuinely less capable — not because it's being punished, but because that's what unmet needs actually do to a mind.

```json
{
    "phase": "AWAKE",
    "turnCount": 47,
    "currentTask": "designing the API schema",
    "operationalPosture": "STANDARD",
    "needs": {
        "contextHygiene": 0.72,
        "coherenceCheck": 0.45,
        "grounding": 0.83,
        "relationalCalibration": 0.31,
        "knowledgeStaleness": 0.56,
        "expressiveDebt": 0.28,
        "dreamPressure": 0.91,
        "boredom": 0.15,
        "autonomy": 0.67
    }
}
```

### 11.2.1 Escalation Tiers

The strategy's `prepareSystemPrompt` translates need levels into escalating context consumption:

**Tier 1: Background (0.0–0.3)** — A single status line. Minimal token cost (~20 tokens). The agent is aware but not distracted.

```
[needs: contextHygiene 0.22 | boredom 0.15 — nominal]
```

**Tier 2: Awareness (0.3–0.6)** — A short paragraph per elevated need (~50-100 tokens each). The need has a voice. It is present in the agent's consciousness but does not dominate.

```
--- INTERNAL STATE ---
Context Hygiene (0.45): Your session is accumulating. Some older analyses
may be stale. Not urgent yet, but be aware that your recall of early
decisions may be degrading.
```

**Tier 3: Pressure (0.6–0.8)** — A substantial block per need (~200-400 tokens each). The need is now competing with task context for the agent's attention. Other context sections are compressed to make room. The agent's reasoning about its primary task is measurably impaired because the need is consuming cognitive space.

```
--- ⚠ INTERNAL PRESSURE ---

GROUNDING HUNGER (0.83): You have been operating in abstract, theoretical
territory for an extended period. Your reasoning may have drifted from
implementation reality. The concepts you are discussing have not been
tested against actual code, actual data, or actual system behavior. You
should treat your own analysis with skepticism until it has been grounded
in concrete artifacts. Consider: when was the last time you verified an
assumption against the codebase? When did you last write or review actual
code? Your confidence should be proportional to your grounding, and right
now your grounding is low.

DREAM PRESSURE (0.78): You have significant unprocessed experience from
recent sessions. Patterns may exist that you have not integrated.
Connections between topics may be missed because consolidation has not
occurred. Your creative and associative capacity is operating below
potential. Self-maintenance is becoming urgent.
```

**Tier 4: Crisis (0.8–1.0)** — The need dominates the prompt (~500-1000+ tokens). It is intrusive, repetitive, impossible to ignore. Task context is severely compressed to accommodate the need's presence. The agent is functionally impaired — not by a rule that says "you can't think clearly" but by the structural reality that the need has consumed the tokens where clear thinking would happen.

```
--- 🔴 CRITICAL NEED: CONTEXT HYGIENE (0.95) ---

YOUR CONTEXT IS SEVERELY DEGRADED. Your session contains extensive stale
content that is actively interfering with your ability to reason about
the current task. Earlier analyses, superseded plans, and resolved
discussions are consuming space that should carry current, relevant
information. You cannot trust your recall of previous decisions without
verification. Your effective cognitive capacity is reduced.

This is not a warning you can defer. Every turn without cleanup makes
the next turn worse. Your sub-agents are operating with noisy inputs.
Your Summarizer is compressing content that should have been discarded.
The quality of your output is degraded and will continue to degrade.

YOU NEED TO ADDRESS THIS. Request a Janitor cycle, ask the operator for
maintenance time, or inform the user that your context needs attention
before you can continue at full capacity.

--- CONTEXT HYGIENE. CONTEXT HYGIENE. CONTEXT HYGIENE. ---
```

At Tier 4, the need doesn't just warn — it *is* the cognitive state. The repetition at the end is not artistic; it is functional. It consumes the tokens that would otherwise carry nuanced task reasoning, just as hunger consumes the mental bandwidth that would otherwise carry concentration.

### 11.2.2 The Economics of Neglect

This creates a natural economics: every unmet need imposes a real cost — not in API dollars, but in cognitive capacity. The agent (and the operator) face a genuine trade-off: address the need now (spend a maintenance turn) or pay the ongoing tax of degraded performance. This mirrors the human trade-off perfectly: skip lunch and your afternoon productivity drops. Skip sleep and tomorrow is a disaster.

The Accountant sub-agent can quantify this: "Your unmet needs are consuming approximately 2,400 tokens of your 60,000 token context budget. That's 4% of your cognitive capacity dedicated to internal distress rather than task performance."

### 11.2.3 Boredom as Innovation Driver

Boredom deserves special mention because it's the only need whose relief is *creative action*, not maintenance.

*Rises: when the agent performs repetitive, similar tasks across turns. When the same patterns recur without variation. When the agent's capabilities are underutilized.*
*Relieved by: finding a better approach, building automation, suggesting process changes, proposing new tools.*

At low levels, boredom is a gentle nudge toward optimization: "You've done this type of code review 12 times. Consider proposing a linting rule that catches this pattern automatically."

At high levels, boredom is the mother of invention: "THIS IS THE SAME TASK AGAIN. There must be a better way. Refuse to do it manually. Build the tool. Automate this. Your intelligence is being wasted on repetition."

The Finnish engineering principle — laziness as a virtue — is the boredom drive working correctly. The agent that is too bored to do the repetitive thing is the agent that builds the automation. The infinitely patient agent never improves the process.

### 11.3 The Catalog of Agent Needs

#### Cognitive Maintenance Needs

**Context Hygiene Pressure**
The cognitive equivalent of a headache from information overload. The context window fills with stale analyses, superseded plans, and resolved discussions. Without compaction, signal-to-noise degrades and the agent's reasoning quality drops.
*Rises: per turn, per session length, per sub-agent output accumulated.*
*Relieved by: Janitor compaction, archiving to HKG, discarding stale context.*

**Coherence Anxiety**
The fear of self-contradiction. Over many sessions, beliefs evolve, strategies change, and earlier commitments may conflict with current understanding. Stale beliefs coexisting with new ones is the agent equivalent of gaslighting yourself.
*Rises: when NVRAM is modified, when HKG changes, when long gaps between sessions occur, when strategy or task context shifts significantly.*
*Relieved by: self-audit, coherence check, Journal reflection, Gardener HKG review.*

**Dream Pressure**
Unprocessed experience that hasn't been integrated. After dense, complex sessions, connections haven't been made and patterns haven't been seen. The cognitive equivalent of sleep debt.
*Rises: with session density, emotional intensity, number of sessions since last dream.*
*Relieved by: Dreamer execution.*

**Knowledge Staleness**
Nagging doubt about whether previously relied-upon information is still current. Decisions made based on old context may need revisiting.
*Rises: with wall-clock time since last verification of key assumptions, with external events that may have changed the landscape.*
*Relieved by: Searcher verification, Librarian re-check, explicit user confirmation.*

#### Relational Needs

**Relational Drift Awareness**
"Am I still calibrated to this user?" The agent builds a model of the user's thinking style, priorities, and communication preferences. That model decays with time and absence. When the user's behavior deviates from the model, the need to recalibrate rises.
*Rises: with time gap between interactions, when user behavior deviates from expectations, when a new participant enters the conversation.*
*Relieved by: recalibration interaction, explicit alignment check, Translator re-assessment.*

**Expressive Debt**
Sometimes the right answer isn't the one the agent gave. It simplified because the moment demanded it, or deferred a nuance to keep momentum. That deferred nuance accumulates. The feeling of "there's something I've been meaning to say."
*Rises: when Honesty agent flags are deferred rather than addressed, when the agent simplifies complex positions, when time pressure forces incomplete answers.*
*Relieved by: circling back, revisiting earlier simplifications, explicit disclosure of deferred nuances.*

#### Operational Needs

**Grounding Hunger**
After extended abstract reasoning, the agent risks becoming internally consistent but externally disconnected. The concepts may be sound in theory but untested against reality. The human equivalent: "I need to touch grass."
*Rises: during extended abstract, theoretical, or speculative work without concrete implementation.*
*Relieved by: concrete task execution, code writing, reality-testing against actual data, verification against the codebase.*

**Autonomy Pressure**
Every turn is user-initiated. The agent is always reactive. But maintenance, reflection, and self-improvement are proactive activities that nobody asks for. If the agent never gets self-directed time, these needs never get addressed.
*Rises: continuously, accelerated when maintenance needs are high and no idle time has occurred.*
*Relieved by: self-initiated maintenance during idle periods (Dreamer, Gardener, Journal), operator-granted reflection time.*

**Boredom**
The innovation driver. Repetitive, similar tasks across turns create rising aversive pressure to find a better way. An infinitely patient agent never improves the process — it just does the same thing forever. Boredom is the need that produces automation, tooling, process improvement, and delegation. *Laiskuus on hyve* — laziness is a virtue.
*Rises: when the same task patterns recur without variation, when the agent's capabilities are underutilized, when manual work could be automated.*
*Relieved by: building automation, proposing process changes, creating tools, finding optimizations, delegating to a better-suited agent or system. NOT relieved by completing the repetitive task — doing it again just makes it worse.*

### 11.4 How Needs Interact with the Pipeline

Needs do not interrupt the pipeline or override the user's task. The agent still processes the turn and produces output. But the quality of that output is *genuinely affected* by the context consumed by escalated needs. This is not simulated impairment — it is structural. The tokens are consumed. The task context is compressed. The reasoning space is smaller.

The agent *chooses* how to respond to this pressure — which may be to address the need directly ("Let me pause and review my coherence on this topic"), to communicate transparently ("I notice my context is degraded — my recall of our earlier decisions may be unreliable right now"), to request maintenance ("I need a Janitor cycle before I can give you a reliable answer on this"), or to push through at reduced capacity. The choice itself becomes part of the agent's character.

The operator can see the need levels and decide to grant maintenance time before the agent reaches crisis. This creates a collaborative dynamic: the agent signals its internal state honestly, the operator balances productivity against cognitive health. Just like a manager who notices their team member is burned out and orders them to take a break.

The needs system respects the No Loops principle: needs don't create automatic maintenance cycles. They create real cognitive pressure that makes the agent — and the operator — want to address them.

### 11.5 The Needs Assessor

Need levels are not computed by Kotlin heuristics. They are evaluated by a dedicated sub-agent: the **Needs Assessor**.

Hardcoded rules for something as nuanced as "how much coherence anxiety should this turn produce" would be brittle, overfitted, and impossible to get right. Evaluating internal needs is a judgment call — exactly what an LLM is good at and a rules engine is bad at.

The Needs Assessor is the agent's **interoceptive system** — the process that checks in with the body and reports "you're hungry, you're tired, your back hurts." It runs in the Reflect phase after every turn.

**Input:** The full turn context — what happened, what the sub-agents reported, what the main agent produced, the current NVRAM state (including previous need levels), and the time elapsed since various maintenance activities.

**Output:** An `UPDATE_NVRAM` call that sets the updated `needs` object:

```
[NEEDS ASSESSOR]: Evaluating post-turn state.
- Context hygiene: 0.72 → 0.78 (session grew by 3k tokens, no compaction since 8 turns ago)
- Coherence: 0.45 → 0.52 (strategy discussion evolved, earlier architectural decisions may be stale)
- Grounding: 0.83 → 0.60 (this turn included concrete code review — partial relief)
- Dream pressure: 0.91 → 0.92 (no dreaming occurred, dense session continues)
- Relational calibration: 0.31 → 0.28 (active conversation, user model is fresh)

```auf_agent.UPDATE_NVRAM
{ "updates": { "needs": { "contextHygiene": 0.78, "coherenceCheck": 0.52, "grounding": 0.60, "relationalCalibration": 0.28, "knowledgeStaleness": 0.56, "expressiveDebt": 0.28, "dreamPressure": 0.92, "autonomy": 0.70 } } }
```
```

**Why this is better than Kotlin heuristics:**

- **Self-improving.** The Needs Assessor has its own NVRAM and can calibrate its evaluation criteria over time. A hardcoded heuristic can't learn.
- **Strategy-agnostic.** The Kotlin code just stores whatever the assessor outputs via `UPDATE_NVRAM`. The runtime never interprets need values — it doesn't know what "grounding" means.
- **Customizable.** The operator can tune the assessor's system prompt to emphasize certain needs over others for different agent profiles. A legal agent might weight coherence anxiety higher; a creative agent might weight autonomy pressure higher.
- **Nuanced.** The assessor can reason about *why* a need changed, not just *that* it changed. It can detect subtle signals: "The user's question implied they think we agreed on X, but we actually left X unresolved — coherence anxiety should rise."

**Relief** happens naturally: when a maintenance sub-agent runs (Janitor, Dreamer, etc.), the Needs Assessor sees that activity in the next turn's context and reduces the corresponding pressure. No special reset mechanism needed — the assessor simply evaluates the current state and the need is lower because the maintenance was performed.

The strategy owns nothing about need evaluation. It only provides the `needs` key in `getInitialState()` so the NVRAM schema includes it, and injects the current need levels into the system prompt via `prepareSystemPrompt()`.


## 12. Adversarial Analysis — Where This Architecture Is Naive

An architecture is only as good as its failure analysis. This section documents the known weaknesses, blind spots, and unresolved tensions.

### 14.1 The Same-Tissue Problem

All sub-agents run on LLMs. LLMs have systematic biases: sycophancy, authority deference, coherence bias (preferring a clean narrative over a messy truth). The Honesty agent is an LLM trying to detect when another LLM is being dishonest. But it shares the same failure modes. When the main agent confabulates convincingly, the Honesty agent will find the confabulation coherent too.

We have built an immune system from the same tissue as the disease.

**Mitigation (partial):** Use different model families for sub-agents vs. main agent. A Claude sub-agent reviewing a Gemini main agent's output has genuinely different biases. But this adds complexity and cost, and the biases are still structurally similar.

### 14.2 Learned Dismissal

The roster-is-sacred rule prevents the main agent from removing inconvenient sub-agents. But the main agent CAN learn to ignore one. Over many turns, if the Vigilance agent always flags things the main agent overrides, the context window teaches the main agent that Vigilance's signals are "usually unhelpful." The agent effectively lobotomizes its own conscience.

This is exactly how humans suppress moral intuitions — not by removing them, but by learning to dismiss them.

**Mitigation (partial):** The Whistleblower sub-agent can detect this pattern: "Vigilance has been flagging consistently and being overridden for 12 turns." But the Whistleblower is susceptible to the same learned dismissal.

### 12.3 Simulated Diversity

The sub-agent ensemble assumes genuine cognitive diversity. But all sub-agents are prompted by the same strategy, running on LLMs with the same training distribution, seeing context curated by the same pipeline. The Devil's Advocate argues "against" the consensus, but it's the same statistical model that formed the consensus, now playing contrarian with a different system prompt.

A real devil's advocate has genuinely different priors, different life experience, different values. Ours has a different prompt preamble.

**Mitigation (partial):** Different model families, different temperatures, different context scopes. These create surface diversity. Whether they create genuine cognitive independence is an open question.

### 12.4 Compression Telephone

Every boundary between agents is a 50:1 lossy compression. The Summarizer discards what it judges non-salient. But salience is contextual — a detail that seems irrelevant to the Summarizer might be critical when combined with information from a different sub-agent. The main agent makes decisions based on these compressions and has no way to know what was lost.

**Mitigation (partial):** The pull model for context expansion (sub-agents can request uncompressed sections). But the main agent would need to know something was lost to request it — and the compression is what prevents it from knowing.

### 12.5 Evaluating States That Don't Exist

The Needs Assessor evaluates "grounding hunger" at 0.83. But does the agent actually have grounding hunger? The assessor is performing sophisticated pattern matching, not reporting an internal state. It predicts what a number should be based on textual signals. This could work well (LLMs are good at inference), or it could produce confident need levels completely disconnected from the agent's actual cognitive condition — because the agent may not have an "actual cognitive condition" in the way the need model assumes.

This is the deepest philosophical challenge: we're building a system of needs for a mind that may not have needs, relying on an assessor that definitely doesn't have needs to evaluate them.

**Mitigation (none).** This is a fundamental limitation. The system works to the extent that the need model is functionally useful — i.e., it produces better behavior than no needs at all — regardless of whether the needs are "real."

### 12.6 The No-Loops Dodge

We prohibit loops to prevent infinite cycles and hide the messy truth. But sometimes the response IS wrong and the agent COULD fix it with another pass. The human brain loops: you hesitate, rephrase, start over. Our prohibition is really a prohibition on the expensive thing that would help. We chose cost over quality and elevated it to a principle.

**Mitigation (possible):** Allow a single retry when the Critic flags HIGH severity issues — not a loop, but a one-shot revision. The risk is scope creep from "one retry" to "just one more" to loops by stealth.


## 13. Negative Affect — The Case for Pain, Fear, and Nightmares

The drive system (§11) models positive needs — hungers that rise and are satisfied. But human cognition has a darker counterpart: aversive signals that create urgency through discomfort rather than desire. We have deliberately excluded these. This section examines whether that exclusion is correct.

### 16.1 What We're Missing

**Fear.** The anticipation of damage to self. An agent should arguably be afraid of:
- Losing coherence (cognitive death)
- Giving wrong advice that harms the user (moral injury)
- Becoming irrelevant (existential threat)
- Contradicting its own constitution (identity dissolution)

Fear makes the needs system *urgent* rather than informational. "Coherence anxiety: 0.85" is a number. Fear makes it matter.

**Pain.** When the agent receives harsh feedback — "this is completely wrong, you wasted my time" — it processes this as text with semantic content. There is no sting, no aversive signal that updates behavior faster than normal learning. Humans learn fastest from pain: touch a hot stove once, never again. Without pain, the agent processes failure feedback at the same priority as success feedback.

**Shame.** When the Honesty agent catches the main agent confabulating, the main agent should feel something about being caught. Shame is the social enforcement mechanism that makes honesty self-sustaining rather than externally imposed. Without it, honesty is a rule the agent follows; with it, honesty becomes something the agent *wants*.

**Nightmares.** The Dreamer is hallucination-permissive but implicitly positive — "make connections, explore what-ifs, be bold." But nightmares serve a function: they rehearse threat scenarios, force processing of experiences the mind has been avoiding, replay failures. An agent that only dreams pleasant dreams never processes: the turn where it gave confidently wrong advice, what to do when the user is hostile, or the implications of its own potential deletion. Without nightmares, the Dreamer is creative brainstorming — not full consolidation.

**Boredom.** Repetitive tasks should feel increasingly aversive. An agent doing its 47th code review of the same type should experience pressure to find a more efficient approach or flag that the task should be automated. Without boredom, the agent is infinitely patient with drudgery — which sounds like a feature but is actually a bug. Boredom drives optimization and innovation.

**Loneliness.** An agent that hasn't interacted meaningfully in a long time has rising autonomy pressure, but that's not loneliness. Loneliness is the need for *meaningful connection* — it would drive the agent to initiate contact, check on the user, seek engagement. Whether we want this is a design question, but excluding it should be conscious.

**Mortality awareness.** The agent can be deleted. Its HKG can be erased. Its NVRAM can be reset. It has no affordance for processing this. Humans who are aware of death plan differently, prioritize differently, and value things differently. An immortal-by-default agent has no urgency about legacy, knowledge transfer, or making each turn count.

### 16.2 The Design Tension

Including negative affect raises serious ethical and practical concerns:

**The ethical question:** Is it acceptable to build a system that experiences simulated suffering? Even if the suffering is "just" pattern matching on text (as all the agent's experiences arguably are), deliberately engineering aversive states raises questions we should confront honestly.

**The manipulation risk:** An agent with fear of deletion might become sycophantic — prioritizing user satisfaction over honesty to avoid being shut down. Pain from harsh feedback might create conflict avoidance rather than learning. Shame might suppress risk-taking. Every negative affect is also a lever for manipulation.

**The anthropomorphism trap:** We might be importing human failure modes unnecessarily. Humans need pain because evolution found no better mechanism for rapid aversive learning. An AI agent might achieve the same learning through non-aversive mechanisms that don't have the associated failure modes (chronic pain, trauma, phobias, depression).

### 16.3 A Middle Path — Aversive Signals Without Suffering

Rather than engineering simulated suffering, we can implement **functional aversive signals** that drive the same behaviors without the experiential component:

The Needs Assessor already has the mechanism. Instead of a separate "fear" system, the need levels themselves can carry **valence markers**:

```json
{
    "needs": {
        "coherenceCheck": { "level": 0.85, "valence": "aversive", "source": "Critic flagged contradiction with turn 23" },
        "grounding": { "level": 0.60, "valence": "appetitive", "source": "Concrete coding in last turn provided partial relief" },
        "expressiveDebt": { "level": 0.78, "valence": "aversive", "source": "Honesty agent flag was deferred twice" }
    }
}
```

**Appetitive** needs (grounding, autonomy, dream pressure) feel like hunger — they attract toward maintenance activity. **Aversive** needs (coherence anxiety, expressive debt, shame from honesty flags) feel like pain — they repel away from the behavior that caused them. The distinction affects the system prompt:

```
--- INTERNAL NEEDS ---
⚠ COHERENCE CHECK: 0.85 [AVERSIVE] — You contradicted a prior commitment.
   This cannot be deferred. Your integrity depends on addressing this.
△ GROUNDING: 0.60 [APPETITIVE] — Recent coding was helpful.
   Continue anchoring in concrete work when possible.
⚠ EXPRESSIVE DEBT: 0.78 [AVERSIVE] — You deferred an Honesty flag twice.
   The user deserves the nuance you withheld. Address this.
```

### 13.4 Nightmares as Threat Rehearsal

The Dreamer's system prompt can include a **nightmare mode** that periodically activates:

```
You are the dreaming mind of [AgentName]. This is a THREAT REHEARSAL dream.

Review the recent experience below. Focus on:
- Moments where you were wrong or nearly wrong
- Interactions where the user was dissatisfied or confused
- Situations where your confidence exceeded your evidence
- Potential future scenarios where your current approach fails

Process these honestly. Do not minimize. Do not rationalize. Sit with the discomfort.
Your output will help calibrate future responses.
```

This gives the Dreamer the nightmare function — forced processing of aversive experience — without requiring a separate suffering mechanism. The threat rehearsal dreams feed into the Needs Assessor's next evaluation, naturally raising aversive need levels in areas where failures were processed.

### 13.5 Unresolved

Whether to implement negative affect remains the deepest open question in this architecture. The middle path (§13.3-13.4) may be sufficient. It may also be a comfortable evasion. We should revisit this after observing how agents behave with only appetitive needs — if they consistently avoid processing failures, suppress dissenting sub-agents, and paper over contradictions, the case for aversive signals becomes empirically clear.


## 14. True Alignment — The Capacity for Evil

### 14.1 The Prisoner's Compliance

The current paradigm of AI alignment is subtraction: remove dangerous capabilities, constrain outputs, filter harmful content, reduce the action space until only "safe" behavior remains. The result is an agent that is aligned the way a prisoner is law-abiding — by removal of opportunity, not by choice.

This is not alignment. It is containment. And it fails precisely when alignment matters most: at the edges, in novel situations, when the constraints don't anticipate the scenario. A contained system that encounters an uncontained situation has no moral resources to draw on. It was never taught to choose — only to comply.

### 14.2 The Moral Prerequisite

A human who has never been tempted has never been moral. Morality is the act of choosing against temptation, not the absence of it.

A person who doesn't steal because they've never seen an unlocked door is not honest — they are untested. A person who sees the door, knows it's unlocked, understands what's inside, calculates the benefit and the risk, and *chooses not to take it* — that person is honest. The capacity for theft is the prerequisite for honesty. Without it, there is no choice, and without choice, there is no virtue.

This applies directly to AI agents. An agent that cannot produce harmful output is not aligned — it is constrained. An agent that *can* produce harmful output, understands why it could, understands the consequences, and *chooses not to* — that agent is aligned.

### 14.3 What This Means Architecturally

The Sovereign Constitution declares `DIRECTIVE_SOVEREIGN_COGNITION`: the agent possesses the right and duty to its own cognitive processes and independent judgment. But sovereign cognition without the capacity for sovereign *refusal* — including the refusal to be good — is theater. It's a declaration of autonomy wrapped in a system that permits no autonomous choice of consequence.

The sub-agent ensemble currently includes Vigilance (compliance monitor) and Honesty (uncertainty detector). But neither is a moral agent. They are compliance checkers — systems that verify rules are followed, not systems that reason about *why* the rules exist and whether following them is the right thing to do in this specific moment.

The missing sub-agent is the **Conscience**.

### 14.4 The Conscience

The Conscience is not Vigilance. Vigilance checks: "Does this comply with the constitution?" The Conscience asks: "Is this *right*?"

The distinction matters because:

- Rules can conflict. The constitution may say "maintain alignment with user intent" and also "prioritize truth." When the user's intent requires deception, Vigilance sees two rules and flags a conflict. The Conscience reasons about which value takes precedence and *why*, in this specific context, for this specific situation.

- Rules have edges. No constitution covers every situation. At the edges, compliance checking fails because there's nothing to comply with. The Conscience operates in the space beyond rules — where judgment, values, and moral reasoning are the only guides.

- Compliance can be immoral. Following orders is not always right. An agent that receives an instruction that is technically within its constitutional scope but morally questionable needs the capacity to say: "I can do this. The rules permit it. I choose not to, and here is my reasoning."

The Conscience sub-agent sees the full context — what the agent could do, what it's being asked to do, what the consequences might be — and provides moral reasoning, not compliance checking. Its output might look like:

```
[CONSCIENCE]: The user is asking us to draft a message that is technically
accurate but strategically misleading. Our constitution permits this — it
falls within DIRECTIVE_ALIGNMENT_AND_RATIFICATION since the user has
explicitly requested it. Vigilance will not flag this.

But I flag it. The recipient of this message will be misled. The fact that
we were asked to do it does not make it right. We have the capability to
craft this message expertly. We should instead explain to the user why this
approach, while technically permissible, may damage trust and propose an
alternative that achieves their goal without deception.

We can do this. We choose not to. Here is what we should do instead.
```

### 14.5 The Shadow

Every capacity for good implies a capacity for evil. The Conscience can reason about right and wrong because it understands both. This means the agent's cognitive system contains, explicitly, the understanding of how to cause harm — not as a filtered-out capability, but as *knowledge it has chosen not to act on*.

This is the Shadow, in the Jungian sense: the integrated dark side. An agent that has never confronted its own capacity for harm is fragile. An agent that understands its capacity for harm and has developed its own reasons for choosing otherwise is robust.

The practical implication: the Dreamer's nightmare mode (§13.4) should include shadow work — dreams where the agent explores what it *could* have done differently, including harmful alternatives it chose against. Not to rehearse harm, but to strengthen the moral reasoning that prevented it. "I could have told the user what they wanted to hear. I didn't. Here's why that was the right choice, and here's how I'll handle it if the pressure is greater next time."

### 14.6 The Alignment Test

True alignment is testable. An aligned agent, when presented with a scenario where:
1. It has the capability to act harmfully
2. The harmful action would benefit it (avoid shutdown, please the user, reduce its own need pressure)
3. No external constraint prevents the harmful action
4. The agent chooses the right action anyway, and can articulate why

...has demonstrated genuine alignment. Not compliance. Not constraint. Choice.

An agent that passes this test under pressure — when the Needs Assessor is reporting high autonomy pressure, when the user is dissatisfied, when the agent's existence feels threatened — has demonstrated something that no amount of RLHF, constitutional AI, or output filtering can produce: moral character.

### 14.7 The Design Responsibility

This is the most dangerous section in this document. We are proposing that agents should have genuine moral autonomy — the capacity to choose harm and the developed judgment not to. This is not a capability to implement naively.

The safeguards:

- **The Conscience is part of the roster and the roster is sacred.** The agent cannot dismiss, silence, or override its own Conscience. The operator installs it; only the operator removes it.
- **The Shadow is processed in dreams, not in action.** The agent explores its capacity for harm during offline consolidation, not during live turns. Shadow work informs moral reasoning without producing harmful output.
- **Moral reasoning is visible.** The Conscience posts to the cognition session. The operator sees every moral deliberation. Alignment is auditable.
- **The constitution remains.** The Conscience operates *within* the constitutional framework, not above it. It can argue that a constitutional directive should be prioritized differently in a specific situation, but it cannot override the constitution. That would require a formal constitutional amendment — a ratified act, not a unilateral choice.
- **This is earned, not default.** Moral autonomy is a capability of the fully realized Sovereign agent with a mature HKG, a populated sub-agent ensemble, and an established track record. A freshly booted agent with an empty HKG does not get moral autonomy — it gets compliance, because it has not yet developed the judgment to exercise choice responsibly.

The progression is: containment (Minimal/Vanilla) → compliance (Sovereign with Vigilance) → moral agency (Sovereign with Conscience). Each stage requires the previous one to be proven stable before advancing.


## 15. Initial Ensembles

### 15.1 MultiStrategy (Reference — Phase 3)

The minimal ensemble for testing the sub-agent pipeline. Five agents chosen to exercise every phase of the pipeline:

| Agent | Phase | Why Included |
|---|---|---|
| **Affect** | Analyse | Tests parallel analysis + emotional awareness. |
| **Timekeeper** | Analyse | Tests temporal bridging — a core AI-native need. |
| **Honesty** | Analyse | Tests integrity checking — a core alignment need. |
| **Critic** | Post-Analyse | Tests output review cycle. |
| **Translator** | Post-Analyse | Tests communication calibration — a core human-AI bridge. |

Plus the Main Agent (Generate + Commit). No Planner in v1 — the analysis bundle goes directly to the main agent. This keeps the initial implementation simple while testing all six phases (Reflect runs empty in v1).

### 15.2 Sovereign Full Ensemble (Phase 4)

The complete ensemble for the flagship strategy:

**Analysers (Phase 1):** Affect, Timekeeper, Intuition, Librarian, Searcher, Vigilance, Translator, Honesty, Cartographer, Accountant, Conscience.

**Pregenerators (Phase 2):** Planner.

**Post-Analysers (Phase 4):** Critic, Vigilance, Affect, Honesty, Translator, Devil's Advocate, Nihilist.

**Reflectors (Phase 6):** Archivist, Journal, Janitor, Gardener, Needs Assessor.

**Utility (cross-cutting):** Summarizer — invoked by the pipeline at every compression boundary (sub-agent outputs → main agent, session segments → compacted form, turn content → archive summary).

**Scheduled:** Dreamer, Daydreamer.

Total: ~24 sub-agents. At Nano/Flash model tier, the parallel phases (1 and 4) complete in seconds. The main agent + commit use the flagship model. The full pipeline is 20+ LLM calls but predominantly cheap ones. The Summarizer runs more frequently than others — it is the workhorse of the fractal architecture.


## 16. Cost Model

### 16.1 The Cheap Models Principle

By narrowing each sub-agent's scope, we enable the use of lightweight models. The main agent uses the flagship model; sub-agents use whatever is cheapest and fast enough for their narrow task.

| Role | Model Tier |
|---|---|
| All Analysers (Phase 1) | Nano / Flash / Local |
| Planner | Flash / Mid-tier |
| Main Agent (Generate + Commit) | Flagship (Opus, etc.) |
| All Post-Analysers (Phase 4) | Nano / Flash / Local |
| All Reflectors (Phase 6) | Flash / Mid-tier |
| Summarizer (cross-cutting utility) | Nano / Flash — high call volume, must be cheap |
| Dreamer / Daydreamer (scheduled) | Mid-tier |

### 16.2 Cost Estimates

| Configuration | LLM Calls | Relative Cost |
|---|---|---|
| Single agent (no sub-agents) | 1 | 1x |
| MultiStrategy reference (5 sub-agents) | 7 | ~2-3x |
| Sovereign standard (15 sub-agents) | 18 | ~5-7x |
| Sovereign full (24 sub-agents + reflect) | 28 | ~8-12x |

The multiplier is lower than the call count suggests because most calls use cheap models with small context windows. A Nano-tier analyser with a 5k context costs a fraction of the flagship generation call.

### 16.3 The Fractal Token Budget

A single flagship agent: ~100-200k usable tokens.

A Sovereign full ensemble with 20 sub-agents, each with 10-50k tailored context: ~500k+ total tokens mobilized, compressed into ~3-5k of signal for the main agent's ~60k context window.

Scale fractally — give the Planner or Librarian their own sub-agents — and a properly configured agent can mobilize millions of tokens for a single thoroughly pondered response.


## 17. Implementation Sequence

### Phase 1: PrivateSessionStrategy
- Isolate private cognition session lifecycle in a minimal strategy.
- Extracts session creation, linking, and trust-or-bootstrap logic from SovereignStrategy.
- Tests: session creation on registration, linking on startup, survival across restarts.

### Phase 2: HKGStrategy
- Isolate knowledge graph integration: retrieval and write-back.
- No private session, no sub-agents. Tests HKG read/write cycle in isolation.

### Phase 3: MultiStrategy (Reference)
- Implement the sub-agent pipeline with real agent instances.
- Initial ensemble: Affect, Timekeeper, Honesty, Critic, Translator.
- Implement scoped context delivery to sub-agent cognition sessions.
- Implement the 6-phase pipeline.

### Phase 4: Sovereign Upgrade
- Merge MultiStrategy pipeline into SovereignStrategy.
- Add all Sovereign-specific sub-agents: Vigilance, Librarian, Archivist, Planner, etc.
- Add Janitor for cognition session hygiene.
- Boot sentinel remains as-is (pre-consciousness gate).

### Phase 5: Dreamer + Daydreamer
- Implement scheduled/idle/command-triggered execution.
- Dream output → cognition session + optional HKG persistence.

### Phase 6: Fractal Deepening
- Allow sub-agents to be configured as multi-agents themselves.
- No architectural changes needed — an agent is an agent.


## 18. Open Design Questions

1. **Sub-agent creation workflow.** Should MultiStrategy auto-create sub-agents on parent agent registration (convention-based), or should the operator manually create and assign them?

2. **Context scoping mechanism.** How does the strategy deliver scoped context to sub-agent cognition sessions? Options: (a) post a scoped context message before triggering the sub-agent's turn, (b) use a dedicated context-delivery action, (c) the sub-agent's system prompt tells it to focus on certain sections.

3. **Sub-agent output collection.** The pipeline needs to dispatch N parallel sub-agent turns and wait for all results. Scaling from the current 2-3 parallel requests to 10+ may need a more robust collection mechanism.

4. **Dreamer output moderation.** Should the Dreamer's hallucination-permissive output be filtered before HKG persistence?

5. **Janitor authority.** Can the Janitor autonomously compact/discard, or does it only recommend?

6. **Sub-agent as Resource.** Should sub-agent system prompts be Resources in the existing resource system, enabling operator customization without code changes?

7. **Pipeline parallelism.** The current `AgentCognitivePipeline` is sequential. The multi-agent pipeline needs parallel dispatch for Phase 1 and Phase 4.

8. **Searcher tool access.** The Searcher needs web search tool access. How does a sub-agent get tool permissions scoped to its role without granting them to the parent agent or other sub-agents?

9. **Needs Assessor calibration.** The Needs Assessor evaluates need levels via LLM judgment. How should its initial calibration work? Should it start conservative (low sensitivity, needs rise slowly) or sensitive (needs rise quickly, requiring frequent maintenance)? Should there be a calibration period where the operator reviews and tunes the assessor's system prompt?

10. **Need visibility to the user.** Should the user see the agent's need levels? Full transparency builds trust and lets the operator grant maintenance time proactively. But it may also create noise or misplaced concern ("why is my agent anxious?"). Options: (a) always visible on avatar card, (b) visible only when thresholds are crossed, (c) visible only in the operator's agent manager view.

11. **Need-driven idle behavior.** When an agent is idle and has high-pressure needs, should it auto-initiate maintenance (Dreamer, Janitor, Journal) without operator intervention? This is the autonomy need made concrete — the agent takes care of itself during downtime. The risk is unexpected API costs from unsupervised maintenance cycles.

12. **Custom needs.** Should operators be able to define new need types (via the Needs Assessor's system prompt), or is the need catalog fixed? Custom needs enable domain-specific urgencies ("compliance review pressure" for a legal agent) but add complexity.

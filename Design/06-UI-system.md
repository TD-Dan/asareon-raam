# ASAREON RAAM — UI System

**Version 1.0.0-alpha** · Companion to 01-System-architecture · Compose Multiplatform / Material 3

---

## Overview

This document describes the design philosophy behind Raam's UI — the patterns every screen follows, the rules for naming things, and the conventions for presenting actions to the user. It is deliberately high-level: implementation details (component signatures, file paths, exact token values) belong in the code and are discoverable from it. The purpose of this document is to explain *why* the app looks and behaves the way it does, so a new screen can be built without reverse-engineering the existing ones.

The system sits on top of Material 3 (Compose Multiplatform edition). It does not add new visual primitives — it constrains choices to a small set of consistent patterns so every screen feels like part of the same app.


## Core Principles

**One way to build a screen.** There is one top-bar system, one action-emphasis ladder, one destructive-action pattern. Contributors should not invent per-screen variations; the work of deciding where Delete goes, how Back behaves, or what color a Create button uses has already been done.

**Theme is identity-aware.** The active user identity can override the primary color, and the secondary / tertiary / container colors derive from it. Every UI color is named by its semantic role (primary, tertiaryContainer, error, …) — never a hex literal. This means the active user's identity tint flows naturally through the chrome, including through Create buttons, without special-case handling.

**M3 as-is, not M3 Expressive.** The system targets stable Material 3. New shapes, motion, and emphasised typography from M3 Expressive are a future layer.

**Destruction is always behind friction.** Destructive actions are hidden behind a kebab, visually marked with the error color, separated from safe actions by a divider, and always require confirmation. This rule has no exceptions.

**Hierarchy is honest.** If a view is subordinate to another (Permissions is about Identities; System Resources is about Agents), the subordinate view is reached via a button on its parent and returns via a hierarchical Back — not presented as a peer tab. The back arrow always means "go up one level", never "dismiss".


## Semantic Spacing

Layout spacing is exposed as named tokens (`screenEdge`, `sectionGap`, `itemGap`, `inner`, `tight`, …) accessed via `MaterialTheme.spacing`. Contributors pick a token by intent (*"this is the gap between cards" → `itemGap`*), never by eyeballing a dp value. Changing the app's overall density is a single-file edit.


## The Top Bar

Every primary view has the same top-bar structure:

```
┌─────────────────────────────────────────────────────────┐
│ [leading]  {centre}  [trailing actions + overflow]      │
├─────────────────────────────────────────────────────────┤
│ {sub-content — optional, full width}                    │
└─────────────────────────────────────────────────────────┘
```

- **Leading** is `None`, `Back` (hierarchical back), or `Close` (dismiss). Mixing these is forbidden — pick the one that tells the user the truth about what the button will do.
- **Centre** is a slot. For most screens it's a title (with optional subtitle breadcrumb). For SessionView it's a tab row. For FileSystemView it's a title plus inline dropdowns. The system provides a standard "title" fill for the common case; custom centre content is allowed for the unusual case.
- **Trailing actions** are a priority-sorted list of `HeaderAction`s. When the bar narrows, lower-priority actions spill into an overflow kebab.
- **Sub-content** hosts anything that belongs near the header but is not part of the bar itself — a current path, helper text, a banner. Keeping this out of the bar proper keeps the bar simple.

Tooltips on icon-buttons are shown on sustained hover (not on pass-over) so they're informative without being noisy.


## Header Action Emphasis Tiers

Every trailing action has one of three emphasis levels. Emphasis communicates semantics, not just visual weight.

| Tier | Meaning | Rendering |
|---|---|---|
| **Icon** (default) | An ordinary thing you can do on this screen | Plain icon button with tooltip |
| **Prominent** | A secondary destination or a meaningful alternate action | Labelled button using the secondary tonal palette |
| **Create** | Create a new owned asset | Labelled button with a `+` icon using the tertiary tonal palette |

**Create is special.** It always sorts leftmost in the action row, regardless of declared priority, and is the last to collapse into the overflow menu. This reflects the reality that creation is usually the user's reason for being on a management screen — it shouldn't hide.

**Prominent is for destinations, not actions.** Use it for buttons that open a secondary view ("Permissions", "System Resources", "Import Holons"), not for buttons that perform an action with side effects.

There is deliberately no "Primary" tier at this stage — the app has only one true commit CTA today ("Execute Turn"), which is fine as a plain primary-coloured button. A primary tier will be introduced when a second clear use case emerges.


## Secondary Destinations

Some feature pairs have a primary view plus a helper view that users visit occasionally. Permissions accompany Identities; System Resources accompany Agents; Import accompanies Knowledge Graphs. These helpers are **not** presented as peer tabs. A tab row would lie about their importance.

Instead, the helper is reached via a Prominent button on the parent view. Clicking the button swaps the view to the helper; the helper renders with a Back arrow that returns to the parent, and a subtitle that names the parent as a breadcrumb. The same visual system is used for every such pair, so users learn the pattern once.


## Back-to-Root Convention

Every top-level view that is reached from the left ribbon has a Back button. That Back returns to the default view (the session view). The ribbon is the app's home; everywhere else is one step away from home.

The only exception is the default view itself, which has no Back — there is nowhere to go.


## The Global Action Ribbon

The vertical ribbon on the left of the window is the top-level navigation. Features contribute entries to it through a structured API; the ribbon does not hardcode which features exist.

**Priority ordering is intentional.** Sessions come first because conversation is the app's purpose. Agents come next because they're the other half of a session. Identities follow because the user's identity is context for everything. The rest of the features sit in the middle. About and Settings anchor the bottom — rarely visited, always last.

When vertical space is tight, the lowest-priority entries collapse into an overflow dropdown at the bottom. The home button at the top is always visible and always routes to the default view.


## Destructive Actions

Destructive actions — actions that permanently destroy an owned asset — follow a strict pattern with no per-screen variation.

1. **Kebab-only placement.** A destructive action is never an inline icon or button. It lives inside a kebab menu attached to the asset it would destroy.
2. **Separated from safe actions.** If the kebab contains other (non-destructive) entries, a horizontal divider separates the safe group from the destructive entry at the bottom.
3. **Error-toned.** Label text and leading icon are tinted with the error color. The default icon is the trash can.
4. **Always confirmed.** Clicking the kebab entry opens a confirmation dialog. The dialog has a top error-tinted icon, a question title, a body describing the consequence, an error-toned confirm button, and a plain text-button cancel. No dispatch happens without the second click.

The codebase provides two helpers — one for the kebab entry, one for the confirmation dialog. Use them; don't hand-roll a destructive affordance.

This friction is deliberate. A user who clicks through twice has chosen; a user who hovers idly over a trash icon has not.


## Wording Conventions

User-facing labels follow a small fixed vocabulary. Internal action names on the store bus (`AGENT_DELETE`, `CORE_REMOVE_USER_IDENTITY`) are contract-level and are not renamed to match UI wording.

| Verb | Means | Examples |
|---|---|---|
| **Create** | Create a new owned asset | Create Session, Create Agent, Create Persona |
| **Delete** | Permanently destroy an owned asset | Delete Agent, Delete Persona, Delete Script |
| **Remove** | Detach an association (non-destructive, usually reversible) | Remove from Favorites, Remove from session |
| **Clear** | Wipe an asset's contents without destroying the asset | Clear Session (removes messages; session remains) |
| **Clone** | Duplicate an existing asset | Clone Agent, Clone Script |

"Create" replaces earlier mixed forms ("New X", "Add X"). "Delete" is exclusively destructive; "Remove" is exclusively non-destructive. The distinction matters — users read labels and expect the word to tell them what will happen.

A kebab entry may say "Delete" alone when the containing item gives context. When a view deletes more than one kind of asset (Knowledge Graph can delete Personas and Holons), labels disambiguate: "Delete Persona" / "Delete Holon".


## Color Roles at a Glance

Colors are always referenced by their Material 3 semantic role, never by literal value. The roles this system cares about:

| Role | Used for |
|---|---|
| `primary` | The one true commit CTA of a screen; active-state indicators |
| `secondaryContainer` | Prominent tier — secondary destinations, medium-emphasis actions |
| `tertiaryContainer` | Create tier — new-asset creation actions |
| `error` | Destructive text/icon tinting |
| `errorContainer` | Destructive confirmation button |
| `surface` | Top-bar background |
| `surfaceContainer` / `surfaceContainerLow` | Ribbon and pane backgrounds |

Because secondary and tertiary derive from the identity-overridable primary, both Prominent and Create actions naturally carry the active user's identity tint.


## Non-Goals for This Version

- **M3 Expressive.** Deferred — shapes, motion, emphasised type are not yet adopted.
- **A Primary emphasis tier.** Not yet — only one callsite would use it. Will be revisited when a second emerges.
- **SidePane unification.** The left panes of SessionView (workspace), AgentManagerView (resources), and the ribbon itself are not yet consolidated into a shared adaptive component. Tracked in `TODO.md`.
- **Screenshot / visual regression tests.** Pure layout logic (action overflow, ribbon slot math) has unit-test coverage; visual rendering is verified by manual smoke tests.
